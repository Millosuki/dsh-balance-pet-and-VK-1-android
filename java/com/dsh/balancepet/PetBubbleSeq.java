package com.dsh.balancepet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 泡泡的「点击序列」（三期）。对应上游的 {@code bubbleSeq} / {@code bubbleNext} / {@code whaleClick}。
 *
 * <p>上游语义（**逐条从源码核实**，行号标出）：
 * <ul>
 *   <li><b>点角色</b>（{@code whaleClick}，L13646–13676）：
 *     <ul>
 *       <li>当前无泡泡 → 开新轮：{@code roundOn=true; seqIdx=0; showNext()}</li>
 *       <li>「点按角色推进泡泡队列」（{@code tapAdvance}）**开** → 等价于点泡泡（往后推进一项）</li>
 *       <li>**关**（默认）时：正显示第 1 项 → **只重置留存计时**（续时，不换内容）；
 *           第 2 项及以后 → **回到序列开头**（{@code seqIdx=0} 再 showNext）</li>
 *     </ul>
 *   </li>
 *   <li><b>点泡泡</b>（{@code bubbleNext}，L13680）：还有下一项 → 推进；已是最后一项 → 收起</li>
 *   <li><b>收起后</b>（{@code hideBubble}）：{@code seqIdx=0} → 下次从第 1 项开始</li>
 *   <li><b>并列 choice</b>：每轮按权重抽一个，{@code weight = max(1, round(w || 1))}（{@code bubbleChoiceWeight}，L7655）</li>
 *   <li>单项文案按 {@code kind} 渲染：{@code random} / {@code custom}（带 modules）/ 其余走默认页
 *       （{@code bubbleShowSeqNext}，L12430–12446）</li>
 * </ul>
 *
 * <p>持久化形状与上游一致：{@code {v:1, items:[...], tapAdvance:bool}}
 * （上游 {@code saveBubbleCfg}，L8222），因此**可以直接粘贴原插件的序列 JSON**。
 * 上游还有一个 {@code lib}（模块库）字段，本版不使用但**原样保留**，避免覆盖用户配置时丢数据。
 *
 * <p>⚠️ 本版未支持 {@code random}（随机语句）项：遇到时**回落到默认页**并写日志，不假装支持。
 */
public final class PetBubbleSeq {

    public static final String KIND_NORMAL = "normal";
    public static final String KIND_CUSTOM = "custom";
    public static final String KIND_CHOICE = "choice";
    public static final String KIND_RANDOM = "random";

    private PetBubbleSeq() {}

    /** 并列选项：{@code w} 是权重，{@code item} 是命中后要显示的那一项。 */
    public static final class Option {
        public int w = 1;
        public Item item;

        public Option() {}

        public Option(int w, Item item) {
            this.w = w;
            this.item = item;
        }
    }

    /** 序列里的一项。 */
    public static final class Item {
        public String kind = KIND_NORMAL;
        /** {@code custom} 项的内容模块。 */
        public final List<PetBubbleModule> modules = new ArrayList<>();
        /** {@code choice} 项的候选。 */
        public final List<Option> options = new ArrayList<>();
        /** 该项的自动收起时长（毫秒）；0 = 用默认。 */
        public int ttlMs = 0;

        public boolean isChoice() {
            return KIND_CHOICE.equals(kind) && !options.isEmpty();
        }

        public String describe() {
            if (isChoice()) {
                StringBuilder sb = new StringBuilder("并列(");
                sb.append(options.size()).append("选1)");
                for (int i = 0; i < options.size(); i++) {
                    Option o = options.get(i);
                    sb.append(i > 0 ? " / " : " ").append("w=").append(weightOf(o));
                }
                return sb.toString();
            }
            if (KIND_CUSTOM.equals(kind)) {
                return "自定义(" + modules.size() + " 模块)";
            }
            if (KIND_RANDOM.equals(kind)) {
                return "随机语句（本版未支持 → 当默认页处理）";
            }
            return "默认页（余额）";
        }
    }

    /** 权重：与上游 {@code bubbleChoiceWeight} 完全一致（{@code max(1, round(w || 1))}）。 */
    public static int weightOf(Option o) {
        if (o == null) return 1;
        double raw = o.w;
        long rounded = Math.round(raw);
        if (rounded < 1) rounded = 1;
        return (int) rounded;
    }

    /**
     * 按权重抽一个候选（与上游 {@code bubblePickChoiceStep}，L12420–12434 同算法：
     * {@code r = random()*total} 后累加权重，取第一个使 {@code r < acc} 的项）。
     */
    public static Item pick(Item step, Random rnd) {
        if (step == null) return null;
        if (!step.isChoice()) return step;
        int total = 0;
        for (Option o : step.options) total += weightOf(o);
        if (total <= 0) return null;
        double r = rnd.nextDouble() * total;
        int acc = 0;
        for (Option o : step.options) {
            acc += weightOf(o);
            if (r < acc) return o.item;
        }
        Option last = step.options.get(step.options.size() - 1);
        return last == null ? null : last.item;
    }

    // ------------------------------------------------------------------ JSON

    public static Item itemFromJson(JSONObject o) {
        Item it = new Item();
        it.kind = o.optString("kind", KIND_NORMAL);
        if (o.has("ttlMs")) it.ttlMs = Math.max(0, o.optInt("ttlMs", 0));
        JSONArray mods = o.optJSONArray("modules");
        if (mods != null) {
            for (int i = 0; i < mods.length(); i++) {
                JSONObject mo = mods.optJSONObject(i);
                if (mo != null) it.modules.add(PetBubbleModule.fromJson(mo));
            }
        }
        JSONArray opts = o.optJSONArray("options");
        if (opts != null) {
            for (int i = 0; i < opts.length(); i++) {
                JSONObject oo = opts.optJSONObject(i);
                if (oo == null) continue;
                Option op = new Option();
                op.w = oo.optInt("w", 1);
                JSONObject io = oo.optJSONObject("item");
                op.item = io == null ? null : itemFromJson(io);
                it.options.add(op);
            }
        }
        return it;
    }

    public static JSONObject itemToJson(Item it) {
        JSONObject o = new JSONObject();
        try {
            o.put("kind", it.kind == null ? KIND_NORMAL : it.kind);
            if (it.ttlMs > 0) o.put("ttlMs", it.ttlMs);
            if (!it.modules.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (PetBubbleModule m : it.modules) arr.put(m.toJson());
                o.put("modules", arr);
            }
            if (!it.options.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (Option op : it.options) {
                    JSONObject oo = new JSONObject();
                    oo.put("w", op.w);
                    if (op.item != null) oo.put("item", itemToJson(op.item));
                    arr.put(oo);
                }
                o.put("options", arr);
            }
        } catch (Exception e) {
            Log.write("序列项序列化失败: " + e);
        }
        return o;
    }

    /** 解析整个序列配置；解析失败返回**空序列**并写日志（不抛异常、不假装成功）。 */
    public static PetBubbleSeq fromJson(String json) {
        PetBubbleSeq seq = new PetBubbleSeq();
        if (json == null || json.trim().isEmpty()) return seq;
        try {
            JSONObject root = new JSONObject(json);
            seq.version = root.optInt("v", 1);
            seq.tapAdvance = root.optBoolean("tapAdvance", false);
            seq.libRaw = root.opt("lib");
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject io = items.optJSONObject(i);
                    if (io != null) seq.items.add(itemFromJson(io));
                }
            }
        } catch (Exception e) {
            Log.write("序列 JSON 解析失败（按空序列处理）: " + e);
            PetBubbleSeq empty = new PetBubbleSeq();
            empty.parseFailed = true;
            return empty;
        }
        return seq;
    }

    public String toJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("v", version);
            JSONArray arr = new JSONArray();
            for (Item it : items) arr.put(itemToJson(it));
            root.put("items", arr);
            root.put("tapAdvance", tapAdvance);
            if (libRaw != null) {
                // 按原类型回写（上游 lib 是数组；绝不能变成字符串，否则用户配置被改坏）
                root.put("lib", libRaw);
            }
        } catch (Exception e) {
            Log.write("序列序列化失败: " + e);
        }
        return root.toString();
    }

    // ------------------------------------------------------------------ 默认序列

    /**
     * 内置默认序列（空配置时使用）。
     *
     * <p>上游的出厂默认是「第 1 泡 = 余额页，第 2 泡 = 并列候选（内容是随机语句）」。
     * 本版还不支持随机语句，所以第 2 项改成**并列二选一**：
     * 峰谷倒计时页（w=2）与消费页（w=1）—— 结构与上游一致（都是 {@code choice} + 权重），
     * 只是候选内容换成了我们支持的类型。**这是有意偏离，已在此声明。**
     */

    /**
     * 推荐结构（v1.11.0）：**三次点击、三次不同内容**，且**第 2 项沿用用户自己的并列候选**。
     *
     * <pre>
     * 第 1 个（点桌宠） 余额页（经典四行）
     * 第 2 个（点泡泡） 并列候选：峰谷倒计时页(权重 2) / 累计消费页(权重 1)   ← 每轮按权重抽一个
     * 第 3 个（再点泡泡）随机文字页（权重 w10 的大字组，预设文案取自上游源码）
     * </pre>
     *
     * <p>为什么这么排（用户 2026-10-05 截图纠正）：他的第 2 项本来就是「并列候选」（抽中的是峰谷倒计时页），
     * 而那段**文字**应该在**第 3 个**；之前我把默认写成了「②文字页」，编辑器又拿它当占位显示，
     * 于是预览与真机对不上。
     */
    public static PetBubbleSeq recommendedSeq() {
        PetBubbleSeq seq = new PetBubbleSeq();
        seq.tapAdvance = false;

        // 第 1 个：余额页 → 走「经典四行 + 峰谷行」（kind=normal）
        Item first = new Item();
        first.kind = KIND_NORMAL;
        seq.items.add(first);

        // 第 2 个：并列候选（权重 2:1）
        Item choice = new Item();
        choice.kind = KIND_CHOICE;
        choice.options.add(new Option(2, peakPage()));
        choice.options.add(new Option(1, costPage()));
        seq.items.add(choice);

        // 第 3 个：随机文字页（v1.12.0：带上游**全部 13 句**语录，每句等概率）
        seq.items.add(randomItem(PetBubbleModule.presetLinesAll()));
        return seq;
    }

    /** 内置默认序列 = 推荐结构（新装用户与「恢复默认」都用它）。 */
    public static PetBubbleSeq defaultSeq() {
        return recommendedSeq();
    }

    /** 并列候选之一：峰谷倒计时页（大字倒计时 + 峰谷状态行）。 */
    private static Item peakPage() {
        Item it = new Item();
        it.kind = KIND_CUSTOM;
        PetBubbleModule title = PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT);
        title.text = WhaleBubbleSpec.DEFAULT_LABEL;
        title.size = 7;
        title.bold = true;
        it.modules.add(title);
        PetBubbleModule count = PetBubbleModule.newOf(PetBubbleModule.TYPE_PEAK);
        count.peakStyle = PeakValley.STYLE_COUNT;
        count.size = 23;
        count.bold = true;
        count.ul = true;
        it.modules.add(count);
        PetBubbleModule word = PetBubbleModule.newOf(PetBubbleModule.TYPE_PEAK);
        word.size = 7;
        word.bold = true;
        it.modules.add(word);
        return it;
    }

    /** 并列候选之二：累计消费页。 */
    private static Item costPage() {
        Item it = new Item();
        it.kind = KIND_CUSTOM;
        PetBubbleModule title = PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT);
        title.text = "累计消费";
        title.size = 7;
        title.bold = true;
        it.modules.add(title);
        PetBubbleModule cost = PetBubbleModule.newOf(PetBubbleModule.TYPE_COST);
        cost.size = 23;
        cost.bold = true;
        cost.color = "#e0433f";
        it.modules.add(cost);
        return it;
    }

    /** 造一个「随机语句」页（带权重；文案表取自上游源码）。 */
    public static Item randomItem(java.util.List<PetBubbleModule.Line> lines) {
        Item it = new Item();
        it.kind = KIND_CUSTOM;
        PetBubbleModule m = PetBubbleModule.newOf(PetBubbleModule.TYPE_RANDOM);
        m.lines.clear();   // newOf 自带预设，先清空再用传入的句子（否则会叠一份）
        if (lines != null && !lines.isEmpty()) m.lines.addAll(lines);
        it.modules.add(m);
        return it;
    }

    /** 造一个「一句文字」的自定义页（默认序列与「新增点击项」共用）。 */
    public static Item textItem(String text, int size) {
        Item it = new Item();
        it.kind = KIND_CUSTOM;
        PetBubbleModule m = PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT);
        m.text = text == null ? "" : text;
        m.size = Math.max(1, Math.min(50, size));
        m.bold = true;
        it.modules.add(m);
        return it;
    }

    // ------------------------------------------------------------------ 字段

    /** 序列项。 */
    public final List<Item> items = new ArrayList<>();
    /** 「点按角色推进泡泡队列」（上游 v727 加的开关）。 */
    public boolean tapAdvance = false;

    /**
     * 改写 JSON 里的 {@code tapAdvance} 并保留其它字段（设置页开关用）。
     * 解析失败时**原样返回**，不覆盖用户配置。
     */
    public static String withTapAdvance(String json, boolean value) {
        PetBubbleSeq seq = fromJson(json);
        if (seq.parseFailed) return json;
        seq.tapAdvance = value;
        return seq.toJson();
    }

    /** 读取 JSON 里的 {@code tapAdvance}（解析失败/空 → false）。 */
    public static boolean tapAdvanceOf(String json) {
        return fromJson(json).tapAdvance;
    }

    // ------------------------------------------------------------------ 编辑器用的纯逻辑（可单测）

    /** 上移一位；到头/越界返回 false。 */
    public static <T> boolean moveUp(java.util.List<T> list, int idx) {
        if (list == null || idx <= 0 || idx >= list.size()) return false;
        java.util.Collections.swap(list, idx, idx - 1);
        return true;
    }

    /** 下移一位；到尾/越界返回 false。 */
    public static <T> boolean moveDown(java.util.List<T> list, int idx) {
        if (list == null || idx < 0 || idx >= list.size() - 1) return false;
        java.util.Collections.swap(list, idx, idx + 1);
        return true;
    }

    /** 删除一项；越界返回 null。 */
    public static <T> T removeAt(java.util.List<T> list, int idx) {
        if (list == null || idx < 0 || idx >= list.size()) return null;
        return list.remove(idx);
    }

    /** 按类型造一个空序列项（编辑器「新增」用）。 */
    public static Item newItem(String kind) {
        Item it = new Item();
        it.kind = kind == null ? KIND_NORMAL : kind;
        if (KIND_CUSTOM.equals(it.kind)) {
            // 给一个能看的东西，避免新增后是空白泡泡
            it.modules.add(PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT));
        } else if (KIND_CHOICE.equals(it.kind)) {
            Item a = new Item();
            a.kind = KIND_NORMAL;
            Item b = new Item();
            b.kind = KIND_NORMAL;
            it.options.add(new Option(1, a));
            it.options.add(new Option(1, b));
        }
        return it;
    }
    public int version = 1;
    /** 上游的模块库字段（通常是 JSONArray）。本版不用，但**按原类型保留**，避免回写时改类型丢数据。 */
    public Object libRaw = null;
    /** 上次解析是否失败（供设置页如实提示）。 */
    public boolean parseFailed = false;

    public int size() {
        return items.size();
    }

    /** 一行摘要（日志/设置页用）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(items.size()).append(" 项 · 点角色")
                .append(tapAdvance ? "推进" : "回开头（默认）");
        for (int i = 0; i < items.size(); i++) {
            sb.append(" ｜ ").append(i + 1).append(". ").append(items.get(i).describe());
        }
        return sb.toString();
    }
}