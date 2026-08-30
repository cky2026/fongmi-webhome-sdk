package com.github.catvod.spider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PlayerLauncher — 启动 fongmi 壳的播放器。
 *
 * <p>尝试顺序：
 * <ol>
 *   <li>反射调 fongmi 壳的 VideoActivity (调 spider 链路)</li>
 *   <li>反射调 fongmi 壳的 push_agent (SiteApi.PUSH)</li>
 *   <li>降级到 Intent.ACTION_VIEW 系统选择器</li>
 * </ol>
 */
class PlayerLauncher {

    private static final String TAG = "PlayerLauncher";

    private static final String[] FONGMI_PACKAGES = {
            "com.fongmi.android.tv",
            "com.fongmi.vodplus",
            "com.github.tv.fongmi",
            "com.github.catvod"
    };

    private final Context ctx;

    PlayerLauncher(Context context) {
        this.ctx = context;
    }

    void play(String url, String title, JSONObject options) {
        Log.d(TAG, "play: " + url);
        if (TextUtils.isEmpty(url)) return;
        if (ctx == null) return;

        // 先尝试 fongmi 壳的 push_agent 链路 (SiteApi.PUSH)
        // 那是 fongmi 壳的 spider 接入，能识别 m3u8/mp4/magnet 等
        if (tryPushAgent(url, title, options)) return;
        // 再尝试 fongmi 壳的 VideoActivity
        if (tryVideoActivity(url, title, options)) return;
        // 降级系统播放
        fallbackToSystemPlayer(url, title);
    }

    /** 尝试 fongmi 壳的 push_agent 链路 (SiteApi.PUSH key) */
    private boolean tryPushAgent(String url, String title, JSONObject options) {
        // 拼装 push:// 协议的 Intent，调用壳的 push_agent
        String pushUrl = url.startsWith("push://") ? url : "push://" + url;
        // fongmi 壳通过 SiteApi.PUSH key 的 VideoActivity
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(pushUrl));
        intent.putExtra("title", title);
        intent.putExtra("siteKey", "push_agent");
        intent.putExtra("vodId", pushUrl);
        if (options != null) {
            if (options.has("headers")) {
                try {
                    intent.putExtra("headers", options.getJSONObject("headers").toString());
                } catch (JSONException ignored) {}
            }
            if (options.has("pic")) intent.putExtra("pic", options.optString("pic"));
            if (options.has("wallPic")) intent.putExtra("wallPic", options.optString("wallPic"));
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // 尝试 setClassName 到 fongmi 壳
        for (String pkg : FONGMI_PACKAGES) {
            String[] classNames = {
                    pkg + ".ui.activity.VideoActivity",
                    pkg + ".ui.activity.PushActivity",
                    pkg + ".ui.activity.PlayerActivity"
            };
            for (String fqcn : classNames) {
                if (tryStartActivityWithClass(intent, pkg, fqcn)) {
                    Log.d(TAG, "started VideoActivity: " + fqcn);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryVideoActivity(String url, String title, JSONObject options) {
        Intent intent = new Intent();
        intent.setAction("android.intent.action.VIEW");
        intent.putExtra("title", title);
        intent.putExtra("siteKey", "push_agent");
        intent.putExtra("vodId", url);
        intent.setData(Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        for (String pkg : FONGMI_PACKAGES) {
            String[] classNames = {
                    pkg + ".ui.activity.VideoActivity",
                    pkg + ".ui.video.VideoActivity"
            };
            for (String fqcn : classNames) {
                if (tryStartActivityWithClass(intent, pkg, fqcn)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryStartActivityWithClass(Intent intent, String pkg, String fqcn) {
        try {
            Class<?> cls = Class.forName(fqcn);
            if (!hasActivity(ctx, pkg, fqcn)) return false;
            intent.setClassName(pkg, fqcn);
            ctx.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasActivity(Context ctx, String pkg, String fqcn) {
        try {
            Intent probe = new Intent();
            probe.setClassName(pkg, fqcn);
            return ctx.getPackageManager().resolveActivity(probe, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void fallbackToSystemPlayer(String url, String title) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), guessMimeType(url));
            intent.putExtra("title", title);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "fallbackToSystemPlayer failed", t);
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
}
