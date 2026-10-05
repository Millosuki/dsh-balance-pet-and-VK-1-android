package com.dsh.balancepet;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * 极简「自动换行」容器（手写，无 Gradle / 不引 androidx —— 本工程没有 RecyclerView / FlexboxLayout 可用）。
 *
 * <p>用途：编辑器的**调色板**（芯片排满一行自动换到下一行）。
 *
 * <p>实现范围内的保证：子视图按加入顺序从左到右铺开，放不下就换行；换行后的行高按该行最高子视图；
 * 超出宽度不会裁剪（会换行）。**不做**的东西：margin（用子视图自己的 padding / 容器 gap）、
 * gravity、RTL、baseline 对齐 —— 编辑器不需要，写了也没人验证。
 */
public final class FlowLayout extends ViewGroup {

    private int gapPx;

    public FlowLayout(Context context) {
        super(context);
    }

    public FlowLayout(Context context, int gapPx) {
        super(context);
        this.gapPx = Math.max(0, gapPx);
    }

    public void setGapPx(int px) {
        gapPx = Math.max(0, px);
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        // v1.12.4：**容器自己的 padding 必须算进来**。
                // 以前这里完全忽略 padding（既不减左右、也不加顶/底），于是
                // candidateBar.setPadding(0, dp(6), 0, 0) 形同虚设 —— 候选芯片紧贴上一排目标芯片，
                // 看起来就是“候选 1 的框被上面横切”。
                int padL = getPaddingLeft();
                int padR = getPaddingRight();
                int padT = getPaddingTop();
                int padB = getPaddingBottom();
                int size = MeasureSpec.getSize(widthSpec);
                if (size <= 0) size = Integer.MAX_VALUE;
                int avail = size == Integer.MAX_VALUE ? size : Math.max(1, size - padL - padR);

        int usedW = 0, rowH = 0, totalH = 0, maxW = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (usedW > 0 && usedW + gapPx + cw > avail) {
                maxW = Math.max(maxW, usedW);
                totalH += rowH + gapPx;
                usedW = 0;
                rowH = 0;
            }
            usedW += (usedW > 0 ? gapPx : 0) + cw;
            rowH = Math.max(rowH, ch);
        }
        maxW = Math.max(maxW, usedW);
        totalH += rowH;

        int contentW = maxW + padL + padR;
                int contentH = totalH + padT + padB;
                int w = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY
                        ? size : resolveSize(contentW, widthSpec);
                setMeasuredDimension(w, resolveSize(contentH, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // v1.12.4：与 onMeasure 对齐 —— 起点从 padding 内边缘开始（以前直接从 (0,0) 摆）
            int padL = getPaddingLeft();
            int width = Math.max(1, (r - l) - padL - getPaddingRight());
        int x = 0, y = getPaddingTop(), rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (x > 0 && x + gapPx + cw > width) {
                x = 0;
                y += rowH + gapPx;
                rowH = 0;
            } else if (x > 0) {
                x += gapPx;
            }
            child.layout(padL + x, y, padL + x + cw, y + ch);
            x += cw;
            rowH = Math.max(rowH, ch);
        }
    }
}
