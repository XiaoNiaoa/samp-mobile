package com.sampmobile.game.ui;

import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * JS 侧的 window.samp 对象（2026-11-22）。
 *
 * 注意：这些方法由 WebView 的 JavaBridge 线程调用，不是主线程。
 * 所以这里只做转发，线程切换统一交给 WebOverlay。
 *
 * 另外：这个类不要加 @Obfuscate。paranoid 会把被注解的类/方法改名，
 * 而 @JavascriptInterface 的方法名是字符串约定（samp.close 之类），
 * 一旦被改名，release 包里页面就调不到任何东西了。
 */
public final class WebBridge {

    private static final String TAG = "WebOverlay.JS";

    private final WebOverlay overlay;

    WebBridge(WebOverlay overlay) {
        this.overlay = overlay;
    }

    /** 页面脚本与 bridge 都就绪，可以收 context 了。 */
    @JavascriptInterface
    public void ready() {
        overlay.onBridgeReady();
    }

    /** 关闭覆盖层。 */
    @JavascriptInterface
    public void close() {
        overlay.hide();
    }

    /** 发一条服务端命令，例如 samp.command('/wvbuy 1024')。 */
    @JavascriptInterface
    public void command(String cmd) {
        overlay.sendCommand(cmd);
    }

    /** 原生 Toast，不占游戏 HUD。 */
    @JavascriptInterface
    public void toast(String text) {
        overlay.toast(text);
    }

    /** 客户端环境 + show() 时传入的 data（JSON 字符串）。 */
    @JavascriptInterface
    public String env() {
        return overlay.envJson();
    }

    /** 打到 logcat，tag = WebOverlay，方便 adb logcat -s WebOverlay。 */
    @JavascriptInterface
    public void log(String text) {
        Log.i(TAG, String.valueOf(text));
    }
}
