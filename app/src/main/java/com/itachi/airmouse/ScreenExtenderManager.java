package com.itachi.airmouse;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;

public class ScreenExtenderManager {

    public interface FrameCallback {
        void onFrameReceived(Bitmap bitmap);
        void onConnectionChanged(String status, boolean isConnected);
    }

    private static final int PORT = 8080;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private Thread clientReaderThread;
    private volatile Socket currentActiveClient;
    private volatile boolean isRunning = false;
    private volatile boolean currentClientIsUsb = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private FrameCallback callback;

    public ScreenExtenderManager(FrameCallback callback) {
        this.callback = callback;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        serverThread = new Thread(this::listenForConnections, "ScreenExtenderServerThread");
        serverThread.start();
    }

    public void stop() {
        isRunning = false;
        closeCurrentClient();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
    }

    private synchronized void closeCurrentClient() {
        if (currentActiveClient != null) {
            try {
                currentActiveClient.close();
            } catch (IOException ignored) {}
            currentActiveClient = null;
        }
        if (clientReaderThread != null) {
            clientReaderThread.interrupt();
            clientReaderThread = null;
        }
    }

    private void listenForConnections() {
        try {
            serverSocket = new ServerSocket(PORT);
            serverSocket.setReuseAddress(true);

            notifyStatus("Ready for Connection (USB Priority)", false);

            while (isRunning) {
                Socket incomingSocket = serverSocket.accept();
                incomingSocket.setTcpNoDelay(true);
                try {
                    incomingSocket.setReceiveBufferSize(524288);
                } catch (Exception ignored) {}

                boolean isUsb = incomingSocket.getInetAddress().isLoopbackAddress()
                        || "127.0.0.1".equals(incomingSocket.getInetAddress().getHostAddress());

                synchronized (this) {
                    // USB PRIORITY ENFORCEMENT:
                    // 1. If currently connected via USB, reject any incoming Wi-Fi connection
                    if (currentActiveClient != null && !currentActiveClient.isClosed() && currentClientIsUsb && !isUsb) {
                        try {
                            incomingSocket.close();
                        } catch (IOException ignored) {}
                        continue;
                    }

                    // 2. If incoming is USB, or replacing older connection, close previous client
                    closeCurrentClient();

                    currentActiveClient = incomingSocket;
                    currentClientIsUsb = isUsb;
                    final Socket activeSocket = incomingSocket;
                    final boolean activeIsUsb = isUsb;

                    clientReaderThread = new Thread(() -> handleClientStream(activeSocket, activeIsUsb), "ScreenExtenderReaderThread");
                    clientReaderThread.start();
                }
            }
        } catch (IOException e) {
            if (isRunning) {
                notifyStatus("Port 8080 Offline", false);
            }
        }
    }

    private void handleClientStream(Socket client, boolean isUsb) {
        String connLabel = isUsb ? "🟢 USB (Ultra-Fast 120 FPS)" : "🟡 Wi-Fi (" + client.getInetAddress().getHostAddress() + ")";
        notifyStatus(connLabel + " • Streaming", true);

        try {
            DataInputStream dis = new DataInputStream(new BufferedInputStream(client.getInputStream(), 131072));
            byte[] buffer = new byte[8_000_000];
            long fpsStartTime = System.currentTimeMillis();
            int framesReceived = 0;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inMutable = true;

            while (isRunning && !client.isClosed()) {
                int length = dis.readInt();
                if (length <= 0 || length > 25_000_000) {
                    break;
                }
                if (buffer.length < length) {
                    buffer = new byte[length + 131072];
                }
                dis.readFully(buffer, 0, length);
                Bitmap bitmap = BitmapFactory.decodeByteArray(buffer, 0, length, options);
                if (bitmap != null) {
                    framesReceived++;
                    long now = System.currentTimeMillis();
                    if (now - fpsStartTime >= 1000) {
                        int fps = (int) (framesReceived * 1000f / (now - fpsStartTime));
                        notifyStatus(connLabel + " • " + fps + " FPS (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")", true);
                        framesReceived = 0;
                        fpsStartTime = now;
                    }
                    final Bitmap frameBitmap = bitmap;
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onFrameReceived(frameBitmap);
                        }
                    });
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (IOException ignored) {}
            synchronized (this) {
                if (currentActiveClient == client) {
                    currentActiveClient = null;
                    notifyStatus("Ready for Connection (USB Priority)", false);
                }
            }
        }
    }

    private void notifyStatus(String status, boolean connected) {
        mainHandler.post(() -> {
            if (callback != null) {
                callback.onConnectionChanged(status, connected);
            }
        });
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
