package com.dsh.balancepet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块化编辑器（v2）的**纯逻辑内核**：行 / 模块的增删改排、拖放落点判定、序列化。
 *
 * <p>为什么单独抽出来：编辑器界面（{@link PetBubbleEditorActivity}）在真机上**我点不了**
 * （ColorOS 拦 {@code am start}、也无法注入真实触摸），所以「拖拽排序 / 跨行并排 / 插入位置 /
 * 行内与行的上限」这些语义必须能**离线断言**（{@link SelfTests}），并且能被脚本动作
 * （{@code ACTION_TEST_EDIT}）驱动。本类不依赖 Context / View，也就没有「只能在真机上碰运气」的分支。
 *
 * <p>语义与上游 {@code whale-widget.js} 一致（行号见另一篇记忆《【dshpet 搬运清单】…》）：
 * <ul>
 *   <li>行 = 一组**并排**的模块；行内顺序 = 左右顺序；行序 = 上下顺序</li>
 *   <li>硬约束：每行 ≤ {@value PetBubbleModule#MAX_PER_ROW} 个模块、每泡 ≤ {@value PetBubbleModule#MAX_ROWS} 行</li>
 *   <li>落点四态（上游触摸端自研拖拽同款）：行**上边缘**=插到该行之前 / 行**下边缘**=插到该行之后 /
 *       行内**左半**=并入该行左侧 / **右半**=并入该行右侧</li>
 * </ul>
 *
 * <p>⚠️ 如实说明：本类实现的是「拖放语义」，**不含手势**（长按 400ms、跟手、落点高亮在
 * {@link PetDragListLayout} 里）。手势只能由用户亲测。
 */
public final class PetBubbleEditModel {

    private PetBubbleEditModel() {}

    // ---------------------------------------------------------------- 落点四态

    public static final int DROP_BEFORE = 0;
    public static final int DROP_AFTER = 1;
    public static final int DROP_PAIR_LEFT = 2;
    public static final int DROP_PAIR_RIGHT = 3;

    /** 行的上 / 下边缘带宽（占行高的比例）：落在这条带里 =「另起一行」；中间 =「并入此行」。 */
    public static final float EDGE_BAND = 0.28f;

    /** 「被拖的是整行手柄 ⠿」时用的列号。 */
    public static final int HANDLE = -1;

    public static String dropZoneLabel(int zone) {
        if (zone == DROP_BEFORE) return "插到这一行之前";
        if (zone == DROP_AFTER) return "插到这一行之后";
        if (zone == DROP_PAIR_LEFT) return "并入这一行（放到左边）";
        if (zone == DROP_PAIR_RIGHT) return "并入这一行（放到右边）";
        return "无效落点";
    }

    /**
     * 落点判定（**纯函数**，编辑器与自检共用同一份逻辑）。
     *
     * @param yInRow 手指在行内的纵坐标（px）
     * @param rowH   行高（px）
     * @param xInRow 手指在行内的横坐标（px）
     * @param rowW   行宽（px）
     */
    public static int dropZone(float yInRow, float rowH, float xInRow, float rowW) {
        float rh = Math.max(1f, rowH);
        float r = yInRow / rh;
        if (r < EDGE_BAND) return DROP_BEFORE;
        if (r > 1f - EDGE_BAND) return DROP_AFTER;
        return (xInRow / Math.max(1f, rowW)) < 0.5f ? DROP_PAIR_LEFT : DROP_PAIR_RIGHT;
    }

    // ---------------------------------------------------------------- 行 ↔ 模块

    /**
     * 把模块列表按 {@code row} 分组到行里。
     *
     * <p>与 {@link PetBubbleModule#groupRows(List)} 的**唯一区别**：这里**不丢东西**
     * （编辑器必须原样显示用户已有的配置，哪怕它超过上限）。分组的键与顺序规则完全一致：
     * {@code row >= 0} → 同号同排；{@code row < 0} → 自己独占一行（键里带模块下标）。
     */
    public static List<List<PetBubbleModule>> rowsOf(List<PetBubbleModule> modules) {
        List<List<PetBubbleModule>> rows = new ArrayList<>();
        if (modules == null) return rows;
        Map<String, List<PetBubbleModule>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < modules.size(); i++) {
            PetBubbleModule m = modules.get(i);
            if (m == null) continue;
            String key = m.row >= 0 ? ("r" + m.row) : ("auto" + i);
            List<PetBubbleModule> line = byKey.get(key);
            if (line == null) {
                line = new ArrayList<>();
                byKey.put(key, line);
            }
            line.add(m);
        }
        rows.addAll(byKey.values());
        return rows;
    }

    /**
     * 行 → 模块列表（保存用）：按行序展开；**多模块的行写显式 {@code row} 号**（同号同排），
     * 单模块的行保持 {@code -1}（「独占一行」），这样 JSON 不会平白多出一堆 {@code row} 键。
     *
     * <p>行的顺序由「首次出现顺序」决定（渲染端 {@code groupRows} 用的是 LinkedHashMap），
     * 因此只要按行序展开，行序就一定是用户看到的那个顺序。
     */
    public static List<PetBubbleModule> toModules(List<List<PetBubbleModule>> rows) {
        List<PetBubbleModule> out = new ArrayList<>();
        if (rows == null) return out;
        for (int i = 0; i < rows.size(); i++) {
            List<PetBubbleModule> row = rows.get(i);
            if (row == null || row.isEmpty()) continue;
            boolean multi = row.size() > 1;
            for (PetBubbleModule m : row) {
                if (m == null) continue;
                m.row = multi ? i : -1;
                out.add(m);
            }
        }
        return out;
    }

    /** 解析 JSON → 行（解析不了返回**空行**，调用方自行决定是否使用默认值）。 */
    public static List<List<PetBubbleModule>> rowsFromJson(String json) {
        return rowsOf(PetBubbleModule.listFromJson(json));
    }

    /** 行 → JSON 字符串（编辑器保存时写进 state.json 的就是它）。 */
    public static String rowsToJson(List<List<PetBubbleModule>> rows) {
        return PetBubbleModule.listToJson(toModules(rows));
    }

    /** 一行摘要（日志用）：{@code 行1[文本"x"+余额] | 行2[余额] …} */
    public static String describe(List<List<PetBubbleModule>> rows) {
        StringBuilder sb = new StringBuilder();
        if (rows == null || rows.isEmpty()) return "（空）";
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(" ｜ ");
            List<PetBubbleModule> row = rows.get(i);
            sb.append("行").append(i + 1).append('[');
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) sb.append('+');
                PetBubbleModule m = row.get(j);
                sb.append(m.type);
                if (PetBubbleModule.TYPE_TEXT.equals(m.type)) sb.append('"').append(m.text).append('"');
            }
            sb.append(']');
        }
        return String.format(java.util.Locale.US, "%s（%d 行 / %d 模块）",
                sb.toString(), rows.size(), count(rows));
    }

    public static int count(List<List<PetBubbleModule>> rows) {
        int n = 0;
        if (rows == null) return 0;
        for (List<PetBubbleModule> row : rows) if (row != null) n += row.size();
        return n;
    }

    /**
     * 结构签名：把「行分组 + 行内顺序 + 每个模块的类型/文本」压成一个字符串。
     *
     * <p>用途：拖放后判断**是否真的动了**。{@link #applyDrop} 说「执行成功」但结果可能与原来完全一样
     * （例如把某行拖到它自己上方一行的「之后」），那种情况不该告诉用户「已移动」。
     */
    public static String signature(List<List<PetBubbleModule>> rows) {
        StringBuilder sb = new StringBuilder();
        if (rows == null) return "";
        for (List<PetBubbleModule> row : rows) {
            sb.append('[');
            if (row != null) {
                for (PetBubbleModule m : row) {
                    if (m == null) continue;
                    sb.append(m.type).append(':').append(m.text).append(':')
                            .append(m.size).append(':').append(m.color).append(':')
                            .append(m.bg).append(':').append(m.rgb).append(':').append(m.bgRgb)
                            .append(',');
                }
            }
            sb.append(']');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 增删

    /**
     * 在指定行**末尾**追加一个模块；行内已满（{@value PetBubbleModule#MAX_PER_ROW}）返回 false
     * 且**不改动任何数据**。
     */
    public static boolean addToRow(List<List<PetBubbleModule>> rows, int rowIdx, PetBubbleModule m) {
        if (rows == null || m == null) return false;
        if (rowIdx < 0 || rowIdx >= rows.size()) return false;
        List<PetBubbleModule> row = rows.get(rowIdx);
        if (row.size() >= PetBubbleModule.MAX_PER_ROW) return false;
        m.row = -1;              // 具体行号由 toModules() 统一归一化
        row.add(m);
        return true;
    }

    /**
     * 追加一个**新行**（只含一个模块）。
     *
     * @param atIndex 插入到第几行之前；{@code <0} 或超出范围 = 追加到末尾
     * @return 新行的下标；超过最大行数（{@value PetBubbleModule#MAX_ROWS}）返回 -1（不做改动）
     */
    public static int addRow(List<List<PetBubbleModule>> rows, PetBubbleModule m, int atIndex) {
        if (rows == null || m == null) return -1;
        if (rows.size() >= PetBubbleModule.MAX_ROWS) return -1;
        int at = (atIndex < 0 || atIndex > rows.size()) ? rows.size() : atIndex;
        m.row = -1;
        List<PetBubbleModule> row = new ArrayList<>();
        row.add(m);
        rows.add(at, row);
        return at;
    }

    /** 删除一个模块；**删空的行会被移除**。返回被删的模块（越界返回 null）。 */
    public static PetBubbleModule removeModule(List<List<PetBubbleModule>> rows, int rowIdx, int col) {
        if (rows == null || rowIdx < 0 || rowIdx >= rows.size()) return null;
        List<PetBubbleModule> row = rows.get(rowIdx);
        if (col < 0 || col >= row.size()) return null;
        PetBubbleModule m = row.remove(col);
        if (row.isEmpty()) rows.remove(rowIdx);
        return m;
    }

    /** 复制一个模块（编辑器「＋」里选「复制这一行」用得到；纯数据操作，便于自检）。 */
    public static PetBubbleModule copyOf(PetBubbleModule m) {
        if (m == null) return null;
        return PetBubbleModule.fromJson(m.toJson());
    }

    // ---------------------------------------------------------------- 移动

    /** 整行上下移动；越界或原地不动返回 false。 */
    public static boolean moveRow(List<List<PetBubbleModule>> rows, int from, int to) {
        if (rows == null) return false;
        if (from < 0 || from >= rows.size()) return false;
        if (to < 0 || to >= rows.size() || to == from) return false;
        List<PetBubbleModule> r = rows.remove(from);
        rows.add(to, r);
        return true;
    }

    /**
     * 移动**单个模块**到「目标行的第 {@code insertCol} 个位置之前」。
     *
     * <p>{@code insertCol} 按**行内原始下标**理解（即「插到第 k 个元素前面」）；
     * 同行内往后拖时会自动修正下标，所以调用方不需要自己算。
     * 目标行已满（且不是同一行内的重排）时返回 false 且不改动数据。
     */
    public static boolean moveModuleTo(List<List<PetBubbleModule>> rows,
                                       int fromRow, int fromCol, int toRow, int insertCol) {
        if (rows == null) return false;
        if (fromRow < 0 || fromRow >= rows.size()) return false;
        List<PetBubbleModule> src = rows.get(fromRow);
        if (fromCol < 0 || fromCol >= src.size()) return false;
        if (toRow < 0 || toRow > rows.size()) return false;

        boolean sameRow = toRow == fromRow;
        if (!sameRow) {
            if (toRow >= rows.size()) return false;
            if (rows.get(toRow).size() >= PetBubbleModule.MAX_PER_ROW) return false;   // 目标行已满
        }
        PetBubbleModule m = src.remove(fromCol);
        int target = toRow;
        if (src.isEmpty()) {
            rows.remove(fromRow);
            if (fromRow < toRow) target--;                 // 摘空的行被删掉，目标行下标要跟着改
        }
        if (target < 0 || target >= rows.size()) {         // 目标行就是被删掉的那一行：原地放回成独立行
            List<PetBubbleModule> row = new ArrayList<>();
            row.add(m);
            rows.add(Math.max(0, Math.min(rows.size(), target)), row);
            return true;
        }
        List<PetBubbleModule> dst = rows.get(target);
        int col = insertCol;
        if (sameRow && col > fromCol) col--;               // 同一行内：摘掉一个之后下标左移
        col = Math.max(0, Math.min(dst.size(), col));
        dst.add(col, m);
        return true;
    }

    /**
     * 把单个模块**并入**目标行的最左 / 最右（对应「拖到某行左半 / 右半」）。
     * 同一行内的「并入」没有意义（本来就是并排）→ 返回 false。
     */
    public static boolean pairModule(List<List<PetBubbleModule>> rows,
                                     int fromRow, int fromCol, int toRow, boolean left) {
        if (rows == null) return false;
        if (fromRow == toRow) return false;
        if (toRow < 0 || toRow >= rows.size()) return false;
        int col = left ? 0 : rows.get(toRow).size();
        return moveModuleTo(rows, fromRow, fromCol, toRow, col);
    }

    /** 把**整行**并入另一行（左 / 右）。行内总数超上限 → 返回 false 且不做改动。 */
    public static boolean pairRow(List<List<PetBubbleModule>> rows, int fromRow, int toRow, boolean left) {
        if (rows == null) return false;
        if (fromRow == toRow) return false;
        if (fromRow < 0 || fromRow >= rows.size()) return false;
        if (toRow < 0 || toRow >= rows.size()) return false;
        List<PetBubbleModule> src = rows.get(fromRow);
        List<PetBubbleModule> dst = rows.get(toRow);
        if (dst.size() + src.size() > PetBubbleModule.MAX_PER_ROW) return false;
        rows.remove(fromRow);
        int target = toRow;
        if (fromRow < toRow) target--;
        List<PetBubbleModule> into = rows.get(Math.max(0, Math.min(rows.size() - 1, target)));
        if (left) into.addAll(0, src);
        else into.addAll(src);
        return true;
    }

    /**
     * 把一行拆成「第一个模块留在原行、其余各自成行」。
     *
     * <p>本版编辑器**没有绑定这个操作**（拆分靠把芯片拖出去实现），保留它是为了让
     * 「一行拆开」的语义有据可依、可被自检覆盖。行数不够时，剩下的模块留在原行（不丢）。
     */
    public static boolean splitRow(List<List<PetBubbleModule>> rows, int rowIdx) {
        if (rows == null || rowIdx < 0 || rowIdx >= rows.size()) return false;
        List<PetBubbleModule> row = rows.get(rowIdx);
        if (row.size() <= 1) return false;
        List<PetBubbleModule> rest = new ArrayList<>(row.subList(1, row.size()));
        row.subList(1, row.size()).clear();
        for (int i = 0; i < rest.size(); i++) {
            if (rows.size() >= PetBubbleModule.MAX_ROWS) break;
            List<PetBubbleModule> one = new ArrayList<>();
            one.add(rest.get(i));
            rows.add(rowIdx + 1 + i, one);
        }
        return true;
    }

    // ---------------------------------------------------------------- 应用一次拖放

    /**
     * 应用一次拖放（编辑器松手时调用；也是 {@code ACTION_TEST_EDIT} 的 {@code drop:} 命令实现）。
     *
     * @param dragIsRow true = 拖的是行左端 ⠿ 手柄（整行）；false = 拖的是模块芯片
     * @param dragRow   被拖的行下标
     * @param dragCol   被拖的模块在行内的下标（{@code dragIsRow} 时忽略）
     * @param overRow   手指下方的行下标；{@code -1} = 在第一行之上，{@code rows.size()} = 在最后一行之下
     * @param zone      {@link #DROP_BEFORE} / {@link #DROP_AFTER} / {@link #DROP_PAIR_LEFT} / {@link #DROP_PAIR_RIGHT}
     * @return 是否真的改动了结构（false = 无变化 / 被上限拒绝，调用方可以据此提示用户）
     */
    public static boolean applyDrop(List<List<PetBubbleModule>> rows, boolean dragIsRow,
                                    int dragRow, int dragCol, int overRow, int zone) {
        if (rows == null || rows.isEmpty()) return false;
        if (dragRow < 0 || dragRow >= rows.size()) return false;
        int size = rows.size();

        // ---- 落点在全部分行之外：上边 = 移到最前，下边 = 移到最后 ----
        if (overRow < 0 || overRow >= size) {
            if (dragIsRow) {
                int to = overRow < 0 ? 0 : size - 1;
                return moveRow(rows, dragRow, to);
            }
            int at = overRow < 0 ? 0 : size;
            return moveModuleToNewRow(rows, dragRow, dragCol, at);
        }

        if (zone == DROP_PAIR_LEFT || zone == DROP_PAIR_RIGHT) {
            boolean left = zone == DROP_PAIR_LEFT;
            if (dragIsRow) return pairRow(rows, dragRow, overRow, left);
            return pairModule(rows, dragRow, dragCol, overRow, left);
        }

        // ---- 另起一行 ----
        int insertAt = zone == DROP_AFTER ? overRow + 1 : overRow;
        if (dragIsRow) {
            if (dragRow == overRow) return false;
            if (rows.size() >= PetBubbleModule.MAX_ROWS && dragRow >= PetBubbleModule.MAX_ROWS) return false;
            List<PetBubbleModule> r = rows.remove(dragRow);
            int at = insertAt > dragRow ? insertAt - 1 : insertAt;
            at = Math.max(0, Math.min(rows.size(), at));
            rows.add(at, r);
            return true;
        }
        return moveModuleToNewRow(rows, dragRow, dragCol, insertAt);
    }

    /** 把单个模块摘出来，作为**独立的一行**插到第 {@code at} 行之前。 */
    public static boolean moveModuleToNewRow(List<List<PetBubbleModule>> rows,
                                             int fromRow, int fromCol, int at) {
        if (rows == null || fromRow < 0 || fromRow >= rows.size()) return false;
        List<PetBubbleModule> src = rows.get(fromRow);
        if (fromCol < 0 || fromCol >= src.size()) return false;
        PetBubbleModule m = src.remove(fromCol);
        boolean rowGone = src.isEmpty();
        if (rowGone) rows.remove(fromRow);
        // 行数上限：源行被摘空时「删一行 + 加一行」＝总数不变，所以只在实际会增加行数时拦
        if (!rowGone && rows.size() >= PetBubbleModule.MAX_ROWS) {
            src.add(Math.min(fromCol, src.size()), m);
            return false;
        }
        int idx = at;
        if (rowGone && fromRow < at) idx--;
        idx = Math.max(0, Math.min(rows.size(), idx));
        List<PetBubbleModule> row = new ArrayList<>();
        row.add(m);
        rows.add(idx, row);
        return true;
    }

    // ---------------------------------------------------------------- 序列项写回

    /** 读「点击序列」第 {@code index} 项的内容模块（空配置 → 用内置默认序列；越界 / 解析失败返回空表）。 */
    public static List<PetBubbleModule> modulesOfSeqItem(String seqJson, int index) {
        PetBubbleSeq seq = PetBubbleSeq.fromJson(seqJson);
        if (seq.parseFailed) return new ArrayList<>();
        if (seq.items.isEmpty()) seq = PetBubbleSeq.defaultSeq();
        if (index < 0 || index >= seq.items.size()) return new ArrayList<>();
        return new ArrayList<>(seq.items.get(index).modules);
    }

    /**
     * 把编辑好的模块写回「点击序列」第 {@code index} 项（顶层）。
     *
     * <p>该项原本不是 {@code custom} 时会被改成 {@code custom}（编辑器界面上会**明确告知**用户），
     * 因为只有自定义项才会用模块渲染。
     *
     * @return 新的序列 JSON；解析失败 / 下标越界返回 **null**（调用方**不得**覆盖用户配置）
     */
    public static String applySeqModules(String seqJson, int index, List<List<PetBubbleModule>> rows) {
        PetBubbleSeq seq = PetBubbleSeq.fromJson(seqJson);
        if (seq.parseFailed) return null;
        if (seq.items.isEmpty()) seq = PetBubbleSeq.defaultSeq();
        if (index < 0 || index >= seq.items.size()) return null;
        PetBubbleSeq.Item item = seq.items.get(index);
        item.kind = PetBubbleSeq.KIND_CUSTOM;
        item.modules.clear();
        item.modules.addAll(toModules(rows));
        return seq.toJson();
    }

    /** 该项原来是不是「自定义模块页」（编辑器用来提示「保存会把类型改成自定义」）。 */
    public static boolean seqItemIsCustom(String seqJson, int index) {
        PetBubbleSeq seq = PetBubbleSeq.fromJson(seqJson);
        if (seq.items.isEmpty()) seq = PetBubbleSeq.defaultSeq();
        if (index < 0 || index >= seq.items.size()) return false;
        return PetBubbleSeq.KIND_CUSTOM.equals(seq.items.get(index).kind);
    }

    // ---------------------------------------------------------------- 文本命令（脚本验证用）

    /**
     * 执行一条文本命令（脚本 / 自检共用，避免「测试用一套逻辑、界面用另一套」）。
     *
     * <pre>
     * add:行号:type       在该行末尾加一个某类型模块
     * new:type[:位置]      新建一行
     * rm:行:列            删除模块
     * mrow:从&gt;到          整行移动
     * mmod:行,列&gt;行,列     单模块移动到「目标行的第 k 列之前」
     * pair:行,列&gt;行,L|R    并入目标行（左/右）
     * drop:行,列|row&gt;行:zone 应用一次拖放（zone = B/A/L/R）
     * </pre>
     *
     * @return 是否执行成功
     */
    public static boolean exec(List<List<PetBubbleModule>> rows, String op) {
        if (rows == null || op == null) return false;
        String s = op.trim();
        if (s.isEmpty()) return false;
        try {
            if (s.startsWith("add:")) {
                String[] p = s.substring(4).split(":");
                if (p.length < 2) return false;
                return addToRow(rows, Integer.parseInt(p[0]), PetBubbleModule.newOf(p[1]));
            }
            if (s.startsWith("new:")) {
                String[] p = s.substring(4).split(":");
                int at = p.length > 1 ? Integer.parseInt(p[1]) : -1;
                return addRow(rows, PetBubbleModule.newOf(p[0]), at) >= 0;
            }
            if (s.startsWith("rm:")) {
                String[] p = s.substring(3).split(",");
                return removeModule(rows, Integer.parseInt(p[0]), Integer.parseInt(p[1])) != null;
            }
            if (s.startsWith("mrow:")) {
                String[] p = s.substring(5).split("[>,]");
                return moveRow(rows, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
            }
            if (s.startsWith("mmod:")) {
                String[] p = s.substring(5).split("[>,]");
                return moveModuleTo(rows, Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            }
            if (s.startsWith("pair:")) {
                String[] p = s.substring(5).split("[>,]");
                boolean left = p[3].trim().equalsIgnoreCase("L");
                return pairModule(rows, Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]), left);
            }
            if (s.startsWith("drop:")) {
                String[] p = s.substring(5).split("[>]");
                String[] from = p[0].split(",");
                String[] to = p[1].split(":");
                int dragRow = Integer.parseInt(from[0].trim());
                boolean isRow = from[1].trim().equalsIgnoreCase("row");
                int dragCol = isRow ? -1 : Integer.parseInt(from[1].trim());
                int overRow = Integer.parseInt(to[0].trim());
                String z = to[1].trim().toUpperCase(java.util.Locale.US);
                int zone = "B".equals(z) ? DROP_BEFORE : "A".equals(z) ? DROP_AFTER
                        : "L".equals(z) ? DROP_PAIR_LEFT : DROP_PAIR_RIGHT;
                return applyDrop(rows, isRow, dragRow, dragCol, overRow, zone);
            }
            if (s.startsWith("row:")) {        // 整行并入：row:从>到,L|R
                String[] p = s.substring(4).split("[>,]");
                return pairRow(rows, Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        p[2].trim().equalsIgnoreCase("L"));
            }
            return false;
        } catch (Exception e) {
            Log.write("编辑命令解析失败：" + op + " / " + e);
            return false;
        }
    }

    /** 依次执行多条命令（{@code ;} 分隔），返回成功条数；失败的命令写日志（不静默）。 */
    public static int execAll(List<List<PetBubbleModule>> rows, String ops) {
        if (ops == null || ops.trim().isEmpty()) return 0;
        int ok = 0;
        for (String op : ops.split(";")) {
            if (op.trim().isEmpty()) continue;
            if (exec(rows, op)) ok++;
            else Log.write("编辑命令未生效（被上限拒绝或参数非法）：" + op.trim());
        }
        return ok;
    }

    /** 供日志／设置页显示的一行 JSON 摘要（就是保存时写进 state.json 的那份）。 */
    public static String briefJson(List<List<PetBubbleModule>> rows) {
        return PetBubbleModule.listToJson(toModules(rows));
    }
}
