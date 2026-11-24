package com.sampmobile.game.ui;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 极简本地静态文件服务器（2026-11-22）。
 *
 * 为什么需要它：WebView 加载 file:// 页面时受同源策略限制，fetch / ES module /
 * localStorage 都有坑，而且 file:// 读 /sdcard 在部分机型上直接失败。走
 * http://127.0.0.1:&lt;随机端口&gt;/ 后，页面就是一个正常的 http 源：
 * 相对路径、fetch、module、缓存控制全部符合直觉。
 *
 * 关键收益：页面根目录是外部存储里的 files/web/，改页面只要 adb push 一个文件
 * 再 reload，不必重新打包 APK。
 *
 * 只绑 127.0.0.1（不开局域网），只处理 GET / HEAD，读不到就 404。
 */
final class WebRootServer {

    private static final String TAG = "WebRootServer";

    /** 单次请求头上限，超过就断开，避免恶意长头把内存撑爆。 */
    private static final int MAX_HEAD_LINES = 64;
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

    private final File root;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "webroot-io");
        t.setDaemon(true);
        return t;
    });

    private volatile ServerSocket server;
    private volatile boolean running;

    WebRootServer(File root) {
        this.root = root;
    }

    /** 启动并返回实际端口；端口由系统分配，避免和游戏/其它服务抢。 */
    int start() throws IOException {
        ServerSocket ss = new ServerSocket(0, 24, InetAddress.getByName("127.0.0.1"));
        server = ss;
        running = true;
        Thread t = new Thread(() -> acceptLoop(ss), "webroot-accept");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "listening on 127.0.0.1:" + ss.getLocalPort() + " root=" + root);
        return ss.getLocalPort();
    }

    void stop() {
        running = false;
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
        try {
            pool.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                final Socket s = ss.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept: " + e);
                if (ss.isClosed()) return;
            }
        }
    }

    // ------------------------------------------------------------------ 请求处理

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(8000);
            sock.setTcpNoDelay(true);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), "ISO-8859-1"), 1024);

            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            // 读完请求头（我们不需要 header 内容，但必须消费掉，否则客户端可能阻塞）
            for (int i = 0; i < MAX_HEAD_LINES; i++) {
                String h = in.readLine();
                if (h == null || h.isEmpty()) break;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeStatus(sock, 400, "Bad Request", "bad request line");
                return;
            }

            String method = parts[0].toUpperCase(Locale.US);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeStatus(sock, 405, "Method Not Allowed", "only GET/HEAD");
                return;
            }

            File f = resolve(parts[1]);
            if (f == null) {
                writeStatus(sock, 403, "Forbidden", "bad path");
                return;
            }
            if (!f.isFile()) {
                writeStatus(sock, 404, "Not Found", parts[1]);
                return;
            }
            if (f.length() > MAX_BODY_BYTES) {
                writeStatus(sock, 413, "Too Large", "file too big");
                return;
            }

            byte[] body = readAll(f);
            if (body == null) {
                writeStatus(sock, 500, "Read Failed", f.getName());
                return;
            }

            StringBuilder sb = new StringBuilder(160);
            sb.append("HTTP/1.1 200 OK\r\n");
            sb.append("Content-Type: ").append(mimeOf(f.getName())).append("\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n");
            // 开发期：永远不缓存。改完 push 上去 reload 就是新的，不用清 WebView 缓存。
            sb.append("Cache-Control: no-store, must-revalidate\r\n");
            sb.append("Pragma: no-cache\r\n");
            sb.append("Connection: close\r\n\r\n");

            OutputStream out = sock.getOutputStream();
            out.write(sb.toString().getBytes("ISO-8859-1"));
            if (!"HEAD".equals(method)) out.write(body);
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "handle: " + t);
        }
    }

    /** URL -> 磁盘文件。越界、目录穿越一律拒绝。 */
    private File resolve(String rawTarget) {
        try {
            String p = rawTarget;
            int q = p.indexOf('?');
            if (q >= 0) p = p.substring(0, q);
            int hash = p.indexOf('#');
            if (hash >= 0) p = p.substring(0, hash);
            p = URLDecoder.decode(p, "UTF-8");

            if (p.isEmpty() || "/".equals(p)) p = "/index.html";
            while (p.startsWith("/")) p = p.substring(1);
            if (p.isEmpty()) p = "index.html";

            File f = new File(root, p);
            if (f.isDirectory()) f = new File(f, "index.html");

            String canonRoot = root.getCanonicalPath();
            String canonFile = f.getCanonicalPath();
            if (!canonFile.equals(canonRoot) && !canonFile.startsWith(canonRoot + File.separator)) {
                Log.w(TAG, "path escape blocked: " + rawTarget);
                return null;
            }
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "resolve(" + rawTarget + "): " + t);
            return null;
        }
    }

    private static byte[] readAll(File f) {
        try (InputStream ins = new FileInputStream(f)) {
            long len = f.length();
            byte[] buf = new byte[(int) len];
            int off = 0;
            while (off < buf.length) {
                int n = ins.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off == buf.length) return buf;
            byte[] cut = new byte[off];
            System.arraycopy(buf, 0, cut, 0, off);
            return cut;
        } catch (Throwable t) {
            Log.w(TAG, "read " + f + ": " + t);
            return null;
        }
    }

    private void writeStatus(Socket sock, int code, String reason, String detail) {
        try {
            String body = "<!doctype html><meta charset=utf-8>"
                    + "<body style='background:#111;color:#eee;font:14px sans-serif;padding:24px'>"
                    + "<h3>" + code + " " + reason + "</h3><pre>" + escape(detail) + "</pre>";
            byte[] b = body.getBytes("UTF-8");
            String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = sock.getOutputStream();
            out.write(head.getBytes("ISO-8859-1"));
            out.write(b);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        switch (ext) {
            case "html":
            case "htm":
                return "text/html; charset=utf-8";
            case "css":
                return "text/css; charset=utf-8";
            case "js":
            case "mjs":
                return "application/javascript; charset=utf-8";
            case "json":
                return "application/json; charset=utf-8";
            case "svg":
                return "image/svg+xml";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "webp":
                return "image/webp";
            case "gif":
                return "image/gif";
            case "ico":
                return "image/x-icon";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            case "ttf":
                return "font/ttf";
            case "otf":
                return "font/otf";
            case "mp3":
                return "audio/mpeg";
            case "ogg":
                return "audio/ogg";
            case "wav":
                return "audio/wav";
            case "mp4":
                return "video/mp4";
            case "txt":
                return "text/plain; charset=utf-8";
            default:
                return "application/octet-stream";
        }
    }
}
