package com.dsh.balancepet;

/**
 * WhaleWidget（DeepSeek-Balance-Whale-Widget 0.3.17）泡泡的全部视觉规格。
 *
 * 全部数据来自其源码 assets/whale-widget.js（不是估的），逐条注明出处：
 *   - SVG 模板：viewBox="0 0 1026 700"，主体 path 与两个 ellipse（见 WHALE_SVG_* 常量）
 *   - CSS：.dshwv-pop{aspect-ratio:1026/700; --dshw-u: calc(base/1026)}
 *          .dshwv-pop .dshwv-bshape/.b1/.b2{opacity:0;transform:scale(.7);...transition:opacity .2s ease,transform .2s ease}
 *          .dshwv-pop.dshwv-pop-open .b2{transition-delay:0s} .b1{.13s} .bshape{.26s}
 *          .dshwv-text{left:44.25%;top:36%;width:66%;height:64%;transform:translate(-50%,-50%)}
 *          .dshwv-pop.dshwv-pop-open .dshwv-text{opacity:1;transition:opacity .16s ease .36s}
 *          .dshwv-label{font-size:66u;font-weight:600} .dshwv-amount{font-size:128u;font-weight:800}
 *          .dshwv-hint{font-size:56u;color:#9fb0d9}
 *   - 文案默认值：labelEl.textContent = 'DeepSeek 余额'
 */
public final class WhaleBubbleSpec {

    private WhaleBubbleSpec() {}

    // ---- 画布与形状 ----
    public static final float VIEW_W = 1026f;
    public static final float VIEW_H = 700f;

    /** 主体：一张"气泡"轮廓（四段椭圆弧 + 一个 10° 旋转的过渡弧）。 */
    public static final String SHAPE_PATH =
            "M 827 248 "
                    + "A 373 232 0 1 0 81 246 "
                    + "A 373 232 0 0 0 301 465 "
                    + "A 57 32 10 0 0 413 484 "
                    + "A 373 232 0 0 0 827 248 Z";

    /** 尾巴：两个椭圆，按 b2 → b1 → 主体 的顺序依次鼓起。 */
    public static final float TAIL1_CX = 352f, TAIL1_CY = 561f, TAIL1_RX = 37.5f, TAIL1_RY = 26f;
    public static final float TAIL2_CX = 442f, TAIL2_CY = 646f, TAIL2_RX = 24.5f, TAIL2_RY = 18f;

    /** 填充与描边（源码里写死在 SVG 属性上）。 */
    public static final int FILL = 0xFFFFFFFF;          // #FFFFFF
    public static final int STROKE = 0xFF203170;        // #203170
    public static final float STROKE_WIDTH = 18f;       // viewBox 单位

    // ---- 入场动画 ----
    public static final float POP_FROM_SCALE = 0.7f;    // transform: scale(.7)
    public static final long POP_DURATION_MS = 200;     // transition: .2s ease
    public static final long POP_DELAY_TAIL2_MS = 0;    // b2
    public static final long POP_DELAY_TAIL1_MS = 130;  // b1
    public static final long POP_DELAY_SHAPE_MS = 260;  // bshape
    public static final long TEXT_FADE_DELAY_MS = 360;  // .36s
    public static final long TEXT_FADE_DURATION_MS = 160;

    // ---- 文字层几何（相对 pop 层） ----
    public static final float TEXT_CENTER_X = 0.4425f;  // 44.25%
    public static final float TEXT_CENTER_Y = 0.36f;    // 36%
    public static final float TEXT_BOX_W = 0.66f;       // 66%
    public static final float TEXT_BOX_H = 0.64f;       // 64%

    /** pop 层高度 = 宽度 × VIEW_H / VIEW_W（aspect-ratio: 1026/700）。 */
    public static float popHeight(float boxWidth) {
        return boxWidth * VIEW_H / VIEW_W;
    }

    // ---- 字号（u = 画布宽度 / 1026） ----
    public static final float FONT_LABEL_U = 66f;
    public static final float FONT_AMOUNT_U = 128f;
    public static final float FONT_PERIOD_U = 104f;
    public static final float FONT_HINT_U = 56f;

    /**
     * 峰谷行字号（v1.5.0 新增）。
     *
     * <p>⚠️ **这一项不是从上游抄的**，是本工程自定的：上游该行是「逐模块字号档」
     * （size 1–50 线性映射 40u→240u），默认预设里峰谷状态行 size=2（≈44u）、
     * 倒计时行 size=4（≈52u）。本工程是单行显示，44u/52u 在高分屏上偏小，
     * 所以取一个介于提示行(56u)与金额行(128u)之间的值。
     * 倒计时样式则用规格里早已预留的 {@link #FONT_PERIOD_U}（104u）。
     */
    public static final float FONT_PEAK_U = 64f;

    /** 单位 u：把 viewBox 单位换算成像素。 */
    public static float unit(float canvasWidth) {
        return canvasWidth / VIEW_W;
    }

    // ---- 主体轮廓的椭圆近似（文字/底色块的裁剪判定用；也是自检的依据） ----
    /** 主体 path 的椭圆近似中心与半径（四段弧 ≈ 中心 (454,249)、rx373、ry232）。 */
    public static final float SHAPE_CX = 454f;
    public static final float SHAPE_CY = 249f;
    public static final float SHAPE_RX = 373f;
    public static final float SHAPE_RY = 232f;

    /**
     * viewBox 坐标是否落在泡泡**主体椭圆**内（{@code r <= 1}）。
     *
     * <p>用途：文字区（66%×64%，居中于 44.25%/36%）的**下边缘会伸到椭圆变窄处**，
     * 所以模块底色块必须裁剪到轮廓内，否则方块的角会压在描边上
     * （用户反馈「方框出界」的根因）。本方法同时作为自检依据。
     */
    public static boolean insideShapeEllipse(float x, float y) {
        float dx = (x - SHAPE_CX) / SHAPE_RX;
        float dy = (y - SHAPE_CY) / SHAPE_RY;
        return dx * dx + dy * dy <= 1.0f;
    }

    // ---- 配色（源码里的文字色） ----
    public static final int COLOR_MAIN = 0xFF203170;    // #203170：深藏青（描边同色）
    public static final int COLOR_HINT = 0xFF9FB0D9;    // #9fb0d9：提示行

    // ---- 默认文案 ----
    public static final String DEFAULT_LABEL = "DeepSeek 余额";
    public static final String OFFLINE_AMOUNT = "--";

    // ---- 行为（源码：点泡泡 = bubbleNext()，点鲸鱼 = 从队列开头） ----
    /**
     * 泡泡自动收起时间（毫秒）。0 = 不自动收（由点击/序列控制）。
     *
     * <p>上游是 {@code var BUBBLE_MS = 5000}（whale-widget.js L335）。
     * ⚠️ 本工程早期误写成 6000 并在注释里声称「取自源码」——**已改正为 5000 对齐上游**，
     * 并开放为用户可配置（设置 → Whale挂件 → 泡泡留存时间）。
     */
    public static final long DEFAULT_TTL_MS = 5000;
}