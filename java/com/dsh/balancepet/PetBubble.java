package com.dsh.balancepet;

/**
 * 一个泡泡的内容。对应 WhaleWidget 的「模块行」渲染结果，
 * 第 1 步只做「标题 + 金额 + 提示」三行（就是它 DOM 里的 labelEl/amountEl/hintEl）。
 */
public final class PetBubble {

    public String label = WhaleBubbleSpec.DEFAULT_LABEL;
    public String amount = "--";
    public String hint = "";

    // ---- v1.5.0：峰谷行（对应上游的 peak 模块） ----

    /** 峰谷行文案（空 = 不画这一行）。由 1 秒 ticker 原地改写。 */
    public String peakText = "";
    /** 峰谷行颜色（ARGB，已含 alpha）。由 1 秒 ticker 按峰/谷状态原地改写。 */
    public int peakColor = 0xFF203170;
    /** 是否为「倒计时」样式（字号更大 + 带下划线，对应上游预设的 size4 + ul）。 */
    public boolean peakCountdown = false;

    // ---- v1.5.2：模块化内容（对应上游的 modules[] + 按 row 分组渲染） ----

    /**
     * 泡泡内容模块。**非空时走模块渲染路径**，为空则退回 v1.5.1 的「四行」路径
     * （所以升级后默认观感不变；想用模块就在设置页点「用默认模块预设」）。
     */
    public final java.util.List<PetBubbleModule> modules = new java.util.ArrayList<>();
    /** 当前是否为高峰：模块渲染用它挑峰/谷配色，由 1 秒 ticker 维护。 */
    public boolean peakIsPeak = false;
    /** 随机语句模块的抽签用随机源（同一泡内共用，保证一次组装只抽一次）。 */
    public transient java.util.Random pickRandom;
    /**
         * 画到桌宠**下方**时置 true（v1.12.6）：{@link #resolve} 会把「↓」类箭头换成「↑」类 ——
         * 箭头是用来指桌宠的，泡泡在下面时当然得朝上。**只影响这一次显示**，不动用户配置里的原始文字。
         */
        public boolean arrowsUp;
    /**
     * 当前**全局**「显示样式」。模块在「跟随全局」({@link PetBubbleModule#STYLE_FOLLOW_GLOBAL}) 时用它；
     * 由服务在组装泡泡与每秒刷新时写入，这样用户在设置里改样式能**立刻**反映到已显示的泡泡上。
     */
    public int peakStyleGlobal = PeakValley.STYLE_DEFAULT;

    public boolean hasModules() { return !modules.isEmpty(); }

    /** 内容代号（用于日志/去重），例如 "balance" / "offline"。 */
    public String kind = "balance";

    /**
     * 占位符表（对应上游的 token map）。
     * 本轮接入：{@code {status}} / {@code {countdown}} / {@code {balance_ds}}；
     * 后续要加 {@code {expense_ds}} / {@code {session}} 等，只需在组装泡泡时多 put 一个 token。
     */
    private final java.util.Map<String, String> tokens = new java.util.HashMap<>();

    public PetBubble() {}

    public PetBubble(String kind, String label, String amount, String hint) {
        this.kind = kind;
        this.label = label;
        this.amount = amount;
        this.hint = hint;
    }

    /** 登记一个占位符值（只影响后续的 {@link #resolve}）。 */
    public void putToken(String key, String value) {
        if (key == null) return;
        if (value == null) tokens.remove(key);
        else tokens.put(key, value);
    }

    /**
     * 把模板里的 {@code {key}} 换成 token 值：**逐字符单趟扫描**，一次替换到位
     * （不做「反复 replace 直到不变」那种可能互相污染的做法）。
     * 未知占位符**原样保留**（宁可看见 {@code {xxx}}，也不要静默变成空串把问题藏起来）。
     */
    public String resolve(String template) {
            String text = resolveTokens(template);
            // v1.12.6：画到桌宠**下方**时，箭头要指回桌宠（↓ → ↑）
            return arrowsUp ? ArrowFlip.up(text) : text;
        }
        /** token 替换本体（逐字符单趟扫描，一次替换到位）。 */
        private String resolveTokens(String template) {
            if (template == null || template.isEmpty()) return "";
            if (template.indexOf('{') < 0) return template;
        StringBuilder out = new StringBuilder(template.length() + 16);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int end = template.indexOf('}', i + 1);
                if (end > i) {
                    String key = template.substring(i + 1, end);
                    String value = tokens.get(key);
                    if (value != null) {
                        out.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
         * 解析后的正文摘要（v1.12.6，日志/取证用）。
         *
         * <p>注意：随机语句模块每次读取都会**重新抽一句**，所以这里的样句可能与实际绘制的那一句不同 ——
         * 只用来排查“文字有没有被替换 / 箭头方向对不对”，不做精确对账。
         */
        public String textSummary() {
            StringBuilder sb = new StringBuilder();
            if (!modules.isEmpty()) {
                for (PetBubbleModule m : modules) {
                    if (m == null) continue;
                    sb.append('「').append(m.contentOf(this)).append('」');
                }
            } else {
                sb.append('「').append(labelText()).append('」');
                sb.append('「').append(amountText()).append('」');
                if (!hint.isEmpty()) sb.append('「').append(hintText()).append('」');
            }
            return sb.toString();
        }
        public String labelText() { return resolve(label); }

    public String amountText() { return resolve(amount); }

    public String hintText() { return resolve(hint); }

    public String peakLine() { return resolve(peakText); }

    public boolean hasPeakLine() { return peakLine().length() > 0; }

    public String describe() {
        return kind + "|" + label + "|" + amount + (hint.isEmpty() ? "" : "|" + hint);
    }
}