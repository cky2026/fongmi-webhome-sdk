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

/**
 * PlayerLauncher — 直接启动壳的 VideoActivity (在壳内播放, 不是外部 app)。
 *
 * <p>关键: webhtv 用 {@code VideoActivity.start(activity, SiteApi.PUSH, url, ...)}
 * 启动的是壳自己的 VideoActivity, key=push_agent 让 VideoActivity 走 push 链路。
 *
 * <p>VideoActivity 接收的 Intent extras:
 * <ul>
 *   <li>key: 站点 key, push_agent = 推送播放</li>
 *   <li>id: 视频 id 或 URL</li>
 *   <li>name: 标题</li>
 *   <li>pic: 海报</li>
 *   <li>wallPic: 背景图</li>
 *   <li>mark: 默认选中集</li>
 * </ul>
 */
class PlayerLauncher {

    private static final String TAG = "PlayerLauncher";

    private static final String[] FONGMI_PACKAGES = {
            "com.fongmi.android.tv",
            "com.fongmi.vodplus",
            "com.github.tv.fongmi",
            "com.github.catvod"
    };

    /** 壳里 VideoActivity 类全名 (按已知包名 + 类名拼接) */
    private static final String[][] VIDEO_ACTIVITY_CANDIDATES = {
            {"com.fongmi.android.tv", "com.fongmi.android.tv.ui.activity.VideoActivity"},
            {"com.fongmi.android.tv", "com.fongmi.android.tv.ui.video.VideoActivity"},
            {"com.fongmi.vodplus",     "com.fongmi.vodplus.ui.activity.VideoActivity"},
            {"com.github.tv.fongmi",   "com.github.tv.fongmi.ui.activity.VideoActivity"},
            {"com.github.catvod",      "com.github.catvod.ui.activity.VideoActivity"}
    };

    private final Context ctx;
    private final Handler main;

    PlayerLauncher(Context context) {
        this.ctx = context;
        this.main = new Handler(Looper.getMainLooper());
    }

    /**
     * 在壳内播放
     * @param id 视频 id 或 URL (push_agent 模式下 id 就是 URL)
     * @param title 标题
     * @param pic 海报
     * @param wallPic 背景图
     * @param key 站点 key, "push_agent" 表示推送播放
     */
    void playInShell(final String id, final String title, final String pic,
                     final String wallPic, final String key) {
        if (ctx == null) return;
        main.post(() -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(findVideoActivity());
                intent.putExtra("key", key);
                intent.putExtra("id", id);
                intent.putExtra("name", title == null ? "" : title);
                intent.putExtra("pic", pic == null ? "" : pic);
                intent.putExtra("wallPic", wallPic == null ? "" : wallPic);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                Log.d(TAG, "Started VideoActivity: key=" + key + " id=" + id);
            } catch (Throwable t) {
                Log.e(TAG, "playInShell failed, fallback to system", t);
                fallbackToSystem(id, title);
            }
        });
    }

    void openSearch(String keyword, boolean direct) {
        main.post(() -> {
            try {
                Intent intent = new Intent("android.intent.action.SEARCH");
                intent.putExtra("query", keyword);
                if (direct) intent.putExtra("direct", true);
                intent.setComponent(findComponentByActivityName("SearchActivity"));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Throwable t) {
                Log.w(TAG, "openSearch failed", t);
            }
        });
    }

    void openMainActivity(String simpleName) {
        main.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                intent.setComponent(findComponentByActivityName(simpleName));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Throwable t) {
                Log.w(TAG, "openMainActivity " + simpleName + " failed", t);
            }
        });
    }

    /** 在 fongmi 候选包名中找已安装的壳, 返回那个壳的 ComponentName */
    private ComponentName findVideoActivity() {
        for (String[] pair : VIDEO_ACTIVITY_CANDIDATES) {
            String pkg = pair[0];
            String fqcn = pair[1];
            if (isInstalled(pkg) && classExists(fqcn)) {
                return new ComponentName(pkg, fqcn);
            }
        }
        // 兜底: 直接用 com.fongmi.android.tv
        return new ComponentName("com.fongmi.android.tv",
                "com.fongmi.android.tv.ui.activity.VideoActivity");
    }

    private ComponentName findComponentByActivityName(String simpleName) {
        for (String[] pair : VIDEO_ACTIVITY_CANDIDATES) {
            String pkg = pair[0];
            String fqcn = pair[0] + ".ui.activity." + simpleName;
            if (isInstalled(pkg) && classExists(fqcn)) {
                return new ComponentName(pkg, fqcn);
            }
        }
        return new ComponentName("com.fongmi.android.tv",
                "com.fongmi.android.tv.ui.activity." + simpleName);
    }

    private boolean isInstalled(String pkg) {
        if (ctx == null) return false;
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean classExists(String fqcn) {
        try {
            Class.forName(fqcn);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void fallbackToSystem(String url, String title) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), guessMimeType(url));
            intent.putExtra("title", title);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
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
}
