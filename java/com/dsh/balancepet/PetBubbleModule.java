package com.dsh.balancepet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 泡泡里的一个「模块」。字段名**刻意与上游 {@code whale-widget.js} 的模块对象保持一致**，
 * 这样用户可以直接把原插件的模块 JSON 粘进来（也能原样导出）。
 *
 * <p>上游规格出处（已核实）：
 * <ul>
 *   <li>字号档公式 {@code bubbleModuleFontU}（L12450）：{@code u = round(40 + (档-1) × 200 / 49)}
 *       —— 档 1 → 40u，档 50 → 240u</li>
 *   <li>行分组 {@code bubbleRowsOf}（L12242）：按 {@code m.row} 分组；
 *       **不带 row 的模块各自占一行**（上游默认预设就是这样，见 L5238–5241）</li>
 *   <li>硬约束：每行 ≤ 6 模块、每泡 ≤ 6 行（图片类独占一行且一泡只能一个，图片类本版未实现）</li>
 *   <li>峰谷模块专属字段与默认色：{@code peakColor #e0433f} / {@code offColor #2fa24c} /
 *       {@code peakBg #fbe7e6} / {@code offBg #e4f3e7}</li>
 * </ul>
 *
 * <p>⚠️ 本版（一期）**只实现纯色与底色纯色**；{@code rgb}/{@code bgRgb}（跑马灯渐变，16 套）
 * 字段会被**解析并保留**（不丢配置），但渲染时回落到纯色 —— 二期实现。这一点在设置页也会写明。
 */
public final class PetBubbleModule {

    public PetBubbleModule() {}

    /** 支持的模块类型。（与上游同名；上游的 {@code nextpeak} 归一化成 {@code peak}。） */
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_BALANCE = "balance";
    public static final String TYPE_COST = "cost";
    public static final String TYPE_STATUS = "status";
    public static final String TYPE_PEAK = "peak";
    /** 今日已用（v1.12.0）—— 数据来自本地账本 {@link SpendLedger}（接口没有这个字段）。 */
    public static final String TYPE_TODAY = "today";
    /**
     * 随机语句（对应上游 {@code type:"random"}）。
     *
     * <p>v1.12.0 按用户要求简化：**每一句等概率**（不再用权重；{@code w} 字段仍然解析/写回，
     * 只是为了跟上游 JSON 互通，抽取时不看它）。
     */
    public static final String TYPE_RANDOM = "random";

    /** 这个类型名是不是引擎认识的？（v1.12.8：给「两份清单必须同步」的自检用） */
    public static boolean isKnownType(String type) {
        if (type == null) return false;
        for (String t : TYPE_NAMES) {
            if (t.equals(type)) return true;
        }
        return false;
    }

    /**
     * 编辑器「➕ 添加模块」的可加清单：{显示名, 类型 id}。
     *
     * <p>**这是模块类型的唯一权威清单**（v1.12.9 起）：{@link #TYPE_NAMES} 由它派生，
     * 编辑器 UI 也直接用它 —— 不会再出现「引擎加了新类型、UI 里却加不出来」的漏改
     * （v1.12.0 真的踩过：加了「今日已用」但只改了 TYPE_NAMES）。
     *
     * <p>id 里带 {@code -} 的是**同一类型的变体入口**（例如 {@code peak-count} = 峰谷的「倒计时」样式），
     * 不算新类型。
     */
    public static final String[][] PALETTE = {
            {"文本", TYPE_TEXT},
            {"余额数值", TYPE_BALANCE},
            {"累计消费", TYPE_COST},
            {"今日已用", TYPE_TODAY},
            {"连接状态", TYPE_STATUS},
            {"峰谷时段", TYPE_PEAK},
            {"时段倒计时", TYPE_PEAK + "-count"},
            {"随机语句", TYPE_RANDOM},
    };
    /** 全部**真实**类型名（由 {@link #PALETTE} 派生：去掉变体入口、去重、保序）。 */
    public static final String[] TYPE_NAMES = deriveTypeNames(PALETTE);
    private static String[] deriveTypeNames(String[][] palette) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String[] entry : palette) {
            String id = entry[1];
            String base = id.contains("-") ? id.substring(0, id.indexOf('-')) : id;
            if (!out.contains(base)) out.add(base);
        }
        return out.toArray(new String[0]);
    }

    /** 中文说明，设置页显示用。 */
    public static String typeLabel(String type) {
        if (TYPE_TEXT.equals(type)) return "自定义文字";
        if (TYPE_BALANCE.equals(type)) return "余额";
        if (TYPE_COST.equals(type)) return "累计消费";
        if (TYPE_TODAY.equals(type)) return "今日已用";
        if (TYPE_STATUS.equals(type)) return "连接状态";
        if (TYPE_PEAK.equals(type)) return "峰谷/倒计时";
        if (TYPE_RANDOM.equals(type)) return "随机语句（带权重）";
        return "未知类型";
    }

    /** 上游有、本版**尚未支持**的类型（编辑器要如实告知，不能假装能用）。 */
    public static final String[] UNSUPPORTED_TYPES = {
            "session（对话名，Android 侧没有会话概念）",
            "quota / plan（额度/套餐）",
            "image / randimg（图片，需素材库）",
            "link（链接）",
    };

    // ---- 字段（名与上游一致） ----
    public String type = TYPE_TEXT;
    /** 行键：同值同排；**< 0 表示自带一行**（上游行为）。 */
    public int row = -1;
    /** 文本模块的正文（也可含占位符）。 */
    public String text = "";
    /** 整句文案模板：非空时**覆盖**内置类型文案（如 write「今日已用 {cost_ds}」）。 */
    public String tpl = "";
    /** 字号档 1–50（上游 size）。 */
    public int size = 7;
    public boolean bold = false;
    public boolean italic = false;
    /** 下划线（上游字段名是 ul）。 */
    public boolean ul = false;
    /** 纯色文字色，{@code #RRGGBB}；空 = 用泡泡正文色。 */
    public String color = "";
    /** 跑马灯渐变色方案名（一期仅保留，二期渲染）。 */
    public String rgb = "";
    /** 底色纯色（一期支持）。 */
    public String bg = "";
    /** 底色跑马灯方案名（一期仅保留）。 */
    public String bgRgb = "";
    public String fontFamily = "";
    /** session 模块的保留长度（本版未支持该类型，字段保留以便兼容上游 JSON）。 */
    public int len = 0;
    /** balance 模块可绑定的模型 id（本版单账号，字段保留）。 */
    public String modelId = "";

    // ---- 随机语句（v1.10.0，对应上游 type:"random" + lines:[{t,w,bold,size}]） ----

    /** 一句候选（字段名与上游一致：t=文字、w=权重、bold、size=0 表示跟随模块字号档）。 */
    public static final class Line {
        public String text = "";
        public int weight = 1;
        public boolean bold = false;
        public int size = 0;

        public Line() {}

        public Line(String text, int weight) {
            this.text = text;
            this.weight = weight;
        }

        public Line(String text, int weight, boolean bold, int size) {
            this.text = text;
            this.weight = weight;
            this.bold = bold;
            this.size = size;
        }

        public String describe() {
            return "\"" + text + "\"" + (size > 0 ? " 档" + size : "");
        }
    }

    /** 随机语句的备选（空 = 该模块没有内容）。 */
    public final java.util.List<Line> lines = new java.util.ArrayList<>();

    /** 本泡抽中的那一句（渲染期缓存，**不参与序列化**）。 */
    public transient Line picked;
    /** 上一次抽中的文字（避免连续两次一样，对应上游「不连续重复」的愿望）。 */
    public transient String lastPickedText;

    /**
     * 抽一句（v1.12.0：**等概率**，不看权重），并尽量避开与上一句相同
     * —— 对齐上游 {@code bubblePickLine}（whale-widget.js L13038）的「最多重试 6 次」，属尽力而为。
     */
    public Line pickLine(java.util.Random rnd) {
        if (lines.isEmpty()) return null;
        Line first = pickUniform(rnd);
        for (int i = 0; i < 6 && lines.size() > 1 && first != null
                && first.text != null && first.text.equals(lastPickedText); i++) {
            first = pickUniform(rnd);
        }
        lastPickedText = first == null ? null : first.text;
        picked = first;
        return first;
    }

    /**
     * 等概率抽一句（v1.12.0 按用户要求简化：**不再用权重**）。
     *
     * <p>{@code w} 字段仍会解析/写回（保持与上游 JSON 兼容），只是**不参与抽取**。
     */
    private Line pickUniform(java.util.Random rnd) {
        if (lines.isEmpty()) return null;
        java.util.Random r = rnd == null ? new java.util.Random() : rnd;
        return lines.get(Math.floorMod(r.nextInt(), lines.size()));
    }

    /** 随机语句的候选数量摘要（UI 用）。 */
    public String linesSummary() {
        if (lines.isEmpty()) return "（没有句子）";
        StringBuilder sb = new StringBuilder(lines.size() + " 句：");
        for (int i = 0; i < lines.size() && i < 3; i++) {
            sb.append(i > 0 ? " / " : " ").append(lines.get(i).describe());
        }
        if (lines.size() > 3) sb.append(" …");
        return sb.toString();
    }

    /** 上游出厂默认的「大字」组（w10 / 档22，取自已核实源码 L7366 起）。 */
    public static java.util.List<Line> presetLinesBig() {
        java.util.List<Line> l = new java.util.ArrayList<>();
        l.add(new Line("好模型...↓", 10, true, 22));
        l.add(new Line("好女孩...↓", 10, false, 22));
        l.add(new Line("哦鲸鲸...", 10, false, 22));
        return l;
    }

    /** 上游出厂的**全部语录**（大字组 3 句 + 小字组 10 句 = 13 句，逐条取自源码 L7366 起）。 */
    public static java.util.List<Line> presetLinesAll() {
        java.util.List<Line> l = new java.util.ArrayList<>();
        l.addAll(presetLinesBig());
        l.addAll(presetLinesSmall());
        return l;
    }

    /** 上游出厂默认的「小字」组（w3，取自已核实源码 L7366 起）。 */
    public static java.util.List<Line> presetLinesSmall() {
        java.util.List<Line> l = new java.util.ArrayList<>();
        l.add(new Line("难道说...", 3, false, 11));
        l.add(new Line("没吃饱喵", 3, false, 9));
        l.add(new Line("终于上当了！", 3, false, 0));
        l.add(new Line("不知道用户有什么用，先养着吧～", 3, false, 11));
        l.add(new Line("我...我...我也要挣钱吗？", 3, false, 0));
        l.add(new Line("我去吃饭啦！测完叫我", 3, false, 0));
        l.add(new Line("压力一只蓝色大肥鱼？！", 3, false, 0));
        l.add(new Line("DeepSleep...", 3, false, 11));
        l.add(new Line("坏了...用户彻底怒了！", 3, false, 0));
        l.add(new Line("你目录里的dsh是什么...大烧货吗...?", 3, false, 9));
        return l;
    }

    // ---- 峰谷模块专属 ----
    /**
     * 峰谷显示样式的**哨兵值**：{@code -1} = 跟随全局设置（设置 → Whale挂件 → 显示样式）。
     *
     * <p>为什么加这个：模块自己钉死样式后，用户在设置里改全局样式就会「看起来没反应」
     * （真实 bug，2026-10-04 用户报告并复现）。现在默认跟随全局，只有用户显式选过某个样式才钉死。
     */
    public static final int STYLE_FOLLOW_GLOBAL = -1;

    /** 峰谷显示样式；{@link #STYLE_FOLLOW_GLOBAL} = 跟随全局。 */
    public int peakStyle = STYLE_FOLLOW_GLOBAL;
    public String peakColor = "#e0433f";
    public String offColor = "#2fa24c";
    public String peakBg = "#fbe7e6";
    public String offBg = "#e4f3e7";
    public String peakRgb = "";
    public String offRgb = "";
    public String peakBgRgb = "";
    public String offBgRgb = "";

    // ------------------------------------------------------------------ 字号档

    /** 上游公式：档 1 → 40u，档 50 → 240u。 */
    public static float fontU(int level) {
        int n = Math.max(1, Math.min(50, Math.round(level)));
        return Math.round(40f + (n - 1) * 200f / 49f);
    }

    public float fontU() {
        // 随机语句抽中的那一句自带字号档时就以它为准（上游 lines[].size），否则用模块自己的档
        if (picked != null && picked.size > 0) return fontU(picked.size);
        return fontU(size);
    }

    // ------------------------------------------------------------------ 内容

    /** 内置类型的默认模板（上游：文本模块用自己的 text，数值模块用占位符）。 */
    public String defaultTemplate() {
        return defaultTemplate(PeakValley.STYLE_DEFAULT);
    }

    /**
     * 内置类型的默认模板。
     *
     * @param globalPeakStyle 全局「显示样式」；峰谷模块在**跟随全局**时用它决定出 {@code {status}} 还是 {@code {countdown}}
     */
    public String defaultTemplate(int globalPeakStyle) {
        if (tpl != null && !tpl.isEmpty()) return tpl;
        if (TYPE_TEXT.equals(type)) return text == null ? "" : text;
        if (TYPE_BALANCE.equals(type)) return "{balance_ds}";
        if (TYPE_COST.equals(type)) return "{cost_ds}";
        // 与上游 today 模块出厂模板逐字一致（上游：{ type:'today', tpl:'今日已用 {expense_ds}' }）
        if (TYPE_TODAY.equals(type)) return "今日已用 {expense_ds}";
        if (TYPE_STATUS.equals(type)) return "{status_text}";
        if (TYPE_PEAK.equals(type)) {
            return PeakValley.isCountStyle(effectivePeakStyle(globalPeakStyle)) ? "{countdown}" : "{status}";
        }
        return text == null ? "" : text;
    }

    /** 该模块当前应显示的文本（占位符已解析；峰谷样式 = 模块钉死值，或跟随 bubble 里的全局值）。 */
    public String contentOf(PetBubble bubble) {
        if (bubble == null) return "";
        // 随机语句：每次组装泡泡抽一句（v1.12.0 等概率 + 尽量不连续重复）
        if (TYPE_RANDOM.equals(type)) {
            Line l = pickLine(bubble.pickRandom == null
                    ? (bubble.pickRandom = new java.util.Random()) : bubble.pickRandom);
            if (l == null) return text == null ? "" : text;
            return bubble.resolve(l.text);
        }
        return bubble.resolve(defaultTemplate(bubble.peakStyleGlobal));
    }

    // ------------------------------------------------------------------ 颜色

    /** {@code #RGB}/{@code #RRGGBB} → 0xRRGGBB；空或非法返回 -1。 */
    public static int parseHex(String value) {
        if (value == null) return -1;
        String t = value.trim();
        if (t.isEmpty()) return -1;
        if (t.charAt(0) == '#') t = t.substring(1);
        if (t.length() == 3) {
            t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        }
        if (t.length() != 6) return -1;
        try {
            return (int) (Long.parseLong(t, 16) & 0xFFFFFFL);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 文字色：峰谷模块按当前峰/谷取色；其余用自身 color；都没有则回落到泡泡正文色。 */
    public int colorOr(int fallback, boolean peakNow) {
        if (TYPE_PEAK.equals(type)) {
            int c = parseHex(peakNow ? peakColor : offColor);
            if (c >= 0) return c;
        }
        int c = parseHex(color);
        return c >= 0 ? c : fallback;
    }

    /** 底色：峰谷模块按峰/谷取；其余用自身 bg；没有则 -1（不画底）。 */
    public int bgOr(boolean peakNow) {
        if (TYPE_PEAK.equals(type)) {
            int c = parseHex(peakNow ? peakBg : offBg);
            if (c >= 0) return c;
        }
        return parseHex(bg);
    }

    /**
     * 是否配了跑马灯（**配置层面**的判断，与当前峰/谷状态无关；日志与设置页用）。
     */
    public boolean hasMarquee() {
        if (!rgb.isEmpty() || !bgRgb.isEmpty()) return true;
        if (TYPE_PEAK.equals(type)) {
            return !peakRgb.isEmpty() || !offRgb.isEmpty()
                    || !peakBgRgb.isEmpty() || !offBgRgb.isEmpty();
        }
        return false;
    }

    /**
     * 跑马灯时长（毫秒）。**不参与序列化**：组装泡泡时为每个模块随机一次
     * （上游 {@code bubbleMarqueeDur() = round(1500 + random()*3000)}，每次渲染各随机）。
     * 定为「每个泡泡一次」而不是「每帧一次」，否则渐变会逐帧乱跳。
     */
    public long marqueeDurMs = 0;

    /** 夹取峰谷样式：{@code <0} 视为「跟随全局」，否则按 5 种样式夹取。 */
    public static int clampPeakStyle(int style) {
        return style < 0 ? STYLE_FOLLOW_GLOBAL : PeakValley.clampStyle(style);
    }

    /** 该模块**实际**生效的峰谷样式（跟随全局时会用传入的全局值）。 */
    public int effectivePeakStyle(int globalStyle) {
        return peakStyle < 0 ? PeakValley.clampStyle(globalStyle) : PeakValley.clampStyle(peakStyle);
    }

    public static String peakStyleLabel(int style) {
        return style < 0 ? "跟随全局「显示样式」" : PeakValley.styleDisplayName(style);
    }
    public int[] textSchemeColors(boolean peakNow) {
        String name = TYPE_PEAK.equals(type) ? (peakNow ? peakRgb : offRgb) : rgb;
        if (name == null || name.trim().isEmpty()) return null;
        return PetBubbleGradients.resolve(name);
    }

    /** 该模块当前的**底色**跑马灯配色（没配则 null）。 */
    public int[] bgSchemeColors(boolean peakNow) {
        String name = TYPE_PEAK.equals(type) ? (peakNow ? peakBgRgb : offBgRgb) : bgRgb;
        if (name == null || name.trim().isEmpty()) return null;
        return PetBubbleGradients.resolve(name);
    }

    /**
     * 给配了跑马灯的模块**随机一个时长**（上游 {@code bubbleMarqueeDur()} =
     * {@code round(1500 + random()*3000)}，每次渲染各随机一次）。
     *
     * <p>服务侧（组装泡泡时）与编辑器预览共用这一份实现，避免两处算法漂移。
     *
     * @return 带动画的模块个数（用于日志）
     */
    public static int assignMarqueeDurations(List<PetBubbleModule> modules) {
        if (modules == null) return 0;
        java.util.Random rnd = new java.util.Random();
        int count = 0;
        for (PetBubbleModule m : modules) {
            if (m != null && m.hasMarquee()) {
                m.marqueeDurMs = 1500 + rnd.nextInt(3001);
                count++;
            }
        }
        return count;
    }

    /** 当前状态下是否真的会跑跑马灯（渲染与帧率判断用）。 */
    public boolean marqueeActive(boolean peakNow) {
        return textSchemeColors(peakNow) != null || bgSchemeColors(peakNow) != null;
    }

    // ------------------------------------------------------------------ 颜色槽位（v1.8.0）

    /**
     * 颜色「槽位」表（用户 2026-10-05 提出：「文字跑马灯」不该单开一行 —— 应该并在颜色里）。
     *
     * <p>6 个槽位各自是**纯色 与 跑马灯 二选一**：设纯色就清掉该槽位的跑马灯，设跑马灯就清掉纯色。
     * 这样 UI 上一个槽位只占一行（点开是一个列表：色板 + 17 套渐变 + 清除/自定义），
     * 既少了一半的设置项，也不可能出现「纯色与渐变同时存在」这种渲染端要猜的状态。
     *
     * <p>字段名仍然与上游一致（{@code color/rgb}、{@code bg/bgRgb}、
     * {@code peakColor/peakRgb}、{@code offColor/offRgb}、{@code peakBg/peakBgRgb}、{@code offBg/offBgRgb}），
     * 所以内置 JSON 依旧可以互相粘贴。
     */
    public static final int SLOT_TEXT = 0;
    public static final int SLOT_BG = 1;
    public static final int SLOT_PEAK_TEXT = 2;
    public static final int SLOT_OFF_TEXT = 3;
    public static final int SLOT_PEAK_BG = 4;
    public static final int SLOT_OFF_BG = 5;

    public static String slotLabel(int slot) {
        switch (slot) {
            case SLOT_TEXT: return "文字";
            case SLOT_BG: return "底色";
            case SLOT_PEAK_TEXT: return "高峰文字";
            case SLOT_OFF_TEXT: return "空闲文字";
            case SLOT_PEAK_BG: return "高峰底色";
            case SLOT_OFF_BG: return "空闲底色";
            default: return "颜色";
        }
    }

    /** 该槽位的纯色值（空 = 未设）。 */
    public String plainOf(int slot) {
        switch (slot) {
            case SLOT_TEXT: return color == null ? "" : color;
            case SLOT_BG: return bg == null ? "" : bg;
            case SLOT_PEAK_TEXT: return peakColor == null ? "" : peakColor;
            case SLOT_OFF_TEXT: return offColor == null ? "" : offColor;
            case SLOT_PEAK_BG: return peakBg == null ? "" : peakBg;
            case SLOT_OFF_BG: return offBg == null ? "" : offBg;
            default: return "";
        }
    }

    /** 该槽位的跑马灯方案名（空 = 未设）。 */
    public String schemeOf(int slot) {
        switch (slot) {
            case SLOT_TEXT: return rgb == null ? "" : rgb;
            case SLOT_BG: return bgRgb == null ? "" : bgRgb;
            case SLOT_PEAK_TEXT: return peakRgb == null ? "" : peakRgb;
            case SLOT_OFF_TEXT: return offRgb == null ? "" : offRgb;
            case SLOT_PEAK_BG: return peakBgRgb == null ? "" : peakBgRgb;
            case SLOT_OFF_BG: return offBgRgb == null ? "" : offBgRgb;
            default: return "";
        }
    }

    /** 设纯色（非空时**清掉同槽位的跑马灯**）。 */
    public void setPlain(int slot, String hex) {
        String v = hex == null ? "" : hex.trim();
        switch (slot) {
            case SLOT_TEXT: color = v; if (!v.isEmpty()) rgb = ""; break;
            case SLOT_BG: bg = v; if (!v.isEmpty()) bgRgb = ""; break;
            case SLOT_PEAK_TEXT: peakColor = v; if (!v.isEmpty()) peakRgb = ""; break;
            case SLOT_OFF_TEXT: offColor = v; if (!v.isEmpty()) offRgb = ""; break;
            case SLOT_PEAK_BG: peakBg = v; if (!v.isEmpty()) peakBgRgb = ""; break;
            case SLOT_OFF_BG: offBg = v; if (!v.isEmpty()) offBgRgb = ""; break;
            default: break;
        }
    }

    /** 设跑马灯方案（非空时**清掉同槽位的纯色**）。 */
    public void setScheme(int slot, String name) {
        String v = name == null ? "" : name.trim();
        switch (slot) {
            case SLOT_TEXT: rgb = v; if (!v.isEmpty()) color = ""; break;
            case SLOT_BG: bgRgb = v; if (!v.isEmpty()) bg = ""; break;
            case SLOT_PEAK_TEXT: peakRgb = v; if (!v.isEmpty()) peakColor = ""; break;
            case SLOT_OFF_TEXT: offRgb = v; if (!v.isEmpty()) offColor = ""; break;
            case SLOT_PEAK_BG: peakBgRgb = v; if (!v.isEmpty()) peakBg = ""; break;
            case SLOT_OFF_BG: offBgRgb = v; if (!v.isEmpty()) offBg = ""; break;
            default: break;
        }
    }

    /** 清空该槽位（纯色与跑马灯都清 = 回到「跟随默认」）。 */
    public void clearSlot(int slot) {
        switch (slot) {
            case SLOT_TEXT: color = ""; rgb = ""; break;
            case SLOT_BG: bg = ""; bgRgb = ""; break;
            case SLOT_PEAK_TEXT: peakColor = ""; peakRgb = ""; break;
            case SLOT_OFF_TEXT: offColor = ""; offRgb = ""; break;
            case SLOT_PEAK_BG: peakBg = ""; peakBgRgb = ""; break;
            case SLOT_OFF_BG: offBg = ""; offBgRgb = ""; break;
            default: break;
        }
    }

    /** 该槽位当前值的可读摘要（UI 行标题用）。 */
    public String slotSummary(int slot) {
        String plain = plainOf(slot);
        String scheme = schemeOf(slot);
        if (!scheme.isEmpty()) return "跑马灯 " + scheme;
        if (!plain.isEmpty()) return plain;
        return "（未设）";
    }

    // ------------------------------------------------------------------ 渲染期缓存（v1.9.0 性能）

    /**
     * 跑马灯着色器缓存（由 {@link PetBubbleView} 在布局/刷新时填充）。
     *
     * <p>为什么放在模块上而不是视图里的一张表：旧实现**每帧**都要拼一个字符串 key 再查哈希表 ——
     * 那是每帧每模块一次 String 分配（90fps × N 个模块），在真机上比省下的着色器构造更贵。
     * 现在模块自己持有两个槽位（文字 / 底色）的着色器，**每帧零查找零分配**，
     * 循环相位只改 {@code LocalMatrix}，淡入淡出改用 {@code Paint.setAlpha()}。
     *
     * <p>布局变化（文案、字号、画布宽）时会被置空重建 —— 见 {@link PetBubbleView} 的 ensureLayout。
     */
    public transient android.graphics.LinearGradient textShader;
    public transient android.graphics.LinearGradient bgShader;
    /** 双倍铺开的配色数组（v1.9.0：每帧复用，避免每帧重建数组）。 */
    public transient int[] textPalette;
    public transient int[] bgPalette;

    // ------------------------------------------------------------------ 行分组

    /**
     * 按 {@code row} 把模块分到行里：同 row 值的模块排在同一行；
     * **不带 row（< 0）的模块各自独占一行**。行的顺序 = 首次出现顺序。
     *
     * <p>硬约束与上游一致：每行最多 {@link #MAX_PER_ROW} 个模块、每泡最多 {@link #MAX_ROWS} 行，
     * 超出的模块会被丢弃并写日志（不静默）。
     */
    public static final int MAX_PER_ROW = 6;
    public static final int MAX_ROWS = 6;

    public static List<List<PetBubbleModule>> groupRows(List<PetBubbleModule> modules) {
        Map<String, List<PetBubbleModule>> byKey = new LinkedHashMap<>();
        if (modules != null) {
            for (int i = 0; i < modules.size(); i++) {
                PetBubbleModule m = modules.get(i);
                if (m == null) continue;
                String key = m.row >= 0 ? ("r" + m.row) : ("auto" + i);
                List<PetBubbleModule> line = byKey.get(key);
                if (line == null) {
                    line = new ArrayList<>();
                    byKey.put(key, line);
                }
                if (line.size() >= MAX_PER_ROW) {
                    Log.write("模块：一行最多 " + MAX_PER_ROW + " 个，已丢弃多余的「"
                            + typeLabel(m.type) + "」");
                    continue;
                }
                line.add(m);
            }
        }
        List<List<PetBubbleModule>> rows = new ArrayList<>(byKey.values());
        if (rows.size() > MAX_ROWS) {
            Log.write("模块：一个泡泡最多 " + MAX_ROWS + " 行，已丢弃多余的行");
            return new ArrayList<>(rows.subList(0, MAX_ROWS));
        }
        return rows;
    }

    // ------------------------------------------------------------------ JSON

    public static PetBubbleModule fromJson(JSONObject o) {
        PetBubbleModule m = new PetBubbleModule();
        String t = o.optString("type", TYPE_TEXT).trim();
        // 上游旧别名 nextpeak ≡ 峰谷倒计时样式
        if ("nextpeak".equals(t)) {
            t = TYPE_PEAK;
            m.peakStyle = PeakValley.STYLE_COUNT;
        }
        m.type = t.isEmpty() ? TYPE_TEXT : t;
        m.row = o.has("row") ? o.optInt("row", -1) : -1;
        m.text = o.optString("text", "");
        m.tpl = o.optString("tpl", "");
        m.size = Math.max(1, Math.min(50, o.optInt("size", 7)));
        m.bold = o.optBoolean("bold", false);
        m.italic = o.optBoolean("italic", false);
        m.ul = o.optBoolean("ul", false);
        m.color = o.optString("color", "");
        m.rgb = o.optString("rgb", "");
        m.bg = o.optString("bg", "");
        m.bgRgb = o.optString("bgRgb", "");
        m.fontFamily = o.optString("fontFamily", "");
        m.len = Math.max(0, o.optInt("len", 0));
        m.modelId = o.optString("modelId", "");
        if (o.has("peakStyle")) m.peakStyle = clampPeakStyle(o.optInt("peakStyle", STYLE_FOLLOW_GLOBAL));
        m.peakColor = o.optString("peakColor", m.peakColor);
        m.offColor = o.optString("offColor", m.offColor);
        m.peakBg = o.optString("peakBg", m.peakBg);
        m.offBg = o.optString("offBg", m.offBg);
        m.peakRgb = o.optString("peakRgb", "");
        m.offRgb = o.optString("offRgb", "");
        m.peakBgRgb = o.optString("peakBgRgb", "");
        m.offBgRgb = o.optString("offBgRgb", "");
        // 随机语句的候选（上游 lines:[{t,w,bold,size}]）
        JSONArray lines = o.optJSONArray("lines");
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                JSONObject lo = lines.optJSONObject(i);
                if (lo == null) continue;
                Line ln = new Line();
                ln.text = lo.optString("t", "");
                ln.weight = Math.max(1, lo.optInt("w", 1));
                ln.bold = lo.optBoolean("bold", false);
                ln.size = Math.max(0, Math.min(50, lo.optInt("size", 0)));
                if (!ln.text.isEmpty()) m.lines.add(ln);
            }
        }
        return m;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type == null ? TYPE_TEXT : type);
            if (row >= 0) o.put("row", row);
            if (!text.isEmpty()) o.put("text", text);
            if (!tpl.isEmpty()) o.put("tpl", tpl);
            o.put("size", Math.max(1, Math.min(50, size)));
            if (bold) o.put("bold", true);
            if (italic) o.put("italic", true);
            if (ul) o.put("ul", true);
            if (!color.isEmpty()) o.put("color", color);
            if (!rgb.isEmpty()) o.put("rgb", rgb);
            if (!bg.isEmpty()) o.put("bg", bg);
            if (!bgRgb.isEmpty()) o.put("bgRgb", bgRgb);
            if (!fontFamily.isEmpty()) o.put("fontFamily", fontFamily);
            if (len > 0) o.put("len", len);
            if (!modelId.isEmpty()) o.put("modelId", modelId);
            // 随机语句的候选（上游 lines:[{t,w,bold,size}]，形状一致，可直接互粘）
            if (!lines.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (Line ln : lines) {
                    JSONObject lo = new JSONObject();
                    lo.put("t", ln.text);
                    if (Math.max(1, ln.weight) != 1) lo.put("w", Math.max(1, ln.weight));
                    if (ln.bold) lo.put("bold", true);
                    if (ln.size > 0) lo.put("size", ln.size);
                    arr.put(lo);
                }
                o.put("lines", arr);
            }
            if (TYPE_PEAK.equals(type)) {
                // 只有「钉死」的样式才写进 JSON；跟随全局（-1）不写，保持与上游 JSON 形状接近
                if (peakStyle >= 0) o.put("peakStyle", PeakValley.clampStyle(peakStyle));
                o.put("peakColor", peakColor);
                o.put("offColor", offColor);
                o.put("peakBg", peakBg);
                o.put("offBg", offBg);
                if (!peakRgb.isEmpty()) o.put("peakRgb", peakRgb);
                if (!offRgb.isEmpty()) o.put("offRgb", offRgb);
                if (!peakBgRgb.isEmpty()) o.put("peakBgRgb", peakBgRgb);
                if (!offBgRgb.isEmpty()) o.put("offBgRgb", offBgRgb);
            }
        } catch (Exception e) {
            Log.write("模块序列化失败: " + e);
        }
        return o;
    }

    /** JSON 数组字符串 → 模块列表；解析失败返回**空列表**并写日志（不抛异常、不假装成功）。 */
    public static List<PetBubbleModule> listFromJson(String json) {
        List<PetBubbleModule> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(fromJson(o));
            }
        } catch (Exception e) {
            Log.write("模块 JSON 解析失败（将按空处理）: " + e);
            return new ArrayList<>();
        }
        return list;
    }

    public static String listToJson(List<PetBubbleModule> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (PetBubbleModule m : list) {
                if (m != null) arr.put(m.toJson());
            }
        }
        return arr.toString();
    }

    /**
     * 「经典四行」的等价模块预设 —— 用来一键把现有观感转成模块。
     *
     * <p>字号档是按上游公式反推的：现有常量 label 66u≈档 7、amount 128u≈档 23、
     * hint 56u≈档 5、峰谷行 64u≈档 7。
     * 内容用占位符表达，因此升级后**与 v1.5.1 的观感一致**。
     */
    public static List<PetBubbleModule> defaultPreset() {
        List<PetBubbleModule> list = new ArrayList<>();
        PetBubbleModule label = new PetBubbleModule();
        label.type = TYPE_TEXT;
        label.text = WhaleBubbleSpec.DEFAULT_LABEL;
        label.size = 7;
        label.bold = true;
        list.add(label);

        PetBubbleModule amount = new PetBubbleModule();
        amount.type = TYPE_BALANCE;
        amount.size = 23;
        amount.bold = true;
        list.add(amount);

        PetBubbleModule status = new PetBubbleModule();
        status.type = TYPE_STATUS;
        status.size = 5;
        list.add(status);

        PetBubbleModule peak = new PetBubbleModule();
        peak.type = TYPE_PEAK;
        peak.size = 7;
        peak.bold = true;
        // peakStyle 保持默认值「跟随全局」——这样用户在设置里改「显示样式」就能真的看到变化
        list.add(peak);
        return list;
    }

    /**
     * 按类型造一个模块（编辑器「新增模块」用），给上合理的默认值，
     * 避免新增出来是个「看不出是什么」的空模块。
     */
    public static PetBubbleModule newOf(String type) {
        PetBubbleModule m = new PetBubbleModule();
        String t = type == null ? TYPE_TEXT : type;
        m.type = t;
        if (TYPE_TEXT.equals(t)) {
            m.text = "文字";
            m.size = 7;
        } else if (TYPE_BALANCE.equals(t)) {
            m.size = 23;
            m.bold = true;
        } else if (TYPE_COST.equals(t)) {
            m.size = 23;
            m.bold = true;
            m.color = "#e0433f";
        } else if (TYPE_TODAY.equals(t)) {
            m.size = 23;
            m.bold = true;
        } else if (TYPE_STATUS.equals(t)) {
            m.size = 5;
        } else if (TYPE_PEAK.equals(t)) {
            m.size = 7;
            m.bold = true;
        } else if (TYPE_RANDOM.equals(t)) {
            // 从调色板新增的随机语句模块：直接带上游出厂文案（大字组），点 ✎ 就能改
            m.size = 22;
            m.bold = true;
            m.lines.addAll(presetLinesAll());          // 全套 13 句上游语录
        }
        return m;
    }

    /** 给日志/设置页用的一行摘要。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(typeLabel(type));
        sb.append("·档").append(Math.max(1, Math.min(50, size)));
        sb.append("(").append(Math.round(fontU())).append("u)");
        if (row >= 0) sb.append("·行").append(row);
        else sb.append("·独占行");
        if (bold) sb.append("·粗");
        if (italic) sb.append("·斜");
        if (ul) sb.append("·下划线");
        if (!color.isEmpty()) sb.append("·色").append(color);
        if (!bg.isEmpty()) sb.append("·底").append(bg);
        if (hasMarquee()) {
            sb.append("·跑马灯");
            String t = TYPE_PEAK.equals(type) ? (peakRgb + "/" + offRgb) : rgb;
            String b = TYPE_PEAK.equals(type) ? (peakBgRgb + "/" + offBgRgb) : bgRgb;
            if (!t.isEmpty()) sb.append("字=").append(t);
            if (!b.isEmpty()) sb.append("底=").append(b);
            if (marqueeDurMs > 0) sb.append("·").append(marqueeDurMs).append("ms");
        }
        if (TYPE_PEAK.equals(type)) sb.append("·").append(peakStyleLabel(peakStyle));
        if (TYPE_TEXT.equals(type)) sb.append("·\"").append(text).append("\"");
        return String.format(Locale.US, "%s", sb.toString());
    }
}