package com.dsh.balancepet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 泡泡的「跑马灯渐变」配色表（二期）。
 *
 * <p>数据**逐条抠自**上游 {@code assets/whale-widget.js} 的 CSS（行号标在每条注释里），
 * 不是估色。上游的写法是：
 * <pre>
 * .dshwv-trow.dshwv-rgb-candy,...{background-image:linear-gradient(90deg,rgb(..),..)}
 * .dshwv-trow.dshwv-bgrgb-candy,...{...}
 * </pre>
 * 文字与底色用的是**同一批颜色**（L693–L710 与 L714–L731 一一对应），所以本类只维护一张表。
 *
 * <p>动画语义（上游 CSS）：
 * <pre>
 * background-size: 200% auto;
 * animation: dshwvRainbow 2.6s linear infinite;      // L586 / L712
 * &#64;keyframes dshwvRainbow{0%{background-position:0% 0}100%{background-position:200% 0}}   // L691
 * </pre>
 * 即：渐变图宽度是元素的 2 倍，用 {@code 0%→200%} 的背景位置把它**向左平移 2 倍宽度**后循环。
 * 每套配色的**首尾色相同**（已用脚本逐套校验），所以循环处看不出接缝 —— 这也是本类
 * 提供 {@link #seamless(String)} 断言的原因。
 *
 * <p>上游的时长是**随机**的：{@code bubbleMarqueeDur() = round(1500 + random()*3000) + 'ms'}（L12991），
 * 每个模块每次渲染各随机一次。本工程在**组装泡泡时**为每个模块定一次时长（避免逐帧抖动）。
 */
public final class PetBubbleGradients {

    private PetBubbleGradients() {}

    /** 未指定名字时的兜底方案。上游：{@code rgb === true ? 'macaron' : ...}，且基类 {@code .dshwv-rgb} 就是 macaron。 */
    public static final String DEFAULT_SCHEME = "macaron";

    /** 全部方案（顺序与上游 CSS 定义顺序一致，便于对照）。 */
    private static final Map<String, int[]> SCHEMES;

    static {
        Map<String, int[]> m = new LinkedHashMap<>();
        // ---- L692/L713：基类 .dshwv-rgb / .dshwv-bgrgb（即 macaron）----
        m.put("macaron", new int[]{
                0xFFB4C8, 0xFFCDAA, 0xFFE1A5, 0xF5F0B4, 0xBEF0D2,
                0xB4E6F5, 0xBED7FA, 0xDCC8F5, 0xF0C8E6, 0xFFB4C8});
        // L693/L714
        m.put("candy", new int[]{
                0xFF91AA, 0xFFAA82, 0xFFC36E, 0xF0DC73, 0x8CDCAF, 0x73D2CD,
                0x82C3F0, 0xA0AAEB, 0xD29BE6, 0xEB87BE, 0xFF91AA});
        // L694/L715
        m.put("rouge", new int[]{
                0x8C192D, 0xAF233C, 0x781437, 0xA0284B, 0xBE3750, 0x821E41, 0x8C192D});
        // L695/L716
        m.put("bamboo", new int[]{
                0x46B455, 0x5FC869, 0x37A546, 0x6ED778, 0x50BE5F, 0x3CAC4E, 0x46B455});
        // L696/L717
        m.put("aurora", new int[]{
                0x46F0C8, 0x5AC8FF, 0x788CFF, 0xB478FF, 0xF08CFF, 0x46F0C8});
        // L697/L718
        m.put("deepsea", new int[]{
                0x145AB4, 0x1E8CD2, 0x28B4DC, 0x1478BE, 0x32A0E6, 0x1964C8, 0x145AB4});
        // L698/L719
        m.put("sunset", new int[]{
                0xFFB450, 0xFF825A, 0xFF5A6E, 0xDC5A96, 0xA05ABE, 0xFFB450});
        // L699/L720
        m.put("forest", new int[]{
                0x1E643C, 0x3C8C50, 0x5AB45A, 0x8CC850, 0xB4D25A, 0x1E643C});
        // L700/L721
        m.put("champagne", new int[]{
                0xDCB464, 0xF0CD82, 0xFFE1A0, 0xE6BE6E, 0xF5D28C, 0xDCB464});
        // L701/L722
        m.put("lavender", new int[]{
                0xB496FF, 0xC8AAFF, 0xE6B4F0, 0xFFBEDC, 0xF0A0C8, 0xB496FF});
        // L702/L723
        m.put("mint", new int[]{
                0x78E6B4, 0x96F0C8, 0xAAF0E6, 0x8CDCF0, 0x78C8DC, 0x78E6B4});
        // L703/L724
        m.put("lava", new int[]{
                0xFF3C28, 0xFF6E1E, 0xFFAA28, 0xFFD246, 0xFF8C32, 0xFF3C28});
        // L704/L725
        m.put("galaxy", new int[]{
                0x281E5A, 0x463282, 0x6E46AA, 0xA05ABE, 0xDC78B4, 0x281E5A});
        // L706/L727
        m.put("ink", new int[]{
                0x141414, 0x505050, 0x8C8C8C, 0xC8C8C8, 0xFAFAFA,
                0xFAFAFA, 0xC8C8C8, 0x8C8C8C, 0x505050, 0x141414});
        // L707/L728
        m.put("indigo", new int[]{
                0x203170, 0x344C92, 0x4A66B4, 0x647ED2, 0x8284E0,
                0x8284E0, 0x647ED2, 0x4A66B4, 0x344C92, 0x203170});
        // L709/L730
        m.put("blaze", new int[]{
                0x960F19, 0xC81E1E, 0xF04619, 0xFF821E, 0xFFC83C, 0xFFF0AA,
                0xFFC83C, 0xFF821E, 0xF04619, 0xC81E1E, 0x960F19});
        // L710/L731
        m.put("amber", new int[]{
                0xE65A00, 0xFF8C00, 0xFFB400, 0xFFD728, 0xFFF078,
                0xFFD728, 0xFFB400, 0xFF8C00, 0xE65A00});
        SCHEMES = Collections.unmodifiableMap(m);
    }

    /** 全部方案名（供设置页展示/核对）。 */
    public static String[] names() {
        return SCHEMES.keySet().toArray(new String[0]);
    }

    public static int size() {
        return SCHEMES.size();
    }

    public static boolean isKnown(String name) {
        return name != null && SCHEMES.containsKey(name.trim().toLowerCase(Locale.US));
    }

    /**
     * 名字 → 颜色表。与上游行为一致：
     * <ul>
     *   <li>已知名字 → 该方案</li>
     *   <li>{@code ""} / {@code "true"} / 未知名字 → {@link #DEFAULT_SCHEME}（macaron）</li>
     * </ul>
     * 上游对未知名字只加基类 {@code .dshwv-rgb}，而基类的渐变就是 macaron —— 所以这里是**忠于上游**的。
     */
    public static int[] resolve(String name) {
        if (name == null) return SCHEMES.get(DEFAULT_SCHEME);
        String key = name.trim().toLowerCase(Locale.US);
        if (key.isEmpty() || "true".equals(key)) return SCHEMES.get(DEFAULT_SCHEME);
        int[] colors = SCHEMES.get(key);
        return colors != null ? colors : SCHEMES.get(DEFAULT_SCHEME);
    }

    /** 该方案是否首尾同色（决定循环处有没有接缝）。 */
    public static boolean seamless(String name) {
        int[] c = resolve(name);
        return c != null && c.length >= 2 && c[0] == c[c.length - 1];
    }

    /**
     * 把配色「首尾闭合」：首尾不同色时补一个首色，否则**原样返回**（不产生新对象）。
     *
     * <p>为什么需要（v1.9.0）：跑马灯现在是 **REPEAT 平铺**（对齐上游 CSS 的
     * {@code background-repeat: repeat}），平铺时「上一轮的末色」会紧挨着「下一轮的首色」——
     * 若两者不同就会在接缝处出现一条硬边在元素上扫过。上游 17 套配色本来就是首尾同色，
     * 这个方法只是**防御性的**（万一以后加了新配色忘了收尾）。
     */
    public static int[] loopClosed(int[] colors) {
        if (colors == null || colors.length == 0) return colors;
        if (colors[0] == colors[colors.length - 1]) return colors;
        int[] out = new int[colors.length + 1];
        System.arraycopy(colors, 0, out, 0, colors.length);
        out[colors.length] = colors[0];
        return out;
    }
}