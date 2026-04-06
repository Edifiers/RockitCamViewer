package com.rockitcam.viewer;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * RockitCam UCP H.265 图传接收端（802.11n 2.4GHz 优化版）
 *
 * UCP 协议：每个 UDP 包前 12 字节包头
 *   [0]     ver_magic = 0x15
 *   [1]     flags: [confirm:1|moredata:1|session:2|hb:1|reserved:3]
 *   [2-3]   una (uint16, 网络序)
 *   [4-5]   sn  (uint16, 网络序) — 每个分片独立递增
 *   [6-7]   len (uint16, 网络序)
 *   [8-11]  timestamp (uint32, 网络序)
 *
 * 通过 SN 间隙精确检测丢包，丢包后等完整无损 IDR 恢复
 * 
 * 编码器：H.265 (低码率高画质，适合 2.4GHz 远距离传输)
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String SERVER_IP = "192.168.4.1";
    private static final int SERVER_PORT = 8080;
    private static final int LOCAL_PORT = 9000;

    // UCP 协议常量
    private static final int UCP_HDR_SIZE      = 12;
    private static final int UCP_VER_MAGIC     = 0x15;
    private static final int UCP_FLAG_MOREDATA = 0x40;
    private static final int UCP_SESSION_CTRL  = 0x10;
    private static final int UCP_FLAG_HB       = 0x08;
    private static final int BLOCK_ACK_MAGIC   = 0x5A;

    // H.265 NALU 类型
    private static final int NAL_VPS = 32;
    private static final int NAL_SPS = 33;
    private static final int NAL_PPS = 34;
    private static final int NAL_IDR = 19;  // 或 20, 21

    private volatile boolean running = false;
    private DatagramSocket recvSocket;
    private DatagramSocket sendSocket;
    private MediaCodec decoder;
    private Surface surface;
    private TextView statusText;
    private Handler uiHandler;

    // 统计
    private volatile long recvBytes = 0;
    private volatile int recvPkts = 0;
    private volatile int decodedFrames = 0;
    private volatile int lostFrames = 0;
    private volatile int lostSnCount = 0;
    private long lastStatTime = 0;
    
    // 新增：详细统计数据
    private volatile int idrFrameCount = 0;
    private volatile int pFrameCount = 0;
    private volatile long totalFrameBytes = 0;
    private volatile int decodeErrors = 0;
    private volatile long minFrameSize = Long.MAX_VALUE;
    private volatile long maxFrameSize = 0;
    private volatile long lastFrameTime = 0;
    private volatile int currentFps = 0;

    // 帧组装
    private byte[] frameBuf = new byte[1024 * 1024];
    private int frameLen = 0;

    // SN 丢包检测
    private int videoRcvNxt = 0;
    private boolean firstPacket = true;
    private boolean frameHasLoss = false;

    // 参数集缓存（H.265 需要 VPS/SPS/PPS）
    private volatile boolean codecConfigured = false;
    private byte[] vpsData;
    private byte[] spsData;
    private byte[] ppsData;

    private int decodeErrorCount = 0;
    private volatile boolean registered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        uiHandler = new Handler(Looper.getMainLooper());

        SurfaceView sv = findViewById(R.id.surfaceView);
        sv.getHolder().addCallback(this);

        updateStatus("等待 Surface 就绪...");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        running = true;

        new Thread(this::mainLoop, "RecvDecode").start();
        new Thread(this::outputLoop, "DecodeOut").start();
        new Thread(this::heartbeatLoop, "Heartbeat").start();

        uiHandler.postDelayed(this::updateStats, 1000);
        updateStatus("[802.11n 2.4GHz] 已启动，等待数据...");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 设置 SurfaceView 保持原始视频比例（800x480 = 5:3）
        adjustSurfaceViewAspectRatio(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        sendCommand("RKCAM:LEAVE");
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        releaseDecoder();
        closeSocket(recvSocket);
        closeSocket(sendSocket);
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    private void closeSocket(DatagramSocket s) {
        if (s != null && !s.isClosed()) s.close();
    }

    // ============================================================
    //  调整 SurfaceView 比例（保持 800x480 = 5:3）
    // ============================================================
    private void adjustSurfaceViewAspectRatio(int surfaceWidth, int surfaceHeight) {
        SurfaceView sv = findViewById(R.id.surfaceView);
        if (sv == null) return;

        // 视频原始比例 800:480 = 5:3
        float videoAspect = 800f / 480f;
        float surfaceAspect = (float) surfaceWidth / surfaceHeight;

        int newWidth, newHeight;
        if (surfaceAspect > videoAspect) {
            // 屏幕更宽，以高度为准
            newHeight = surfaceHeight;
            newWidth = (int) (surfaceHeight * videoAspect);
        } else {
            // 屏幕更高，以宽度为准
            newWidth = surfaceWidth;
            newHeight = (int) (surfaceWidth / videoAspect);
        }

        android.view.ViewGroup.LayoutParams params = sv.getLayoutParams();
        params.width = newWidth;
        params.height = newHeight;
        sv.setLayoutParams(params);
    }

    // ============================================================
    //  UCP 包头解析
    // ============================================================
    private boolean isValidUcp(byte[] buf, int len) {
        return len >= UCP_HDR_SIZE && (buf[0] & 0xFF) == UCP_VER_MAGIC;
    }

    private int parseSn(byte[] buf) {
        return ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
    }

    private boolean parseMoreData(byte[] buf) {
        return (buf[1] & UCP_FLAG_MOREDATA) != 0;
    }

    // ============================================================
    //  构建 UCP 心跳包（12字节头 + 11字节 Block ACK）
    // ============================================================
    private byte[] buildUcpHeartbeat(int una) {
        byte[] pkt = new byte[UCP_HDR_SIZE + 11];
        pkt[0] = (byte) UCP_VER_MAGIC;
        pkt[1] = (byte) (UCP_SESSION_CTRL | UCP_FLAG_HB);
        pkt[2] = (byte) ((una >> 8) & 0xFF);
        pkt[3] = (byte) (una & 0xFF);
        int totalLen = pkt.length;
        pkt[6] = (byte) ((totalLen >> 8) & 0xFF);
        pkt[7] = (byte) (totalLen & 0xFF);
        // Block ACK
        int off = UCP_HDR_SIZE;
        pkt[off] = (byte) BLOCK_ACK_MAGIC;
        pkt[off + 1] = (byte) ((una >> 8) & 0xFF);
        pkt[off + 2] = (byte) (una & 0xFF);
        return pkt;
    }

    // ============================================================
    //  心跳线程（独立 socket）
    // ============================================================
    private void heartbeatLoop() {
        while (running && !registered) {
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
        }
        try {
            sendSocket = new DatagramSocket();
            InetAddress addr = InetAddress.getByName(SERVER_IP);

            // 先发文本注册
            byte[] reg = "RKCAM:CONNECT".getBytes();
            sendSocket.send(new DatagramPacket(reg, reg.length, addr, SERVER_PORT));

            while (running) {
                try {
                    if (firstPacket) {
                        byte[] data = "RKCAM:CONNECT".getBytes();
                        sendSocket.send(new DatagramPacket(data, data.length, addr, SERVER_PORT));
                    } else {
                        byte[] hb = buildUcpHeartbeat(videoRcvNxt & 0xFFFF);
                        sendSocket.send(new DatagramPacket(hb, hb.length, addr, SERVER_PORT));
                    }
                } catch (Exception e) { /* ignore */ }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void sendCommand(String cmd) {
        try {
            if (sendSocket != null && !sendSocket.isClosed()) {
                byte[] data = cmd.getBytes();
                InetAddress addr = InetAddress.getByName(SERVER_IP);
                sendSocket.send(new DatagramPacket(data, data.length, addr, SERVER_PORT));
            }
        } catch (Exception e) { /* ignore */ }
    }

    // ============================================================
    //  H.265 IDR NALU 检测
    // ============================================================
    private boolean containsIDR(byte[] data, int length) {
        for (int i = 0; i + 4 < length; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                int scLen = 0;
                if (i + 3 < length && data[i + 2] == 0 && data[i + 3] == 1) {
                    scLen = 4;
                } else if (data[i + 2] == 1) {
                    scLen = 3;
                }
                if (scLen > 0 && i + scLen < length) {
                    int nalType = (data[i + scLen] >> 1) & 0x3F;  // H.265 NALU type
                    if (nalType == 19 || nalType == 20 || nalType == 21) {  // IDR
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ============================================================
    //  主线程：收包 → UCP 解析 → SN 丢包检测 → 整帧喂解码器
    // ============================================================
    private void mainLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            recvSocket = new DatagramSocket(LOCAL_PORT);
            recvSocket.setReceiveBufferSize(4 * 1024 * 1024);
            recvSocket.setSoTimeout(2000);

            // 用 recvSocket 发注册（确保端口 9000 先到达发送端）
            byte[] reg = "RKCAM:CONNECT".getBytes();
            InetAddress addr = InetAddress.getByName(SERVER_IP);
            recvSocket.send(new DatagramPacket(reg, reg.length, addr, SERVER_PORT));
            registered = true;

            updateStatus("已注册，等待 UCP H.265 数据...");

            byte[] buf = new byte[1500];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);

            while (running) {
                try {
                    recvSocket.receive(pkt);
                    int len = pkt.getLength();
                    if (!isValidUcp(buf, len)) continue;

                    recvBytes += len;
                    recvPkts++;

                    int sn = parseSn(buf);
                    boolean moreData = parseMoreData(buf);
                    int dataOffset = UCP_HDR_SIZE;
                    int dataLen = len - UCP_HDR_SIZE;

                    // ── SN 丢包检测 ──
                    if (firstPacket) {
                        videoRcvNxt = sn;
                        firstPacket = false;
                    }

                    int snDiff = (sn - videoRcvNxt) & 0xFFFF;
                    if (snDiff > 0x8000) {
                        continue;  // 旧/重复包
                    }
                    if (snDiff > 0) {
                        lostSnCount += snDiff;
                        frameHasLoss = true;
                    }
                    videoRcvNxt = (sn + 1) & 0xFFFF;

                    // ── 累积帧数据 ──
                    if (frameLen + dataLen < frameBuf.length) {
                        System.arraycopy(buf, dataOffset, frameBuf, frameLen, dataLen);
                        frameLen += dataLen;
                    }

                    // ── moredata=0 → 帧结束 ──
                    if (!moreData) {
                        if (frameLen > 0) {
                            // 统计帧大小
                            if (frameLen < minFrameSize) minFrameSize = frameLen;
                            if (frameLen > maxFrameSize) maxFrameSize = frameLen;
                            totalFrameBytes += frameLen;
                            
                            // 检测帧类型
                            boolean isIDR = containsIDR(frameBuf, frameLen);
                            if (isIDR) idrFrameCount++;
                            else pFrameCount++;
                            
                            if (!codecConfigured) {
                                parseParamSets(frameBuf, frameLen);
                                // 在状态栏显示诊断信息
                                final int fLen = frameLen;
                                final int fSn = sn;
                                final boolean hasVps = (vpsData != null);
                                final boolean hasSps = (spsData != null);
                                final boolean hasPps = (ppsData != null);
                                final boolean cfg = codecConfigured;
                                updateStatus("SN=" + fSn + " len=" + fLen +
                                    "\nVPS=" + hasVps + " SPS=" + hasSps + " PPS=" + hasPps +
                                    "\nconfigured=" + cfg);
                            }
                            if (decoder != null && codecConfigured) {
                                feedWholeFrame(frameBuf, frameLen);
                            }
                        }
                        if (frameHasLoss) {
                            lostFrames++;
                        }

                        frameLen = 0;
                        frameHasLoss = false;
                    }

                } catch (java.net.SocketTimeoutException e) {
                    // 正常
                }
            }
        } catch (Exception e) {
            updateStatus("网络错误: " + e.getMessage());
        }
    }

    // ============================================================
    //  解析 VPS/SPS/PPS 参数集
    // ============================================================
    private void parseParamSets(byte[] data, int len) {
        int pos = 0;
        int lastStart = -1;
        int lastStartCodeLen = 0;

        while (pos + 2 < len) {
            if (data[pos] == 0 && data[pos + 1] == 0) {
                int scLen = 0;
                if (pos + 3 < len && data[pos + 2] == 0 && data[pos + 3] == 1) {
                    scLen = 4;
                } else if (data[pos + 2] == 1) {
                    scLen = 3;
                }
                if (scLen > 0) {
                    if (lastStart >= 0) {
                        saveParamNalu(data, lastStart, pos - lastStart, lastStartCodeLen);
                    }
                    lastStart = pos;
                    lastStartCodeLen = scLen;
                    pos += scLen;
                    continue;
                }
            }
            pos++;
        }
        if (lastStart >= 0) {
            saveParamNalu(data, lastStart, len - lastStart, lastStartCodeLen);
        }

        if (!codecConfigured) tryInitDecoder();
    }

    private void saveParamNalu(byte[] data, int offset, int length, int scLen) {
        if (length <= scLen) return;
        int naluType = (data[offset + scLen] >> 1) & 0x3F;  // H.265 NALU type (6位)

        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);

        if (naluType == NAL_VPS) vpsData = copy;
        else if (naluType == NAL_SPS) spsData = copy;
        else if (naluType == NAL_PPS) ppsData = copy;
    }

    // ============================================================
    //  初始化 H.265 解码器
    // ============================================================
    private void tryInitDecoder() {
        if (vpsData != null && spsData != null && ppsData != null && !codecConfigured) {
            initDecoder();
        }
    }

    private void initDecoder() {
        releaseDecoder();

        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/hevc", 800, 480);

            // H.265 参数集：VPS + SPS + PPS
            int csdLen = vpsData.length + spsData.length + ppsData.length;
            byte[] csd = new byte[csdLen];
            int off = 0;
            System.arraycopy(vpsData, 0, csd, off, vpsData.length); off += vpsData.length;
            System.arraycopy(spsData, 0, csd, off, spsData.length); off += spsData.length;
            System.arraycopy(ppsData, 0, csd, off, ppsData.length);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));

            format.setInteger("low-latency", 1);
            format.setInteger("priority", 0);

            decoder = MediaCodec.createDecoderByType("video/hevc");
            decoder.configure(format, surface, null, 0);
            decoder.start();
            codecConfigured = true;
            decodeErrorCount = 0;

            updateStatus("H.265 解码器已初始化 (802.11n 2.4GHz)");
        } catch (IOException e) {
            updateStatus("解码器初始化失败: " + e.getMessage());
        }
    }

    // ============================================================
    //  整帧喂解码器
    // ============================================================
    private void feedWholeFrame(byte[] data, int length) {
        try {
            int inIdx = decoder.dequeueInputBuffer(18000);
            if (inIdx < 0) return;

            ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
            if (inBuf == null) return;

            inBuf.clear();
            inBuf.put(data, 0, length);

            decoder.queueInputBuffer(inIdx, 0, length, System.nanoTime() / 1000, 0);
            decodeErrorCount = 0;
            
            // 记录帧时间（用于计算实时帧率）
            long now = System.currentTimeMillis();
            if (lastFrameTime > 0) {
                long interval = now - lastFrameTime;
                if (interval > 0) {
                    currentFps = (int) (1000 / interval);
                }
            }
            lastFrameTime = now;
            
        } catch (Exception e) {
            decodeErrorCount++;
            decodeErrors++;
            if (decodeErrorCount > 30 && vpsData != null && spsData != null && ppsData != null) {
                codecConfigured = false;
                sendCommand("RKCAM:IDR");
            }
        }
    }

    // ============================================================
    //  输出线程
    // ============================================================
    private void outputLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (running) {
            if (decoder == null || !codecConfigured) {
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                continue;
            }

            try {
                int outIdx = decoder.dequeueOutputBuffer(info, 18000);
                if (outIdx >= 0) {
                    decoder.releaseOutputBuffer(outIdx, true);
                    decodedFrames++;
                }
            } catch (Exception e) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void releaseDecoder() {
        codecConfigured = false;
        if (decoder != null) {
            try { decoder.stop(); decoder.release(); } catch (Exception e) { /* ignore */ }
            decoder = null;
        }
    }

    // ============================================================
    //  UI 统计（详细调试信息）
    // ============================================================
    private void updateStats() {
        if (!running) return;

        long now = System.currentTimeMillis();
        if (lastStatTime > 0) {
            float dt = (now - lastStatTime) / 1000f;
            float kbps = recvBytes * 8 / dt / 1000;
            float fps = decodedFrames / dt;
            
            // 计算平均帧大小
            long avgFrameSize = (idrFrameCount + pFrameCount > 0) ? 
                totalFrameBytes / (idrFrameCount + pFrameCount) : 0;
            
            // 计算丢包率
            float lossRate = (recvPkts > 0) ? 
                (lostSnCount * 100f / (recvPkts + lostSnCount)) : 0;

            String status = String.format(Locale.US,
                "╔═══ RockitCam H.265 (802.11n 2.4GHz) ═══╗\n" +
                "║ 网络统计\n" +
                "║  码率: %.1f kbps | 收包: %d\n" +
                "║  丢包: %d (%.1f%%) | 丢帧: %d\n" +
                "║\n" +
                "║ 视频统计\n" +
                "║  解码帧率: %.1f fps | 实时帧率: %d fps\n" +
                "║  IDR帧: %d | P帧: %d\n" +
                "║  帧大小: 平均 %d KB\n" +
                "║  帧大小: 最小 %d KB | 最大 %d KB\n" +
                "║\n" +
                "║ 解码器状态\n" +
                "║  状态: %s\n" +
                "║  解码错误: %d\n" +
                "╚═══════════════════════════════════════╝",
                kbps, recvPkts,
                lostSnCount, lossRate, lostFrames,
                fps, currentFps,
                idrFrameCount, pFrameCount,
                avgFrameSize / 1024,
                minFrameSize == Long.MAX_VALUE ? 0 : minFrameSize / 1024,
                maxFrameSize / 1024,
                codecConfigured ? "✓ 解码中" : "✗ 等待 VPS/SPS/PPS",
                decodeErrors);

            runOnUiThread(() -> statusText.setText(status));

            // 重置统计
            recvBytes = 0;
            decodedFrames = 0;
            recvPkts = 0;
            lostSnCount = 0;
            lostFrames = 0;
            idrFrameCount = 0;
            pFrameCount = 0;
            totalFrameBytes = 0;
            minFrameSize = Long.MAX_VALUE;
            maxFrameSize = 0;
        }
        lastStatTime = now;

        uiHandler.postDelayed(this::updateStats, 2000);
    }

    private void updateStatus(String msg) {
        runOnUiThread(() -> statusText.setText(msg));
    }
}
