package com.github.catvod.spider;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * PlayerLauncher — 通过 HTTP 推送给壳触发播放, 不弹外部 app.
 *
 * <p>关键: webhtv 蜂蜜壳的"输入链接直接播放"走的是
 * {@code POST http://127.0.0.1:9978/action?do=push&url=<url>}。
 * 壳的 NanoHTTPD 收到后通过 EventBus 派发 ServerEvent.PUSH,
 * HomeActivity 监听到后调 VideoActivity.push → VideoActivity.start(key=push_agent, id=url)。
 * 整个链路在壳内, 不会弹外部 app。
 *
 * <p>NanoHTTPD 端口是 9978-9998 第一个可用, 我们尝试 9978~9999。
 */
class PlayerLauncher {

    private static final String TAG = "PlayerLauncher";

    private static final int[] PORTS = {9978, 9979, 9980, 9981, 9982, 9983, 9984, 9985, 9986, 9987, 9988, 9989, 9990, 9991, 9992, 9993, 9994, 9995, 9996, 9997, 9998, 9999};
    private static final String ACTION_PATH = "/action?do=push";

    private final Context ctx;
    private final Handler main;
    private volatile int detectedPort = -1;

    PlayerLauncher(Context context) {
        this.ctx = context;
        this.main = new Handler(Looper.getMainLooper());
    }

    /**
     * 推送到壳内播放. 完全在壳内, 不弹外部 app.
     * @param url 视频 URL (m3u8 / mp4 / magnet: / ed2k: / push:// 都可以)
     * @param title 标题 (壳目前用不到, 主要给系统播放器 fallback 用)
     * @param pic 海报 (备用)
     * @param wallPic 背景图 (备用)
     * @param key 站点 key (推送给壳时, key=push_agent 表示推送播放)
     */
    void playInShell(String url, String title, String pic, String wallPic, String key) {
        if (TextUtils.isEmpty(url)) {
            Log.e(TAG, "playInShell: url empty");
            return;
        }
        main.post(() -> pushToShell(url));
    }

    /**
     * 通过 HTTP POST 推送 url 到壳的 NanoHTTPD /action?do=push
     */
    private void pushToShell(String url) {
        new Thread(() -> {
            // 1. 检测端口: 找第一个能连通的端口
            int port = detectPort();
            if (port <= 0) {
                Log.e(TAG, "no NanoHTTPD port found (9978-9999), fallback to system player");
                fallbackToSystem(url, "");
                return;
            }
            detectedPort = port;
            Log.d(TAG, "found shell NanoHTTPD at port " + port);

            // 2. POST /action?do=push&url=<url>
            try {
                boolean ok = doPost(port, url);
                if (ok) {
                    Log.d(TAG, "push success: " + url);
                } else {
                    Log.w(TAG, "push failed, fallback to system player");
                    fallbackToSystem(url, "");
                }
            } catch (Throwable t) {
                Log.e(TAG, "pushToShell failed", t);
                fallbackToSystem(url, "");
            }
        }, "webhome-push").start();
    }

    /**
     * 探测 NanoHTTPD 端口: 找第一个能响应的 (GET / 返回任何内容)
     */
    private int detectPort() {
        for (int p : PORTS) {
            if (isPortOpen(p)) return p;
        }
        return -1;
    }

    private boolean isPortOpen(int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/device").openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /**
     * POST /action?do=push&url=<encoded_url>
     */
    private boolean doPost(int port, String url) {
        HttpURLConnection conn = null;
        try {
            // 用 form 表单格式 POST
            String fullUrl = "http://127.0.0.1:" + port + ACTION_PATH + "&url=" + URLEncoder.encode(url, "UTF-8");
            conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            // 即使没有 body 也要发空 body
            OutputStream os = conn.getOutputStream();
            os.write(new byte[0]);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            String body = "";
            try {
                java.io.InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (is != null) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    body = baos.toString("UTF-8").trim();
                    is.close();
                }
            } catch (Throwable ignored) {}
            // webhtv 壳的 /action 成功响应 "OK"
            boolean ok = code == 200 && ("OK".equals(body) || body.isEmpty());
            Log.d(TAG, "push response: code=" + code + " body=" + body);
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "doPost failed", t);
            return false;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void fallbackToSystem(String url, String title) {
        // 兜底: 调起系统播放器 (用户看到"选择打开"对话框就懂了)
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(android.net.Uri.parse(url), guessMimeType(url));
            intent.putExtra("title", title);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ctx != null) ctx.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "fallback failed", t);
        }
    }

    private String guessMimeType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8")) return "application/x-mpegURL";
        if (lower.contains(".mpd")) return "application/dash+xml";
        if (lower.contains(".mp4")) return "video/mp4";
        if (lower.contains(".mkv")) return "video/x-matroska";
        if (lower.contains(".webm")) return "video/webm";
        if (lower.contains(".ts")) return "video/mp2t";
        return "video/*";
    }

    void openSearch(String keyword, boolean direct) {
        if (ctx == null) return;
        // search 走 /action?do=search
        main.post(() -> {
            new Thread(() -> {
                int port = detectedPort > 0 ? detectedPort : detectPort();
                if (port <= 0) return;
                try {
                    String fullUrl = "http://127.0.0.1:" + port + "/action?do=search&word=" + URLEncoder.encode(keyword, "UTF-8");
                    HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setDoOutput(true);
                    conn.getOutputStream().write(new byte[0]);
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Throwable t) {
                    Log.w(TAG, "openSearch failed", t);
                }
            }).start();
        });
    }

    void openMainActivity(String simpleName) {
        // 通用壳没有 HTTP 入口打开 Activity, 默认 no-op
        // 壳作者可以通过 setHandler() 覆盖实现
    }
}
