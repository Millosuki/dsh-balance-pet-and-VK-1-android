package com.dsh.balancepet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

/**
 * 轮廓模式的一块窗口：只负责把「属于自己矩形的那部分画面」画出来。
 * 所有分带窗口画的是同一份渲染器内容，只是各自反向平移了 -band.origin，
 * 因此拼起来与整图绘制像素一致（平板文字可跨带无缝）。
 */
public final class PetBandView extends View {

    private final PetRenderer renderer;
    private final float left;
    private final float top;

    public PetBandView(Context context, PetRenderer renderer, RectF band) {
        super(context);
        this.renderer = renderer;
        this.left = band.left;
        this.top = band.top;
        // 安全：其它悬浮窗叠在上面时，拒绝把点击"转交"给我们（防点击劫持）
        setFilterTouchesWhenObscured(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        renderer.updateShake();
        canvas.save();
        // 窗口左上角即 band 原点：反向平移后画面与原图对齐（含抖动位移）
        canvas.translate(renderer.shakeX - left, renderer.shakeY - top);
        renderer.drawCharacter(canvas);
        canvas.restore();
    }
}