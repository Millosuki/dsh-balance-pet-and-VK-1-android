package com.dsh.balancepet;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

/**
 * 极小的「富文本」工具（v1.12.1）。
 *
 * <p>为什么需要：界面文案里有不少用 markdown 的 {@code **加粗**} 写的强调（开发时就是那么写的），
 * 但 {@link android.widget.TextView} 不认 markdown —— **之前是把这些星号原样画在屏幕上**：
 * 用户截图里能直接看到「是**并列候选**：…」「拖到某行的 **上边缘**=…」，既难看又像是文案写错了。
 *
 * <p>这里只做最小解析：把成对的 {@code **} 转成真加粗；**其它字符一律原样保留**
 * （绝不吞字符；落单的 {@code **} 只去掉标记、不加粗）。
 */
public final class RichText {
    private RichText() {}

    /** 把 {@code **强调**} 转成加粗的 {@link CharSequence}；没有标记时原样返回（零分配）。 */
    public static CharSequence bold(String text) {
        if (text == null) return "";
        if (text.indexOf("**") < 0) return text;
        StringBuilder sb = new StringBuilder(text.length());
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        int open = -1;
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                if (open < 0) {
                    open = sb.length();
                } else {
                    if (sb.length() > open) spans.add(new int[]{open, sb.length()});
                    open = -1;
                }
                i += 2;
                continue;
            }
            sb.append(text.charAt(i));
            i++;
        }
        SpannableString out = new SpannableString(sb.toString());
        for (int[] r : spans) {
            out.setSpan(new StyleSpan(Typeface.BOLD), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }
}
