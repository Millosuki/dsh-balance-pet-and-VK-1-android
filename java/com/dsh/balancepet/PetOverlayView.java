package com.dsh.balancepet;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/**
 * 轮廓模式下的「飘字 + 充值绿环」独立层。
 *
 * 窗口几何固定为整个桌宠矩形、只在需要时创建/销毁，
 * 因此不会每帧调用 updateViewLayout（这是之前掉帧的另一个来源）。
 * 存在的这段时间内，桌宠矩形内的点击会由本层接管；空闲时本层不存在，上方区域不挡点击。
 */
public final class PetOverlayView extends View {

    private final PetRenderer renderer;

    public PetOverlayView(Context context, PetRenderer renderer) {
        super(context);
        this.renderer = renderer;
        setFilterTouchesWhenObscured(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        renderer.drawTopupRing(canvas);
        renderer.drawFloats(canvas);
    }
}