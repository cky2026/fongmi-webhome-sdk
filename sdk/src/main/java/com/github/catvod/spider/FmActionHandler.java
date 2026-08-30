package com.github.catvod.spider;

import org.json.JSONObject;

/**
 * FmActionHandler SPI — 壳实现以接管 SDK 业务。
 * 默认实现 {@link DefaultFmActionHandler} 是 no-op。
 */
public interface FmActionHandler {

    /** FmBridge 走自己内置的 doHttp, 除非 handler 想接管。返回 null 表示让 FmBridge 走默认实现 */
    default FmHttpResponse http(String url, String method, JSONObject headers, String body,
                                String responseType, int timeout, boolean includeCookie) {
        return null;
    }

    void playUrl(String url, String title, JSONObject options);
    void playVod(String siteKey, String vodId, String title, String pic, JSONObject options);
    void playVodInline(JSONObject payload);
    void preloadArtwork(String pic, String wallPic);
    void controlPlayer(String action);
    JSONObject playerStatus();

    void search(String keyword, JSONObject options);
    void openVod();
    void openLive();
    void openKeep();
    void openSetting();
    JSONObject history();

    String cacheGet(String key, String rule);
    void cacheSet(String key, String value, String rule);
    void cacheDel(String key, String rule);

    void setChrome(JSONObject options);
    void restoreChrome();
    void setToolbar(boolean visible);
    JSONObject getViewport();

    JSONObject deviceInfo();
    JSONObject siteInfo();
    JSONObject configInfo();

    JSONObject extInfo();
    void extLog(String message, String data);
    void extToast(String message);

    JSONObject panCheck(JSONObject payload);
    void panPlay(JSONObject payload);

    void navigationBack();
    void navigationReload();
}
