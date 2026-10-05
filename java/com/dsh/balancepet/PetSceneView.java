package com.dsh.balancepet;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/**
 * 单窗口模式：整个桌宠只用一个悬浮窗，角色 / 平板文字 / 飘字 / 绿环一次画完。
 * 代价是「透明区域也会吃掉点击」，换来的是 1 个 Surface 的最低开销。
 */
public final class PetSceneView extends View {

    private final PetRenderer renderer;

    public PetSceneView(Context context, PetRenderer renderer) {
        super(context);
        this.renderer = renderer;
        setFilterTouchesWhenObscured(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        renderer.drawScene(canvas);
    }
}