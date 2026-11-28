package com.sampmobile.game.ui.cef;

import android.webkit.JavascriptInterface;

/**
 * window.cefNative —— window.cef 的页面侧实现与原生之间的通道。
 *
 * 与 CEF 的进程边界对应：
 *   PC: renderer 进程 CefV8Handler -> SendProcessMessage(PID_BROWSER) -> client.cpp
 *   Android: 同一个进程，WebView 的 JavaBridge 线程直接回调到这里
 *
 * 线程：方法运行在 WebView 的 JavaBridge 线程，不是主线程。这里不做 UI 操作，
 * 只把事件交给 CefManager -> JNI -> CefNetwork，因此不需要切主线程（切了反而
 * 会把页面点击与服务端回执之间的延迟拉长一帧）。
 *
 * 安全边界：接口只挂在单个 WebView 实例上（addJavascriptInterface 是 per-view
 * 的），且只有该 view 加载的页面能访问。内容来源与服务端同源（omp-cef 的 UI
 * 资源由服务端下发），与 PC 版任何页面都可调用 window.cef.emit 的信任模型一致。
 */
final class CefJsBridge {

    private final int browserId;

    CefJsBridge(int browserId) {
        this.browserId = browserId;
    }

    /** cef.emit(eventName, ...args)；argsJson 是 JSON.stringify 后的标量数组。 */
    @JavascriptInterface
    public void emit(String name, String argsJson) {
        if (name == null || name.length() == 0) return;
        CefManager.onJsEmit(browserId, name, argsJson != null ? argsJson : "[]");
    }

    /** cef.set_focus(hasFocus) -> FocusBrowser 语义（与 PC manager.FocusBrowser 一致）。 */
    @JavascriptInterface
    public void setFocus(boolean hasFocus) {
        CefManager.focusBrowser(browserId, hasFocus);
    }

    /**
     * 页面声明自己的可交互区域（屏幕物理像素，CSV：left,top,right,bottom;...）。
     *
     * 声明后，只有从这些矩形内开始的触摸属于 WebView；矩形外的手指直接交给
     * 游戏原生触摸管线，因此聚焦的 CEF 页面不会压死虚拟摇杆/视角拖动/原生 HUD。
     * 不调用时保持旧行为：浏览器有焦点即整屏接管。
     */
    @JavascriptInterface
    public void setTouchRegions(String csv) {
        CefManager.setBrowserTouchRegions(browserId, csv);
    }

    /** 单矩形兼容入口。 */
    @JavascriptInterface
    public void setTouchRegion(int left, int top, int right, int bottom) {
        CefManager.setBrowserTouchRegion(browserId, left, top, right, bottom);
    }
}
