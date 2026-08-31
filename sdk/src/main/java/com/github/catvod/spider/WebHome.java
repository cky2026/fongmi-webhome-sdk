package com.github.catvod.spider;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class WebHome extends Spider {

    private static final String TAG = "WebHome";

    private static final String PROXY_HOST = "127.0.0.1";
    private static final int PROXY_PORT = 9978;
    private static final String PROXY_PATH = "/webResource";
    private static final String PROXY_PREFIX = "http://" + PROXY_HOST + ":" + PROXY_PORT + PROXY_PATH;

    private static final int MAX_ACTIVITY_RETRIES = 12;
    private static final long RETRY_INTERVAL_MILLIS = 250L;

    private static volatile Context appContext;
    private static volatile boolean lifecycleInstalled;
    private static volatile boolean memoryTrimInstalled;
    private static volatile boolean httpCacheInstalled;
    private static volatile Overlay overlay;
    private static volatile WeakReference<Activity> foreground = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    /*
     * 统一后台优先级 + 空闲回收。原来是 newFixedThreadPool(12)，
     * 12 条核心线程永不退出，还跟主线程同优先级抢 CPU。
     */
    private static final int HTTP_POOL_SIZE = Math.max(4, Math.min(10, Runtime.getRuntime().availableProcessors() * 2));
    private static final ThreadPoolExecutor HTTP_EXECUTOR = new ThreadPoolExecutor(
            HTTP_POOL_SIZE, HTTP_POOL_SIZE, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), new NamedFactory("webhome-http"));

    /* 探测独立成池：进页时会一次性探多个域名，不能跟图片下载抢线程 */
    private static final ExecutorService PROBE_EXECUTOR = new ThreadPoolExecutor(
            0, 4, 20L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(64), new NamedFactory("webhome-probe"));

    /*
     * 刷新原来是单线程池：首页几十张图同时过期会串行排队到分钟级，等于失效。
     * 拒绝策略保持默认 AbortPolicy —— DiscardPolicy 会静默丢任务，
     * 那样 REFRESHING 里的 key 永远摘不掉，该图此后无法刷新。
     */
    private static final ExecutorService REFRESH_EXECUTOR = new ThreadPoolExecutor(
            0, 3, 20L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(64), new NamedFactory("webhome-refresh"));

    static {
        HTTP_EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private static final class NamedFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger(1);
        private final String prefix;

        NamedFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + seq.getAndIncrement());
            t.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
            t.setDaemon(true);
            return t;
        }
    }

    /* Cookie 必须带 TTL：登录/换号后 CookieManager 会变，无过期缓存会一直返回旧值 */
    private static final long COOKIE_TTL_MILLIS = 15 * 1000L;
    private static final ConcurrentHashMap<String, CookieEntry> COOKIE_CACHE = new ConcurrentHashMap<>();

    private static final class CookieEntry {
        final String value;
        final long at;

        CookieEntry(String value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    private static final ConcurrentHashMap<String, Map<String, String>> HEADER_CACHE = new ConcurrentHashMap<>();
    private static final int HEADER_CACHE_MAX = 200;
    private static final Set<String> REFRESHING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /*
     * 域名线路记忆：能裸访的域名直接把原图 URL 交给 WebView 原生加载
     * （Chromium 自带连接复用和磁盘缓存），不行的才走 Java 代理。
     */
    private static final int ROUTE_UNKNOWN = 0;
    private static final int ROUTE_DIRECT = 1;
    private static final int ROUTE_PROXY = 2;
    private static final long ROUTE_TTL_MILLIS = 30 * 60 * 1000L;
    private static final int PROBE_CONNECT_TIMEOUT = 500;
    private static final int PROBE_READ_TIMEOUT = 1000;
    private static final int PROBE_HEAD_BYTES = 1024;
    private static final ConcurrentHashMap<String, DomainRoute> DOMAIN_ROUTES = new ConcurrentHashMap<>();

    private static final class DomainRoute {
        int mode = ROUTE_UNKNOWN;
        long checkedAt;
        boolean probing;
    }

    /*
     * 图片内存缓存：24MB（原来 48MB，对 512MB/1GB 的盒子太激进），
     * 并注册 ComponentCallbacks2，系统内存紧张时主动让出，避免被 LMK 杀。
     */
    private static final int MAX_MEMORY_CACHE_BYTES = 24 * 1024 * 1024;
    private static final int TRIMMED_MEMORY_CACHE_BYTES = MAX_MEMORY_CACHE_BYTES / 4;
    private static final int MAX_CACHE_ITEM_BYTES = 2 * 1024 * 1024;
    private static final long DEFAULT_FRESH_MILLIS = 5 * 60 * 1000L;
    private static final LruCache<String, CachedResource> MEMORY_CACHE = new LruCache<String, CachedResource>(MAX_MEMORY_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, CachedResource value) {
            return value.bytes.length;
        }
    };

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
            Context app = context.getApplicationContext();
            if (app != null) context = app;
            appContext = context;
            installLifecycleTracker(appContext);
            installHttpCache(appContext);
            installMemoryTrim(appContext);
        }
        this.extend = str == null ? "" : str.trim();
    }

    /*
     * HttpURLConnection 的 setUseCaches 是空操作，必须装系统 HttpResponseCache
     * 才能真正落盘。配合提高 keep-alive 连接池上限，首页几十张海报可并行复用连接。
     */
    private static void installHttpCache(Context context) {
        if (httpCacheInstalled) return;
        synchronized (LOCK) {
            if (httpCacheInstalled) return;
            try {
                System.setProperty("http.maxConnections", "24");
                File dir = new File(context.getCacheDir(), "webhome_http_cache");
                if (!dir.exists()) dir.mkdirs();
                if (android.net.http.HttpResponseCache.getInstalled() == null) {
                    android.net.http.HttpResponseCache.install(dir, 64L * 1024 * 1024);
                }
            } catch (Throwable ignored) {
            }
            httpCacheInstalled = true;
        }
    }

    private static void installMemoryTrim(Context context) {
        if (memoryTrimInstalled) return;
        synchronized (LOCK) {
            if (memoryTrimInstalled) return;
            try {
                context.registerComponentCallbacks(new ComponentCallbacks2() {
                    @Override
                    public void onTrimMemory(int level) {
                        if (level >= TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) {
                            MEMORY_CACHE.evictAll();
                        } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
                            MEMORY_CACHE.trimToSize(TRIMMED_MEMORY_CACHE_BYTES);
                        }
                    }

                    @Override
                    public void onLowMemory() {
                        MEMORY_CACHE.evictAll();
                    }

                    @Override
                    public void onConfigurationChanged(Configuration c) {
                    }
                });
            } catch (Throwable ignored) {
            }
            memoryTrimInstalled = true;
        }
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
                            public void run() {
                                open(str, str2, i + 1);
                            }
                        }, RETRY_INTERVAL_MILLIS);
                    }
                    return;
                }
                remember(activity);
                String normalize = normalize(str);
                if (normalize.length() == 0) return;
                warmUp(normalize);
                if (overlay != null && overlay.isShowing()) overlay.dismiss();
                overlay = new Overlay(activity, normalize, str2);
                overlay.show();
            }
        });
    }

    private static void close() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (overlay != null) overlay.dismiss();
                overlay = null;
            }
        });
    }

    private static void installLifecycleTracker(Context context) {
        if (lifecycleInstalled || !(context instanceof Application)) return;
        synchronized (LOCK) {
            if (lifecycleInstalled) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity a, Bundle b) {
                    remember(a);
                }

                @Override
                public void onActivityStarted(Activity a) {
                    remember(a);
                }

                @Override
                public void onActivityResumed(Activity a) {
                    remember(a);
                }

                @Override
                public void onActivityPaused(Activity a) {
                }

                @Override
                public void onActivityStopped(Activity a) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity a, Bundle b) {
                }

                @Override
                public void onActivityDestroyed(Activity a) {
                    if (foreground.get() == a) foreground = new WeakReference<>(null);
                    /* Activity 没了浮层还挂着 -> 泄漏 + WindowLeaked */
                    Overlay o = overlay;
                    if (o != null && o.getContext() == a) o.dismiss();
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

    private static Field F_M_ACTIVITIES;
    private static Field F_PAUSED;
    private static Field F_ACTIVITY;
    private static Method M_CURRENT_THREAD;
    private static volatile boolean reflectFailed;

    /*
     * 反射兜底拿 Activity。原来每次都 Class.forName + getDeclaredField，
     * 且把 getDeclaredField("paused") 放在循环里对每个 Activity 记录重复反射；
     * 这里把 Field/Method 全部静态缓存，失败一次后不再重试。
     */
    private static Activity activity() {
        Activity a = foreground.get();
        if (usable(a)) return a;
        if (reflectFailed) return null;
        try {
            if (M_CURRENT_THREAD == null) {
                Class<?> at = Class.forName("android.app.ActivityThread");
                M_CURRENT_THREAD = at.getMethod("currentActivityThread");
                F_M_ACTIVITIES = at.getDeclaredField("mActivities");
                F_M_ACTIVITIES.setAccessible(true);
            }
            Object thread = M_CURRENT_THREAD.invoke(null);
            if (thread == null) return null;
            Object obj = F_M_ACTIVITIES.get(thread);
            if (!(obj instanceof Map)) return null;
            for (Object r : ((Map<?, ?>) obj).values()) {
                if (r == null) continue;
                if (F_PAUSED == null) {
                    F_PAUSED = r.getClass().getDeclaredField("paused");
                    F_PAUSED.setAccessible(true);
                    F_ACTIVITY = r.getClass().getDeclaredField("activity");
                    F_ACTIVITY.setAccessible(true);
                }
                if (Boolean.TRUE.equals(F_PAUSED.get(r))) continue;
                Object act = F_ACTIVITY.get(r);
                if (act instanceof Activity && usable((Activity) act)) return (Activity) act;
            }
        } catch (Throwable t) {
            reflectFailed = true;
        }
        return null;
    }

    private static String normalize(String str) {
        if (str == null) return "";
        String trim = str.trim();
        if (trim.startsWith("file://") || trim.startsWith("http://") || trim.startsWith("https://")) return trim;
        if (trim.startsWith("/")) return Uri.fromFile(new File(trim)).toString();
        return trim;
    }

    /* 页面正式加载前先打一次握手，预热 DNS/TLS，图片和页面都受益 */
    private static void warmUp(final String pageUrl) {
        if (!(pageUrl.startsWith("http://") || pageUrl.startsWith("https://"))) return;
        HTTP_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(pageUrl).openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", defaultUA());
                    conn.getResponseCode();
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        });
    }

    // ================= Native 异步网络请求 =================

    public static String doNativeReq(String urlStr, String optJson) {
        JSONObject res = new JSONObject();
        HttpURLConnection conn = null;
        try {
            JSONObject opt = TextUtils.isEmpty(optJson) ? new JSONObject() : new JSONObject(optJson);
            String method = opt.optString("method", "GET").toUpperCase(Locale.ROOT);
            JSONObject headers = opt.optJSONObject("headers");
            String body = opt.optString("body", "");
            /* JS 传 0 会变成无限等待，夹到 [3,60] 秒 */
            int timeout = Math.max(3, Math.min(opt.optInt("timeout", 20), 60)) * 1000;
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(true);
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
            boolean hasUA = false, hasCookie = false, hasCT = false;
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = headers.optString(k, "");
                    conn.setRequestProperty(k, v);
                    if ("user-agent".equalsIgnoreCase(k)) hasUA = true;
                    if ("cookie".equalsIgnoreCase(k) && !TextUtils.isEmpty(v)) hasCookie = true;
                    if ("content-type".equalsIgnoreCase(k)) hasCT = true;
                }
            }
            if (!hasCookie) {
                String cookie = getCachedCookie(urlStr);
                if (!TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);
            }
            if (!hasUA) conn.setRequestProperty("User-Agent", defaultUA());

            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
                conn.setDoOutput(true);
                if (!hasCT) conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                if (!TextUtils.isEmpty(body)) {
                    /* 原来只 write 不 close/flush，chunked 缓冲可能丢掉最后一块 */
                    OutputStream os = conn.getOutputStream();
                    try {
                        os.write(body.getBytes("UTF-8"));
                        os.flush();
                    } finally {
                        os.close();
                    }
                }
            }
            int code = conn.getResponseCode();
            res.put("status", code);
            res.put("ok", code >= 200 && code < 300);
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is != null) {
                String enc = conn.getContentEncoding();
                if ("gzip".equalsIgnoreCase(enc)) is = new GZIPInputStream(is);
                else if ("deflate".equalsIgnoreCase(enc)) is = new InflaterInputStream(is);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                is.close();
                /* 原来强行按 UTF-8 解码，GBK 站点会乱码 */
                String cs = encodingOf(conn.getContentType());
                res.put("body", new String(baos.toByteArray(), cs == null ? "UTF-8" : cs));
            } else {
                res.put("body", "");
            }
        } catch (Throwable t) {
            try {
                res.put("ok", false);
                res.put("status", 0);
                res.put("error", t.getMessage());
            } catch (Throwable ignored) {
            }
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
        return res.toString();
    }

    private static String encodingOf(String contentType) {
        if (TextUtils.isEmpty(contentType)) return null;
        int i = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (i < 0) return null;
        String v = contentType.substring(i + 8).split("[,;\\s]")[0].trim();
        if (v.length() == 0) return null;
        try {
            Charset.forName(v);
        } catch (Throwable t) {
            return null;
        }
        return v;
    }

    private static String defaultUA() {
        return "Mozilla/5.0 (Linux; Android 14; ELI-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    }

    /* ================= 域名直连探测 ================= */

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable t) {
            return "";
        }
    }

    private static DomainRoute routeFor(String host) {
        DomainRoute r = DOMAIN_ROUTES.get(host);
        if (r == null) {
            r = new DomainRoute();
            DomainRoute old = DOMAIN_ROUTES.putIfAbsent(host, r);
            if (old != null) r = old;
        }
        return r;
    }

    /*
     * 非阻塞线路决策（本文件最关键的一处改动）：
     *
     * 原实现在 synchronized 里 r.wait(最多 2s)，而调用方 NativeBridge.res()
     * 跑在 WebView 的 JS 线程上。首页有 N 个图片域名时，JS 会被串行阻塞 N×2s，
     * 期间整个页面渲染停摆。
     *
     * 现在：已知线路立即返回；未知立即返回 PROXY（本次必出图）并后台探测，
     * 结果对后续请求生效；记忆过期则沿用旧决策、后台重测。
     * JS 线程在任何情况下都不会被探测挂起。
     */
    private static int decideRoute(final String host, final String sampleUrl) {
        DomainRoute r = routeFor(host);
        boolean startProbe = false;
        int mode;
        synchronized (r) {
            if (r.mode == ROUTE_UNKNOWN || System.currentTimeMillis() - r.checkedAt > ROUTE_TTL_MILLIS) {
                if (!r.probing) {
                    r.probing = true;
                    startProbe = true;
                }
            }
            mode = r.mode == ROUTE_UNKNOWN ? ROUTE_PROXY : r.mode;
        }
        if (startProbe) submitProbe(r, host, sampleUrl);
        return mode;
    }

    private static void submitProbe(final DomainRoute r, final String host, final String sampleUrl) {
        try {
            PROBE_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    int mode = probeDirect(sampleUrl) ? ROUTE_DIRECT : ROUTE_PROXY;
                    synchronized (r) {
                        r.mode = mode;
                        r.checkedAt = System.currentTimeMillis();
                        r.probing = false;
                    }
                    android.util.Log.d(TAG, "probe " + host + " -> " + (mode == ROUTE_DIRECT ? "DIRECT" : "PROXY"));
                }
            });
        } catch (Throwable t) {
            /* 绝不能留下 probing=true 的死状态 */
            synchronized (r) {
                r.mode = ROUTE_PROXY;
                r.checkedAt = System.currentTimeMillis();
                r.probing = false;
            }
        }
    }

    /*
     * JS 主动上报待探测域名：页面一插 <img> 就把域名样本送过来，native 并发探测，
     * 等 JS 真正调 fm.res() 时线路已经定了，首屏即可走直连而不是先代理兑底。
     */
    private static void prefetchHosts(String sampleUrlsJson) {
        if (TextUtils.isEmpty(sampleUrlsJson)) return;
        JSONArray arr;
        try {
            arr = new JSONArray(sampleUrlsJson);
        } catch (Throwable t) {
            return;
        }
        for (int i = 0; i < arr.length() && i < 32; i++) {
            String sample = arr.optString(i, "");
            if (TextUtils.isEmpty(sample)) continue;
            String host = hostOf(sample);
            if (TextUtils.isEmpty(host)) continue;
            DomainRoute r = routeFor(host);
            synchronized (r) {
                if (r.mode != ROUTE_UNKNOWN || r.probing) continue;
                r.probing = true;
            }
            submitProbe(r, host, sample);
        }
    }

    /* 只 GET 图片头部几 KB 校验魔数就断开，不下载整张图 */
    private static boolean probeDirect(String sampleUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(sampleUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(PROBE_CONNECT_TIMEOUT);
            conn.setReadTimeout(PROBE_READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", defaultUA());
            if (conn.getResponseCode() != 200) return false;
            String type = conn.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) return false;
            InputStream is = conn.getInputStream();
            boolean ok = is != null && looksLikeImage(readHead(is, PROBE_HEAD_BYTES));
            if (is != null) {
                try {
                    is.close();
                } catch (Throwable ignored) {
                }
            }
            return ok;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /* 代理拉图成功且无需关键头 = 该域名可裸访，就地升级 */
    private static void promoteDirect(String host) {
        if (TextUtils.isEmpty(host)) return;
        DomainRoute r = routeFor(host);
        synchronized (r) {
            if (r.mode == ROUTE_UNKNOWN) {
                r.mode = ROUTE_DIRECT;
                r.checkedAt = System.currentTimeMillis();
            }
        }
    }

    private static byte[] readHead(InputStream in, int limit) {
        if (in == null) return new byte[0];
        try {
            byte[] head = new byte[limit];
            int off = 0, n;
            while (off < limit && (n = in.read(head, off, limit - off)) != -1) off += n;
            return off == limit ? head : Arrays.copyOf(head, off);
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    /*
     * 魔数校验：JPEG/PNG/GIF/WebP/BMP/AVIF 直接通过；未知魔数只要不是
     * HTML/错误页也放行，兼顾识别"返回 200 但内容是防盗链提示页"的假图片。
     * ISO-8859-1 保证字节 1:1 映射；toLowerCase 必须带 Locale.ROOT
     * （土耳其 locale 下 I -> ı 会让匹配失效）。
     */
    private static boolean looksLikeImage(byte[] head) {
        if (head == null || head.length < 4) return false;
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) return true;                 /* JPEG */
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return true; /* PNG */
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') return true;                    /* GIF */
        if (head[0] == 'B' && head[1] == 'M') return true;                                      /* BMP */
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head.length >= 12 && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return true; /* WebP */
        if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return true; /* AVIF */
        String s = new String(head, 0, Math.min(head.length, 64), Charset.forName("ISO-8859-1")).toLowerCase(Locale.ROOT);
        return !(s.contains("<!doctype") || s.contains("<html") || s.contains("<?xml"));
    }

    /*
     * 把 SDK 传来的 Cookie 同步进 CookieManager，这样带 Cookie 的图片域名
     * 也能走直连（WebView 加载图片时会自动携带同域 Cookie）。
     */
    private static void syncCookie(String url, String cookie) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setCookie(url, cookie);
        } catch (Throwable ignored) {
        }
    }

    /* Referer/UA/Authorization 无法附加在 <img> 直连请求上，仍必须走代理 */
    private static boolean needsCriticalHeader(JSONObject headers) {
        if (headers == null) return false;
        try {
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if ("referer".equalsIgnoreCase(k) || "user-agent".equalsIgnoreCase(k)
                        || "authorization".equalsIgnoreCase(k)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* Map 版本：fetchResourceData 已解析过一次，不再二次 new JSONObject */
    private static boolean needsCriticalHeader(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return false;
        for (String k : headers.keySet()) {
            if (k == null) continue;
            if ("referer".equalsIgnoreCase(k) || "user-agent".equalsIgnoreCase(k)
                    || "authorization".equalsIgnoreCase(k)) return true;
        }
        return false;
    }

    // ================= fm.res 资源代理 =================

    private static class WebResourceData {
        int code = 200;
        String message = "OK";
        String contentType = "image/*";
        String contentRange = "";
        String contentLength = "";
        String etag = "";
        String cacheControl = "";
        String lastModified = "";
        String expires = "";
        String acceptRanges = "";
        InputStream stream;
    }

    private static String getCachedCookie(String url) {
        try {
            URL u = new URL(url);
            String host = u.getHost();
            if (TextUtils.isEmpty(host)) return null;
            CookieEntry e = COOKIE_CACHE.get(host);
            long now = System.currentTimeMillis();
            if (e != null && now - e.at < COOKIE_TTL_MILLIS) return e.value;
            String cookie = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(cookie)) {
                COOKIE_CACHE.put(host, new CookieEntry(cookie, now));
                return cookie;
            }
            /* 空 Cookie 不缓存，否则登录后会一直拿到空值 */
            COOKIE_CACHE.remove(host);
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, String> getCachedHeaders(String headersJson) {
        if (TextUtils.isEmpty(headersJson)) return Collections.emptyMap();
        Map<String, String> cached = HEADER_CACHE.get(headersJson);
        if (cached != null) return cached;
        try {
            JSONObject obj = new JSONObject(headersJson);
            Map<String, String> map = new HashMap<>();
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, obj.optString(key, ""));
            }
            Map<String, String> result = Collections.unmodifiableMap(map);
            if (HEADER_CACHE.size() > HEADER_CACHE_MAX) HEADER_CACHE.clear();
            HEADER_CACHE.put(headersJson, result);
            return result;
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    private static String safeHeader(HttpURLConnection conn, String name) {
        try {
            String value = conn.getHeaderField(name);
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static WebResourceData fetchResourceData(Uri uri, String extraRange) {
        if (uri == null) return null;
        String targetUrl = uri.getQueryParameter("url");
        if (TextUtils.isEmpty(targetUrl)) return null;
        String headersJson = uri.getQueryParameter("headers");
        long start = System.currentTimeMillis();

        /* Range 请求（视频拖动）不缓存 */
        boolean cacheable = TextUtils.isEmpty(extraRange);
        String memKey = cacheKey(targetUrl, headersJson);

        if (cacheable) {
            CachedResource hit = MEMORY_CACHE.get(memKey);
            if (hit != null) {
                if (!hit.isFresh()) refreshAsync(memKey, targetUrl, headersJson, hit);
                android.util.Log.d(TAG, "fm.res " + (hit.isFresh() ? "CACHE" : "STALE") + " "
                        + (System.currentTimeMillis() - start) + "ms " + targetUrl);
                return fromCache(hit);
            }
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(targetUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(true);
            /* 图片本身已压缩，关掉 gzip 省掉服务端压缩和 Java 解压的 CPU 开销 */
            conn.setRequestProperty("Accept-Encoding", "identity");

            Map<String, String> headers = getCachedHeaders(headersJson);
            boolean hasUA = false, hasCookie = false;
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String hKey = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(hKey)) continue;
                conn.setRequestProperty(hKey, value == null ? "" : value);
                if ("user-agent".equalsIgnoreCase(hKey)) hasUA = true;
                if ("cookie".equalsIgnoreCase(hKey) && !TextUtils.isEmpty(value)) hasCookie = true;
            }
            if (!hasCookie) {
                String cookie = getCachedCookie(targetUrl);
                if (!TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);
            }
            if (!hasUA) conn.setRequestProperty("User-Agent", defaultUA());
            if (!TextUtils.isEmpty(extraRange)) conn.setRequestProperty("Range", extraRange);
            conn.connect();

            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is == null) {
                conn.disconnect();
                return null;
            }

            WebResourceData data = new WebResourceData();
            data.code = code;
            String msg = conn.getResponseMessage();
            data.message = TextUtils.isEmpty(msg) ? "OK" : msg;
            data.contentType = TextUtils.isEmpty(conn.getContentType()) ? "image/*" : conn.getContentType();
            data.contentRange = safeHeader(conn, "Content-Range");
            data.contentLength = safeHeader(conn, "Content-Length");
            data.etag = safeHeader(conn, "ETag");
            data.cacheControl = safeHeader(conn, "Cache-Control");
            data.lastModified = safeHeader(conn, "Last-Modified");
            data.expires = safeHeader(conn, "Expires");
            data.acceptRanges = safeHeader(conn, "Accept-Ranges");

            /*
             * 完整 200 图片响应：边转发边收集，WebView 读完流后异步入缓存，
             * 不给本次响应增加任何延迟。
             */
            if (cacheable && code == 200 && data.contentType.startsWith("image/")) {
                if (!needsCriticalHeader(headers)) promoteDirect(hostOf(targetUrl));
                final String fUrl = targetUrl, fHeaders = headersJson, fType = data.contentType;
                final String fEtag = data.etag, fLast = data.lastModified, fCc = data.cacheControl;
                data.stream = new TeeInputStream(is) {
                    @Override
                    protected void onComplete(byte[] bytes) {
                        putCache(cacheKey(fUrl, fHeaders),
                                new CachedResource(bytes, fType, fEtag, fLast, parseMaxAge(fCc)));
                    }
                };
            } else {
                data.stream = is;
            }

            android.util.Log.d(TAG, "fm.res " + code + " " + (System.currentTimeMillis() - start) + "ms " + targetUrl);
            return data;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "fm.res error: " + targetUrl, t);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    private static final class CachedResource {
        final byte[] bytes;
        final String contentType;
        final String etag;
        final String lastModified;
        final long fetchedAt;
        final long freshMillis;

        CachedResource(byte[] bytes, String contentType, String etag, String lastModified, long freshMillis) {
            this.bytes = bytes;
            this.contentType = TextUtils.isEmpty(contentType) ? "image/*" : contentType;
            this.etag = etag == null ? "" : etag;
            this.lastModified = lastModified == null ? "" : lastModified;
            this.fetchedAt = System.currentTimeMillis();
            this.freshMillis = freshMillis;
        }

        boolean isFresh() {
            return freshMillis > 0 && System.currentTimeMillis() - fetchedAt < freshMillis;
        }
    }

    private static void putCache(String key, CachedResource r) {
        if (r != null && r.bytes.length > 0 && r.bytes.length <= MAX_CACHE_ITEM_BYTES) MEMORY_CACHE.put(key, r);
    }

    private static String cacheKey(String url, String headersJson) {
        return url + "|" + (headersJson == null ? 0 : headersJson.hashCode());
    }

    private static long parseMaxAge(String cacheControl) {
        if (TextUtils.isEmpty(cacheControl)) return DEFAULT_FRESH_MILLIS;
        String cc = cacheControl.toLowerCase(Locale.ROOT);
        if (cc.contains("no-store") || cc.contains("no-cache")) return 0;
        int idx = cc.indexOf("max-age=");
        if (idx >= 0) {
            try {
                long v = Long.parseLong(cacheControl.substring(idx + 8).split("[,; ]")[0].trim()) * 1000L;
                if (v > 0) return v;
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_FRESH_MILLIS;
    }

    private static WebResourceData fromCache(CachedResource hit) {
        WebResourceData data = new WebResourceData();
        data.code = 200;
        data.contentType = hit.contentType;
        data.contentLength = String.valueOf(hit.bytes.length);
        data.etag = hit.etag;
        data.lastModified = hit.lastModified;
        /* 给 Chromium 一个短 max-age，几秒内重绘连拦截器都不进 */
        data.cacheControl = "max-age=300";
        data.acceptRanges = "none";
        data.stream = new ByteArrayInputStream(hit.bytes);
        return data;
    }

    /*
     * 边转发边收集字节：WebView 边读边拿数据（零额外延迟），读完异步入缓存。
     * 超单图上限立即停止收集，不再把整张大图读进内存再丢弃。
     */
    private static abstract class TeeInputStream extends InputStream {
        private final InputStream in;
        private final ByteArrayOutputStream tee = new ByteArrayOutputStream(32 * 1024);
        private boolean completed;
        private boolean overflow;

        TeeInputStream(InputStream in) {
            this.in = in;
        }

        protected abstract void onComplete(byte[] bytes);

        private void complete() {
            if (completed || overflow) {
                completed = true;
                return;
            }
            completed = true;
            final byte[] bytes = tee.toByteArray();
            if (bytes.length > 0 && bytes.length <= MAX_CACHE_ITEM_BYTES) {
                try {
                    HTTP_EXECUTOR.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                onComplete(bytes);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }

        private void collect(byte[] buf, int off, int n) {
            if (overflow || n <= 0) return;
            if (tee.size() + n > MAX_CACHE_ITEM_BYTES) {
                overflow = true;
                tee.reset();
                return;
            }
            tee.write(buf, off, n);
        }

        @Override
        public int read() throws java.io.IOException {
            int b = in.read();
            if (b == -1) complete();
            else collect(new byte[]{(byte) b}, 0, 1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws java.io.IOException {
            int n = in.read(buf, off, len);
            if (n == -1) complete();
            else collect(buf, off, n);
            return n;
        }

        @Override
        public int available() throws java.io.IOException {
            return in.available();
        }

        @Override
        public void close() throws java.io.IOException {
            try {
                complete();
            } finally {
                in.close();
            }
        }
    }

    /* stale-while-revalidate：旧图已先返回，这里后台条件请求刷新，304 续期不耗流量 */
    private static void refreshAsync(final String key, final String targetUrl,
                                     final String headersJson, final CachedResource stale) {
        if (!REFRESHING.add(key)) return;
        try {
            REFRESH_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection conn = null;
                    try {
                        conn = (HttpURLConnection) new URL(targetUrl).openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(15000);
                        conn.setInstanceFollowRedirects(true);
                        conn.setUseCaches(false);
                        conn.setRequestProperty("Accept-Encoding", "identity");
                        if (!TextUtils.isEmpty(stale.etag)) conn.setRequestProperty("If-None-Match", stale.etag);
                        if (!TextUtils.isEmpty(stale.lastModified))
                            conn.setRequestProperty("If-Modified-Since", stale.lastModified);
                        boolean hasUA = false;
                        for (Map.Entry<String, String> e : getCachedHeaders(headersJson).entrySet()) {
                            if (TextUtils.isEmpty(e.getKey())) continue;
                            conn.setRequestProperty(e.getKey(), e.getValue() == null ? "" : e.getValue());
                            if ("user-agent".equalsIgnoreCase(e.getKey())) hasUA = true;
                        }
                        if (!hasUA) conn.setRequestProperty("User-Agent", defaultUA());
                        String cookie = getCachedCookie(targetUrl);
                        if (!TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);

                        int code = conn.getResponseCode();
                        if (code == 304) {
                            putCache(key, new CachedResource(stale.bytes, stale.contentType, stale.etag,
                                    stale.lastModified, parseMaxAge(safeHeader(conn, "Cache-Control"))));
                        } else if (code == 200) {
                            InputStream is = conn.getInputStream();
                            if (is != null) {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                byte[] buf = new byte[8192];
                                int len;
                                while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
                                is.close();
                                putCache(key, new CachedResource(baos.toByteArray(), conn.getContentType(),
                                        safeHeader(conn, "ETag"), safeHeader(conn, "Last-Modified"),
                                        parseMaxAge(safeHeader(conn, "Cache-Control"))));
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        REFRESHING.remove(key);
                        if (conn != null) {
                            try {
                                conn.disconnect();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            /* 任务压根没入队，内部的 finally 不会跑，必须在这里摘掉 key */
            REFRESHING.remove(key);
        }
    }

    /*
     * 资源 URL 统一出口：直连/代理分流 + 代理 URL 拼装。
     * public static 供 FmBridge.resourceUrl 复用，保证任何 JS 路径生成的
     * 图片地址都走同一套路由决策。
     */
    public static String resolveResourceUrl(String url, String options) {
        try {
            /*
             * file:// 页面里的相对路径、锚点、data/blob URI 一律原样返回，
             * 交给 WebView 按当前页面基址解析。拼进代理 URL 会因为
             * new URL("img/a.jpg") 抛异常而全变成 404。
             */
            if (TextUtils.isEmpty(url)) return url;
            if (!url.startsWith("http://") && !url.startsWith("https://")) return url;

            JSONObject opt = TextUtils.isEmpty(options) ? new JSONObject() : new JSONObject(options);
            JSONObject headers = opt.optJSONObject("headers");

            if (headers != null) {
                String ck = headers.optString("cookie", headers.optString("Cookie", ""));
                if (!TextUtils.isEmpty(ck)) syncCookie(url, ck);
            }

            if (!needsCriticalHeader(headers)) {
                String host = hostOf(url);
                if (!TextUtils.isEmpty(host) && decideRoute(host, url) == ROUTE_DIRECT) return url;
            }

            String encodedHeaders = "";
            if (headers != null) encodedHeaders = "&headers=" + URLEncoder.encode(headers.toString(), "UTF-8");
            return PROXY_PREFIX + "?url=" + URLEncoder.encode(url, "UTF-8") + encodedHeaders;
        } catch (Throwable t) {
            return url;
        }
    }

    // ================= Native Bridge =================

    public static class NativeBridge {

        @JavascriptInterface
        public void asyncReq(final String reqId, final String url, final String options) {
            /* 网络请求必须在后台线程跑完，只把结果投递回主线程 */
            HTTP_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    final String resultJson = doNativeReq(url, options);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (overlay == null || overlay.web == null) return;
                            overlay.web.evaluateJavascript("if(window.fm&&window.fm._onReqResult){window.fm._onReqResult("
                                    + JSONObject.quote(reqId) + "," + JSONObject.quote(resultJson) + ");}", null);
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public String res(String url, String options) {
            return resolveResourceUrl(url, options);
        }

        @JavascriptInterface
        public void prefetch(String sampleUrlsJson) {
            prefetchHosts(sampleUrlsJson);
        }

        /* 直连图挂了（防盗链/网络波动）时由 img error 调到这里，记回代理并换 src */
        @JavascriptInterface
        public String directFailed(String host, String url) {
            try {
                if (TextUtils.isEmpty(host)) host = hostOf(url);
                if (!TextUtils.isEmpty(host)) {
                    DomainRoute r = routeFor(host);
                    synchronized (r) {
                        r.mode = ROUTE_PROXY;
                        r.checkedAt = System.currentTimeMillis();
                    }
                }
                if (TextUtils.isEmpty(url)) return "";
                return PROXY_PREFIX + "?url=" + URLEncoder.encode(url, "UTF-8");
            } catch (Throwable t) {
                return url == null ? "" : url;
            }
        }
    }

    // ================= Overlay =================

    private static final class Overlay extends Dialog {
        private final String source;
        private final String sourceKey;
        private WebView web;
        private String injectedUrl;

        Overlay(Activity activity, String source, String sourceKey) {
            super(activity, 0x0103000a);
            this.source = source;
            this.sourceKey = sourceKey == null ? "" : sourceKey;
        }

        @Override
        protected void onCreate(Bundle bundle) {
            super.onCreate(bundle);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(0xFF000000);
            web = new WebView(getContext());
            root.addView(web, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(root);
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0xFF000000));
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                hideSystemBars(window);
            }
            setupWebView(web);
            load(web, source);
            setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                        if (web != null && web.canGoBack()) web.goBack();
                        else dismiss();
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public void onBackPressed() {
            if (web != null && web.canGoBack()) web.goBack();
            else dismiss();
        }

        @Override
        public void dismiss() {
            try {
                CookieManager.getInstance().flush();
            } catch (Throwable ignored) {
            }
            if (web != null) {
                try {
                    web.stopLoading();
                    web.removeJavascriptInterface("fongmiBridge");
                    web.removeJavascriptInterface("_nativeBridge");
                    web.loadUrl("about:blank");
                    web.clearHistory();
                    web.removeAllViews();
                    web.destroy();
                } catch (Throwable ignored) {
                }
                web = null;
            }
            try {
                super.dismiss();
            } catch (Throwable ignored) {
            }
            if (WebHome.overlay == this) WebHome.overlay = null;
        }

        /*
         * file:// 页面需要跨域能力（读本地资源、发跨域 XHR），所以按当前页面
         * 动态开关 universal access：file 页面开，http(s) 页面关。
         * 全程开着的话，一个 file 页面的 XSS 就能读到 App 私有目录。
         */
        private static void applyFileOriginPolicy(WebView v, String url) {
            if (v == null) return;
            try {
                v.getSettings().setAllowUniversalAccessFromFileURLs(url != null && url.startsWith("file://"));
            } catch (Throwable ignored) {
            }
        }

        @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
        private void setupWebView(WebView v) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            if (Build.VERSION.SDK_INT >= 26) v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);

            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
            s.setDisplayZoomControls(false);
            s.setTextZoom(100);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setLoadsImagesAutomatically(true);
            /* 本地 file:// 页面：允许读文件、允许 file 之间互访 */
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(source.startsWith("file://"));
            if (Build.VERSION.SDK_INT >= 23) s.setOffscreenPreRaster(true);

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
            } catch (Throwable ignored) {
            }

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
                    /* startsWith 比 contains 快，也避免误命中页面里恰好含该串的 URL */
                    return url != null && url.startsWith(PROXY_PREFIX)
                            ? handleWebResourceResponse(Uri.parse(url), null)
                            : super.shouldInterceptRequest(view, url);
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    if (req != null && req.getUrl() != null && isProxyRequest(req.getUrl())) {
                        String range = null;
                        if (req.getRequestHeaders() != null) {
                            range = req.getRequestHeaders().get("Range");
                            if (range == null) range = req.getRequestHeaders().get("range");
                        }
                        return handleWebResourceResponse(req.getUrl(), range);
                    }
                    return super.shouldInterceptRequest(view, req);
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    applyFileOriginPolicy(view, url);
                    injectSdk(view, url);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    try {
                        CookieManager.getInstance().flush();
                    } catch (Throwable ignored) {
                    }
                    injectSdk(view, url);
                }
            });
        }

        private static boolean isProxyRequest(Uri uri) {
            return PROXY_PATH.equals(uri.getPath()) && PROXY_PORT == uri.getPort() && PROXY_HOST.equals(uri.getHost());
        }

        private WebResourceResponse handleWebResourceResponse(Uri uri, String range) {
            WebResourceData data = fetchResourceData(uri, range);
            /* 失败绝不能 return null，否则 WebView 会真去请求 9978 等 404，白耗一个往返 */
            if (data == null) return emptyResponse();

            String mimeType = data.contentType;
            int semi = mimeType.indexOf(';');
            if (semi >= 0) mimeType = mimeType.substring(0, semi).trim();
            if (mimeType.length() == 0) mimeType = "application/octet-stream";
            String encoding = encodingOf(data.contentType);

            if (Build.VERSION.SDK_INT >= 21) {
                Map<String, String> respHeaders = new HashMap<>();
                respHeaders.put("Access-Control-Allow-Origin", "*");
                respHeaders.put("Access-Control-Allow-Credentials", "true");
                respHeaders.put("Access-Control-Allow-Headers", "*");
                if (!TextUtils.isEmpty(data.contentRange)) respHeaders.put("Content-Range", data.contentRange);
                if (!TextUtils.isEmpty(data.contentLength)) respHeaders.put("Content-Length", data.contentLength);
                if (!TextUtils.isEmpty(data.etag)) respHeaders.put("ETag", data.etag);
                if (!TextUtils.isEmpty(data.cacheControl)) respHeaders.put("Cache-Control", data.cacheControl);
                if (!TextUtils.isEmpty(data.lastModified)) respHeaders.put("Last-Modified", data.lastModified);
                if (!TextUtils.isEmpty(data.expires)) respHeaders.put("Expires", data.expires);
                if (!TextUtils.isEmpty(data.acceptRanges)) respHeaders.put("Accept-Ranges", data.acceptRanges);
                /*
                 * 源站没给缓存头时给 200 图片补 max-age，让 Chromium 自己缓存代理响应，
                 * 二次渲染不再进拦截器、不再发起网络请求 —— 这是消除
                 * "框架秒出、图片慢半拍" 最关键的一层。
                 */
                if (data.code == 200 && TextUtils.isEmpty(data.cacheControl) && mimeType.startsWith("image/")) {
                    respHeaders.put("Cache-Control", "max-age=86400");
                }
                return new WebResourceResponse(mimeType, encoding, data.code, data.message, respHeaders, data.stream);
            }
            return new WebResourceResponse(mimeType, encoding, data.stream);
        }

        private WebResourceResponse emptyResponse() {
            try {
                if (Build.VERSION.SDK_INT >= 21) {
                    Map<String, String> h = new HashMap<>();
                    h.put("Access-Control-Allow-Origin", "*");
                    h.put("Cache-Control", "no-store");
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h,
                            new ByteArrayInputStream(new byte[0]));
                }
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            } catch (Throwable t) {
                return null;
            }
        }

        /* 按 URL 去重：原来 onPageStarted / onPageFinished 各注入一次，脚本跑两遍 */
        private void injectSdk(WebView v, String url) {
            if (v == null) return;
            if (url != null && url.equals(injectedUrl)) return;
            injectedUrl = url;
            try {
                String reqPolyfill = "if(window.fm&&!window.fm._patched){" +
                        "window.fm._patched=true;" +
                        "window.fm._reqCallbacks={};" +
                        "window.fm._reqSeq=0;" +
                        "window.fm.req=function(u,o){return new Promise(function(resolve){" +
                        "var id='req_'+(++window.fm._reqSeq)+'_'+Date.now();" +
                        "window.fm._reqCallbacks[id]=function(res){" +
                        "if(o&&o.responseType==='json'&&typeof res.body==='string'){try{res.body=JSON.parse(res.body);}catch(e){}}" +
                        "resolve(res);};" +
                        "try{_nativeBridge.asyncReq(id,u,JSON.stringify(o||{}));}" +
                        "catch(e){delete window.fm._reqCallbacks[id];resolve({ok:false,status:0,error:e.message});}" +
                        "});};" +
                        "window.fm._onReqResult=function(id,s){" +
                        "var cb=window.fm._reqCallbacks[id];" +
                        "if(cb){delete window.fm._reqCallbacks[id];try{cb(JSON.parse(s));}catch(e){cb({ok:false,status:0,error:e.message});}}" +
                        "};" +
                        "window.fm.res=function(u,o){try{return _nativeBridge.res(u,JSON.stringify(o||{}));}catch(e){return u;}};" +
                        "}";

                String routeHook = "if(!window.__whRouteHooked){window.__whRouteHooked=true;" +
                        "document.addEventListener('error',function(ev){" +
                        "var el=ev.target;if(!el||el.tagName!=='IMG')return;" +
                        "var src=el.src||'';" +
                        "if(!src||src.indexOf('/webResource')!==-1||src.indexOf('data:')===0)return;" +
                        "if(src.indexOf('http://')!==0&&src.indexOf('https://')!==0)return;" +
                        "var m=src.match(/^(?:https?:)?\\/\\/([^\\/#?]+)/);" +
                        "if(!m)return;" +
                        "try{var p=_nativeBridge.directFailed(m[1],src);if(p){el.src=p;}}catch(e){}" +
                        "},true);}";

                String prefetchHook = "if(!window.__whPF){window.__whPF=1;var __s={},__t=null;" +
                        "function __c(){try{var l=[],e=document.getElementsByTagName('img');" +
                        "for(var i=0;i<e.length;i++){var u=e[i].getAttribute('src')||e[i].getAttribute('data-src')||'';" +
                        "if(u.indexOf('http')!==0)continue;" +
                        "var m=u.match(/^(?:https?:)?\\/\\/([^\\/#?]+)/);" +
                        "if(!m||__s[m[1]])continue;__s[m[1]]=1;l.push(u);}" +
                        "if(l.length)_nativeBridge.prefetch(JSON.stringify(l));}catch(e){}}" +
                        "function __sch(){if(__t)return;__t=setTimeout(function(){__t=null;__c();},120);}" +
                        "__c();" +
                        "if(window.MutationObserver&&document.documentElement){" +
                        "new MutationObserver(__sch).observe(document.documentElement,{childList:1,subtree:1});}" +
                        "document.addEventListener('DOMContentLoaded',__sch);}";

                v.evaluateJavascript(FmSdk.get("normal", false) + "\n" + reqPolyfill + "\n"
                        + routeHook + "\n" + prefetchHook, null);
            } catch (Throwable t) {
                android.util.Log.e(TAG, "injectSdk failed", t);
            }
        }

        private boolean handleUrl(WebView view, String url) {
            if (url == null || url.length() == 0) return true;
            if ("webhome://close".equalsIgnoreCase(url)) {
                dismiss();
                return true;
            }
            if (url.startsWith("file://") || url.startsWith("http://") || url.startsWith("https://")) return false;
            /* 自定义 scheme：原来直接 loadUrl 会让 WebView 报错白屏，先交给系统 */
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                view.getContext().startActivity(intent);
                return true;
            } catch (Throwable ignored) {
            }
            view.loadUrl(url);
            return true;
        }

        private void load(WebView webView, String url) {
            try {
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
                    webView.loadUrl(url);
                } else {
                    webView.loadDataWithBaseURL(null, "<h1>WebHome 路径无效</h1><small>" + url + "</small>",
                            "text/html", "UTF-8", null);
                }
            } catch (Throwable th) {
                webView.loadDataWithBaseURL(null, "<h1>加载失败</h1><small>" + th.getMessage() + "</small>",
                        "text/html", "UTF-8", null);
            }
        }

        private static final int SYSTEM_UI_FLAGS =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

        /* 原来写死 setSystemUiVisibility(5894)。API 30+ 已废弃，compileSdk>=30 可换 InsetsController */
        @SuppressLint("InlinedApi")
        private void hideSystemBars(Window w) {
            if (w == null) return;
            try {
                w.getDecorView().setSystemUiVisibility(SYSTEM_UI_FLAGS);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus) hideSystemBars(getWindow());
        }
    }
}
