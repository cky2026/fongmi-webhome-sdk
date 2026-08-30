package com.github.catvod.spider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FmActionHandler 默认实现 — 在壳内启动 VideoActivity 播放。
 *
 * <p>关键点: webhtv 实现的 playUrl 是调
 * {@code VideoActivity.start(activity, SiteApi.PUSH, url, title, pic, null, wall, content)}
 * 也就是直接启动壳自己的 VideoActivity (key=push_agent, id=URL)。
 * VideoActivity 内部根据 key=push_agent 走 push_agent 链路解析播放。
 */
public class DefaultFmActionHandler implements FmActionHandler {

    private static final String TAG = "WebHomeAction";

    /** fongmi 壳的 push_agent key (SiteApi.PUSH 常量值) */
    private static final String PUSH_AGENT_KEY = "push_agent";

    private final Context appContext;
    private final PlayerLauncher launcher;

    public DefaultFmActionHandler(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.launcher = new PlayerLauncher(appContext);
    }

    // ============== 播放 ==============

    @Override
    public void playUrl(String url, String title, JSONObject options) {
        if (TextUtils.isEmpty(url)) return;
        Log.d(TAG, "playUrl: " + url);
        try {
            String pic = options != null ? options.optString("pic", "") : "";
            String wallPic = options != null ? options.optString("wallPic", "") : "";
            launcher.playInShell(url, title, pic, wallPic, PUSH_AGENT_KEY);
        } catch (Throwable t) {
            Log.e(TAG, "playUrl failed", t);
        }
    }

    @Override
    public void playVod(String siteKey, String vodId, String title, String pic, JSONObject options) {
        if (TextUtils.isEmpty(siteKey) || TextUtils.isEmpty(vodId)) {
            // 没 siteKey/vodId 走 push_agent
            playUrl(vodId, title, options);
            return;
        }
        Log.d(TAG, "playVod: site=" + siteKey + " vod=" + vodId);
        try {
            String wallPic = options != null ? options.optString("wallPic", "") : "";
            launcher.playInShell(vodId, title, pic, wallPic, siteKey);
        } catch (Throwable t) {
            Log.e(TAG, "playVod failed", t);
        }
    }

    @Override
    public void playVodInline(JSONObject payload) {
        if (payload == null) return;
        try {
            String title = payload.optString("vod_name", payload.optString("title", ""));
            String pic = payload.optString("vod_pic", payload.optString("pic", ""));
            String wallPic = payload.optString("wallPic", "");
            String playFrom = payload.optString("vod_play_from", "WebHome");
            String mark = payload.optString("mark", "");
            org.json.JSONArray episodes = payload.optJSONArray("episodes");
            if (episodes == null || episodes.length() == 0) {
                playUrl("", title, payload);
                return;
            }
            // 多集: 拼接为 vodId, 标记 (e.g. "episodes.json#WebHome#02")
            // webhtv 走 WebHomeInlineVodStore, 这里简化: 把整个 payload 编码后作为 id
            // 实际上更简单: 单集直接 playUrl, 多集也走 playUrl 但传拼接 url
            // 单集: 传 url
            if (episodes.length() == 1) {
                org.json.JSONObject ep = episodes.optJSONObject(0);
                String url = ep.optString("url", "");
                if (ep.has("mediaUrl")) url = ep.optString("mediaUrl", url);
                playUrl(url, title, payload);
                return;
            }
            // 多集: 拼接 url 列表
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < episodes.length(); i++) {
                if (i > 0) sb.append("#");
                org.json.JSONObject ep = episodes.optJSONObject(i);
                String url = ep.optString("url", "");
                if (ep.has("mediaUrl")) url = ep.optString("mediaUrl", url);
                sb.append(url);
            }
            // 多集: 用 vodInline 方案, 通过 WebHomeInlineVodStore 简化
            // 这里直接传给 push_agent, 让壳去解析
            String id = "webhome_inline:" + System.currentTimeMillis() + "#" + sb;
            Log.d(TAG, "playVodInline: " + id);
            launcher.playInShell(id, title, pic, wallPic, PUSH_AGENT_KEY);
        } catch (Throwable t) {
            Log.e(TAG, "playVodInline failed", t);
        }
    }

    @Override
    public void preloadArtwork(String pic, String wallPic) {
        // no-op (壳用 Glide 预热, 这里省略)
    }

    @Override
    public void controlPlayer(String action) {
        // no-op, 壳通过 PlaybackService 控制
    }

    @Override
    public org.json.JSONObject playerStatus() {
        return new org.json.JSONObject();
    }

    // ============== App 入口 — 调壳 Activity ==============

    @Override
    public void search(String keyword, JSONObject options) {
        launcher.openSearch(keyword, options != null && options.optBoolean("direct"));
    }

    @Override
    public void openVod() {
        launcher.openMainActivity("HomeActivity");
    }

    @Override
    public void openLive() {
        launcher.openMainActivity("LiveActivity");
    }

    @Override
    public void openKeep() {
        launcher.openMainActivity("KeepActivity");
    }

    @Override
    public void openSetting() {
        launcher.openMainActivity("SettingActivity");
    }

    @Override
    public org.json.JSONObject history() {
        return new org.json.JSONObject();
    }

    // ============== 缓存 (SharedPreferences) ==============

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
        try { v.put("chromeMode", "normal"); } catch (JSONException ignored) {}
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
        new Handler(Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show());
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
        // 网盘推送/磁链都走 push_agent
        if (!TextUtils.isEmpty(url)) {
            playUrl(url, title, payload);
        }
    }

    @Override
    public void navigationBack() { }
    @Override
    public void navigationReload() { }
}
