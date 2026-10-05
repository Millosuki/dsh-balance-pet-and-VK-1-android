package com.dsh.balancepet;

/**
 * 「箭头方向要指着桌宠」——泡泡画到桌宠**下方**时，把**句首 / 句尾**的向下箭头换成向上的
 * （v1.12.6 引入，v1.12.8 收窄到句首 / 句尾）。
 *
 * <p>为什么要看位置（用户 2026-10-05 追加的要求）：句中的箭头常常是别的意思
 * （「看到 ↓ 了吗」是在描述别人），无差别翻转会误伤；而出厂语录 {@code 好模型...↓}、{@code 好女孩...↓}
 * 的箭头就在**句尾**，本来就是指桌宠的。
 *
 * <p><b>判定规则</b>：一行之内，某个箭头**前面（或后面）只剩下空白与标点**时，就算处在句首 / 句尾。
 * <ul>
 *   <li>{@code 好模型...↓} ✓（后面没有东西）</li>
 *   <li>{@code ↓ 余额} ✓（前面没有东西）</li>
 *   <li>{@code 看到↓了吗} ✗（两边都有实词）</li>
 *   <li>{@code (↓)} ✓（两侧只有括号）</li>
 * </ul>
 *
 * <p>只做**单向**替换（↓ 类 → ↑ 类）：用户自己写的 ↑ 不动 —— 那说明他本来就要朝上。
 * 也不改用户配置里的原始文字，只影响这一次显示。
 */
public final class ArrowFlip {
    private ArrowFlip() {}

    /** 把**句首 / 句尾**的向下箭头及其变体换成对应的向上写法；其它字符原样保留（不吞字符）。 */
    public static String up(String text) {
        if (text == null || text.isEmpty()) return text;
        int n = text.length();
        StringBuilder sb = new StringBuilder(n);
        int lineStart = 0;
        for (int i = 0; i <= n; i++) {
            boolean lineEnd = (i == n) || text.charAt(i) == '\n';
            if (!lineEnd) continue;
            appendLine(sb, text, lineStart, i);
            if (i < n) sb.append('\n');
            lineStart = i + 1;
        }
        return sb.toString();
    }

    /** 处理一行 [from, to)：逐字符决定要不要翻。 */
    private static void appendLine(StringBuilder sb, String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            // 🔽（U+1F53D，代理对）→ 🔼（U+1F53C）
            if (c == '\uD83D' && i + 1 < to && text.charAt(i + 1) == '\uDD3D') {
                if (atEdge(text, from, to, i, i + 1)) sb.append('\uD83D').append('\uDD3C');
                else sb.append(c).append(text.charAt(i + 1));
                i++;
                continue;
            }
            char flipped = flipped(c);
            if (flipped != 0 && atEdge(text, from, to, i, i)) sb.append(flipped);
            else sb.append(c);
        }
    }

    /** 只把「向下」的箭头映射成「向上」；不是向下箭头就返回 0（表示不动）。 */
    private static char flipped(char c) {
        switch (c) {
            case '\u2193': return '\u2191';   // ↓ → ↑
            case '\u21E9': return '\u21E7';   // ⇩ → ⇧
            case '\u2B07': return '\u2B06';   // ⬇ → ⬆
            case '\u25BC': return '\u25B2';   // ▼ → ▲
            case '\u25BE': return '\u25B4';   // ▾ → ▴
            case '\u02C5': return '\u02C4';   // ˅ → ˄
            case '\uFE40': return '\uFE3F';   // ﹀ → ︿
            case '\u2304': return '\u2303';   // ⌄ → ⌃
            default: return 0;
        }
    }

    /**
     * 这个箭头（占用字符区间 glyphStart..glyphEnd，含两端）是否处在**句首或句尾**。
     *
     * <p>判据：它前面只剩空白 / 标点，或它后面只剩空白 / 标点。
     */
    private static boolean atEdge(String text, int from, int to, int glyphStart, int glyphEnd) {
        boolean fillerOnlyBefore = true;
        for (int i = from; i < glyphStart; i++) {
            if (!isFiller(text.charAt(i))) {
                fillerOnlyBefore = false;
                break;
            }
        }
        if (fillerOnlyBefore) return true;
        for (int i = glyphEnd + 1; i < to; i++) {
            if (!isFiller(text.charAt(i))) return false;
        }
        return true;
    }

    /**
     * 空白或标点（都算「不是实词」）。
     * 变体选择符 U+FE0F/U+FE0E 也算 —— 否则 {@code ⬇️} 末尾的那个选择符会让它被判成「句中有东西」。
     */
    private static boolean isFiller(char c) {
        if (Character.isWhitespace(c)) return true;
        switch (c) {
            case '\uFE0F': case '\uFE0E':
            case '。': case '，': case '、': case '！': case '？': case '；': case '：':
            case '…': case '—': case '～': case '·': case '「': case '」': case '『': case '』':
            case '（': case '）': case '《': case '》': case '【': case '】':
            case '(': case ')': case '[': case ']': case '{': case '}': case '<': case '>':
            case '\'': case '"': case '“': case '”': case '‘': case '’':
            case '.': case ',': case '!': case '?': case ';': case ':': case '~': case '^':
                return true;
            default:
                return false;
        }
    }
}