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
import java.util.concurrent.ArrayBlockingQueue;

/**
 * RockitCam H.264 UDP 图传接收端
 *
 * 架构：3 线程分离
 *   线程1 UdpRecv：    纯收包，零处理，速度最快
 *   线程2 NaluParser：  从收包队列取数据，拼接+解析 NALU，喂给 MediaCodec 输入
 *   线程3 DecodeOut：   持续排空 MediaCodec 输出，渲染到 Surface（带 vsync）
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
    private volatile long recvBytes = 0;
    private volatile int recvPkts = 0;
    private volatile int decodedFrames = 0;
    private volatile int droppedPkts = 0;
    private long lastStatTime = 0;

    // ── 收包 → 解析 的无锁队列 ──
    // 预分配 byte[] 池，避免 GC
    private static final int QUEUE_SIZE = 512;
    private static final int PKT_BUF_SIZE = 1500;
    private final ArrayBlockingQueue<byte[]> freePool = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final ArrayBlockingQueue<PacketData> recvQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);

    private static class PacketData {
        byte[] data;
        int length;
    }

    // ── NALU 拼接缓冲 ──
    private byte[] naluBuffer = new byte[1024 * 1024];  // 1MB
    private int naluLen = 0;

    private volatile boolean codecConfigured = false;
    private byte[] spsData;
    private byte[] ppsData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        uiHandler = new Handler(Looper.getMainLooper());

        SurfaceView sv = findViewById(R.id.surfaceView);
        sv.getHolder().addCallback(this);

        // 预分配 buffer 池
        for (int i = 0; i < QUEUE_SIZE; i++) {
            freePool.offer(new byte[PKT_BUF_SIZE]);
        }

        updateStatus("等待 Surface 就绪...");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        running = true;

        // 线程1：纯收包
        new Thread(this::udpRecvLoop, "UdpRecv").start();

        // 线程2：NALU 解析 + 喂解码器输入
        new Thread(this::naluParseLoop, "NaluParser").start();

        // 线程3：解码器输出 + 渲染
        new Thread(this::decodeOutputLoop, "DecodeOut").start();

        // 心跳
        new Thread(this::heartbeatLoop, "Heartbeat").start();

        // 统计
        uiHandler.postDelayed(this::updateStats, 1000);

        updateStatus("已启动，等待数据...");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        sendCommand("RKCAM:LEAVE");
        // 等线程退出
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
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
    //  线程1：纯 UDP 收包，不做任何处理
    // ============================================================
    private void udpRecvLoop() {
        try {
            socket = new DatagramSocket(LOCAL_PORT);
            socket.setSoTimeout(2000);
            socket.setReceiveBufferSize(1024 * 1024);  // 1MB 接收缓冲

            sendCommand("RKCAM:CONNECT");
            updateStatus("已注册，等待 H.264 数据...");

            byte[] directBuf = new byte[PKT_BUF_SIZE];
            DatagramPacket pkt = new DatagramPacket(directBuf, directBuf.length);

            while (running) {
                try {
                    socket.receive(pkt);
                    int len = pkt.getLength();
                    if (len <= 0) continue;

                    recvBytes += len;
                    recvPkts++;

                    // 从池中取 buffer，拷贝数据，放入队列
                    byte[] buf = freePool.poll();
                    if (buf == null) {
                        // 池空了 = 解析线程跟不上，丢包
                        droppedPkts++;
                        continue;
                    }
                    System.arraycopy(directBuf, 0, buf, 0, len);

                    PacketData pd = new PacketData();
                    pd.data = buf;
                    pd.length = len;

                    if (!recvQueue.offer(pd)) {
                        // 队列满，归还 buffer 并丢弃
                        freePool.offer(buf);
                        droppedPkts++;
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
    //  线程2：从队列取包 → 拼接 → 解析 NALU → 喂 MediaCodec 输入
    // ============================================================
    private void naluParseLoop() {
        while (running) {
            try {
                PacketData pd = recvQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (pd == null) continue;

                // 追加到 NALU 缓冲
                if (naluLen + pd.length < naluBuffer.length) {
                    System.arraycopy(pd.data, 0, naluBuffer, naluLen, pd.length);
                    naluLen += pd.length;
                }

                // 归还 buffer 到池
                freePool.offer(pd.data);

                // 提取完整 NALU
                processNaluBuffer();

            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * 从缓冲区中找 start code (00 00 00 01) 分割 NALU
     */
    private void processNaluBuffer() {
        int pos = 0;
        int lastStart = -1;

        while (pos + 3 < naluLen) {
            if (naluBuffer[pos] == 0 && naluBuffer[pos + 1] == 0 &&
                naluBuffer[pos + 2] == 0 && naluBuffer[pos + 3] == 1) {

                if (lastStart >= 0) {
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
        } else if (naluLen > 512 * 1024) {
            naluLen = 0;
        }
    }

    /**
     * 把一个完整 NALU 喂给 MediaCodec
     */
    private void feedNalu(byte[] data, int offset, int length) {
        if (length < 5) return;

        int naluType = data[offset + 4] & 0x1F;

        // SPS (7)
        if (naluType == 7) {
            if (!codecConfigured) {
                spsData = new byte[length];
                System.arraycopy(data, offset, spsData, 0, length);
            }
            return;
        }

        // PPS (8)
        if (naluType == 8) {
            if (!codecConfigured && spsData != null) {
                ppsData = new byte[length];
                System.arraycopy(data, offset, ppsData, 0, length);
                initDecoder(spsData, ppsData);
            }
            return;
        }

        // IDR (5) 或 P 帧 (1)
        if (decoder != null && codecConfigured) {
            feedToDecoder(data, offset, length, naluType == 5);
        }
    }

    private void initDecoder(byte[] sps, byte[] pps) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 800, 480);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            // 低延迟提示
            format.setInteger("low-latency", 1);

            decoder = MediaCodec.createDecoderByType("video/avc");
            decoder.configure(format, surface, null, 0);
            decoder.start();
            codecConfigured = true;

            updateStatus("解码器已初始化");
        } catch (IOException e) {
            updateStatus("解码器初始化失败: " + e.getMessage());
        }
    }

    /**
     * 只负责往 MediaCodec 输入端送数据，不取输出（输出由线程3处理）
     */
    private void feedToDecoder(byte[] data, int offset, int length, boolean isIDR) {
        try {
            int inIdx = decoder.dequeueInputBuffer(5000);  // 5ms 超时
            if (inIdx < 0) return;  // 输入满了就跳过，不阻塞收包

            ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
            if (inBuf == null) return;

            inBuf.clear();
            inBuf.put(data, offset, length);

            int flags = isIDR ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
            decoder.queueInputBuffer(inIdx, 0, length, System.nanoTime() / 1000, flags);
        } catch (Exception e) {
            sendCommand("RKCAM:IDR");
        }
    }

    // ============================================================
    //  线程3：持续排空 MediaCodec 输出，渲染到 Surface
    // ============================================================
    private void decodeOutputLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (running) {
            if (decoder == null || !codecConfigured) {
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                continue;
            }

            try {
                int outIdx = decoder.dequeueOutputBuffer(info, 10000);  // 10ms 等待
                if (outIdx >= 0) {
                    // 用时间戳渲染，利用 vsync 防撕裂
                    decoder.releaseOutputBuffer(outIdx, info.presentationTimeUs * 1000);
                    decodedFrames++;
                }
            } catch (Exception e) {
                // 解码器可能被释放了
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void releaseDecoder() {
        codecConfigured = false;
        if (decoder != null) {
            try {
                decoder.stop();
                decoder.release();
            } catch (Exception e) { /* ignore */ }
            decoder = null;
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
                "码率: %.0f kbps | 帧率: %.1f fps\n" +
                "收包: %d | 丢弃: %d\n" +
                "状态: %s",
                kbps, fps, recvPkts, droppedPkts,
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
