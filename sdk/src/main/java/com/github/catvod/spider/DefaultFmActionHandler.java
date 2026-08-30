package com.github.catvod.spider;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FmActionHandler 默认实现 — 真正能用的播放 + 搜索 + 缓存。
 *
 * <p>通过反射调起 fongmi 壳的播放/搜索/历史 Activity (基于包名匹配)。
 * 如果反射失败则降级到 Intent.ACTION_VIEW 让系统选择播放器。
 */
public class DefaultFmActionHandler implements FmActionHandler {

    private static final String TAG = "WebHomeAction";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** 壳包名前缀 — 反射调起 fongmi 系壳的 Activity */
    private static final String[] FONGMI_PACKAGES = {
            "com.fongmi.android.tv",
            "com.fongmi.vodplus",
            "com.github.tv.fongmi",
            "com.github.catvod"
    };

    private final Context appContext;
    private final PlayerLauncher player;

    public DefaultFmActionHandler(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.player = new PlayerLauncher(appContext);
    }

    // ============== 播放 ==============

    @Override
    public void playUrl(String url, String title, JSONObject options) {
        if (TextUtils.isEmpty(url)) return;
        Log.d(TAG, "playUrl: " + url + " title=" + title);
        try {
            player.play(url, title, options);
        } catch (Throwable t) {
            Log.e(TAG, "playUrl failed", t);
        }
    }

    @Override
    public void playVod(String siteKey, String vodId, String title, String pic, JSONObject options) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) {
            // 没有 siteKey/vodId 没法走 spider 链路，fallback 到 playUrl
            playUrl(vodId, title, options);
            return;
        }
        Log.d(TAG, "playVod: site=" + siteKey + " vod=" + vodId);
        // fongmi 的 VideoActivity 通过 siteKey+vodId 调 spider
        Intent intent = buildVideoIntent("site_vod", siteKey, vodId, title, pic, options);
        tryStartActivity(intent, () -> fallbackPlayUrl(buildUrlFromOptions(urlFromVodId(vodId), options), title, options));
    }

    @Override
    public void playVodInline(JSONObject payload) {
        if (payload == null) return;
        // 把 episodes[] 拼成 "线路1$url1#url2$线路2$url3" 的多集格式
        try {
            String title = payload.optString("vod_name", payload.optString("title", ""));
            String pic = payload.optString("vod_pic", payload.optString("pic", ""));
            String wallPic = payload.optString("wallPic", "");
            String playFrom = payload.optString("vod_play_from", "WebHome");
            String mark = payload.optString("mark", "");
            org.json.JSONArray episodes = payload.optJSONArray("episodes");
            if (episodes == null || episodes.length() == 0) {
                playUrl("", title, null);
                return;
            }
            List<String> urls = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < episodes.length(); i++) {
                org.json.JSONObject ep = episodes.optJSONObject(i);
                if (ep == null) continue;
                String name = ep.optString("name", String.valueOf(i + 1));
                String url = ep.optString("url", "");
                if (ep.has("mediaUrl")) url = ep.optString("mediaUrl", url);
                if (!TextUtils.isEmpty(url)) {
                    urls.add(url);
                    names.add(name);
                }
            }
            if (urls.isEmpty()) {
                playUrl("", title, null);
                return;
            }
            String joined = TextUtils.join("#", urls);
            // 单集/多集都走 playUrl
            playUrl(joined, title, payload);
        } catch (Throwable t) {
            Log.e(TAG, "playVodInline failed", t);
        }
    }

    @Override
    public void preloadArtwork(String pic, String wallPic) {
        // 默认 no-op — 壳如果有 Glide 预热可覆盖
    }

    @Override
    public void controlPlayer(String action) {
        // 默认 no-op — 壳可覆盖为 PlaybackService 控制
    }

    @Override
    public org.json.JSONObject playerStatus() {
        return new org.json.JSONObject();
    }

    // ============== App 入口 — 反射调起 fongmi Activity ==============

    @Override
    public void search(String keyword, JSONObject options) {
        if (TextUtils.isEmpty(keyword)) return;
        tryStartActivity(buildSearchIntent(keyword, options), null);
    }

    @Override
    public void openVod() {
        tryStartActivity(buildMainIntent("HomeActivity"), null);
    }

    @Override
    public void openLive() {
        tryStartActivity(buildMainIntent("LiveActivity"), null);
    }

    @Override
    public void openKeep() {
        tryStartActivity(buildMainIntent("KeepActivity"), null);
    }

    @Override
    public void openSetting() {
        tryStartActivity(buildMainIntent("SettingActivity"), null);
    }

    @Override
    public org.json.JSONObject history() {
        return new org.json.JSONObject();
    }

    // ============== 缓存 ==============

    @Override
    public String cacheGet(String key, String rule) {
        if (appContext == null) return "";
        return appContext.getSharedPreferences("fongmi_webhome", Context.MODE_PRIVATE)
                .getString(cacheKey(rule, key), "");
    }

    @Override
    public void cacheSet(String key, String value, String rule) {
        if (appContext == null) return;
        appContext.getSharedPreferences("fongmi_webhome", Context.MODE_PRIVATE)
                .edit().putString(cacheKey(rule, key), value == null ? "" : value).apply();
    }

    @Override
    public void cacheDel(String key, String rule) {
        if (appContext == null) return;
        appContext.getSharedPreferences("fongmi_webhome", Context.MODE_PRIVATE)
                .edit().remove(cacheKey(rule, key)).apply();
    }

    private String cacheKey(String rule, String key) {
        return "cache_" + (TextUtils.isEmpty(rule) ? "" : rule + "_") + key;
    }

    // ============== UI — no-op ==============

    @Override public void setChrome(org.json.JSONObject options) { }
    @Override public void restoreChrome() { }
    @Override public void setToolbar(boolean visible) { }
    @Override public org.json.JSONObject getViewport() {
        org.json.JSONObject v = new org.json.JSONObject();
        try {
            v.put("width", 0);
            v.put("height", 0);
            v.put("chromeMode", "normal");
        } catch (JSONException ignored) {}
        return v;
    }

    @Override
    public org.json.JSONObject deviceInfo() {
        org.json.JSONObject d = new org.json.JSONObject();
        try {
            d.put("uuid", "");
            d.put("name", android.os.Build.MODEL);
            d.put("ip", "http://127.0.0.1:9978");
            d.put("type", 1);
            d.put("time", System.currentTimeMillis());
        } catch (JSONException ignored) {}
        return d;
    }

    @Override
    public org.json.JSONObject siteInfo() {
        org.json.JSONObject s = new org.json.JSONObject();
        try {
            s.put("key", "webhome");
            s.put("name", "WebHome");
            s.put("homePage", "");
            s.put("type", 3);
        } catch (JSONException ignored) {}
        return s;
    }

    @Override
    public org.json.JSONObject configInfo() {
        org.json.JSONObject c = new org.json.JSONObject();
        try { c.put("driveCheck", true); } catch (JSONException ignored) {}
        return c;
    }

    @Override
    public org.json.JSONObject extInfo() {
        org.json.JSONObject e = new org.json.JSONObject();
        try {
            e.put("siteKey", "webhome");
            e.put("siteName", "WebHome");
            e.put("enabled", true);
            e.put("matched", true);
            e.put("ready", true);
        } catch (JSONException ignored) {}
        return e;
    }

    @Override
    public void extLog(String message, String data) {
        Log.d(TAG, "[ext] " + message + " " + data);
    }

    @Override
    public void extToast(String message) {
        if (appContext == null) return;
        android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public org.json.JSONObject panCheck(org.json.JSONObject payload) {
        org.json.JSONObject r = new org.json.JSONObject();
        try { r.put("results", new org.json.JSONArray()); } catch (JSONException ignored) {}
        return r;
    }

    @Override
    public void panPlay(org.json.JSONObject payload) {
        if (payload == null) return;
        String url = payload.optString("url", "");
        String type = payload.optString("type", "");
        String title = payload.optString("title", url);
        String pic = payload.optString("pic", "");
        // panPlay 也走 playUrl — fongmi 壳能识别 magnet: / ed2k: / thunder: 等
        // 链通过 push_agent 链路
        if (url.startsWith("magnet:") || url.startsWith("ed2k:") || url.startsWith("thunder:")) {
            playUrl(url, title, payload);
        } else {
            playUrl(url, title, payload);
        }
    }

    @Override
    public void navigationBack() { }
    @Override
    public void navigationReload() { }

    // ============== Intent 构建 — 反射调起 fongmi 壳 ==============

    private Intent buildSearchIntent(String keyword, JSONObject options) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEARCH);
        intent.putExtra("query", keyword);
        if (options != null && options.optBoolean("direct")) {
            intent.putExtra("direct", true);
        }
        return findActivity(intent, "SearchActivity");
    }

    private Intent buildMainIntent(String activityName) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return findActivity(intent, activityName);
    }

    private Intent buildVideoIntent(String action, String siteKey, String vodId,
                                    String title, String pic, JSONObject options) {
        Intent intent = new Intent();
        intent.setAction(action);
        intent.putExtra("siteKey", siteKey);
        intent.putExtra("vodId", vodId);
        if (!TextUtils.isEmpty(title)) intent.putExtra("title", title);
        if (!TextUtils.isEmpty(pic)) intent.putExtra("pic", pic);
        if (options != null) {
            if (options.has("wallPic")) intent.putExtra("wallPic", options.optString("wallPic"));
            if (options.has("headers")) {
                try {
                    intent.putExtra("headers", options.getJSONObject("headers").toString());
                } catch (JSONException ignored) {}
            }
        }
        return findActivity(intent, "VideoActivity");
    }

    /** 在 fongmi 系壳的包名中找包含指定 Activity 名简写的类 */
    private Intent findActivity(Intent intent, String simpleName) {
        for (String pkg : FONGMI_PACKAGES) {
            // 拼全类名
            String[] candidates = {
                    pkg + ".ui.activity." + simpleName,
                    pkg + ".ui." + simpleName,
                    pkg + "." + simpleName
            };
            for (String fqcn : candidates) {
                try {
                    Class<?> cls = Class.forName(fqcn);
                    intent.setClassName(pkg, fqcn);
                    return intent;
                } catch (Throwable ignored) {}
            }
        }
        return intent; // 找不到就返回原始 intent（可能 ACTION_SEARCH 等系统能处理）
    }

    private void tryStartActivity(Intent intent, Runnable fallback) {
        if (intent == null || appContext == null) {
            if (fallback != null) fallback.run();
            return;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "tryStartActivity failed, fallback", t);
            if (fallback != null) fallback.run();
        }
    }

    private void fallbackPlayUrl(String url, String title, JSONObject options) {
        if (TextUtils.isEmpty(url)) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("title", title);
            if (appContext != null) appContext.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "fallbackPlayUrl failed", t);
        }
    }

    private String urlFromVodId(String vodId) {
        return vodId.startsWith("http") ? vodId : "";
    }

    private String buildUrlFromOptions(String url, JSONObject options) {
        return url;
    }
}
