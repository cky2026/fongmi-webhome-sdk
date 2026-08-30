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
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.github.catvod.crawler.Spider;

import com.github.catvod.crawler.Spider;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * WebHome Spider — fongmi/catvod 系影视壳的 WebHome 接入点。
 *
 * <p>站点配置 (URL 写在 ext 字段, 跟 webhtv 一致):
 * <pre>
 * {
 *   "key": "webhome",
 *   "name": "WebHome 演示",
 *   "type": 3,
 *   "api": "csp_WebHome",
 *   "ext": "https://www.252035.xyz/xs/tvbox/nostr.html",
 *   "jar": "..."
 * }
 * </pre>
 */
public class WebHome extends Spider {

    private static final int MAX_ACTIVITY_RETRIES = 18;
    private static volatile Context appContext;
    private static volatile boolean lifecycleInstalled;
    private static volatile Overlay overlay;
    private static volatile WeakReference<Activity> foreground = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static volatile FmActionHandler globalHandler;

    private String extend = "";

    /** 壳可以注入自己的 handler; 不注入就用默认带播放/搜索/缓存的 handler */
    public static void setHandler(FmActionHandler handler) {
        globalHandler = handler;
    }

    /** 用默认 handler (反射调 fongmi 壳播放/搜索 Activity) */
    public static void useDefaultHandler() {
        globalHandler = new DefaultFmActionHandler(appContext);
    }

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

    public String homeContent(boolean z) {
        open(this.extend, runtimeSiteKey(), 0);
        return "{\"class\":[],\"list\":[]}";
    }

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

    private static Context getContext() {
        return appContext;
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

    // ================= Overlay =================

    private static final class Overlay extends Dialog {
        private final Activity host;
        private final String source;
        private final String sourceKey;
        private WebView web;
        private FmBridge bridge;

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
            bridge = new FmBridge(v, h);
            v.addJavascriptInterface(bridge, "fongmiBridge");
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

                // 关键: 拦截资源请求, 由 SDK 自己代理 (处理图片防盗链 + 强制主框架 HTML mime)
                // 注意: 这个回调在 WebView worker 线程, 不能调 view.getXxx() 方法
                // 老的 2 参版本 (String url) 总是返回 null, 让 WebView 自己加载
                // 新版本 (WebResourceRequest) 处理所有 (主框架 + sub resource)
                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                    return null;  // 让 WebView 自己处理
                }

                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    if (req == null || req.getUrl() == null) return null;
                    return bridge.intercept(req);
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    // 在 main 线程更新当前 URL — bridge.intercept 后续会读
                    bridge.setCurrentPageUrl(url);
                    injectSdk(view);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    bridge.setCurrentPageUrl(url);
                    try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
                    injectSdk(view);
                }
            });
        }

        private void injectSdk(WebView v) {
            try {
                String js = FmSdk.get("normal", false);
                v.evaluateJavascript(js, null);
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
                    // 在 main 线程, 提前设 currentPageUrl 防止 intercept 拿不到
                    if (bridge != null) bridge.setCurrentPageUrl(url);
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
