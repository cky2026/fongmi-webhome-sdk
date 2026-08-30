package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.github.catvod.crawler.Spider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class WebHome extends Spider {

    private static final int MAX_ACTIVITY_RETRIES = 18;
    private static volatile Context appContext;
    private static volatile boolean lifecycleInstalled;
    private static volatile Overlay overlay;
    private static volatile WeakReference<Activity> foreground = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    // 1. API 请求线程池（最大并发 12，防止 JS 卡死）
    private static final ExecutorService HTTP_EXECUTOR = Executors.newFixedThreadPool(12);

    // 2. 图片拦截信号量（限制同时最多 6 个图片下载请求，其余在后台排队，防止挤爆网络）
    private static final Semaphore IMAGE_SEMAPHORE = new Semaphore(6);

    private static volatile FmActionHandler globalHandler;
    private String extend = "";

    public static void setHandler(FmActionHandler handler) {
        globalHandler = handler;
    }

    public static void useDefaultHandler() {
        globalHandler = new DefaultFmActionHandler(appContext);
    }

    @Override
    public void init(Context context, String str) {
        if (context != null) {
            Context applicationContext = context.getApplicationContext();
            if (applicationContext != null) {
                context = applicationContext;
            }
            appContext = context;
            installLifecycleTracker(appContext);
        }
        this.extend = str == null ? "" : str.trim();
    }

    @Override
    public String homeContent(boolean z) {
        open(this.extend, runtimeSiteKey(), 0);
        return "{\"class\":[],\"list\":[]}";
    }

    @Override
    public String homeVideoContent() {
        return "{\"list\":[]}";
    }

    @Override
    public void destroy() {
        close();
    }

    private String runtimeSiteKey() {
        return this.siteKey == null ? "" : this.siteKey.trim();
    }

    private static void open(final String str, final String str2, final int i) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Activity activity = activity();
                if (!usable(activity)) {
                    if (i < MAX_ACTIVITY_RETRIES) {
                        MAIN.postDelayed(new Runnable() {
                            @Override
                            public void run() { open(str, str2, i + 1); }
                        }, 180L);
                    }
                    return;
                }
                remember(activity);
                String normalize = normalize(str);
                if (normalize.length() == 0) return;
                if (overlay != null && overlay.isShowing()) {
                    overlay.dismiss();
                }
                overlay = new Overlay(activity, normalize, str2);
                overlay.show();
            }
        });
    }

    private static void close() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (overlay != null) {
                    overlay.dismiss();
                }
                overlay = null;
            }
        });
    }

    private static void installLifecycleTracker(Context context) {
        if (lifecycleInstalled || !(context instanceof Application)) return;
        synchronized (LOCK) {
            if (lifecycleInstalled) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) { remember(a); }
                @Override public void onActivityStarted(Activity a) { remember(a); }
                @Override public void onActivityResumed(Activity a) { remember(a); }
                @Override public void onActivityPaused(Activity a) { }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) {
                    if (((Activity) foreground.get()) == a) {
                        foreground = new WeakReference<>(null);
                    }
                }
            });
            lifecycleInstalled = true;
        }
    }

    private static void remember(Activity a) {
        if (usable(a)) foreground = new WeakReference<>(a);
    }

    private static boolean usable(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    private static Activity activity() {
        Activity a = foreground.get();
        if (usable(a)) return a;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object obj = f.get(thread);
            if (obj instanceof Map) {
                for (Object r : ((Map<?, ?>) obj).values()) {
                    if (r == null) continue;
                    Field pf = r.getClass().getDeclaredField("paused");
                    pf.setAccessible(true);
                    if (Boolean.TRUE.equals(pf.get(r))) continue;
                    Field af = r.getClass().getDeclaredField("activity");
                    af.setAccessible(true);
                    Object act = af.get(r);
                    if (act instanceof Activity && usable((Activity) act)) {
                        return (Activity) act;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String normalize(String str) {
        if (str == null) return "";
        String trim = str.trim();
        if (trim.startsWith("file://") || trim.startsWith("http://") || trim.startsWith("https://")) {
            return trim;
        }
        if (trim.startsWith("/")) return Uri.fromFile(new File(trim)).toString();
        return trim;
    }

    // ================= Native 内部 HTTP 执行器 =================

    public static String doNativeReq(String urlStr, String optJson) {
        JSONObject res = new JSONObject();
        try {
            JSONObject opt = TextUtils.isEmpty(optJson) ? new JSONObject() : new JSONObject(optJson);
            String method = opt.optString("method", "GET").toUpperCase();
            JSONObject headers = opt.optJSONObject("headers");
            String body = opt.optString("body", "");
            int timeout = opt.optInt("timeout", 20) * 1000;

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");

            boolean hasUA = false;
            boolean hasCookie = false;

            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = headers.optString(k, "");
                    // 保留空值头（例如 Referer: ""）
                    conn.setRequestProperty(k, v);
                    if ("user-agent".equalsIgnoreCase(k)) hasUA = true;
                    if ("cookie".equalsIgnoreCase(k) && !TextUtils.isEmpty(v)) hasCookie = true;
                }
            }

            if (!hasCookie) {
                String cookie = CookieManager.getInstance().getCookie(urlStr);
                if (!TextUtils.isEmpty(cookie)) {
                    conn.setRequestProperty("Cookie", cookie);
                }
            }

            if (!hasUA) {
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; ELI-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            }

            if ("POST".equals(method) || "PUT".equals(method)) {
                conn.setDoOutput(true);
                if (!TextUtils.isEmpty(body)) {
                    conn.getOutputStream().write(body.getBytes("UTF-8"));
                }
            }

            int code = conn.getResponseCode();
            res.put("status", code);
            res.put("ok", code >= 200 && code < 300);

            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is != null) {
                String encoding = conn.getContentEncoding();
                if (encoding != null) {
                    if (encoding.equalsIgnoreCase("gzip")) {
                        is = new GZIPInputStream(is);
                    } else if (encoding.equalsIgnoreCase("deflate")) {
                        is = new InflaterInputStream(is);
                    }
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                is.close();
                res.put("body", new String(baos.toByteArray(), "UTF-8"));
            } else {
                res.put("body", "");
            }
        } catch (Throwable t) {
            try {
                res.put("ok", false);
                res.put("status", 0);
                res.put("error", t.getMessage());
            } catch (Throwable ignored) {}
        }
        return res.toString();
    }

    // ================= 图片资源排队与流控制 =================

    private static class WebResourceData {
        int code = 200;
        String message = "OK";
        String contentType = "image/*";
        String contentRange = "";
        InputStream stream;
    }

    // 自动在流关闭时释放信号量的包装 InputStream
    private static class SemaphoreInputStream extends FilterInputStream {
        private final Semaphore semaphore;
        private boolean released = false;

        protected SemaphoreInputStream(InputStream in, Semaphore semaphore) {
            super(in);
            this.semaphore = semaphore;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                release();
            }
        }

        private synchronized void release() {
            if (!released) {
                released = true;
                if (semaphore != null) {
                    semaphore.release();
                }
            }
        }
    }

    private static WebResourceData fetchResourceData(Uri uri, String extraRange) {
        if (uri == null) return null;
        String targetUrl = uri.getQueryParameter("url");
        if (TextUtils.isEmpty(targetUrl)) return null;

        String headersJson = uri.getQueryParameter("headers");
        boolean acquired = false;

        try {
            // 排队获取信号量，最多等待 10 秒
            acquired = IMAGE_SEMAPHORE.tryAcquire(10, TimeUnit.SECONDS);
            if (!acquired) return null;

            URL url = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");

            boolean hasUA = false;

            if (!TextUtils.isEmpty(headersJson)) {
                try {
                    JSONObject jsonObj = new JSONObject(headersJson);
                    Iterator<String> keys = jsonObj.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = jsonObj.optString(key, "");
                        conn.setRequestProperty(key, value);
                        if ("user-agent".equalsIgnoreCase(key)) hasUA = true;
                    }
                } catch (Throwable ignored) {}
            }

            if (!TextUtils.isEmpty(extraRange)) {
                conn.setRequestProperty("Range", extraRange);
            }

            String cookie = CookieManager.getInstance().getCookie(targetUrl);
            if (!TextUtils.isEmpty(cookie)) {
                conn.setRequestProperty("Cookie", cookie);
            }

            if (!hasUA) {
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; ELI-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            }

            conn.connect();

            int responseCode = conn.getResponseCode();
            String responseMessage = conn.getResponseMessage();

            InputStream is = (responseCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                if (acquired) IMAGE_SEMAPHORE.release();
                return null;
            }

            String encoding = conn.getContentEncoding();
            if (encoding != null) {
                if (encoding.equalsIgnoreCase("gzip")) {
                    is = new GZIPInputStream(is);
                } else if (encoding.equalsIgnoreCase("deflate")) {
                    is = new InflaterInputStream(is);
                }
            }

            WebResourceData data = new WebResourceData();
            data.code = responseCode;
            data.message = TextUtils.isEmpty(responseMessage) ? "OK" : responseMessage;
            data.contentType = conn.getContentType() != null ? conn.getContentType() : "image/*";
            data.contentRange = conn.getHeaderField("Content-Range");
            // 将输入流包装，WebView 读取完毕或关闭流时会自动释放信号量
            data.stream = new SemaphoreInputStream(is, IMAGE_SEMAPHORE);

            return data;

        } catch (Throwable t) {
            if (acquired) {
                IMAGE_SEMAPHORE.release();
            }
            android.util.Log.e("WebHome", "fetchResourceData error", t);
        }
        return null;
    }

    // ================= Native 桥接注入对象 =================

    public static class NativeBridge {
        @JavascriptInterface
        public void asyncReq(final String reqId, final String url, final String options) {
            // 扔进线程池异步执行，绝不卡死 WebView JS 主线程
            HTTP_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    final String resultJson = doNativeReq(url, options);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (overlay != null && overlay.web != null) {
                                String js = "if(window.fm&&window.fm._onReqResult){window.fm._onReqResult("
                                        + JSONObject.quote(reqId) + ","
                                        + JSONObject.quote(resultJson) + ");}";
                                overlay.web.evaluateJavascript(js, null);
                            }
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public String res(String url, String options) {
            try {
                String encodedUrl = URLEncoder.encode(url, "UTF-8");
                String encodedHeaders = "";
                if (!TextUtils.isEmpty(options)) {
                    JSONObject opt = new JSONObject(options);
                    JSONObject headers = opt.optJSONObject("headers");
                    if (headers != null) {
                        encodedHeaders = "&headers=" + URLEncoder.encode(headers.toString(), "UTF-8");
                    }
                }
                return "http://127.0.0.1:9978/webResource?url=" + encodedUrl + encodedHeaders;
            } catch (Throwable t) {
                return url;
            }
        }
    }

    // ================= Overlay UI 容器 =================

    private static final class Overlay extends Dialog {
        private final Activity host;
        private final String source;
        private final String sourceKey;
        private WebView web;

        Overlay(Activity activity, String source, String sourceKey) {
            super(activity, 0x0103000a);
            this.host = activity;
            this.source = source;
            this.sourceKey = sourceKey == null ? "" : sourceKey;
        }

        @Override
        protected void onCreate(Bundle bundle) {
            super.onCreate(bundle);
            requestWindowFeature(1);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(0xFF000000);
            web = new WebView(getContext());
            root.addView(web, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0xFF000000));
                window.setLayout(-1, -1);
                window.addFlags(512);
                hideSystemBars(window);
            }
            setupWebView(web);
            load(web, source);
            setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                        if (web != null && web.canGoBack()) {
                            web.goBack();
                        } else {
                            dismiss();
                        }
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public void onBackPressed() {
            if (web != null && web.canGoBack()) {
                web.goBack();
            } else {
                dismiss();
            }
        }

        @Override
        public void dismiss() {
            try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
            if (web != null) {
                try {
                    web.stopLoading();
                    web.removeJavascriptInterface("fongmiBridge");
                    web.removeJavascriptInterface("_nativeBridge");
                    web.loadUrl("about:blank");
                    web.clearHistory();
                    web.removeAllViews();
                    web.destroy();
                } catch (Throwable ignored) {}
                web = null;
            }
            super.dismiss();
            if (WebHome.overlay == this) WebHome.overlay = null;
        }

        @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
        private void setupWebView(WebView v) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            if (Build.VERSION.SDK_INT >= 26) {
                v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
            }
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setTextZoom(100);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
            if (Build.VERSION.SDK_INT >= 23) {
                s.setOffscreenPreRaster(true);
            }
            v.setBackgroundColor(0xFF000000);
            v.setOverScrollMode(View.OVER_SCROLL_NEVER);
            v.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            v.requestFocus();
            try {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(v, true);
            } catch (Throwable ignored) {}

            FmActionHandler h = globalHandler != null ? globalHandler : new DefaultFmActionHandler(getContext());
            v.addJavascriptInterface(new FmBridge(v, h), "fongmiBridge");
            v.addJavascriptInterface(new NativeBridge(), "_nativeBridge");

            v.setWebChromeClient(new WebChromeClient());
            v.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return handleUrl(view, url);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    if (req == null || req.getUrl() == null) return true;
                    return handleUrl(view, req.getUrl().toString());
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                    if (url != null && url.contains("/webResource")) {
                        return handleWebResourceResponse(Uri.parse(url), null);
                    }
                    return super.shouldInterceptRequest(view, url);
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    if (req != null && req.getUrl() != null) {
                        String urlStr = req.getUrl().toString();
                        if (urlStr.contains("/webResource")) {
                            String range = null;
                            if (req.getRequestHeaders() != null) {
                                range = req.getRequestHeaders().get("Range");
                                if (range == null) range = req.getRequestHeaders().get("range");
                            }
                            return handleWebResourceResponse(req.getUrl(), range);
                        }
                    }
                    return super.shouldInterceptRequest(view, req);
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    injectSdk(view);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
                    injectSdk(view);
                }
            });
        }

        private WebResourceResponse handleWebResourceResponse(Uri uri, String range) {
            WebResourceData data = fetchResourceData(uri, range);
            if (data == null) return null;

            String mimeType = "image/*";
            String encoding = "UTF-8";
            if (data.contentType != null) {
                String[] parts = data.contentType.split(";");
                mimeType = parts[0].trim();
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.toLowerCase().startsWith("charset=")) {
                        encoding = p.substring(8).trim();
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= 21) {
                Map<String, String> respHeaders = new HashMap<>();
                respHeaders.put("Access-Control-Allow-Origin", "*");
                respHeaders.put("Access-Control-Allow-Credentials", "true");
                respHeaders.put("Access-Control-Allow-Headers", "*");
                if (!TextUtils.isEmpty(data.contentRange)) {
                    respHeaders.put("Content-Range", data.contentRange);
                }

                return new WebResourceResponse(
                        mimeType,
                        encoding,
                        data.code,
                        data.message,
                        respHeaders,
                        data.stream
                );
            } else {
                return new WebResourceResponse(mimeType, encoding, data.stream);
            }
        }

        private void injectSdk(WebView v) {
            try {
                String js = FmSdk.get("normal", false);
                // 彻底解决同步阻塞卡死的 纯异步 Promise 桥接脚本
                String reqPolyfill = "if(window.fm && !window.fm._patched){" +
                        "window.fm._patched=true;" +
                        "window.fm._reqCallbacks={};" +
                        "window.fm._reqSeq=0;" +
                        "window.fm.req=function(u,o){return new Promise(function(resolve){" +
                        "  var id='req_'+(++window.fm._reqSeq)+'_'+Date.now();" +
                        "  window.fm._reqCallbacks[id]=function(res){" +
                        "    if(o && o.responseType==='json' && typeof res.body==='string'){" +
                        "      try{ res.body=JSON.parse(res.body); }catch(e){}" +
                        "    }" +
                        "    resolve(res);" +
                        "  };" +
                        "  try{ _nativeBridge.asyncReq(id, u, JSON.stringify(o||{})); }" +
                        "  catch(e){ delete window.fm._reqCallbacks[id]; resolve({ok:false, status:0, error:e.message}); }" +
                        "});};" +
                        "window.fm._onReqResult=function(id, resJsonStr){" +
                        "  var cb=window.fm._reqCallbacks[id];" +
                        "  if(cb){" +
                        "    delete window.fm._reqCallbacks[id];" +
                        "    try{ cb(JSON.parse(resJsonStr)); }catch(e){ cb({ok:false, status:0, error:e.message}); }" +
                        "  }" +
                        "};" +
                        "window.fm.res=function(u,o){" +
                        "  try{ return _nativeBridge.res(u, JSON.stringify(o||{})); }catch(e){ return u; }" +
                        "};" +
                        "}";
                v.evaluateJavascript(js + "\n" + reqPolyfill, null);
            } catch (Throwable t) {
                android.util.Log.e("WebHome", "injectSdk failed", t);
            }
        }

        private boolean handleUrl(WebView view, String url) {
            if (url == null || url.length() == 0) return true;
            if ("webhome://close".equalsIgnoreCase(url)) {
                dismiss();
                return true;
            }
            if (url.startsWith("file://") || url.startsWith("http://") || url.startsWith("https://")) {
                return false;
            }
            view.loadUrl(url);
            return true;
        }

        private void load(WebView webView, String url) {
            try {
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
                    webView.loadUrl(url);
                } else {
                    webView.loadDataWithBaseURL(null, "<h1>WebHome 路径无效</h1><small>" + url + "</small>", "text/html", "UTF-8", null);
                }
            } catch (Throwable th) {
                webView.loadDataWithBaseURL(null, "<h1>加载失败</h1><small>" + th.getMessage() + "</small>", "text/html", "UTF-8", null);
            }
        }

        private void hideSystemBars(Window w) {
            if (w == null) return;
            w.getDecorView().setSystemUiVisibility(5894);
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus) hideSystemBars(getWindow());
        }
    }
}
