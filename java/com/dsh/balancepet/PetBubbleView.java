package com.dsh.balancepet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * WhaleWidget 泡泡的 Android 复刻。
 *
 * 形状与动画全部照抄其源码（出处见 {@link WhaleBubbleSpec} / {@link PetBubbleStyle}）：
 *   出场：三个形状各自 opacity .7→1 且 scale .7→1、.2s ease，
 *         延迟 b2=0 / b1=130ms / bshape=260ms；文字 .36s 后 .16s 淡入
 *   退场：三个形状各自 opacity→0 且 scale→.7、.2s ease，
 *         延迟 bshape=100ms / b1=200ms / b2=300ms；文字立即 .16s 淡出
 *   绘制层序：与 SVG 一致 —— 主体 path → b1 → b2（b2 在最上）
 */
public final class PetBubbleView extends View {

    private final PetBubble bubble;
    private final PetBubbleStyle style;
    private final Runnable onTap;

    private final Path shapePath = SvgPath.parse(WhaleBubbleSpec.SHAPE_PATH);
    private final RectF shapeBounds = new RectF();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintAmount = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 峰谷行（v1.5.0）：颜色由 {@link PetBubble#peakColor} 决定，每秒可能被原地改写。 */
    private final Paint paintPeak = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- 模块渲染（v1.5.2） ----
    /** 模块文字（逐个模块设置字体/字号/颜色后绘制）。 */
    private final Paint paintModule = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 模块底色块。 */
    private final Paint paintModuleBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF moduleBgRect = new RectF();
    /** 模块间的水平间距（viewBox 单位）。**本工程自定**（上游是 CSS gap）。 */
    private static final float MODULE_GAP_U = 18f;
    /** 行间距（viewBox 单位），同样是本工程自定。 */
    private static final float MODULE_ROW_GAP_U = 10f;
    /** 底色块内边距（viewBox 单位）。 */
    private static final float MODULE_BG_PAD_U = 12f;
    /** 一行超宽时允许缩小的下限（上游会溢出，我们选择整行缩小）。 */
    private static final float MIN_ROW_SCALE = 0.5f;
    private static final java.util.HashMap<String, Typeface> TYPEFACE_CACHE = new java.util.HashMap<>();
    private final PathInterpolator ease = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);

    /** 退场时序（毫秒，取自关闭态 CSS 的 transition-delay）。 */
    private static final long CLOSE_TEXT_MS = 160;
    private static final long CLOSE_SHAPE_DELAY_MS = 100;
    private static final long CLOSE_TAIL1_DELAY_MS = 200;
    private static final long CLOSE_TAIL2_DELAY_MS = 300;
    private static final long CLOSE_SHAPE_MS = 200;

    private long shownAtMs = System.currentTimeMillis();
    private long closeAtMs = -1;
    private boolean flipped = false;
    /**
     * 静态模式（v1.6.0 编辑器预览专用）：不播入场动画、不淡出、忽略点击，永远画「完全展开」的样子。
     *
     * <p>为什么要它：编辑器里的预览必须**所见即所得**，而入场动画的头 0.36 秒文字是透明的、
     * 形状还在缩放 —— 给用户看一个「刚开始弹」的半成品没有意义。
     */
    private boolean staticMode = false;

    public PetBubbleView(Context context, PetBubble bubble, PetBubbleStyle style, Runnable onTap) {
        super(context);
        this.bubble = bubble;
        this.style = style;
        this.onTap = onTap;
        shapePath.computeBounds(shapeBounds, true);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(style.fill());
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeWidth(WhaleBubbleSpec.STROKE_WIDTH);
        strokePaint.setColor(style.stroke());
        paintLabel.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paintAmount.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paintHint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paintPeak.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        setFilterTouchesWhenObscured(true);
    }

    public PetBubble content() { return bubble; }

    public void setFlipped(boolean flipped) { this.flipped = flipped; }

    /**
     * 静态模式（编辑器预览用）：永远画「完全展开」，且不响应点击。
     *
     * <p>跑马灯仍会动（每次重绘按时间取相位），由 {@link #onDraw} 自己申请下一帧 ——
     * 这样预览能真的看到渐变在跑，而不是一张静止图。
     */
    public void setStaticMode(boolean on) {
        staticMode = on;
        if (on) {
            closeAtMs = -1;
            shownAtMs = System.currentTimeMillis();
            setClickable(false);
        }
        invalidate();
    }

    public boolean isStaticMode() { return staticMode; }

    public void restart() {
        shownAtMs = System.currentTimeMillis();
        closeAtMs = -1;
        invalidate();
    }

    /**
     * 原地刷新（v1.5.0）：1 秒 ticker 改过峰谷行的文案/配色之后调用。
     *
     * <p>只重绘画布，**不重置入场时间、不重播动画** —— 对应上游 {@code bubbleCountdownTick}
     * 「原地改写文字/配色，不做整泡重绘，保持『泡泡显示期间内容稳定』」的约定。
     */
    public void refresh() {
        layoutGen++;      // v1.9.0：文案/配色变了 → 布局缓存失效（否则量出的宽度会是旧的）
        invalidate();
    }

    /** 开始渐隐退场（不是立刻消失）。静态模式（预览）下忽略。 */
    public void startClose() {
        if (staticMode) return;
        if (closeAtMs > 0) return;
        closeAtMs = System.currentTimeMillis();
        invalidate();
    }

    public boolean isClosing() { return closeAtMs > 0; }

    /** 退场是否播完（播完才可以移除窗口）。 */
    public boolean closeFinished() {
        if (closeAtMs <= 0) return false;
        return System.currentTimeMillis() - closeAtMs
                >= CLOSE_TAIL2_DELAY_MS + CLOSE_SHAPE_MS + 40;
    }

    /** 出场动画是否还在进行（用于决定要不要继续驱动帧）。 */
    public boolean opening() {
        if (staticMode) return false;
        if (closeAtMs > 0) return false;
        long age = System.currentTimeMillis() - shownAtMs;
        return age < WhaleBubbleSpec.TEXT_FADE_DELAY_MS + WhaleBubbleSpec.TEXT_FADE_DURATION_MS + 120;
    }

    private float openProgress(long delayMs, long durationMs) {
        long elapsed = System.currentTimeMillis() - shownAtMs - delayMs;
        if (elapsed <= 0) return 0f;
        if (elapsed >= durationMs) return 1f;
        return ease.getInterpolation(elapsed / (float) durationMs);
    }

    /** 每个形状的 {不透明度, 缩放}：出场 .7→1，退场 1→.7。 */
    private float[] shapeState(long openDelayMs, long closeDelayMs) {
        if (staticMode) return new float[]{1f, 1f};   // 预览：永远完全展开
        if (closeAtMs > 0) {
            long elapsed = System.currentTimeMillis() - closeAtMs - closeDelayMs;
            float t = elapsed <= 0 ? 0f : Math.min(1f, elapsed / (float) CLOSE_SHAPE_MS);
            float k = 1f - ease.getInterpolation(t);
            return new float[]{k, WhaleBubbleSpec.POP_FROM_SCALE + (1f - WhaleBubbleSpec.POP_FROM_SCALE) * k};
        }
        float t = openProgress(openDelayMs, WhaleBubbleSpec.POP_DURATION_MS);
        return new float[]{t, WhaleBubbleSpec.POP_FROM_SCALE + (1f - WhaleBubbleSpec.POP_FROM_SCALE) * t};
    }

    private float textAlpha() {
        if (staticMode) return 1f;                    // 预览：文字一直可见
        if (closeAtMs > 0) {
            long elapsed = System.currentTimeMillis() - closeAtMs;
            float t = elapsed <= 0 ? 0f : Math.min(1f, elapsed / (float) CLOSE_TEXT_MS);
            return 1f - ease.getInterpolation(t);
        }
        return openProgress(WhaleBubbleSpec.TEXT_FADE_DELAY_MS, WhaleBubbleSpec.TEXT_FADE_DURATION_MS);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float unit = WhaleBubbleSpec.unit(w);
        canvas.save();
        canvas.scale(unit, unit);
        if (flipped) canvas.scale(1f, -1f, 0f, WhaleBubbleSpec.VIEW_H / 2f);   // 只镜像形状，文字保持正立

        // 层序与 SVG 模板一致：path → b1 → b2
        drawShape(canvas, shapeState(WhaleBubbleSpec.POP_DELAY_SHAPE_MS, CLOSE_SHAPE_DELAY_MS), 0);
        drawShape(canvas, shapeState(WhaleBubbleSpec.POP_DELAY_TAIL1_MS, CLOSE_TAIL1_DELAY_MS), 1);
        drawShape(canvas, shapeState(WhaleBubbleSpec.POP_DELAY_TAIL2_MS, CLOSE_TAIL2_DELAY_MS), 2);

        canvas.restore();

        float alpha = textAlpha();
        if (alpha > 0f) {
            // 把文字与模块底色块**裁剪在泡泡轮廓内**：否则贴着文字的底色方块会在椭圆变窄处越出轮廓
            canvas.save();
            clipToBubble(canvas, unit);
            // v1.5.2：装了模块就走模块渲染；否则保持 v1.5.1 的「四行」路径
            if (bubble.hasModules()) drawModules(canvas, w, h, unit, alpha);
            else drawTexts(canvas, w, h, unit, alpha);
            canvas.restore();
        }

        // 预览（静态模式）里若配了跑马灯，自己申请下一帧，让渐变真的在跑（服务侧由帧循环负责）
        if (staticMode && marqueeAnimating()) postInvalidateOnAnimation();
    }

    /** kind：0=主体 path，1=尾巴 b1，2=尾巴 b2。 */
    private void drawShape(Canvas canvas, float[] state, int kind) {
        float k = state[0];
        if (k <= 0f) return;
        int fillA = (int) Math.round(style.fillAlpha * k);
        int strokeA = (int) Math.round(style.strokeAlpha * k);
        if (fillA <= 0 && strokeA <= 0) return;
        fillPaint.setColor((fillA << 24) | (style.fillColor & 0xFFFFFF));
        strokePaint.setColor((strokeA << 24) | (style.strokeColor & 0xFFFFFF));

        canvas.save();
        if (kind == 0) {
            canvas.scale(state[1], state[1], shapeBounds.centerX(), shapeBounds.centerY());
            if (fillA > 0) canvas.drawPath(shapePath, fillPaint);
            if (strokeA > 0) canvas.drawPath(shapePath, strokePaint);
        } else {
            float cx = kind == 1 ? WhaleBubbleSpec.TAIL1_CX : WhaleBubbleSpec.TAIL2_CX;
            float cy = kind == 1 ? WhaleBubbleSpec.TAIL1_CY : WhaleBubbleSpec.TAIL2_CY;
            float rx = kind == 1 ? WhaleBubbleSpec.TAIL1_RX : WhaleBubbleSpec.TAIL2_RX;
            float ry = kind == 1 ? WhaleBubbleSpec.TAIL1_RY : WhaleBubbleSpec.TAIL2_RY;
            canvas.scale(state[1], state[1], cx, cy);
            RectF oval = new RectF(cx - rx, cy - ry, cx + rx, cy + ry);
            if (fillA > 0) canvas.drawOval(oval, fillPaint);
            if (strokeA > 0) canvas.drawOval(oval, strokePaint);
        }
        canvas.restore();
    }

    /** 上游 CSS 的默认动画时长（`animation: dshwvRainbow 2.6s linear infinite`，L586/L712）。 */
    private static final long MARQUEE_FALLBACK_MS = 2600L;

    /**
     * 是否有模块在跑跑马灯。
     *
     * <p>⚠️ **必须**把它纳入服务侧的「谁在动」判断（{@code PetService.bubbleAnimating()}），
     * 否则泡泡静止时帧循环会掉到空闲档（120ms），跑马灯会卡成约 8fps。
     * 这与上次「按压形变看不见」是同一个坑：**绘制期状态 + 按需重绘**，新增动画必须同步加进判定集合。
     */
    public boolean marqueeAnimating() {
        if (!bubble.hasModules()) return false;
        for (PetBubbleModule m : bubble.modules) {
            if (m != null && m.marqueeActive(bubble.peakIsPeak)) return true;
        }
        return false;
    }

    /** 跑马灯相位 0…1（按模块自己的时长循环；上游时长随机 1500–4500ms）。 */
    private float marqueePhase(PetBubbleModule m) {
        long dur = m.marqueeDurMs > 0 ? m.marqueeDurMs : MARQUEE_FALLBACK_MS;
        long age = System.currentTimeMillis() - shownAtMs;
        return (float) (((age % dur) + dur) % dur) / (float) dur;
    }

    // ---------------------------------------------------------------- 跑马灯着色器（v1.9.0 重做）

    private final android.graphics.Matrix marqueeMatrix = new android.graphics.Matrix();

    /**
     * 给 Paint 装上跑马灯渐变着色器（复刻上游 CSS：{@code background-size:200% auto} +
     * {@code @keyframes dshwvRainbow} 把 background-position 从 0% 走到 200%）。
     *
     * <p>⚠️ **v1.9.0 修的真 bug（用户反馈「播完一轮卡一下再重播」）**：以前用
     * {@code TileMode.CLAMP}，且把渐变整体平移到 {@code left - 2W × phase}。相位 &gt; 0.5 之后，
     * 元素可见窗口已经越过渐变末端，被 CLAMP 压成**纯末色** —— 于是每轮最后一段时间颜色
     * **不再变化**（看起来“卡一下”），相位归零瞬间又跳回起点颜色。
     *
     * <p>修法：把配色**铺两轮**（{@code loop + loop}）并让总跨度 = 4×元素宽，
     * 这样无论相位多少，{@code W} 宽的元素窗口都**始终落在渐变内部**（CLAMP 也不会变成纯色）；
     * 同时把配色首尾闭合（{@link PetBubbleGradients#loopClosed}），循环处颜色与时序都连续。
     *
     * <p>性能：**着色器与双倍配色数组都缓存在模块上**（两个槽位），每帧只改一次
     * {@code LocalMatrix} 的相位 —— 不分配任何对象、不做哈希查找。实测与「每帧新建
     * {@code LinearGradient}」的写法同条件相当（39.2% vs 41.8% 单核占用），但本写法
     * 不会给 GC 添垃圾，所以保留这一版。
     * 淡入淡出交给 {@code Paint.setAlpha}（不再把 alpha 烘进颜色，避免每帧重建数组）。
     */
    private void marqueeShader(Paint p, PetBubbleModule m, boolean bgSlot, int[] colors,
                               float left, float width, float phase, int alpha) {
        float span = Math.max(1f, width * 2f);          // 单轮跨度（= CSS 的 background-size:200%）
        LinearGradient g = bgSlot ? m.bgShader : m.textShader;
        if (g == null) {
            int[] twice = bgSlot ? m.bgPalette : m.textPalette;
            if (twice == null) {
                int[] loop = PetBubbleGradients.loopClosed(colors);
                twice = new int[loop.length * 2];
                System.arraycopy(loop, 0, twice, 0, loop.length);
                System.arraycopy(loop, 0, twice, loop.length, loop.length);
                if (bgSlot) m.bgPalette = twice; else m.textPalette = twice;
            }
            g = new LinearGradient(0f, 0f, span * 2f, 0f, twice, null, Shader.TileMode.CLAMP);
            if (bgSlot) m.bgShader = g; else m.textShader = g;
        }
        marqueeMatrix.reset();
        marqueeMatrix.setTranslate(left - span * phase, 0f);
        g.setLocalMatrix(marqueeMatrix);
        p.setShader(g);
        p.setAlpha(alpha);                              // 入场/退场的淡入淡出走 Paint 的 alpha
    }

    /** 模块渲染路径（v1.5.2，对应上游 {@code bubbleRowsTo → bubbleRowsOf → blockOf}）。 */
    private static Typeface typefaceOf(PetBubbleModule m) {
        String family = (m.fontFamily == null || m.fontFamily.isEmpty()) ? "sans-serif" : m.fontFamily;
        int style = (m.bold ? Typeface.BOLD : 0) | (m.italic ? Typeface.ITALIC : 0);
        String key = family + "#" + style;
        Typeface tf = TYPEFACE_CACHE.get(key);
        if (tf == null) {
            tf = Typeface.create(family, style);
            TYPEFACE_CACHE.put(key, tf);
        }
        return tf;
    }

    // ---------------------------------------------------------------- 模块布局缓存（v1.9.0）

    /**
     * 模块布局缓存。
     *
     * <p>旧实现**每帧**都要：把模块按行分组（分配若干 List）、对每个模块 {@code measureText}、
     * 再分配 4 个数组 —— 跑马灯动画时这是除绘制以外最大的开销。布局只与「文本 / 字号 / 宽度」有关
     * （颜色与相位无关），所以缓存到下一次 {@link #refresh()}（峰谷 ticker 改文案时会调用）为止。
     */
    private java.util.List<java.util.List<PetBubbleModule>> layRowsList;
    private String[][] layTexts;
    private float[][] layWidths;
    private float[] layRowHeights;
    private float[] layRowScales;
    private float layTotalH;
    private float layUnit = -1f;
    private int layGen = -1;

    /** 布局代次：{@link #refresh()} 时 +1（文案变了就要重新量宽）。 */
    private int layoutGen = 0;

    /** 确保布局缓存与当前「模块 + 画布宽」匹配；返回是否有内容可画。 */
    private boolean ensureLayout(float unit) {
        if (layGen == layoutGen && Math.abs(layUnit - unit) < 0.01f && layRowsList != null) {
            return !layRowsList.isEmpty();
        }
        layRowsList = PetBubbleModule.groupRows(bubble.modules);
        layGen = layoutGen;
        layUnit = unit;
        // 布局重建 → 每个模块缓存的跑马灯着色器也要重建（跨度=元素宽，随文案/字号变化）
        for (java.util.List<PetBubbleModule> row : layRowsList) {
            for (PetBubbleModule mm : row) {
                if (mm == null) continue;
                mm.textShader = null;
                mm.bgShader = null;
                mm.textPalette = null;
                mm.bgPalette = null;
            }
        }
        final int n = layRowsList.size();
        if (n == 0) return false;
        final float boxW = unit * WhaleBubbleSpec.VIEW_W * WhaleBubbleSpec.TEXT_BOX_W;
        layTexts = new String[n][];
        layWidths = new float[n][];
        layRowHeights = new float[n];
        layRowScales = new float[n];
        for (int i = 0; i < n; i++) {
            java.util.List<PetBubbleModule> row = layRowsList.get(i);
            int c = row.size();
            layTexts[i] = new String[c];
            layWidths[i] = new float[c];
            float totalW = 0f;
            float maxH = 0f;
            for (int j = 0; j < c; j++) {
                PetBubbleModule m = row.get(j);
                String t = m.contentOf(bubble);
                layTexts[i][j] = t;
                paintModule.setTypeface(typefaceOf(m));
                paintModule.setTextSize(unit * m.fontU());
                layWidths[i][j] = paintModule.measureText(t);
                totalW += layWidths[i][j];
                Paint.FontMetrics fm = paintModule.getFontMetrics();
                maxH = Math.max(maxH, fm.descent - fm.ascent);
            }
            if (c > 1) totalW += (c - 1) * unit * MODULE_GAP_U;
            float scale = (totalW > boxW && totalW > 0f)
                    ? Math.max(MIN_ROW_SCALE, boxW / totalW) : 1f;
            layRowScales[i] = scale;
            layRowHeights[i] = maxH * scale;
        }
        float totalH = 0f;
        for (int i = 0; i < n; i++) totalH += layRowHeights[i];
        totalH += (n - 1) * unit * MODULE_ROW_GAP_U;
        layTotalH = totalH;
        return true;
    }

    /**
     * 逐行绘制模块。与上游的差异（**如实记录，不假装一致**）：
     * <ul>
     *   <li>上游一行用 CSS flex 并排；这里是 Canvas，所以**行内水平居中并排、行间垂直堆叠**，
     *       整个文本块依旧以 (44.25%, 36%) 为中心垂直居中。</li>
     *   <li>一行总宽超过文本区宽度时：上游会溢出/换行，这里选择**整行等比缩小**
     *       （下限 {@link #MIN_ROW_SCALE}）。</li>
     * </ul>
     */
    private void drawModules(Canvas canvas, float w, float h, float unit, float alpha) {
        if (!ensureLayout(unit)) return;

        final float centerX = w * WhaleBubbleSpec.TEXT_CENTER_X;
        final float centerY = textCenterY(h);
        final int a = (int) Math.round(255 * alpha);
        final int n = layRowsList.size();

        float rowTop = centerY - layTotalH / 2f;
        for (int i = 0; i < n; i++) {
            java.util.List<PetBubbleModule> row = layRowsList.get(i);
            float scale = layRowScales[i];
            float rowH = layRowHeights[i];
            float rowW = 0f;
            for (int j = 0; j < row.size(); j++) rowW += layWidths[i][j] * scale;
            if (row.size() > 1) rowW += (row.size() - 1) * unit * MODULE_GAP_U * scale;

            float x = centerX - rowW / 2f;
            for (int j = 0; j < row.size(); j++) {
                PetBubbleModule m = row.get(j);
                String t = layTexts[i][j];
                float sizePx = unit * m.fontU() * scale;
                paintModule.setTypeface(typefaceOf(m));
                paintModule.setTextSize(sizePx);
                paintModule.setTextAlign(Paint.Align.LEFT);
                Paint.FontMetrics fm = paintModule.getFontMetrics();
                float textW = layWidths[i][j] * scale;
                float baseline = rowTop + (rowH - (fm.descent - fm.ascent)) / 2f - fm.ascent;
                float phase = marqueePhase(m);

                // 底色块（跑马灯优先，其次纯色）
                float pad = unit * MODULE_BG_PAD_U * scale;
                moduleBgRect.set(x - pad, rowTop - pad * 0.5f,
                        x + textW + pad, rowTop + rowH + pad * 0.5f);
                float radius = unit * 10f * scale;
                int[] bgScheme = m.bgSchemeColors(bubble.peakIsPeak);
                if (bgScheme != null) {
                    marqueeShader(paintModuleBg, m, true, bgScheme, moduleBgRect.left,
                            moduleBgRect.width(), phase, a);
                    canvas.drawRoundRect(moduleBgRect, radius, radius, paintModuleBg);
                } else {
                    int bg = m.bgOr(bubble.peakIsPeak);
                    if (bg >= 0) {
                        paintModuleBg.setShader(null);
                        paintModuleBg.setAlpha(255);   // 用着色器时改过 alpha，这里必须复位
                        paintModuleBg.setColor(((a & 0xFF) << 24) | (bg & 0xFFFFFF));
                        canvas.drawRoundRect(moduleBgRect, radius, radius, paintModuleBg);
                    }
                }

                // 文字（跑马灯优先，其次纯色）
                int[] textScheme = m.textSchemeColors(bubble.peakIsPeak);
                if (textScheme != null) {
                    marqueeShader(paintModule, m, false, textScheme, x, textW, phase, a);
                } else {
                    paintModule.setShader(null);
                    paintModule.setAlpha(255);         // 同上：复位，避免上一次的 alpha 叠乘
                    paintModule.setColor(((a & 0xFF) << 24)
                            | (m.colorOr(style.textColor, bubble.peakIsPeak) & 0xFFFFFF));
                }
                canvas.drawText(t, x, baseline, paintModule);
                if (m.ul) {
                    float uy = baseline + 3f * unit * scale;
                    canvas.drawLine(x, uy, x + textW, uy, paintModule);
                }
                x += textW + unit * MODULE_GAP_U * scale;
            }
            rowTop += rowH + unit * MODULE_ROW_GAP_U;
        }
    }

    private final Path clipPath = new Path();
    private final Path clipRing = new Path();
    private final Paint clipRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix clipMatrix = new android.graphics.Matrix();

    /**
     * 把绘制范围**限制在泡泡轮廓内侧**（模块底色块与文字都不允许碰到描边）。
     *
     * <p>为什么需要：模块底色块是「贴着文字的小方块 + 内边距」（**上游也是这个设计**，
     * 底色画在 `.dshwv-trow` 行元素上、按内容宽度居中）。当某一行落在椭圆下半部
     * 逐渐变窄的位置时，方块的左右下角会越过轮廓中线、**盖住描边的内半侧** ——
     * 看起来就像「方框贴到泡泡外面去了」。
     *
     * <p>做法：描边是**居中**画在轮廓上的（{@code stroke-width 18}），所以轮廓内侧有 9u
     * 属于描边。这里先取轮廓，再减去「以轮廓为中心、宽 18u 的环」，得到的就是
     * **描边内边缘以内的区域**，底色块与文字被裁在这里面 —— 永远压不到描边。
     */
    private void clipToBubble(Canvas canvas, float unit) {
        clipMatrix.reset();
        if (flipped) clipMatrix.postScale(1f, -1f, 0f, WhaleBubbleSpec.VIEW_H / 2f);
        clipMatrix.postScale(unit, unit);
        shapePath.transform(clipMatrix, clipPath);
        // 减去描边占用的那一圈（描边居中 → 内侧 9u）
        clipRingPaint.setStyle(Paint.Style.STROKE);
        clipRingPaint.setStrokeWidth(WhaleBubbleSpec.STROKE_WIDTH * unit);
        clipRingPaint.getFillPath(clipPath, clipRing);
        clipPath.op(clipRing, Path.Op.DIFFERENCE);
        canvas.clipPath(clipPath);
    }

    /**
     * 文字块中心的 Y 比例。
     *
     * <p>⚠️ 翻转（泡泡画在桌宠下方）时**必须**镜像这个值：形状被垂直镜像了，
     * 但文字区（{@code TEXT_CENTER_Y = 0.36}，偏上）如果不跟着镜像，文字就会落到
     * 尾巴那一侧、压在描边上 —— 这正是早期「不做翻转」的真正原因。
     */
    private float textCenterY(float h) {
        float ratio = flipped ? (1f - WhaleBubbleSpec.TEXT_CENTER_Y) : WhaleBubbleSpec.TEXT_CENTER_Y;
        return h * ratio;
    }

    /** 四行文字：在 (44.25%,36%) 为中心的 66%×64% 区域内垂直居中排列。 */
    private void drawTexts(Canvas canvas, float w, float h, float unit, float alpha) {
        int a = (int) Math.round(255 * alpha);
        float centerX = w * WhaleBubbleSpec.TEXT_CENTER_X;
        float centerY = textCenterY(h);

        // 文案走占位符解析（{status}/{countdown}/{balance_ds}…），由 PetBubble 统一负责
        String label = bubble.labelText();
        String amount = bubble.amountText();
        String hint = bubble.hintText();
        String peak = bubble.peakLine();

        paintLabel.setTextSize(unit * WhaleBubbleSpec.FONT_LABEL_U);
        paintAmount.setTextSize(unit * WhaleBubbleSpec.FONT_AMOUNT_U);
        paintHint.setTextSize(unit * WhaleBubbleSpec.FONT_HINT_U);
        paintPeak.setTextSize(unit * (bubble.peakCountdown
                ? WhaleBubbleSpec.FONT_PERIOD_U : WhaleBubbleSpec.FONT_PEAK_U));
        paintLabel.setColor(style.textFor(a));
        paintAmount.setColor(style.textFor(a));
        paintHint.setColor(style.hintFor(a));
        // 峰谷行用「峰色/谷色」（用户可配），与正文色无关；不透明度跟着入场/退场一起缩放
        paintPeak.setColor(((a & 0xFF) << 24) | (bubble.peakColor & 0xFFFFFF));
        paintLabel.setTextAlign(Paint.Align.CENTER);
        paintAmount.setTextAlign(Paint.Align.CENTER);
        paintHint.setTextAlign(Paint.Align.CENTER);
        paintPeak.setTextAlign(Paint.Align.CENTER);

        Paint.FontMetrics fl = paintLabel.getFontMetrics();
        Paint.FontMetrics fa = paintAmount.getFontMetrics();
        Paint.FontMetrics fh = paintHint.getFontMetrics();
        Paint.FontMetrics fp = paintPeak.getFontMetrics();
        float hLabel = fl.descent - fl.ascent;
        float hAmount = fa.descent - fa.ascent;
        float hHint = hint.isEmpty() ? 0f : (fh.descent - fh.ascent);
        float hPeak = peak.isEmpty() ? 0f : (fp.descent - fp.ascent);
        float total = hLabel + hAmount + hHint + hPeak;
        float top = centerY - total / 2f;

        canvas.drawText(label, centerX, top - fl.ascent, paintLabel);
        float amountTop = top + hLabel;
        canvas.drawText(amount, centerX, amountTop - fa.ascent, paintAmount);
        float hintTop = amountTop + hAmount;
        if (hHint > 0f) {
            canvas.drawText(hint, centerX, hintTop - fh.ascent, paintHint);
        }
        if (hPeak > 0f) {
            float peakTop = hintTop + hHint;
            float baseline = peakTop - fp.ascent;
            canvas.drawText(peak, centerX, baseline, paintPeak);
            // 倒计时样式带下划线（对应上游预设里的 ul:true）
            if (bubble.peakCountdown) {
                float half = paintPeak.measureText(peak) / 2f;
                float y = baseline + 3f * unit;
                canvas.drawLine(centerX - half, y, centerX + half, y, paintPeak);
            }
        }
    }

    /** 点泡泡：对应源码里 bubbleBox 的 click → bubbleNext()（第 1 步先做"收起"）。 */
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (staticMode) return false;        // 预览不响应触摸
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (onTap != null) onTap.run();
            return true;
        }
        return event.getActionMasked() == MotionEvent.ACTION_DOWN;
    }
}