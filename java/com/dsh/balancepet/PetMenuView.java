package com.dsh.balancepet;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮菜单，复刻原版右键菜单（含二级菜单）。
 *
 * Android 没有右键，改为「长按桌宠」呼出。实现为一层全屏透明窗口：
 * 点击卡片以外的任何地方立即关闭；子菜单在原卡片位置就地替换（带「‹ 返回」）。
 * 配色遵循原生系统设置风格：克制蓝灰调、圆角、细分隔线。
 */
public final class PetMenuView extends FrameLayout {

    /** 一个菜单项，可带子菜单。 */
    public static final class Item {
        public final String title;
        public boolean checked;
        public boolean separator;
        public boolean header;
        public boolean enabled = true;
        public Runnable action;
        public List<Item> children;

        public Item(String title) { this.title = title; }

        public static Item action(String title, Runnable action) {
            Item i = new Item(title);
            i.action = action;
            return i;
        }

        public static Item check(String title, boolean checked, Runnable action) {
            Item i = action(title, action);
            i.checked = checked;
            return i;
        }

        public static Item submenu(String title, List<Item> children) {
            Item i = new Item(title);
            i.children = children;
            return i;
        }

        public static Item separator() {
            Item i = new Item("");
            i.separator = true;
            return i;
        }

        public static Item header(String title) {
            Item i = new Item(title);
            i.header = true;
            return i;
        }
    }

    private final Context context;
    private final Runnable onDismiss;
    private final boolean night;
    private final List<List<Item>> stack = new ArrayList<>();
    private int anchorX = 0;
    private int anchorY = 0;
    private boolean hasAnchor = false;

    public PetMenuView(Context ctx, List<Item> root, Runnable onDismiss) {
        super(ctx);
        this.context = ctx;
        this.onDismiss = onDismiss;
        this.night = (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setBackgroundColor(Color.TRANSPARENT);
        stack.add(root);
        rebuild();
    }

    public void dismiss() {
        if (onDismiss != null) onDismiss.run();
    }

    private void push(List<Item> items) {
        stack.add(items);
        rebuild();
    }

    private void pop() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            rebuild();
        } else {
            dismiss();
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                context.getResources().getDisplayMetrics()));
    }

    private void rebuild() {
        removeAllViews();
        List<Item> items = stack.get(stack.size() - 1);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedCard());
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setMinimumWidth(dp(212));

        if (stack.size() > 1) {
            card.addView(row("‹ 返回", false, true, new Runnable() {
                @Override public void run() { pop(); }
            }));
            card.addView(divider());
        }

        for (final Item item : items) {
            if (item.separator) {
                card.addView(divider());
                continue;
            }
            if (item.header) {
                card.addView(row(item.title, false, false, null));
                continue;
            }
            card.addView(row(label(item), item.checked, item.enabled, new Runnable() {
                @Override public void run() {
                    if (item.children != null && !item.children.isEmpty()) {
                        push(item.children);
                    } else if (item.action != null) {
                        dismiss();
                        item.action.run();
                    }
                }
            }));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 全屏层本身负责「点空白关闭」
        setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) dismiss();
                return true;
            }
        });
    }

    private String label(Item item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.checked ? "✓ " : "　");
        sb.append(item.title);
        if (item.children != null && !item.children.isEmpty()) sb.append("   ›");
        return sb.toString();
    }

    private TextView row(String text, boolean checked, boolean enabled, final Runnable action) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setPadding(dp(14), dp(11), dp(14), dp(11));
        tv.setBackground(hitBackground());
        if (!enabled) {
            tv.setTextColor(night ? Color.rgb(120, 124, 128) : Color.rgb(168, 174, 180));
        } else if (checked) {
            tv.setTextColor(night ? Color.rgb(150, 180, 210) : Color.rgb(74, 107, 138));
        } else if (action == null) {
            tv.setTextColor(night ? Color.rgb(150, 155, 160) : Color.rgb(122, 130, 138));
        } else {
            tv.setTextColor(night ? Color.rgb(230, 232, 234) : Color.rgb(26, 28, 30));
        }
        if (action != null && enabled) {
            tv.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { action.run(); }
            });
        }
        return tv;
    }

    private View divider() {
        View v = new View(context);
        v.setBackgroundColor(night ? Color.rgb(58, 62, 66) : Color.rgb(233, 235, 238));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
        lp.setMargins(dp(10), dp(3), dp(10), dp(3));
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable roundedCard() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(14));
        d.setColor(night ? Color.rgb(38, 41, 45) : Color.WHITE);
        d.setStroke(Math.max(1, dp(0.7f)), night ? Color.rgb(62, 66, 70) : Color.rgb(219, 222, 226));
        return d;
    }

    private GradientDrawable hitBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(10));
        d.setColor(Color.TRANSPARENT);
        return d;
    }

    /** 菜单左上角的目标位置（全屏窗口坐标）。 */
    public void setAnchor(int x, int y) {
        anchorX = x;
        anchorY = y;
        hasAnchor = true;
        requestLayout();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            int x = hasAnchor ? anchorX : dp(8);
            int y = hasAnchor ? anchorY : dp(8);
            x = Math.max(dp(8), Math.min(x, Math.max(dp(8), getWidth() - w - dp(8))));
            y = Math.max(dp(8), Math.min(y, Math.max(dp(8), getHeight() - h - dp(8))));
            child.layout(x, y, x + w, y + h);
        }
    }
}