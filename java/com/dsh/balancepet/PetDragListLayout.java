package com.dsh.balancepet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 可拖拽的「泡泡内容」列表（手写，无 Gradle / 不引 androidx）。
 *
 * <p>它只负责**手势与视觉反馈**，「结构怎么变」全部委托给 {@link PetBubbleEditModel}（纯函数，可自检）。
 * 这样即使手势我没法在真机上验证（ColorOS 拦 am start、无法注入触摸），改动语义也仍然是有保证的。
 *
 * <p>交互（与上游 {@code whale-widget.js} 的触摸端一致）：
 * <ul>
 *   <li>**长按 400ms** 才拾起；在这之前手指一动就交还给 ScrollView 正常滚动（不会「滚不动」）</li>
 *   <li>拖 <b>⠿</b> 手柄 = 整行；拖**模块芯片** = 单个模块</li>
 *   <li>落点四态：行上边缘 = 插到该行之前 / 下边缘 = 之后 / 行内左半 = 并入（左）/ 右半 = 并入（右）</li>
 *   <li>跟手的是半透明「幽灵」芯片；目标行会高亮，幽灵上写明这一放会发生什么</li>
 * </ul>
 *
 * <p>⚠️ 已知限制（如实记录）：**没有自动滚动**（手指停在列表上下边缘时不会自动滚到更远的行），
 * 也**没有**惯性/动画收尾。要跨很多行就分几次拖。
 */
public final class PetDragListLayout extends LinearLayout {

    /** 挂在视图 tag 上的坐标：{@link #col} = {@link PetBubbleEditModel#HANDLE} 表示整行手柄。 */
    public static final class Slot {
        public final int row;
        public final int col;

        public Slot(int row, int col) {
            this.row = row;
            this.col = col;
        }

        public boolean isHandle() { return col == PetBubbleEditModel.HANDLE; }
        public boolean isNoDrag() { return col == NO_DRAG_COL; }
    }

    /** 不参与拖拽的视图（例如芯片上的 ✎ 编辑 / ✕ 删除，或行的空白区域）。 */
    public static final int NO_DRAG_COL = -99;

    public interface Listener {
        /** 结构被改动（增 / 删 / 改排）→ 编辑器重建列表与预览。 */
        void onStructureChanged();

        /** 一次拖放结束：{@code changed=false} 表示被上限拒绝或无变化（编辑器可以提示用户）。 */
        void onDropApplied(boolean changed, String message);

        /** 拖拽状态变化（编辑器用它显示/隐藏「拖拽中」提示与缩放预览）。 */
        void onDragStateChanged(boolean dragging);
    }

    private static final long LONG_PRESS_MS = 400;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int slop;

    private Listener listener;
    private ViewGroup overlayParent;
    private List<List<PetBubbleModule>> rows;

    private Slot pendingSlot;
    private View pendingView;
    private float downX, downY;
    private Runnable pendingPress;

    private boolean dragging;
    private boolean dragIsRow;
    private int dragRow = -1, dragCol = -1;
    private View dragSource;
    private TextView ghost;
    private float ghostDx, ghostDy;

    private int hlRow = Integer.MIN_VALUE;
    private int hlZone = -1;
    private Drawable hlOriginal;

    public PetDragListLayout(Context context) {
        super(context);
        setOrientation(VERTICAL);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 幽灵芯片要加到哪个容器里（一般是编辑器的根 FrameLayout）。 */
    public void setOverlayParent(ViewGroup parent) { this.overlayParent = parent; }

    /** 与编辑器共享的行数据（只读引用；拖拽时用来生成幽灵上的文字）。 */
    public void setRows(List<List<PetBubbleModule>> rows) { this.rows = rows; }

    public boolean isDragging() { return dragging; }

    // ---------------------------------------------------------------- 触摸

    /**
     * 由 {@link DragScrollView} 在 {@code dispatchTouchEvent} 里逐事件调用。
     *
     * @return true = 本次事件由拖拽逻辑消费（不要再分发给子视图 / ScrollView）
     */
    public boolean onDragTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelPending();
                float x = ev.getRawX(), y = ev.getRawY();
                View[] holder = new View[1];
                Slot slot = hitSlot(x, y, holder);
                if (slot != null && !slot.isNoDrag() && holder[0] != null) {
                    pendingSlot = slot;
                    pendingView = holder[0];
                    downX = x;
                    downY = y;
                    pendingPress = new Runnable() {
                        @Override public void run() {
                            if (pendingSlot == null) return;
                            startDrag(pendingSlot, pendingView);
                        }
                    };
                    handler.postDelayed(pendingPress, LONG_PRESS_MS);
                }
                return false;                      // 先不拦截：让点击与滚动照常工作

            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    updateDrag(ev);
                    return true;
                }
                if (pendingSlot != null && dist(ev) > slop) cancelPending();   // 手指动了 → 交还滚动
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPending();
                if (dragging) {
                    finishDrag(ev);
                    return true;
                }
                return false;

            default:
                return dragging;
        }
    }

    private float dist(MotionEvent ev) {
        return (float) Math.hypot(ev.getRawX() - downX, ev.getRawY() - downY);
    }

    private void cancelPending() {
        if (pendingPress != null) handler.removeCallbacks(pendingPress);
        pendingPress = null;
        pendingSlot = null;
        pendingView = null;
    }

    // ---------------------------------------------------------------- 拾起 / 拖动 / 落下

    private void startDrag(Slot slot, View src) {
        pendingPress = null;
        if (src == null) return;
        dragging = true;
        dragIsRow = slot.isHandle();
        dragRow = slot.row;
        dragCol = slot.col;
        dragSource = src;
        int[] loc = new int[2];
        src.getLocationOnScreen(loc);
        ghostDx = downX - loc[0];
        ghostDy = downY - loc[1];
        src.setPressed(false);
        src.setAlpha(dragIsRow ? 0.5f : 0.3f);

        if (overlayParent != null) {
            ghost = new TextView(getContext());
            ghost.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            ghost.setTextColor(Color.WHITE);
            ghost.setPadding(dp(12), dp(8), dp(12), dp(8));
            ghost.setBackground(roundRect(0xF04A6B8A, dp(10)));
            ghost.setElevation(dp(6));
            ghost.setText(ghostLabel() + "\n" + PetBubbleEditModel.dropZoneLabel(
                    PetBubbleEditModel.DROP_BEFORE));
            FrameLayoutParams lp = new FrameLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            overlayParent.addView(ghost, lp);
            moveGhost(downX, downY);
        }
        if (listener != null) listener.onDragStateChanged(true);
        Log.write("编辑器：拾起" + (dragIsRow ? "整行" : "模块") + " 行=" + dragRow
                + (dragIsRow ? "" : " 列=" + dragCol));
    }

    /** FrameLayout$LayoutParams（不 import android.widget.FrameLayout 的常量，避免歧义）。 */
    private static final class FrameLayoutParams extends android.widget.FrameLayout.LayoutParams {
        FrameLayoutParams(int w, int h) { super(w, h); }
    }

    private void updateDrag(MotionEvent ev) {
        float x = ev.getRawX(), y = ev.getRawY();
        moveGhost(x, y);
        int[] target = targetAt(x, y);
        setHighlight(target[0], target[1]);
        if (ghost != null) {
            ghost.setText(ghostLabel() + "\n" + dropHint(target[0], target[1]));
        }
    }

    private void moveGhost(float rawX, float rawY) {
        if (ghost == null || overlayParent == null) return;
        int[] loc = new int[2];
        overlayParent.getLocationOnScreen(loc);
        ghost.setX(rawX - loc[0] - ghostDx);
        ghost.setY(rawY - loc[1] - ghostDy - dp(6));
        ghost.bringToFront();
    }

    private void finishDrag(MotionEvent ev) {
        float x = ev.getRawX(), y = ev.getRawY();
        int[] target = targetAt(x, y);
        boolean overRow = target[0] < 0 || target[0] >= size();
        boolean changed;
        String message;
        if (overRow && dragIsRow && target[0] >= size() && dragRow == size() - 1) {
            changed = false;
            message = "已经在最后一行";
        } else if (overRow && dragIsRow && target[0] < 0 && dragRow == 0) {
            changed = false;
            message = "已经在第一行";
        } else if (target[0] == dragRow && !dragIsRow
                && (target[1] == PetBubbleEditModel.DROP_PAIR_LEFT
                || target[1] == PetBubbleEditModel.DROP_PAIR_RIGHT)) {
            changed = false;
            message = "同一行内本来就是并排的";
        } else {
            String before = PetBubbleEditModel.signature(rows);
            boolean moved = PetBubbleEditModel.applyDrop(rows, dragIsRow, dragRow, dragCol,
                    target[0], target[1]);
            String after = PetBubbleEditModel.signature(rows);
            // 结构签名没变 = 「原地放下」：虽然 applyDrop 认为执行成功，也不该说「已移动」
            changed = moved && !before.equals(after);
            message = changed
                    ? (dragIsRow ? "整行已移动：" : "模块已移动：") + dropHint(target[0], target[1])
                    : "这一放没有生效（原地放下，或超过了每行 6 个 / 每泡 6 行的上限）";
        }
        Log.write("编辑器：" + (dragIsRow ? "整行" : "模块") + "拖放 → " + message
                + "；结果=" + PetBubbleEditModel.describe(rows));
        endDragVisuals();
        if (listener != null) {
            if (changed) listener.onStructureChanged();
            listener.onDropApplied(changed, message);
            listener.onDragStateChanged(false);
        }
    }

    private void endDragVisuals() {
        dragging = false;
        if (dragSource != null) dragSource.setAlpha(1f);
        dragSource = null;
        if (ghost != null && overlayParent != null) overlayParent.removeView(ghost);
        ghost = null;
        clearHighlight();
    }

    private String ghostLabel() {
        if (rows == null || dragRow < 0 || dragRow >= rows.size()) {
            return dragIsRow ? "整行" : "模块";
        }
        List<PetBubbleModule> row = rows.get(dragRow);
        if (dragIsRow) {
            StringBuilder sb = new StringBuilder("⠿");
            for (PetBubbleModule m : row) sb.append(' ').append(shortName(m));
            return sb.toString();
        }
        if (dragCol < 0 || dragCol >= row.size()) return "模块";
        return shortName(row.get(dragCol));
    }

    private static String shortName(PetBubbleModule m) {
        if (m == null) return "?";
        String label = PetBubbleModule.typeLabel(m.type);
        if (PetBubbleModule.TYPE_TEXT.equals(m.type) && m.text != null && !m.text.isEmpty()) {
            String t = m.text;
            return label + ":" + (t.length() > 6 ? t.substring(0, 6) + "…" : t);
        }
        return label;
    }

    private String dropHint(int overRow, int zone) {
        if (overRow < 0) return "移到最前面";
        if (overRow >= size()) return "移到最后面";
        return PetBubbleEditModel.dropZoneLabel(zone);
    }

    private int size() { return getChildCount(); }

    // ---------------------------------------------------------------- 命中判定

    private Slot hitSlot(float rawX, float rawY, View[] holder) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View rowView = getChildAt(i);
            if (!containsOnScreen(rowView, rawX, rawY)) continue;
            View[] deepOut = new View[1];
            Slot deep = deepestSlot(rowView, rawX, rawY, 0, deepOut);
            holder[0] = deepOut[0] != null ? deepOut[0] : rowView;   // 幽灵跟手时对齐「真正按住的那个视图」
            return deep;
        }
        return null;
    }

    /** 取最深的带 Slot 的视图（这样芯片里的 ✎/✕ 能覆盖芯片本身的语义）。 */
    private Slot deepestSlot(View v, float rawX, float rawY, int depth, View[] out) {
        if (v instanceof ViewGroup && depth < 3) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View child = g.getChildAt(i);
                if (!containsOnScreen(child, rawX, rawY)) continue;
                Slot s = deepestSlot(child, rawX, rawY, depth + 1, out);
                if (s != null) return s;
            }
        }
        Object tag = v.getTag();
        if (tag instanceof Slot) {
            out[0] = v;
            return (Slot) tag;
        }
        return null;
    }

    private static boolean containsOnScreen(View v, float rawX, float rawY) {
        if (v.getVisibility() != VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + v.getHeight();
    }

    /**
     * 手指下方是哪一行、什么落点。
     *
     * @return {@code {行下标, 落点}}；{@code -1} = 在第一行之上，{@code size()} = 在最后一行之下
     */
    public int[] targetAt(float rawX, float rawY) {
        int n = getChildCount();
        if (n == 0) return new int[]{-1, PetBubbleEditModel.DROP_BEFORE};
        int[] loc = new int[2];
        for (int i = 0; i < n; i++) {
            View row = getChildAt(i);
            row.getLocationOnScreen(loc);
            int h = row.getHeight();
            if (rawY >= loc[1] && rawY <= loc[1] + h) {
                return new int[]{i, PetBubbleEditModel.dropZone(
                        rawY - loc[1], h, rawX - loc[0], row.getWidth())};
            }
        }
        // 落在行与行的缝隙里：按最近一行的中线决定插到它前面还是后面
        int nearest = -1;
        float best = Float.MAX_VALUE;
        float center = 0f;
        for (int i = 0; i < n; i++) {
            View row = getChildAt(i);
            row.getLocationOnScreen(loc);
            float c = loc[1] + row.getHeight() / 2f;
            float d = Math.abs(rawY - c);
            if (d < best) {
                best = d;
                nearest = i;
                center = c;
            }
        }
        if (nearest < 0) return new int[]{-1, PetBubbleEditModel.DROP_BEFORE};
        return new int[]{nearest,
                rawY < center ? PetBubbleEditModel.DROP_BEFORE : PetBubbleEditModel.DROP_AFTER};
    }

    // ---------------------------------------------------------------- 高亮

    private void setHighlight(int row, int zone) {
        if (hlRow == row && hlZone == zone) return;
        clearHighlight();
        if (row < 0 || row >= getChildCount()) return;
        View v = getChildAt(row);
        hlOriginal = v.getBackground();
        boolean pair = zone == PetBubbleEditModel.DROP_PAIR_LEFT
                || zone == PetBubbleEditModel.DROP_PAIR_RIGHT;
        v.setBackground(roundRect(pair ? 0x334A6B8A : 0x22203170, dp(10)));
        hlRow = row;
        hlZone = zone;
    }

    private void clearHighlight() {
        if (hlRow == Integer.MIN_VALUE) return;
        if (hlRow >= 0 && hlRow < getChildCount()) {
            getChildAt(hlRow).setBackground(hlOriginal);
        }
        hlRow = Integer.MIN_VALUE;
        hlZone = -1;
        hlOriginal = null;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 编辑器用的 ScrollView：先把事件交给拖拽控制器，控制器不要（未进入拖拽）时照常滚动与点击。
     *
     * <p>为什么放在这里而不是继承改造：{@link PetDragListLayout} 需要「在手指按在芯片上长按 400ms」
     * 时接管事件流，但它不是事件的根；把判断放在 ScrollView 的 {@code dispatchTouchEvent} 里，
     * 既能拿到**全部**事件（包括被系统判定为滚动的移动），又能在未进入拖拽时**完全不干扰**滚动。
     */
    public static final class DragScrollView extends ScrollView {
        private PetDragListLayout list;

        public DragScrollView(Context context) {
            super(context);
        }

        public void attach(PetDragListLayout l) { this.list = l; }

        @Override public boolean dispatchTouchEvent(MotionEvent ev) {
            if (list != null && list.onDragTouch(ev)) return true;
            return super.dispatchTouchEvent(ev);
        }
    }
}