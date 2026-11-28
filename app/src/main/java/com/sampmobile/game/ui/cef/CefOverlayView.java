package com.sampmobile.game.ui.cef;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * 承载一个 omp-cef WebView 的触摸闸门。
 *
 * 为什么需要它：
 *   WebView 一旦可见就会吃掉所有触摸，而 omp-cef 的语义是"浏览器有焦点时
 *   页面接管输入"。如果整屏交给页面，虚拟摇杆、视角拖动、原生 HUD 全部失效。
 *
 * 现在的策略是**按手指拆分**：
 *   - 页面通过 cefNative.setTouchRegions(...) 上报按钮/摇杆的屏幕矩形；
 *   - 落在矩形内的手指归 WebView；
 *   - 落在矩形外的手指直接送进游戏现有的 Activity.onTouchEvent()；
 *   - 同一段 Android 手势里可以同时有 CEF 手指和游戏手指，互不阻塞。
 *
 * 没有声明区域的页面保持旧行为：浏览器有焦点即整屏接管。
 */
final class CefOverlayView extends FrameLayout {

    private static final String TAG = "CefOverlay";

    private static final int OWNER_NONE = 0;
    private static final int OWNER_CEF = 1;
    private static final int OWNER_GAME = 2;

    private static final int MAX_REGIONS = 8;

    private final int browserId;
    private final WebView content;

    /** true = 整段放行给游戏；false = 按区域拆分。 */
    private boolean passThrough = false;

    /** 页面声明的可交互矩形（屏幕物理像素，坐标相对本 View）。 */
    private final Rect[] touchRegions = new Rect[MAX_REGIONS];
    private int touchRegionCount = 0;

    /** 当前手势中每根手指的归属，key = pointerId。 */
    private final SparseIntArray owners = new SparseIntArray();

    private int cefPointers = 0;
    private int gamePointers = 0;
    private long cefDownTime = 0;
    private long gameDownTime = 0;

    CefOverlayView(Context context, int browserId, WebView content) {
        super(context);
        this.browserId = browserId;
        this.content = content;
        setClipChildren(false);
        setClipToPadding(false);
        addView(content, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void setPassThrough(boolean passThrough) {
        if (this.passThrough == passThrough) return;
        this.passThrough = passThrough;
        cancelAllGestures();
        Log.i(TAG, "browser " + browserId + " touch " + (passThrough ? "-> game" : "-> split"));
    }

    boolean isPassThrough() {
        return passThrough;
    }

    /** 设置页面可交互区域。CSV 格式：left,top,right,bottom;...，屏幕物理像素。 */
    void setTouchRegionsCsv(String csv) {
        touchRegionCount = 0;
        if (csv != null && !csv.isEmpty()) {
            String[] parts = csv.split(";");
            for (String part : parts) {
                if (touchRegionCount >= MAX_REGIONS) break;
                String[] p = part.split(",");
                if (p.length != 4) continue;
                try {
                    int left = Integer.parseInt(p[0].trim());
                    int top = Integer.parseInt(p[1].trim());
                    int right = Integer.parseInt(p[2].trim());
                    int bottom = Integer.parseInt(p[3].trim());
                    if (right <= left || bottom <= top) continue;
                    touchRegions[touchRegionCount++] = new Rect(left, top, right, bottom);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Log.i(TAG, "browser " + browserId + " touch regions=" + touchRegionCount);
    }

    /** 兼容单矩形调用。 */
    void setTouchRegion(int left, int top, int right, int bottom) {
        setTouchRegionsCsv(left + "," + top + "," + right + "," + bottom);
    }

    private boolean inRegion(float x, float y) {
        if (touchRegionCount == 0) return true; // 未声明 = 整屏
        for (int i = 0; i < touchRegionCount; i++) {
            Rect r = touchRegions[i];
            if (r != null && r.contains((int) x, (int) y)) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (passThrough) {
            // 不消费：让 DecorView 继续派发给游戏画面。
            return false;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                cancelAllGestures();
                final int id = ev.getPointerId(0);
                assign(id, ev.getX(0), ev.getY(0));
                dispatchNewPointer(id, ev);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int index = ev.getActionIndex();
                final int id = ev.getPointerId(index);
                assign(id, ev.getX(index), ev.getY(index));
                dispatchNewPointer(id, ev);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                dispatchMoveToBoth(ev);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                final int index = ev.getActionIndex();
                final int id = ev.getPointerId(index);
                dispatchPointerUp(id, ev);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelAllGestures();
                return true;
            }
        }
        return true;
    }

    private void assign(int id, float x, float y) {
        final int owner = inRegion(x, y) ? OWNER_CEF : OWNER_GAME;
        owners.put(id, owner);
        if (owner == OWNER_CEF) {
            if (cefPointers == 0) cefDownTime = SystemClock.uptimeMillis();
            cefPointers++;
        } else {
            if (gamePointers == 0) gameDownTime = SystemClock.uptimeMillis();
            gamePointers++;
        }
    }

    private void dispatchNewPointer(int id, MotionEvent ev) {
        final int owner = owners.get(id, OWNER_GAME);
        final int count = owner == OWNER_CEF ? cefPointers : gamePointers;
        final int action = count <= 1
                ? MotionEvent.ACTION_DOWN
                : (MotionEvent.ACTION_POINTER_DOWN
                        | (subsetIndex(ev, owner, id) << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        dispatchSide(owner, ev, id, action);
    }

    private void dispatchMoveToBoth(MotionEvent ev) {
        if (cefPointers > 0) dispatchSide(OWNER_CEF, ev, -1, MotionEvent.ACTION_MOVE);
        if (gamePointers > 0) dispatchSide(OWNER_GAME, ev, -1, MotionEvent.ACTION_MOVE);
    }

    private void dispatchPointerUp(int id, MotionEvent ev) {
        final int owner = owners.get(id, OWNER_NONE);
        if (owner == OWNER_NONE) return;

        final int count = owner == OWNER_CEF ? cefPointers : gamePointers;
        final int action;
        if (count <= 1) {
            action = MotionEvent.ACTION_UP;
        } else {
            action = MotionEvent.ACTION_POINTER_UP
                    | (subsetIndex(ev, owner, id) << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        dispatchSide(owner, ev, id, action);
        owners.delete(id);
        if (owner == OWNER_CEF) {
            cefPointers = Math.max(0, cefPointers - 1);
            if (cefPointers == 0) cefDownTime = 0;
        } else {
            gamePointers = Math.max(0, gamePointers - 1);
            if (gamePointers == 0) gameDownTime = 0;
        }

        // 另一侧的手指坐标可能也变了，补一个 MOVE。
        final int other = owner == OWNER_CEF ? OWNER_GAME : OWNER_CEF;
        final int otherCount = other == OWNER_CEF ? cefPointers : gamePointers;
        if (otherCount > 0) {
            dispatchSide(other, ev, -1, MotionEvent.ACTION_MOVE);
        }
    }

    /** 计算某根手指在其归属侧子集里的序号（0-based）。 */
    private int subsetIndex(MotionEvent ev, int owner, int targetId) {
        int idx = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) {
            final int id = ev.getPointerId(i);
            if (owners.get(id, OWNER_NONE) != owner) continue;
            if (id == targetId) return idx;
            idx++;
        }
        return 0;
    }

    /**
     * 从原始事件里筛出属于某一侧的手指，重映射 pointerId 为 0..n-1 后投递。
     *
     * @param targetId 用于计算 ACTION_POINTER_* 的 actionIndex；-1 表示 MOVE/CANCEL。
     */
    private void dispatchSide(int owner, MotionEvent ev, int targetId, int action) {
        final int total = ev.getPointerCount();
        int count = 0;
        for (int i = 0; i < total; i++) {
            if (owners.get(ev.getPointerId(i), OWNER_NONE) == owner) count++;
        }
        if (count == 0) return;

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            props[i] = new MotionEvent.PointerProperties();
            coords[i] = new MotionEvent.PointerCoords();
        }

        int out = 0;
        int actionIndex = 0;
        for (int i = 0; i < total; i++) {
            final int id = ev.getPointerId(i);
            if (owners.get(id, OWNER_NONE) != owner) continue;
            ev.getPointerProperties(i, props[out]);
            ev.getPointerCoords(i, coords[out]);
            props[out].id = out; // 每侧都从 0 开始，WebView / 游戏原生管线都更容易处理
            if (id == targetId) actionIndex = out;
            out++;
        }

        int finalAction = action;
        final int masked = action & MotionEvent.ACTION_MASK;
        if (masked == MotionEvent.ACTION_POINTER_DOWN || masked == MotionEvent.ACTION_POINTER_UP) {
            finalAction = masked | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }

        final long downTime = owner == OWNER_CEF ? cefDownTime : gameDownTime;
        MotionEvent subset = MotionEvent.obtain(
                downTime != 0 ? downTime : ev.getDownTime(),
                ev.getEventTime(),
                finalAction,
                count,
                props,
                coords,
                ev.getMetaState(),
                ev.getButtonState(),
                ev.getXPrecision(),
                ev.getYPrecision(),
                ev.getDeviceId(),
                ev.getEdgeFlags(),
                ev.getSource(),
                ev.getFlags());

        try {
            if (owner == OWNER_CEF) {
                // 绕过本类的 dispatchTouchEvent，直接交给 WebView 子树。
                super.dispatchTouchEvent(subset);
            } else {
                CefManager.dispatchGameTouch(subset);
            }
        } finally {
            subset.recycle();
        }
    }

    private void cancelAllGestures() {
        if (cefPointers > 0) cancelSide(OWNER_CEF);
        if (gamePointers > 0) cancelSide(OWNER_GAME);
        owners.clear();
        cefPointers = 0;
        gamePointers = 0;
        cefDownTime = 0;
        gameDownTime = 0;
    }

    private void cancelSide(int owner) {
        final long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        try {
            if (owner == OWNER_CEF) {
                super.dispatchTouchEvent(cancel);
            } else {
                CefManager.dispatchGameTouch(cancel);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cancel side failed", t);
        } finally {
            cancel.recycle();
        }
    }

    /** 给 WebView/游戏补 ACTION_CANCEL，结束正在处理的手势。 */
    void cancelChildGesture() {
        cancelAllGestures();
    }
}
