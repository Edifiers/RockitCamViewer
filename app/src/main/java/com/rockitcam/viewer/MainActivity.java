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
 * RockitCam H.264 UDP 图传接收端
 *
 * 数据流：
 *   UDP 收包 → 拼接 NALU → 解析 SPS/PPS/IDR/P → MediaCodec 硬解 → SurfaceView 显示
 *
 * 协议：
 *   发 "RKCAM:CONNECT" 到泰山派 UDP 8080 注册
 *   泰山派回发裸 H.264 NALU 数据
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "RockitCam";

    // ── 泰山派地址 ──
    private static final String SERVER_IP = "192.168.4.1";
    private static final int SERVER_PORT = 8080;
    private static final int LOCAL_PORT = 9000;

    // ── 状态 ──
    private volatile boolean running = false;
    private DatagramSocket socket;
    private MediaCodec decoder;
    private Surface surface;
    private TextView statusText;
    private Handler uiHandler;

    // ── 统计 ──
    private long recvBytes = 0;
    private int recvPkts = 0;
    private int decodedFrames = 0;
    private long lastStatTime = 0;

    // ── NALU 拼接缓冲 ──
    // 泰山派按 MTU 分片发送，一帧可能跨多个 UDP 包
    // 我们收到数据直接拼接，然后按 start code 分割喂给 MediaCodec
    private byte[] naluBuffer = new byte[512 * 1024];  // 512KB 够大
    private int naluLen = 0;

    private boolean codecConfigured = false;

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

        // 启动网络收包线程
        new Thread(this::networkLoop, "UdpRecv").start();

        // 启动心跳线程
        new Thread(this::heartbeatLoop, "Heartbeat").start();

        // 启动统计刷新
        uiHandler.postDelayed(this::updateStats, 1000);

        updateStatus("已启动，等待数据...");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        sendCommand("RKCAM:LEAVE");
        releaseDecoder();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        super.onDestroy();
    }

    // ============================================================
    //  心跳线程
    // ============================================================
    private void heartbeatLoop() {
        while (running) {
            sendCommand("RKCAM:CONNECT");
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }

    private void sendCommand(String cmd) {
        try {
            if (socket != null && !socket.isClosed()) {
                byte[] data = cmd.getBytes();
                InetAddress addr = InetAddress.getByName(SERVER_IP);
                socket.send(new DatagramPacket(data, data.length, addr, SERVER_PORT));
            }
        } catch (Exception e) {
            // ignore
        }
    }

    // ============================================================
    //  网络收包线程
    // ============================================================
    private void networkLoop() {
        try {
            socket = new DatagramSocket(LOCAL_PORT);
            socket.setSoTimeout(2000);
            socket.setReceiveBufferSize(512 * 1024);

            // 发送注册
            sendCommand("RKCAM:CONNECT");
            updateStatus("已注册，等待 H.264 数据...");

            byte[] buf = new byte[2048];  // 单个 UDP 包最大 ~1460 字节
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);

            while (running) {
                try {
                    socket.receive(pkt);
                    int len = pkt.getLength();
                    if (len <= 0) continue;

                    recvBytes += len;
                    recvPkts++;

                    // 把收到的数据追加到 NALU 缓冲
                    if (naluLen + len < naluBuffer.length) {
                        System.arraycopy(buf, 0, naluBuffer, naluLen, len);
                        naluLen += len;
                    }

                    // 尝试从缓冲中提取完整 NALU 喂给解码器
                    processNaluBuffer();

                } catch (java.net.SocketTimeoutException e) {
                    // 超时正常，继续
                }
            }
        } catch (Exception e) {
            updateStatus("网络错误: " + e.getMessage());
        }
    }

    // ============================================================
    //  NALU 解析与解码
    // ============================================================

    /**
     * 从缓冲区中找到 start code (00 00 00 01) 分割 NALU，逐个喂给 MediaCodec
     */
    private void processNaluBuffer() {
        // 找所有 start code 位置
        int pos = 0;
        int lastStart = -1;

        while (pos + 3 < naluLen) {
            // 检查 4 字节 start code: 00 00 00 01
            if (naluBuffer[pos] == 0 && naluBuffer[pos + 1] == 0 &&
                naluBuffer[pos + 2] == 0 && naluBuffer[pos + 3] == 1) {

                if (lastStart >= 0) {
                    // 从 lastStart 到 pos 是一个完整 NALU
                    feedNalu(naluBuffer, lastStart, pos - lastStart);
                }
                lastStart = pos;
                pos += 4;
            } else {
                pos++;
            }
        }

        // 保留最后一个不完整的 NALU
        if (lastStart >= 0) {
            int remaining = naluLen - lastStart;
            System.arraycopy(naluBuffer, lastStart, naluBuffer, 0, remaining);
            naluLen = remaining;
        } else if (naluLen > 256 * 1024) {
            // 缓冲太大但没找到 start code，清空防止溢出
            naluLen = 0;
        }
    }

    /**
     * 把一个完整 NALU（含 start code）喂给 MediaCodec
     */
    private void feedNalu(byte[] data, int offset, int length) {
        if (length < 5) return;  // start code(4) + nalu_type(1) 最少 5 字节

        int naluType = data[offset + 4] & 0x1F;

        // SPS (7) — 配置解码器
        if (naluType == 7) {
            if (!codecConfigured) {
                // 暂存 SPS，等 PPS 一起配置
                spsData = new byte[length];
                System.arraycopy(data, offset, spsData, 0, length);
            }
            return;
        }

        // PPS (8) — 用 SPS + PPS 初始化 MediaCodec
        if (naluType == 8) {
            if (!codecConfigured && spsData != null) {
                ppsData = new byte[length];
                System.arraycopy(data, offset, ppsData, 0, length);
                initDecoder(spsData, ppsData);
            }
            return;
        }

        // IDR (5) 或 P 帧 (1) — 送解码
        if (decoder != null && codecConfigured) {
            decodeFrame(data, offset, length, naluType == 5);
        }
    }

    private byte[] spsData;
    private byte[] ppsData;

    /**
     * 用 SPS/PPS 初始化 MediaCodec H.264 解码器
     */
    private void initDecoder(byte[] sps, byte[] pps) {
        try {
            // 从 SPS 解析宽高（简单方式：用默认值，MediaCodec 会自动检测）
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 800, 480);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(format, surface, null, 0);
            decoder.start();
            codecConfigured = true;

            updateStatus("解码器已初始化 (800x480)");
        } catch (IOException e) {
            updateStatus("解码器初始化失败: " + e.getMessage());
        }
    }

    /**
     * 送一帧给 MediaCodec 解码
     */
    private void decodeFrame(byte[] data, int offset, int length, boolean isIDR) {
        try {
            // 获取输入缓冲
            int inIdx = decoder.dequeueInputBuffer(10000);  // 10ms 超时
            if (inIdx < 0) return;

            ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
            if (inBuf == null) return;

            inBuf.clear();
            inBuf.put(data, offset, length);

            int flags = isIDR ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            decoder.queueInputBuffer(inIdx, 0, length, System.nanoTime() / 1000, flags);

            // 取出解码结果并渲染
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int outIdx = decoder.dequeueOutputBuffer(info, 0);
            while (outIdx >= 0) {
                decoder.releaseOutputBuffer(outIdx, true);  // true = 渲染到 Surface
                decodedFrames++;
                outIdx = decoder.dequeueOutputBuffer(info, 0);
            }
        } catch (Exception e) {
            // 解码错误，请求 IDR
            sendCommand("RKCAM:IDR");
        }
    }

    private void releaseDecoder() {
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception e) { /* ignore */ }
            decoder = null;
            codecConfigured = false;
        }
    }

    // ============================================================
    //  UI 统计刷新
    // ============================================================
    private void updateStats() {
        if (!running) return;

        long now = System.currentTimeMillis();
        if (lastStatTime > 0) {
            float dt = (now - lastStatTime) / 1000f;
            float kbps = recvBytes * 8 / dt / 1000;
            float fps = decodedFrames / dt;

            String status = String.format(Locale.US,
                "RockitCam Viewer\n" +
                "码率: %.0f kbps\n" +
                "帧率: %.0f fps\n" +
                "收包: %d\n" +
                "状态: %s",
                kbps, fps, recvPkts,
                codecConfigured ? "解码中" : "等待 SPS/PPS...");

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
