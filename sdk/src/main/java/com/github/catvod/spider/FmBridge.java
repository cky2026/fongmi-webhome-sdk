package com.github.catvod.spider;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * FmBridge — fongmiBridge JS 接口 + HTTP 桥.
 * JS 字符串嵌入在 {@link FmSdk}, 通过 {@link #setCurrentPageUrl(String)} 跟踪当前页面 URL.
 *
 * <p>不实现 shouldInterceptRequest — 资源由 WebView 自己加载.
 */
public class FmBridge {

    private static final int INLINE_LIMIT = 12000;
    private static final int CHUNK_SIZE = 60000;
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private final WebView webView;
    private final Context appContext;
    private final FmActionHandler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();

    private volatile String currentPageUrl = "";

    public FmBridge(WebView webView, FmActionHandler handler) {
        this.webView = webView;
        this.appContext = webView.getContext().getApplicationContext();
        this.handler = handler != null ? handler : new DefaultFmActionHandler(this.appContext);
    }

    public void setCurrentPageUrl(String url) {
        this.currentPageUrl = url == null ? "" : url;
    }

    public String getCurrentPageUrl() {
        return currentPageUrl;
    }

    @JavascriptInterface
    public void invoke(final String requestId, final String method, final String payload) {
        POOL.execute(() -> {
            try {
                String result = handle(method, parseObject(payload));
                resolve(requestId, result);
            } catch (Throwable e) {
                reject(requestId, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    @JavascriptInterface
    public void console(String level, String message) {
        android.util.Log.d("WebHomeConsole", "[" + level + "] " + message);
    }

    @JavascriptInterface
    public void network(String type, String method, String url, int status, long durationMs, String detail) {
        android.util.Log.d("WebHomeNet", type + " " + method + " " + url + " " + status + " " + durationMs + "ms " + detail);
    }

    @JavascriptInterface
    public String resourceUrl(String url, String options) {
        try {
            JSONObject opt = parseObject(options);
            StringBuilder sb = new StringBuilder();
            sb.append("http://127.0.0.1:9978/webResource?url=").append(encode(url));
            if (opt.has("headers")) {
                try {
                    sb.append("&headers=").append(encode(opt.get("headers").toString()));
                } catch (JSONException e) { /* ignore */ }
            }
            if ("include".equalsIgnoreCase(opt.optString("credentials"))) {
                sb.append("&credentials=include");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "http://127.0.0.1:9978/webResource?url=" + url;
        }
    }

    @JavascriptInterface
    public int resultLength(String id) {
        String r = results.get(id);
        return r == null ? 0 : r.length();
    }

    @JavascriptInterface
    public String resultChunk(String id, int start) {
        String r = results.get(id);
        if (r == null || start < 0 || start >= r.length()) return "";
        int end = Math.min(start + CHUNK_SIZE, r.length());
        return r.substring(start, end);
    }

    @JavascriptInterface
    public void clearResult(String id) {
        results.remove(id);
    }

    @JavascriptInterface
    public void inlineResult(String id, String payload) {
        // 占位 — 默认 no-op
    }

    private String handle(String method, JSONObject payload) {
        if (handler == null) return "{}";
        switch (method) {
            case "net.request":     return handleNetRequest(payload);
            case "net.resourceUrl": return quote(resourceUrl(payload.optString("url"), payload.toString()));

            case "player.playUrl":      handler.playUrl(payload.optString("url"), payload.optString("title"), payload); return "{}";
            case "player.playVod":      handler.playVod(payload.optString("siteKey"), payload.optString("vodId"),
                    payload.optString("title"), payload.optString("pic"), payload); return "{}";
            case "player.playVodInline": handler.playVodInline(payload); return "{}";
            case "player.preloadArtwork": handler.preloadArtwork(payload.optString("pic"), payload.optString("wallPic")); return "{}";
            case "player.control":      handler.controlPlayer(payload.optString("action")); return "{}";
            case "player.status":       return handler.playerStatus().toString();

            case "app.search":          handler.search(payload.optString("keyword"), payload); return "{}";
            case "app.openVod":         handler.openVod(); return "{}";
            case "app.openLive":        handler.openLive(); return "{}";
            case "app.openKeep":        handler.openKeep(); return "{}";
            case "app.openSetting":     handler.openSetting(); return "{}";
            case "app.history":         return handler.history().toString();

            case "cache.get":           return quote(handler.cacheGet(payload.optString("key"), payload.optString("rule")));
            case "cache.set":           handler.cacheSet(payload.optString("key"), payload.optString("value"), payload.optString("rule")); return "{}";
            case "cache.del":           handler.cacheDel(payload.optString("key"), payload.optString("rule")); return "{}";

            case "ui.setToolbar":       handler.setToolbar(!payload.has("visible") || payload.optBoolean("visible")); return "{}";
            case "ui.setChrome":        handler.setChrome(payload); return "{}";
            case "ui.restoreChrome":    handler.restoreChrome(); return "{}";
            case "ui.getViewport":      return handler.getViewport().toString();

            case "device.info":         return handler.deviceInfo().toString();
            case "site.info":           return handler.siteInfo().toString();
            case "config.info":         return handler.configInfo().toString();

            case "ext.info":            return handler.extInfo().toString();
            case "ext.log":             handler.extLog(payload.optString("message"), payload.optString("data")); return "{}";
            case "ext.toast":           handler.extToast(payload.optString("message")); return "{}";

            case "pan.check":           return handler.panCheck(payload).toString();
            case "pan.play":            handler.panPlay(payload); return "{}";

            case "navigation.back":     handler.navigationBack(); return "{}";
            case "navigation.reload":   handler.navigationReload(); return "{}";

            default:
                throw new IllegalArgumentException("Unknown method: " + method);
        }
    }

    private String handleNetRequest(JSONObject payload) {
        String url = payload.optString("url");
        String method = (payload.optString("method", "GET")).toUpperCase(Locale.ROOT);
        JSONObject headers = payload.optJSONObject("headers");
        String body = payload.optString("body");
        String responseType = payload.optString("responseType", "text");
        int timeout = payload.optInt("timeout", 30);
        boolean includeCookie = "include".equalsIgnoreCase(payload.optString("credentials"));

        FmHttpResponse resp = doHttp(url, method, headers, body, responseType, timeout, includeCookie);
        JSONObject out = new JSONObject();
        try {
            out.put("ok", resp.ok());
            out.put("status", resp.status);
            out.put("url", resp.url);
            if ("base64".equalsIgnoreCase(responseType)) {
                out.put("body", resp.base64 == null ? "" : resp.base64);
            } else {
                out.put("body", resp.text == null ? "" : resp.text);
            }
            if (resp.error != null) out.put("error", resp.error);
        } catch (JSONException ignored) {}
        return out.toString();
    }

    private FmHttpResponse doHttp(String url, String method, JSONObject headers, String body,
                                  String responseType, int timeout, boolean includeCookie) {
        if (TextUtils.isEmpty(url)) return new FmHttpResponse(0, "", null, null, "empty url");
        int toMs = timeout > 0 ? timeout * 1000 : 30000;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(toMs);
            conn.setReadTimeout(toMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
            conn.setRequestProperty("Connection", "keep-alive");

            if (headers != null) {
                Iterator<String> it = headers.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String lk = k.toLowerCase();
                    if (lk.equals("host") || lk.equals("content-length")
                            || lk.equals("connection") || lk.equals("accept-encoding")) continue;
                    conn.setRequestProperty(k, headers.optString(k));
                }
            }

            if (includeCookie && appContext != null) {
                try {
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (!TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);
                } catch (Throwable ignored) {}
            }

            String m = method == null || method.isEmpty() ? "GET" : method;
            conn.setRequestMethod(m);
            if (!"GET".equals(m) && !"HEAD".equals(m) && body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush();
                os.close();
            }

            int code = conn.getResponseCode();
            String finalUrl = conn.getURL().toString();
            String encoding = conn.getContentEncoding();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] raw = readAll(is, encoding);

            if ("base64".equalsIgnoreCase(responseType)) {
                return new FmHttpResponse(code, finalUrl, null,
                        Base64.encodeToString(raw, Base64.NO_WRAP), null);
            }
            return new FmHttpResponse(code, finalUrl, new String(raw, "UTF-8"), null, null);
        } catch (Throwable t) {
            return new FmHttpResponse(0, url, null, null, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static byte[] readAll(InputStream in, String encoding) throws IOException {
        if (in == null) return new byte[0];
        try {
            if ("gzip".equalsIgnoreCase(encoding)) in = new GZIPInputStream(in);
            else if ("deflate".equalsIgnoreCase(encoding)) in = new InflaterInputStream(in);
        } catch (IOException ignored) {}
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private void resolve(String requestId, String value) {
        if (value == null) value = "{}";
        if (value.length() > INLINE_LIMIT) {
            String resultId = "r_" + UUID.randomUUID().toString().replace("-", "");
            results.put(resultId, value);
            value = "{\"__fmResultId\":\"" + resultId + "\"}";
        }
        final String inject = "window.fongmiNative && window.fongmiNative.resolve("
                + quote(requestId) + "," + value + ");";
        runOnUi(() -> {
            try { webView.evaluateJavascript(inject, null); } catch (Throwable t) {}
        });
    }

    private void reject(String requestId, String error) {
        String safe = error == null ? "" : error.replace("'", "\\'").replace("\n", " ");
        final String inject = "window.fongmiNative && window.fongmiNative.reject("
                + quote(requestId) + ",'" + safe + "');";
        runOnUi(() -> {
            try { webView.evaluateJavascript(inject, null); } catch (Throwable t) {}
        });
    }

    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String encode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static JSONObject parseObject(String s) {
        if (TextUtils.isEmpty(s)) return new JSONObject();
        try { return new JSONObject(s); } catch (JSONException e) { return new JSONObject(); }
    }
}
