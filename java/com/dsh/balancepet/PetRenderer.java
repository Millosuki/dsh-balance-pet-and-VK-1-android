package com.dsh.balancepet;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.animation.PathInterpolator;

import java.util.HashMap;
import java.util.Map;

/**
 * 立绘/平板/飘字的统一渲染器。单窗口模式与轮廓多窗口模式共用同一份绘制代码，
 * 避免两套实现各自漂移。
 *
 * 性能要点（对应之前 12 窗口卡顿的根因）：
 *  1. 预缩放：立绘只在「角色 / 尺寸」变化时缩放一次，之后每帧是 1:1 直贴，不再每帧
 *     对 1536×1024 全图做滤波缩放；
 *  2. 预渲染红色受击层：叠色用一张预先算好的位图 + 直接 alpha，省掉每帧的 SRC_ATOP 填充；
 *  3. 预渲染平板文字层：余额数值/状态不变时复用同一张 400×220 小位图，
 *     不再每帧、每个窗口重复 measureText + getFontMetrics；
 *  4. 全部 RectF/Paint 复用，绘制路径上不分配对象（避免 GC 抖动）。
 */
public final class PetRenderer {

    private final PetModel model;
    /** v1.13.0：渲染器只需要「有没有平板 + 平板三坐标」——角色是什么由服务层解析。 */
        private boolean hasTablet;
        private float[] tabletCorners;
    private PetAssets.Artwork art;
    private final float windowW;
    private final float windowH;
    private final float side;
    private final RectF spriteRect;
    private final Matrix tabletMatrix;

    // 预缩放位图
    private Bitmap baseBitmap;
    private Bitmap tintBitmap;
    private Bitmap textBitmap;
    private int baseW, baseH;

    // 文字层缓存键
    private String textKey;

    private final Paint blitPaint = new Paint();               // 1:1 直贴，无需滤波
    private final Paint tintAlphaPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint missingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Rect srcRect = new Rect();
    private final RectF tmpRect = new RectF();
    private final Map<String, Float> textWidthCache = new HashMap<>();

    // 每帧复用的抖动位移
    public float shakeX, shakeY;

    // ---- 按压形变（WhaleWidget：SQUISH = scaleY(.88) scaleX(1.05)，底部中心为轴，.22s 弹性曲线） ----
    /** 形变程度 0…1（0=原始，1=完全按压）。 */
    private float pressAmount = 0f;
    private float pressFrom = 0f;
    private float pressTarget = 0f;
    private long pressStartMs = 0;
    private boolean pressAnimating = false;
    private static final long PRESS_MS = 220;
    private static final float PRESS_SCALE_X = 1.05f;   // scaleX(1.05)
    private static final float PRESS_SCALE_Y = 0.88f;   // scaleY(0.88)
    /** cubic-bezier(.34,1.56,.64,1)：带回弹 */
    private final PathInterpolator pressEase = new PathInterpolator(0.34f, 1.56f, 0.64f, 1f);

    /** 飘字位图缓存：文字+颜色 → 已含阴影的位图（阴影模糊是 CPU 大头，缓存后每帧只做一次 blit）。 */
    private final Map<String, FloatSprite> floatBitmapCache = new HashMap<>();
    private static final int FLOAT_CACHE_MAX = 64;
    private final Paint floatBitmapPaint = new Paint();

    public PetRenderer(PetModel model, PetAssets.Artwork art,
                           float windowW, float windowH, boolean hasTablet, float[] tabletCorners) {
            this.model = model;
            this.hasTablet = hasTablet;
            this.tabletCorners = tabletCorners;
        this.art = art;
        this.windowW = windowW;
        this.windowH = windowH;
        this.side = PetLayout.bodyHeight(windowW);
        this.spriteRect = PetLayout.spriteRect(windowW, windowH);
        // v1.13.0：纯形象角色（Whale / 自定义）没有平板，不建矩阵也不画余额层
                this.tabletMatrix = hasTablet
                        ? PetLayout.tabletMatrix(windowW, windowH, tabletCorners) : new Matrix();

        tintAlphaPaint.setAlpha(0);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.rgb(52, 199, 89));
        missingPaint.setColor(Color.rgb(242, 243, 245));

        prepare();
    }

    public boolean matches(PetAssets.Artwork art, float windowW, float windowH, boolean hasTablet) {
            return this.art == art && this.hasTablet == hasTablet
                && Math.abs(this.windowW - windowW) < 0.5f
                && Math.abs(this.windowH - windowH) < 0.5f;
    }

    public void setTablet(boolean hasTablet, float[] corners) {
            if (this.hasTablet == hasTablet) return;
            this.hasTablet = hasTablet;
            this.tabletCorners = corners;
            rebuildTabletMatrix();
            textKey = null;
        }

    public void setArtwork(PetAssets.Artwork art) {
        if (this.art == art) return;
        this.art = art;
        prepare();
    }

    public RectF spriteRect() { return spriteRect; }

    private void rebuildTabletMatrix() {
            if (!hasTablet) {
                tabletMatrix.reset();
                return;
            }
            Matrix m = PetLayout.tabletMatrix(windowW, windowH, tabletCorners);
            tabletMatrix.set(m);
    }

    /** 建立/重建预缩放位图。只在角色或尺寸变化时调用。 */
    private void prepare() {
        releaseScaled();
        if (art == null || art.bitmap == null || art.bitmap.isRecycled()) return;
        baseW = Math.max(1, Math.round(spriteRect.width()));
        baseH = Math.max(1, Math.round(spriteRect.height()));
        try {
            baseBitmap = Bitmap.createBitmap(baseW, baseH, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            Log.write("预缩放位图分配失败: " + e);
            baseBitmap = null;
            return;
        }
        Canvas canvas = new Canvas(baseBitmap);
        Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        srcRect.set(0, 0, art.width, art.height);
        tmpRect.set(0, 0, baseW, baseH);
        canvas.drawBitmap(art.bitmap, srcRect, tmpRect, scalePaint);

        // 红色受击层：预先算好，运行时只改 alpha
        tintBitmap = Bitmap.createBitmap(baseW, baseH, Bitmap.Config.ARGB_8888);
        Canvas tintCanvas = new Canvas(tintBitmap);
        tintCanvas.drawBitmap(baseBitmap, 0, 0, null);
        Paint tintPaint = new Paint();
        tintPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        tintPaint.setColor(Color.argb(115, 255, 26, 36));   // 0.45 峰值
        tintCanvas.drawRect(0, 0, baseW, baseH, tintPaint);

        textKey = null;
        Log.write(String.format(java.util.Locale.US,
                "预缩放立绘 %dx%d（源 %dx%d）", baseW, baseH, art.width, art.height));
    }

    private void releaseScaled() {
        if (baseBitmap != null && !baseBitmap.isRecycled()) baseBitmap.recycle();
        if (tintBitmap != null && !tintBitmap.isRecycled()) tintBitmap.recycle();
        baseBitmap = null;
        tintBitmap = null;
    }

    public void release() {
        releaseScaled();
        if (textBitmap != null && !textBitmap.isRecycled()) textBitmap.recycle();
        textBitmap = null;
        textWidthCache.clear();
        releaseFloatBitmaps();
    }

    private void releaseFloatBitmaps() {
        for (FloatSprite sprite : floatBitmapCache.values()) {
            if (sprite != null && sprite.bitmap != null && !sprite.bitmap.isRecycled()) {
                sprite.bitmap.recycle();
            }
        }
        floatBitmapCache.clear();
    }

    /** 文本层是否已过期（Service 据此决定要不要让内容层重绘）。 */
    public boolean textLayerOutdated() {
        return !buildTextKey().equals(textKey);
    }

    private String buildTextKey() {
        return model.displayString() + "|" + model.isConnected() + "|"
                + (model.lastError() == null ? "0" : "1");
    }

    /** 飘字精灵（含阴影），按 文字+颜色 缓存。 */
    private FloatSprite floatSprite(PetModel.FloatLabel label) {
        String key = label.color + "|" + label.text;
        FloatSprite cached = floatBitmapCache.get(key);
        if (cached != null && !cached.bitmap.isRecycled()) return cached;
        if (floatBitmapCache.size() > FLOAT_CACHE_MAX) releaseFloatBitmaps();

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        paint.setTextSize(side * PetTuning.FLOAT_FONT_RATIO);
        paint.setColor(label.color);
        float shadow = side * PetTuning.FLOAT_SHADOW_RATIO;
        paint.setShadowLayer(shadow, 0, 0, Color.argb(102, 0, 0, 0));   // 0.4 * 255
        Paint.FontMetrics fm = paint.getFontMetrics();
        int pad = (int) Math.ceil(shadow * 2f) + 2;
        float width = paint.measureText(label.text);
        int w = Math.max(1, (int) Math.ceil(width) + pad * 2);
        int h = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent) + pad * 2);
        Bitmap bm;
        try {
            bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
        Canvas canvas = new Canvas(bm);
        canvas.drawText(label.text, pad, pad - fm.ascent, paint);
        FloatSprite sprite = new FloatSprite(bm, pad, fm.ascent, width);
        floatBitmapCache.put(key, sprite);
        return sprite;
    }

    /** 飘字精灵：预渲染好的小位图 + 阴影留白 + 字体基准线。 */
    private static final class FloatSprite {
        final Bitmap bitmap;
        final int pad;
        final float ascent;
        final float width;

        FloatSprite(Bitmap bitmap, int pad, float ascent, float width) {
            this.bitmap = bitmap;
            this.pad = pad;
            this.ascent = ascent;
            this.width = width;
        }
    }

    /** 由模型状态算出本帧的抖动位移（多层脉冲叠加 + 硬上限）。 */
    public void updateShake() {
        updatePress();
        if (!model.hasShake()) {
            shakeX = 0;
            shakeY = 0;
            return;
        }
        // 单层振幅与原版一致：min(3.2dp, side × 0.025)，叠加后总位移再取硬上限
        float amplitude = Math.min(dp(3.2f), side * 0.025f);
        float cap = Math.min(dp(5f), side * 0.04f);
        float[] offset = model.shakeOffset(cap);
        shakeX = offset[0] * (amplitude / Math.max(0.001f, amplitude));
        shakeY = offset[1] * (amplitude / Math.max(0.001f, amplitude));
        // 叠加时允许超出单层振幅，但不超过硬上限
        if (Math.abs(shakeX) > cap) shakeX = Math.signum(shakeX) * cap;
        if (Math.abs(shakeY) > cap) shakeY = Math.signum(shakeY) * cap;
    }

    /**
     * 按压形变：按下压扁+横向扩张，松开回到原始，用带回弹的 cubic-bezier(.34,1.56,.64,1)。
     * 与 WhaleWidget 的 pressDown/pressUp 一致（body.style.transform = SQUISH / scaleY(1) scaleX(1)）。
     */
    public void setPress(boolean down) {
        float target = down ? 1f : 0f;
        if (target == pressTarget && !pressAnimating) return;
        pressFrom = pressAmount;
        pressTarget = target;
        pressStartMs = System.currentTimeMillis();
        pressAnimating = true;
    }

    public boolean pressAnimating() { return pressAnimating; }

    public float pressAmount() { return pressAmount; }

    /**
     * 推进按压形变。
     *
     * 必须由「帧循环」每帧调用一次（PetService 的主循环），不能只依赖绘制期调用：
     * 形变是绘制期状态，而本工程的重绘是「按需」的（只在抖动/飘字等动画期间 invalidate），
     * 若只在 drawScene 里推进，就会出现「按下→松开这 220ms 内没有任何一帧被画出来」的自锁，
     * 表现就是「单击看不到挤压、双击才看得到」（双击时扣费抖动恰好让循环持续重绘）。
     */
    public void updatePress() {
        if (!pressAnimating) return;
        long elapsed = System.currentTimeMillis() - pressStartMs;
        float t = elapsed >= PRESS_MS ? 1f : pressEase.getInterpolation(elapsed / (float) PRESS_MS);
        pressAmount = pressFrom + (pressTarget - pressFrom) * t;
        if (elapsed >= PRESS_MS) {
            pressAmount = pressTarget;
            pressAnimating = false;
        }
    }

    /** 当前形变缩放（相对 1.0）。 */
    public float pressScaleX() { return 1f + (PRESS_SCALE_X - 1f) * pressAmount; }

    public float pressScaleY() { return 1f + (PRESS_SCALE_Y - 1f) * pressAmount; }

    private float dp(float value) {
        return value * PetPaths.context().getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------ 绘制

    /**
     * 绘制角色本体 + 平板文字（不含飘字/绿环）。
     * 调用方负责按需平移（分带窗口用 -band.origin，单窗口模式用抖动位移）。
     */
    public void drawCharacter(Canvas canvas) {
        if (baseBitmap == null || baseBitmap.isRecycled()) {
            canvas.drawRoundRect(spriteRect, dp(12), dp(12), missingPaint);
            return;
        }
        // 按压形变：以立绘底部中心为轴缩放（对应 CSS transform-origin:50% 100%）
        // 说明：形变在绘制期应用，因此分带模式下由 Service 临时外扩窗口来避免被裁切。
        boolean squashed = pressAmount > 0.0001f;
        int savedLayer = squashed ? canvas.save() : -1;
        if (squashed) {
            canvas.scale(pressScaleX(), pressScaleY(), spriteRect.centerX(), spriteRect.bottom);
        }
        canvas.drawBitmap(baseBitmap, spriteRect.left, spriteRect.top, blitPaint);
        double impact = model.impact();
        if (impact > 0 && tintBitmap != null && !tintBitmap.isRecycled()) {
            tintAlphaPaint.setAlpha((int) Math.round(115 * impact));
            canvas.drawBitmap(tintBitmap, spriteRect.left, spriteRect.top, tintAlphaPaint);
        }
        if (!art.offlineArt && hasTablet) drawTabletLayer(canvas);   // 纯形象角色不画余额
        if (savedLayer >= 0) canvas.restoreToCount(savedLayer);
    }

    /** 把预渲染好的平板文字层贴到倾斜屏幕上（一次 drawBitmap，无文字排版）。 */
    private void drawTabletLayer(Canvas canvas) {
        ensureTextLayer();
        if (textBitmap == null) return;
        canvas.save();
        canvas.concat(tabletMatrix);
        canvas.drawBitmap(textBitmap, 0f, 0f, blitPaint);
        canvas.restore();
    }

    /** 余额数字 / 连接状态变化时才重画文字层。 */
    private void ensureTextLayer() {
        String key = model.displayString() + "|" + model.isConnected() + "|"
                + (model.lastError() == null ? "0" : "1");
        if (key.equals(textKey) && textBitmap != null && !textBitmap.isRecycled()) return;
        if (textBitmap == null || textBitmap.isRecycled()) {
            textBitmap = Bitmap.createBitmap((int) PetLayout.PANEL_W, (int) PetLayout.PANEL_H,
                    Bitmap.Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(textBitmap);
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        canvas.clipRect(0f, 0f, PetLayout.PANEL_W, PetLayout.PANEL_H);

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        labelPaint.setColor(Color.rgb(158, 184, 227));
        labelPaint.setTextSize(PetLayout.LABEL_TEXT);
        labelPaint.setShadowLayer(1.5f, 1.5f, 1.5f, 0xA6000000);
        drawCentered(canvas, labelPaint, "DSH 余额",
                PetLayout.LABEL_CENTER_U, PetLayout.LABEL_CENTER_W);

        Paint currencyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        currencyPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        currencyPaint.setColor(Color.rgb(158, 184, 227));
        currencyPaint.setTextSize(PetLayout.CURRENCY_TEXT);
        currencyPaint.setShadowLayer(1.5f, 1.5f, 1.5f, 0xA6000000);

        Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        numberPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        numberPaint.setTextSize(PetLayout.AMOUNT_TEXT);
        numberPaint.setShadowLayer(1.5f, 1.5f, 1.5f, 0xA6000000);
        numberPaint.setColor(model.isConnected()
                ? Color.rgb(240, 247, 255) : Color.rgb(173, 186, 207));

        String amount = model.displayString();
        float currencyWidth = currencyPaint.measureText("¥ ");
        float numberWidth = numberPaint.measureText(amount);
        float factor = Math.min(1f, PetLayout.PANEL_W * 0.90f / Math.max(1f, currencyWidth + numberWidth));
        if (factor < 1f) {
            currencyPaint.setTextSize(PetLayout.CURRENCY_TEXT * factor);
            numberPaint.setTextSize(PetLayout.AMOUNT_TEXT * factor);
            currencyWidth = currencyPaint.measureText("¥ ");
            numberWidth = numberPaint.measureText(amount);
        }
        Paint.FontMetrics nfm = numberPaint.getFontMetrics();
        float nBaseline = PetLayout.AMOUNT_CENTER_W + (nfm.descent - nfm.ascent) / 2f - nfm.descent;
        float x0 = (PetLayout.PANEL_W - (currencyWidth + numberWidth)) / 2f;
        Paint.FontMetrics cfm = currencyPaint.getFontMetrics();
        float cBaseline = PetLayout.AMOUNT_CENTER_W + (cfm.descent - cfm.ascent) / 2f - cfm.descent;
        canvas.drawText("¥ ", x0, cBaseline, currencyPaint);
        canvas.drawText(amount, x0 + currencyWidth, nBaseline, numberPaint);

        dotPaint.setColor(model.isConnected() ? Color.rgb(52, 199, 89)
                : (model.lastError() == null ? Color.rgb(255, 204, 0) : Color.rgb(255, 59, 48)));
        float dotTop = PetLayout.DOT_W - PetLayout.DOT_SIZE;
        tmpRect.set(PetLayout.DOT_U, dotTop, PetLayout.DOT_U + PetLayout.DOT_SIZE,
                dotTop + PetLayout.DOT_SIZE);
        canvas.drawOval(tmpRect, dotPaint);

        textKey = key;
    }

    private void drawCentered(Canvas canvas, Paint paint, String text, float centerU, float centerW) {
        float width = paint.measureText(text);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = centerW + (fm.descent - fm.ascent) / 2f - fm.descent;
        canvas.drawText(text, centerU - width / 2f, baseline, paint);
    }

    /** 飘字（不随抖动平移，与原版一致）。改为缓存位图 blit：不再每帧做文字排版 + 阴影模糊。 */
    public void drawFloats(Canvas canvas) {
        if (model.floating.isEmpty()) return;
        float start = side;
        float top = windowH - side * PetTuning.FLOAT_TOP_GAP;
        for (PetModel.FloatLabel label : model.floating) {
            float progress = (float) Math.min(1, label.age / model.floatLifetime);
            int alpha = (int) Math.round(255 * Math.max(0, 1 - Math.pow(progress, PetTuning.FLOAT_ALPHA_POW)));
            if (alpha <= 0) continue;
            FloatSprite sprite = floatSprite(label);
            if (sprite == null) continue;
            float x = Math.min(Math.max(0, windowW - side + side * label.x - sprite.width / 2f),
                    Math.max(0, windowW - sprite.width));
            float bottom = start + (top - start) * progress;
            floatBitmapPaint.setAlpha(alpha);
            canvas.drawBitmap(sprite.bitmap, x - sprite.pad, bottom - sprite.pad, floatBitmapPaint);
        }
    }

    /** 充值绿环。 */
    public void drawTopupRing(Canvas canvas) {
        if (model.topupTime() <= 0) return;
        float progress = (float) (1 - model.topupTime() / 0.9);
        float inset = side * (0.03f + 0.06f * (1 - progress));
        tmpRect.set(spriteRect);
        tmpRect.inset(inset, inset);
        ringPaint.setStrokeWidth(side * 0.015f);
        ringPaint.setAlpha((int) Math.round(255 * 0.8 * (1 - progress)));
        canvas.drawOval(tmpRect, ringPaint);
    }

    /** 单窗口模式：一次画完角色 + 平板 + 飘字 + 绿环。 */
    public void drawScene(Canvas canvas) {
        updateShake();
        canvas.save();
        canvas.translate(shakeX, shakeY);
        drawCharacter(canvas);
        canvas.restore();
        drawTopupRing(canvas);
        drawFloats(canvas);
    }

    /** 飘字宽度带缓存（就 "−0.01" 和 "+x.xx" 几种）。 */
    private float measureFloatWidth(String text) {
        Float cached = textWidthCache.get(text);
        if (cached != null) return cached;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        paint.setTextSize(side * 0.080f);
        float width = paint.measureText(text);
        textWidthCache.put(text, width);
        return width;
    }

    public static float measureTextWidth(float sidePx, String text) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        paint.setTextSize(sidePx * 0.080f);
        return paint.measureText(text);
    }
}