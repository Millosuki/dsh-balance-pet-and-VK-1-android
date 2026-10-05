package com.dsh.balancepet;

/**
 * 泡泡的可自定义外观。默认值全部来自 WhaleWidget 源码：
 *   填充 #FFFFFF、描边 #203170（stroke-width 18）、
 *   正文色 #536ba9（`.dshwv-text{color:#536ba9}`，label/amount 继承它）、
 *   提示色 #9fb0d9（`.dshwv-hint{color:#9fb0d9}`）。
 */
public final class PetBubbleStyle {

    /** 颜色（不含 alpha），十六进制 0xRRGGBB。 */
    public int fillColor = 0xFFFFFF;
    public int strokeColor = 0x203170;
    public int textColor = 0x536BA9;
    public int hintColor = 0x9FB0D9;

    /** 不透明度 0…255（用户可调）。 */
    public int fillAlpha = 255;
    public int strokeAlpha = 255;

    public static PetBubbleStyle from(PetState state) {
        PetBubbleStyle style = new PetBubbleStyle();
        style.fillColor = state.bubbleFillColor & 0xFFFFFF;
        style.strokeColor = state.bubbleStrokeColor & 0xFFFFFF;
        style.textColor = state.bubbleTextColor & 0xFFFFFF;
        style.hintColor = state.bubbleHintColor & 0xFFFFFF;
        style.fillAlpha = clamp255(state.bubbleFillAlpha);
        style.strokeAlpha = clamp255(state.bubbleStrokeAlpha);

        // 深色底 + 原来的深色字会看不清：用户没手动改过文字色时自动换成浅色（改了就以用户为准）
        boolean darkFill = luminance(style.fillColor) < 0.5 && style.fillAlpha >= 128;
        if (darkFill) {
            if (style.textColor == 0x536BA9) style.textColor = 0xFFFFFF;
            if (style.hintColor == 0x9FB0D9) style.hintColor = 0xE6E9F2;
        }
        return style;
    }

    /** 感知亮度（0=黑，1=白）：用于判断底色深浅。 */
    public static double luminance(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static int clamp255(int v) { return Math.min(255, Math.max(0, v)); }

    public int fill() { return (fillAlpha << 24) | (fillColor & 0xFFFFFF); }

    public int stroke() { return (strokeAlpha << 24) | (strokeColor & 0xFFFFFF); }

    /** 文字在深色底上会看不清：按背景亮度自动选深/浅字（用户没手动指定时用）。 */
    public int textFor(int alpha) { return ((alpha & 0xFF) << 24) | (textColor & 0xFFFFFF); }

    public int hintFor(int alpha) { return ((alpha & 0xFF) << 24) | (hintColor & 0xFFFFFF); }

    public String describe() {
        return String.format(java.util.Locale.US,
                "填充 #%06X/%d 描边 #%06X/%d 正文 #%06X 提示 #%06X",
                fillColor, fillAlpha, strokeColor, strokeAlpha, textColor, hintColor);
    }
}