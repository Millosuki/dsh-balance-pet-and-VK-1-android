package com.dsh.balancepet;

import android.graphics.Matrix;
import android.graphics.RectF;

/**
 * 四张 1536 × 1024 立绘的几何。尺寸档位含义是「身体高度」，
 * 所以加上鲸尾横向扩展后不会把脸和余额缩小。
 *
 * 移植自 dsh-balance-pet-macos/Sources/PetLayout.swift。
 * 差异：AppKit 原点在左下、y 向上；Android 原点在左上、y 向下，
 * 因此本类的 y 全部向下，平板坐标已按 y 向下重写（见 tabletMatrix）。
 */
public final class PetLayout {
    public static final float ART_W = 1536f;
    public static final float ART_H = 1024f;
    public static final float ASPECT = ART_W / ART_H;   // 1.5，窗口宽度 = 1.5 × side
    public static final float FLOAT_BAND = 0.55f;       // 上方飘字带
    public static final float PANEL_W = 400f;           // 平板逻辑面板
    public static final float PANEL_H = 220f;

    private PetLayout() {}

    /**
     * 尺寸档位（单位 dp，含义是「身体高度」）。
     * 原版 macOS 用 110/150/210/280 pt，但 Android 屏幕窄、窗口宽 = 1.5 × side，
     * 直接照搬会超出屏宽，因此等比缩小为 80/110/150/200 dp（最大宽度 300dp < 360dp）。
     */
    public static final float[] SIZE_PRESETS_DP = {80f, 110f, 150f, 200f};
    public static final String[] SIZE_PRESET_NAMES = {"小", "中", "大", "特大"};
    /** 自定义尺寸范围（dp）。上限还会被屏宽进一步收紧。 */
    public static final float MIN_SIDE_DP = 40f;
    public static final float MAX_SIDE_DP = 320f;

    /**
     * 自定义尺寸钳制：既受 MIN/MAX 限制，也不能宽过屏幕
     * （窗口宽度 = 1.5 × side，留 8dp 余量）。
     */
    public static float clampSideDp(float sideDp, float screenWidthPx, float density) {
        float maxByScreen = (screenWidthPx / Math.max(1f, density)) / ASPECT - 8f;
        float max = Math.min(MAX_SIDE_DP, Math.max(MIN_SIDE_DP, maxByScreen));
        if (Float.isNaN(sideDp)) sideDp = SIZE_PRESETS_DP[1];
        return Math.min(Math.max(sideDp, MIN_SIDE_DP), max);
    }

    /** 窗口尺寸：宽 = side × 1.5，高 = side × 1.55。 */
    public static float windowWidth(float side) { return side * ASPECT; }

    public static float windowHeight(float side) { return side * (1f + FLOAT_BAND); }

    /** 由窗口宽度反推 side。 */
    public static float bodyHeight(float boundsWidth) { return boundsWidth / ASPECT; }

    /**
     * 立绘绘制矩形（窗口坐标系，y 向下）。
     * 原版：size = side × 0.94，水平居中，底边距窗口底部 side × 0.03。
     */
    public static RectF spriteRect(float windowW, float windowH) {
        float side = bodyHeight(windowW);
        float w = side * 0.94f * ASPECT;
        float h = side * 0.94f;
        float left = (windowW - w) / 2f;
        float bottomPad = side * 0.03f;
        float top = windowH - bottomPad - h;
        return new RectF(left, top, left + w, top + h);
    }

    /**
     * 把平板逻辑面板 (u ∈ [0,400], w ∈ [0,220]，左上为原点) 映射到屏幕。
     * 推导自 Swift 版 tabletTransform：图像坐标 → 视图坐标。
     * 结果是一个平行四边形（与三个测点一致）。
     */
    public static Matrix tabletMatrix(float windowW, float windowH, float[] corners) {
            RectF rect = spriteRect(windowW, windowH);
            if (corners == null || corners.length < 6) return new Matrix();   // 纯形象角色没有平板
            float scale = rect.width() / ART_W;
            float[] c = corners;
        float tlx = c[0], tly = c[1], trx = c[2], try_ = c[3], blx = c[4], bly = c[5];

        Matrix m = new Matrix();
        m.setValues(new float[]{
                scale * (trx - tlx) / PANEL_W,   // u → x
                -scale * (tlx - blx) / PANEL_H,  // w → x
                rect.left + scale * tlx,
                scale * (try_ - tly) / PANEL_W,  // u → y
                scale * (bly - tly) / PANEL_H,   // w → y
                rect.top + scale * tly,
                0f, 0f, 1f
        });
        return m;
    }

    /** 面板内各元素的布局常量（面板坐标，y 向下）。 */
    public static final float LABEL_CENTER_U = PANEL_W * 0.5f;
    public static final float LABEL_CENTER_W = PANEL_H * 0.20f;   // 原版 0.80（自下往上）
    public static final float AMOUNT_CENTER_U = PANEL_W * 0.5f;
    public static final float AMOUNT_CENTER_W = PANEL_H * 0.68f;  // 原版 0.32（自下往上）
    public static final float DOT_U = PANEL_W * 0.92f;
    public static final float DOT_W = PANEL_H * 0.23f;            // 原版 0.77
    public static final float DOT_SIZE = 15f;
    public static final float LABEL_TEXT = PANEL_H * 0.21f;
    public static final float AMOUNT_TEXT = PANEL_H * 0.48f;
    public static final float CURRENCY_TEXT = PANEL_H * 0.27f;
}