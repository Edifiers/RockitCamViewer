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
 * RockitCam H.265 UDP 图传接收端
 *
 * 协议：每个 UDP 包前 2 字节包头
 *   [0] flags:  bit7=moredata, bit6~4=帧类型(0=P,1=IDR,2=VPS/SPS/PPS)
 *   [1] frame_seq: 帧序号 0-255
 *   [2...] H.265 裸数据
 *
 * 接收端通过 moredata=0 精确判断帧边界，整帧喂解码器
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String SERVER_IP = "192.168.4.1";
    private static final int SERVER_PORT = 8080;
    private static final int LOCAL_PORT = 9000;

    // 包头常量（与发送端一致）
    private static final int HDR_SIZE = 2;
    private static final int FLAG_MORE = 0x80;
    private static final int TYPE_MASK = 0x70;
    private static final int TYPE_P    = 0x00;
    private static final int TYPE_IDR  = 0x10;
    private static final int TYPE_PARAM = 0x20;

    // H.265 NALU 类型
    private static final int NAL_VPS = 32;
    private static final int NAL_SPS = 33;
    private static final int NAL_PPS = 34;
    private static final int NAL_IDR_W_RADL = 19;
    private static final int NAL_IDR_N_LP = 20;

    private volatile boolean running = false;
    private DatagramSocket recvSocket;
    private MediaCodec decoder;
    private Surface surface;
    private TextView statusText;
    private Handler uiHandler;

    // 统计
    private volatile long recvBytes = 0;
    private volatile int recvPkts = 0;
    private volatile int decodedFrames = 0;
    private volatile int lostFrames = 0;
    private long lastStatTime = 0;

    // 帧组装缓冲（按帧边界整帧拼接后再喂解码器）
    private byte[] frameBuf = new byte[1024 * 1024];
    private int frameLen = 0;
    private int lastFrameSeq = -1;  // 上一帧序号，用于丢包检测

    // 参数集缓存
    private volatile boolean codecConfigured = false;
    private byte[] vpsData;
    private byte[] spsData;
    private byte[] ppsData;

    private int decodeErrorCount = 0;

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
        updateStatus("已启动，等待数据...");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        sendCommand("RKCAM:LEAVE");
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        releaseDecoder();
        closeSocket(recvSocket);
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
    //  心跳线程（复用 recvSocket，确保与数据接收端口一致）
    // ============================================================
    private void heartbeatLoop() {
        // 等待 recvSocket 初始化完成
        while (running && recvSocket == null) {
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
        }
        try {
            InetAddress addr = InetAddress.getByName(SERVER_IP);
            while (running) {
                try {
                    if (recvSocket != null && !recvSocket.isClosed()) {
                        byte[] data = "RKCAM:CONNECT".getBytes();
                        recvSocket.send(new DatagramPacket(data, data.length, addr, SERVER_PORT));
                    }
                } catch (Exception e) { /* ignore */ }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void sendCommand(String cmd) {
        try {
            if (recvSocket != null && !recvSocket.isClosed()) {
                byte[] data = cmd.getBytes();
                InetAddress addr = InetAddress.getByName(SERVER_IP);
                recvSocket.send(new DatagramPacket(data, data.length, addr, SERVER_PORT));
            }
        } catch (Exception e) { /* ignore */ }
    }

    // ============================================================
    //  主线程：收包 → 解析包头 → 按帧边界整帧喂解码器
    // ============================================================
    private void mainLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            recvSocket = new DatagramSocket(LOCAL_PORT);
            recvSocket.setReceiveBufferSize(4 * 1024 * 1024);  // 4MB，防突发丢包
            recvSocket.setSoTimeout(2000);

            // 注册
            byte[] reg = "RKCAM:CONNECT".getBytes();
            InetAddress addr = InetAddress.getByName(SERVER_IP);
            recvSocket.send(new DatagramPacket(reg, reg.length, addr, SERVER_PORT));

            updateStatus("已注册，等待 H.265 数据...");

            byte[] buf = new byte[1500];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);

            while (running) {
                try {
                    recvSocket.receive(pkt);
                    int len = pkt.getLength();
                    if (len <= HDR_SIZE) continue;  // 包太小，跳过

                    recvBytes += len;
                    recvPkts++;

                    // 解析 2 字节包头
                    int flags = buf[0] & 0xFF;
                    int frameSeq = buf[1] & 0xFF;
                    boolean moreData = (flags & FLAG_MORE) != 0;
                    int frameType = flags & TYPE_MASK;

                    int dataOffset = HDR_SIZE;
                    int dataLen = len - HDR_SIZE;

                    // 丢包检测：帧序号不连续 → 请求 IDR
                    if (lastFrameSeq >= 0 && !moreData) {
                        int expected = (lastFrameSeq + 1) & 0xFF;
                        if (frameSeq != expected) {
                            lostFrames++;
                            sendCommand("RKCAM:IDR");
                            // 丢弃当前不完整的帧
                            frameLen = 0;
                        }
                    }

                    // 追加数据到帧缓冲
                    if (frameLen + dataLen < frameBuf.length) {
                        System.arraycopy(buf, dataOffset, frameBuf, frameLen, dataLen);
                        frameLen += dataLen;
                    }

                    // moredata=0 → 一帧结束，处理整帧
                    if (!moreData) {
                        if (frameType == TYPE_PARAM) {
                            // VPS/SPS/PPS 参数集
                            parseParamSets(frameBuf, frameLen);
                        } else if (frameLen > 0) {
                            // IDR 帧通常带 VPS+SPS+PPS，若解码器未初始化则从中提取
                            if (!codecConfigured && frameType == TYPE_IDR) {
                                parseParamSets(frameBuf, frameLen);
                            }
                            // 视频帧（IDR 或 P），整帧喂解码器
                            if (decoder != null && codecConfigured) {
                                feedWholeFrame(frameBuf, frameLen);
                            }
                        }

                        lastFrameSeq = frameSeq;
                        frameLen = 0;
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
        // 扫描 start code，提取各参数集
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
        // 最后一个
        if (lastStart >= 0) {
            saveParamNalu(data, lastStart, len - lastStart, lastStartCodeLen);
        }

        // 尝试初始化解码器
        if (!codecConfigured) tryInitDecoder();
    }

    private void saveParamNalu(byte[] data, int offset, int length, int scLen) {
        if (length <= scLen) return;
        // H.265 NALU type = (first_byte_after_startcode >> 1) & 0x3F
        int naluType = (data[offset + scLen] >> 1) & 0x3F;

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

            // H.265 的 csd-0 = VPS + SPS + PPS 合并
            int csdLen = vpsData.length + spsData.length + ppsData.length;
            byte[] csd = new byte[csdLen];
            int off = 0;
            System.arraycopy(vpsData, 0, csd, off, vpsData.length); off += vpsData.length;
            System.arraycopy(spsData, 0, csd, off, spsData.length); off += spsData.length;
            System.arraycopy(ppsData, 0, csd, off, ppsData.length);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));

            format.setInteger("low-latency", 1);
            format.setInteger("priority", 0);  // 实时优先级

            decoder = MediaCodec.createDecoderByType("video/hevc");
            decoder.configure(format, surface, null, 0);
            decoder.start();
            codecConfigured = true;
            decodeErrorCount = 0;

            updateStatus("H.265 解码器已初始化");
        } catch (IOException e) {
            updateStatus("解码器初始化失败: " + e.getMessage());
        }
    }

    // ============================================================
    //  整帧喂解码器（帧边界已由包头 moredata 精确确定）
    // ============================================================
    private void feedWholeFrame(byte[] data, int length) {
        try {
            int inIdx = decoder.dequeueInputBuffer(18000);  // 18ms（与输出线程一致，参考 mondo）
            if (inIdx < 0) return;

            ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
            if (inBuf == null) return;

            inBuf.clear();
            inBuf.put(data, 0, length);

            decoder.queueInputBuffer(inIdx, 0, length, System.nanoTime() / 1000, 0);
            decodeErrorCount = 0;
        } catch (Exception e) {
            decodeErrorCount++;
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
                int outIdx = decoder.dequeueOutputBuffer(info, 18000);  // 18ms（参考 mondo）
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
    //  UI 统计
    // ============================================================
    private void updateStats() {
        if (!running) return;

        long now = System.currentTimeMillis();
        if (lastStatTime > 0) {
            float dt = (now - lastStatTime) / 1000f;
            float kbps = recvBytes * 8 / dt / 1000;
            float fps = decodedFrames / dt;

            String status = String.format(Locale.US,
                "RockitCam H.265 Viewer\n" +
                "码率: %.0f kbps | 帧率: %.1f fps\n" +
                "收包: %d | 丢帧: %d\n" +
                "状态: %s",
                kbps, fps, recvPkts, lostFrames,
                codecConfigured ? "解码中" : "等待 VPS/SPS/PPS...");

            runOnUiThread(() -> statusText.setText(status));

            recvBytes = 0;
            decodedFrames = 0;
        }
        lastStatTime = now;

        uiHandler.postDelayed(this::updateStats, 2000);
    }

    private void updateStatus(String msg) {
        runOnUiThread(() -> statusText.setText(msg));
    }
}
