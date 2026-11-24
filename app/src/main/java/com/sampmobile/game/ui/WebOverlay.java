package com.sampmobile.game.ui;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.sampmobile.game.SAMP;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 游戏画面之上的 WebView 覆盖层（2026-11-22）。
 *
 * 设计要点：
 *  1. 预加载：onCreate 就建好 WebView 并加载本地壳页，等玩家点开时是"已渲染好"的状态，
 *     不会出现 300~600ms 的白屏。隐藏用 INVISIBLE 而不是 GONE —— GONE 的 View 不参与
 *     布局，WebView 会失去视口尺寸，再显示时要重新 layout + 重排文字，反而更慢。
 *  2. 触摸隔离：WebView 只在自己占据的矩形内吃事件，矩形之外的触摸依旧走 Activity
 *     的 onTouchEvent -> 游戏。所以"面板模式"把 WebView 尺寸设成面板大小，
 *     四周的可操作区完全不受影响；"全屏模式"才整屏接管。
 *  3. 打开/关闭时调用 SAMP.cancelAllNativeTouches()，避免玩家按着摇杆的同时点开面板，
 *     导致原生侧一直认为有一根手指没抬起（粘键）。
 *
 * 线程模型：所有 public 方法都可以在任意线程调用，内部会切到主线程。
 */
public class WebOverlay {

    private static final String TAG = "WebOverlay";

    /**
     * 打包在 APK 里的壳页，只作首次安装的种子 + 外部目录不可用时的兜底。
     *
     * 正常运行时页面来自 {@link #webRootDir()}（外部存储 files/web/），由
     * {@link WebRootServer} 通过 http://127.0.0.1:&lt;port&gt;/ 提供，所以加面板、
     * 改样式都只是 push 一个 html + reload，不需要重新打包 APK。
     */
    public static final String HOME = "file:///android_asset/web/index.html";

    /** 外部页面目录下的入口文件名。 */
    private static final String ENTRY = "index.html";

    /** 调试广播，仅 debuggable 包生效。 */
    public static final String ACTION_DEBUG = "com.sampmobile.OVERLAY";

    public static final int MODE_HIDDEN = 0;
    public static final int MODE_PANEL = 1;
    public static final int MODE_FULL = 2;

    private final SAMP activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WebView webView;
    private WebBridge bridge;
    private BroadcastReceiver debugReceiver;

    private volatile int mode = MODE_HIDDEN;
    private boolean created;
    private boolean pageReady;
    private String pendingUrl;                 // 显式指定的页面（调试广播 / preload）
    private String loadedUrl = "";
    private volatile String openData = "{}";

    // ---- 外部页面目录 + 本地 http 服务（见类注释与 webRootDir()）
    private WebRootServer rootServer;
    private volatile String baseUrl;           // "http://127.0.0.1:<port>/"，服务没起来时为 null
    private volatile String homeUrl = HOME;    // 入口页完整 URL，兜底为 assets

    private float cornerRadiusPx = 0f;
    private long lastGreetAt = 0L;   // 上次给服务端发 /wvm 的时间，节流用

    public WebOverlay(SAMP activity) {
        this.activity = activity;
    }

    // ------------------------------------------------------------------ 生命周期

    /** onCreate 里调用一次：建 WebView + 加载壳页 + 注册调试广播。 */
    public void warmUp() {
        // 故意 post 到消息队列尾部：WebView 构造会占主线程 100ms 左右，
        // 放到 onCreate 之后执行，不拖慢游戏启动，但仍然远早于玩家第一次点开。
        main.post(new Runnable() {
            @Override
            public void run() {
                ensureCreated();
                registerDebugReceiver();
            }
        });
    }

    /** 预加载指定页面（可反复调用，仅在页面不同时才真正加载）。 */
    public void preload(String url) {
        preload(url, null);
    }

    public void preload(final String url, final String json) {
        run(new Runnable() {
            @Override
            public void run() {
                if (url != null && url.length() > 0) pendingUrl = url;
                if (json != null) openData = json;
                if (!created) {
                    ensureCreated();
                    return;
                }
                if (webView != null && pendingUrl != null && !pendingUrl.equals(loadedUrl)) {
                    loadedUrl = pendingUrl;
                    pageReady = false;
                    webView.loadUrl(pendingUrl);
                }
            }
        });
    }

    /** 全屏打开。 */
    public void show() {
        show(null, null);
    }

    public void show(String url, String json) {
        internalShow(url, json, MODE_FULL, 0, 0, 0, 0);
    }

    /** 面板模式：WebView 只占 (x,y,w,h) 这块矩形，其余区域照常操作游戏。 */
    public void showPanel(int x, int y, int w, int h) {
        showPanel(x, y, w, h, null, null);
    }

    public void showPanel(final int x, final int y, final int w, final int h,
                          final String url, final String json) {
        if (w <= 0 || h <= 0) {
            show(url, json);
            return;
        }
        internalShow(url, json, MODE_PANEL, x, y, w, h);
    }

    /** 面板模式（按屏幕比例居中），wRatio/hRatio 建议 0.5~0.85。 */
    public void showPanelCentered(float wRatio, float hRatio, String url, String json) {
        int sw = activity.getResources().getDisplayMetrics().widthPixels;
        int sh = activity.getResources().getDisplayMetrics().heightPixels;
        int w = (int) (sw * clampRatio(wRatio));
        int h = (int) (sh * clampRatio(hRatio));
        showPanel((sw - w) / 2, (sh - h) / 2, w, h, url, json);
    }

    public void hide() {
        run(new Runnable() {
            @Override
            public void run() {
                if (webView == null) {
                    mode = MODE_HIDDEN;
                    return;
                }
                hideIme();
                webView.clearFocus();
                webView.setVisibility(View.INVISIBLE);
                mode = MODE_HIDDEN;
                activity.cancelAllNativeTouches();
            }
        });
    }

    public boolean isVisible() {
        return mode != MODE_HIDDEN;
    }

    /** 交给 SAMP.onBackPressed() 调用：返回 true 表示这次返回键被覆盖层吃掉了。 */
    public boolean onBackPressed() {
        if (mode == MODE_HIDDEN) return false;
        hide();
        return true;
    }

    public void onResume() {
        run(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.resumeTimers();
                    webView.onResume();
                }
            }
        });
    }

    public void onPause() {
        run(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.onPause();
                    webView.pauseTimers();
                }
            }
        });
    }

    public void destroy() {
        run(new Runnable() {
            @Override
            public void run() {
                unregisterDebugReceiver();
                if (webView != null) {
                    ViewGroup parent = null;
                    if (webView.getParent() instanceof ViewGroup) {
                        parent = (ViewGroup) webView.getParent();
                    }
                    if (parent != null) parent.removeView(webView);
                    webView.stopLoading();
                    webView.setWebChromeClient(null);
                    webView.setWebViewClient(null);
                    webView.removeJavascriptInterface("samp");
                    webView.loadUrl("about:blank");
                    webView.destroy();
                    webView = null;
                }
                bridge = null;
                if (rootServer != null) {
                    rootServer.stop();
                    rootServer = null;
                }
                baseUrl = null;
                homeUrl = HOME;
                created = false;
                pageReady = false;
                mode = MODE_HIDDEN;
            }
        });
    }

    /** 面板圆角（px，dp * density）。0 = 直角。 */
    public void setCornerRadius(final float px) {
        run(new Runnable() {
            @Override
            public void run() {
                cornerRadiusPx = px;
                if (webView == null) return;
                if (px <= 0f) {
                    webView.setClipToOutline(false);
                    webView.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                    return;
                }
                webView.setClipToOutline(true);
                webView.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), px);
                    }
                });
                webView.invalidateOutline();
            }
        });
    }

    // ------------------------------------------------------------------ 页面目录 / 本地服务

    /**
     * 页面根目录：/sdcard/Android/data/&lt;包名&gt;/files/web/
     *
     * 这是"加面板不用重编 APK"的关键——页面不属于 APK，属于可写数据目录：
     * <pre>
     *   adb push shop.html /sdcard/Android/data/com.sampmobile.launcher/files/web/
     *   adb shell am broadcast -a com.sampmobile.OVERLAY --es action reload
     * </pre>
     */
    public File webRootDir() {
        File ext = activity.getExternalFilesDir(null);
        return ext == null ? null : new File(ext, "web");
    }

    /** 当前入口页完整 URL（http://127.0.0.1:port/index.html，服务没起来时是 assets 兜底）。 */
    public String homeUrl() {
        return homeUrl;
    }

    /** 热重载当前页面：带 cache buster，push 完文件直接调它就能看到新内容。 */
    public void reload() {
        run(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                String base = (loadedUrl == null || loadedUrl.length() == 0) ? homeUrl : loadedUrl;
                pageReady = false;
                String url = base + (base.indexOf('?') < 0 ? "?" : "&")
                        + "r=" + System.currentTimeMillis();
                Log.i(TAG, "reload -> " + url);
                webView.loadUrl(url);
            }
        });
    }

    private void prepareWebRoot() {
        File web = webRootDir();
        if (web == null) {
            Log.w(TAG, "external files dir unavailable, fallback to assets");
            return;
        }
        try {
            if (!web.isDirectory() && !web.mkdirs()) {
                Log.w(TAG, "cannot create " + web + ", fallback to assets");
                return;
            }
            seedFromAssets(web);

            WebRootServer s = new WebRootServer(web);
            int port = s.start();
            rootServer = s;
            baseUrl = "http://127.0.0.1:" + port + "/";
            homeUrl = baseUrl + ENTRY;
            Log.i(TAG, "web root ready: " + web + " -> " + homeUrl);
        } catch (Throwable t) {
            Log.w(TAG, "web root server failed, fallback to assets: " + t);
            if (rootServer != null) {
                rootServer.stop();
                rootServer = null;
            }
            baseUrl = null;
            homeUrl = HOME;
        }
    }

    /** 只在外部目录还没有 index.html 时把 APK 内置页复制过去当种子；绝不覆盖已有页面。 */
    private void seedFromAssets(File web) {
        File idx = new File(web, ENTRY);
        if (idx.isFile() && idx.length() > 0) return;
        try {
            copyAssetTree("web", web);
            Log.i(TAG, "seeded web root from assets -> " + web);
        } catch (Throwable t) {
            Log.w(TAG, "seed from assets failed: " + t);
        }
    }

    private void copyAssetTree(String assetPath, File dst) throws IOException {
        String[] names = activity.getAssets().list(assetPath);
        if (names == null || names.length == 0) {
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (InputStream in = activity.getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return;
        }
        if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("mkdir " + dst);
        for (String n : names) {
            copyAssetTree(assetPath + "/" + n, new File(dst, n));
        }
    }

    // ------------------------------------------------------------------ 内部

    private void internalShow(final String url, final String json, final int m,
                              final int x, final int y, final int w, final int h) {
        run(new Runnable() {
            @Override
            public void run() {
                ensureCreated();
                if (webView == null) return;

                openData = (json == null || json.length() == 0) ? "{}" : json;

                String target = (url == null || url.length() == 0) ? pendingUrl : url;
                if (target == null || target.length() == 0) target = homeUrl;
                if (!target.equals(loadedUrl)) {
                    loadedUrl = target;
                    pageReady = false;
                    webView.loadUrl(target);
                } else if (pageReady) {
                    pushContext();
                }

                applyLayout(m, x, y, w, h);
                mode = m;
                webView.setVisibility(View.VISIBLE);
                webView.bringToFront();
                webView.resumeTimers();
                webView.onResume();
                webView.requestFocus();

                // 面板出现的一瞬间把游戏侧的手势清零，避免粘键
                activity.cancelAllNativeTouches();

                // 顺手向服务端登记一次"我在用手机面板"，这样 /wvshop 才会推给我们
                greetServerIfNeeded();
            }
        });
    }

    private void applyLayout(int m, int x, int y, int w, int h) {
        if (webView == null) return;
        ViewGroup.LayoutParams raw = webView.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) {
            raw = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            webView.setLayoutParams(raw);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        if (m == MODE_FULL) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.leftMargin = 0;
            lp.topMargin = 0;
        } else {
            lp.width = Math.max(1, w);
            lp.height = Math.max(1, h);
            lp.leftMargin = Math.max(0, x);
            lp.topMargin = Math.max(0, y);
        }
        lp.gravity = Gravity.TOP | Gravity.START;
        webView.setLayoutParams(lp);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureCreated() {
        if (created) return;
        if (activity.isFinishing()) return;
        created = true;

        // 先把外部页面目录 + 本地 http 服务准备好，下面才能拿到 homeUrl
        prepareWebRoot();

        WebView wv = new WebView(activity);
        wv.setBackgroundColor(Color.TRANSPARENT);
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        if ((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        wv.setWebViewClient(new OverlayClient());
        wv.setWebChromeClient(new OverlayChrome());
        bridge = new WebBridge(this);
        wv.addJavascriptInterface(bridge, "samp");

        // 挂在 DecorView 上而不是 getMainView()：DecorView 一定是 FrameLayout，
        // 且加在 content 之后天然位于游戏画面之上，不依赖 GTASA 的内部结构。
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        decor.addView(wv, lp);
        wv.setVisibility(View.INVISIBLE);

        webView = wv;
        String start = (pendingUrl != null && pendingUrl.length() > 0) ? pendingUrl : homeUrl;
        loadedUrl = start;
        wv.loadUrl(start);
        Log.i(TAG, "warm up -> " + start);
    }

    private void pushContext() {
        if (webView == null) return;
        String payload;
        try {
            payload = JSONObject.quote(openData == null ? "{}" : openData);
        } catch (Throwable t) {
            payload = "\"{}\"";
        }
        String js = "(function(){try{if(window.SampOverlay&&window.SampOverlay.onContext){"
                + "window.SampOverlay.onContext(" + payload + ");}}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private void hideIme() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && webView != null) {
                imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;

        if (url.startsWith("sampmobile://")) {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if ("close".equals(host)) {
                hide();
            } else if ("cmd".equals(host)) {
                sendCommand(u.getQueryParameter("text"));
            } else if ("toast".equals(host)) {
                toast(u.getQueryParameter("text"));
            }
            return true;
        }

        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
            return false;   // 页面内部跳转，留在 WebView
        }

        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable ignored) {
        }
        return true;
    }

    // ------------------------------------------------------------------ 供 WebBridge 调用

    void onBridgeReady() {
        run(new Runnable() {
            @Override
            public void run() {
                pageReady = true;
                pushContext();
            }
        });
    }

    /**
     * 页面 -> 服务端的唯一通道：当成一条聊天命令发出去。
     *
     * 这里必须用 _keyboardSend 而不是 _chatSend：UI.chatSend 在 native 侧
     * 没有对应实现（Java_com_sampmobile_game_ui_UI_chatSend 不存在），
     * 调用会抛 UnsatisfiedLinkError。_keyboardSend -> keyboardSend(byte[])
     * 会进 Chat::keyboardEvent()，走 / 开头的分支直接 pNetGame->SendChatCommand()，
     * 不需要打开聊天框，服务端 OnPlayerCommandText 直接收到。
     */
    void sendCommand(final String raw) {
        run(new Runnable() {
            @Override
            public void run() {
                if (raw == null) return;
                String cmd = raw.replace('\n', ' ').replace('\r', ' ').trim();
                if (cmd.length() == 0 || cmd.length() > 120) return;
                if (cmd.charAt(0) != '/') cmd = "/" + cmd;
                UI ui = activity.ui;
                if (ui == null) return;
                Log.i(TAG, "cmd -> " + cmd);
                try {
                    ui._keyboardSend(cmd);
                } catch (Throwable t) {
                    Log.w(TAG, "send command failed: " + t);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 服务端推来的指令

    /**
     * 服务端 -> 覆盖层。服务端用 ShowPlayerDialog 的标题 "#WV#" 当传输通道，
     * 消息体是 "ACTION|PAYLOAD"；UI.showDialog 看到这个标题就不弹原生对话框，
     * 直接转到这里。返回 true 表示这条消息已经被覆盖层接管。
     */
    public boolean handleServerDialog(String message) {
        if (message == null) return false;
        String body = message.trim();
        int bar = body.indexOf('|');
        String action = (bar < 0 ? body : body.substring(0, bar)).trim();
        String payload = (bar < 0 ? "" : body.substring(bar + 1)).trim();
        if (action.length() == 0) return false;

        if ("OPEN".equals(action)) {
            // 服务端开的店：面板模式，别整屏挡住游戏
            showPanelCentered(0.80f, 0.72f, null, payload);
            return true;
        }
        if ("OPENURL".equals(action)) {
            // 服务端指定任意页面（新增面板不需要重编 APK）：
            //   {"u":"shop.html","w":80,"h":72}   w/h 是屏幕百分比，缺省 80/72
            String page = ENTRY;
            float wr = 0.80f;
            float hr = 0.72f;
            try {
                JSONObject o = new JSONObject(payload);
                page = o.optString("u", ENTRY);
                wr = o.optInt("w", 80) / 100f;
                hr = o.optInt("h", 72) / 100f;
            } catch (Throwable t) {
                Log.w(TAG, "OPENURL bad payload: " + payload);
            }
            String full;
            if (page.startsWith("http://") || page.startsWith("https://") || page.startsWith("file:")) {
                full = page;                                  // 远程页面
            } else {
                full = (baseUrl == null) ? HOME : baseUrl + page;
            }
            showPanelCentered(wr, hr, full, payload);
            return true;
        }
        if ("CLOSE".equals(action)) {
            hide();
            return true;
        }
        if ("SYNC".equals(action) || "TOAST".equals(action)) {
            dispatchServerEvent(action, payload);
            return true;
        }
        return false;
    }

    private void dispatchServerEvent(final String action, final String payload) {
        run(new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                String js = "(function(){try{if(window.SampOverlay&&window.SampOverlay.onServerEvent){"
                        + "window.SampOverlay.onServerEvent(" + JSONObject.quote(action) + ","
                        + JSONObject.quote(payload) + ");}}catch(e){}})();";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    /** 告诉服务端"我是手机端"，服务端据此才允许推面板。/wv 命令由 webview_test.inc 处理。 */
    void greetServerIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastGreetAt < 60000L) return;
        lastGreetAt = now;
        sendCommand("/wvm");
    }

    void toast(final String text) {
        run(new Runnable() {
            @Override
            public void run() {
                if (text == null || text.length() == 0) return;
                try {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    String envJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("platform", "android");
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("mode", mode);
            o.put("url", loadedUrl);
            o.put("root", baseUrl == null ? "assets" : "http");
            o.put("w", activity.getResources().getDisplayMetrics().widthPixels);
            o.put("h", activity.getResources().getDisplayMetrics().heightPixels);
            o.put("density", (double) activity.getResources().getDisplayMetrics().density);
            try {
                o.put("data", new JSONObject(openData));
            } catch (Throwable t) {
                try {
                    o.put("data", new JSONObject());
                } catch (Throwable ignored) {
                }
            }
            return o.toString();
        } catch (Throwable t) {
            return "{\"platform\":\"android\"}";
        }
    }

    // ------------------------------------------------------------------ 调试入口

    private void registerDebugReceiver() {
        if (debugReceiver != null) return;
        if ((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;

        debugReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getStringExtra("action");
                if ("reload".equals(action)) {
                    // 改完 files/web/ 里的页面后热重载，不用重装 APK
                    reload();
                    return;
                }
                // 直接发一条聊天命令，等价于页面里 window.samp.command()。
                // 好处是绕开手机输入法（adb input text 在中文 IME 下会吞字符）：
                //   adb shell am broadcast -a com.sampmobile.OVERLAY -p <包名> --es cmd "/wvpage bag.html"
                String cmd = intent.getStringExtra("cmd");
                if (cmd != null && cmd.length() > 0) {
                    sendCommand(cmd);
                    return;
                }
                String url = intent.getStringExtra("url");
                String data = intent.getStringExtra("data");
                int m = intent.getIntExtra("mode", MODE_FULL);
                if (m == MODE_PANEL) {
                    showPanel(intent.getIntExtra("x", 0), intent.getIntExtra("y", 0),
                            intent.getIntExtra("w", 0), intent.getIntExtra("h", 0), url, data);
                } else {
                    show(url, data);
                }
            }
        };

        try {
            IntentFilter filter = new IntentFilter(ACTION_DEBUG);
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(debugReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                activity.registerReceiver(debugReceiver, filter);
            }
            Log.i(TAG, "debug receiver ready: " + ACTION_DEBUG);
        } catch (Throwable t) {
            Log.w(TAG, "register debug receiver failed: " + t);
            debugReceiver = null;
        }
    }

    private void unregisterDebugReceiver() {
        if (debugReceiver == null) return;
        try {
            activity.unregisterReceiver(debugReceiver);
        } catch (Throwable ignored) {
        }
        debugReceiver = null;
    }

    // ------------------------------------------------------------------ 工具

    private void run(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main.post(r);
        }
    }

    private static float clampRatio(float v) {
        if (v < 0.2f) return 0.2f;
        if (v > 1f) return 1f;
        return v;
    }

    // ------------------------------------------------------------------ WebView 回调

    private final class OverlayClient extends WebViewClient {

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUrl(url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString());
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageReady = true;
            pushContext();
        }
    }

    private final class OverlayChrome extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage cm) {
            Log.i(TAG, "[js] " + cm.message() + " (" + cm.lineNumber() + ")");
            return true;
        }
    }
}
