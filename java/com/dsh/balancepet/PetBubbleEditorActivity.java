package com.dsh.balancepet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 泡泡内容「模块化编辑器」（v2，v1.6.0）。
 *
 * <p>布局与上游插件的编辑器一致（**已按用户提供的截图对齐**）：
 * <pre>
 * ① 「可选模块」调色板        —— 点一下就把该模块加到末尾
 * ② 「泡泡内容」排列框        —— ⠿ 拖整行 / 拖芯片拖单个模块；
 *                                落到行的上/下边缘 = 另起一行，左/右半 = 并入该行
 * ③ 真渲染的「实时预览」      —— 与真泡泡同一个 {@link PetBubbleView}（静态模式，所见即所得）
 * 底部：[取消] [恢复默认] [保存]
 * </pre>
 *
 * <p>目标（Intent）：{@link #EXTRA_TARGET}
 * = {@link #TARGET_MODULES}（全局模块，所有泡泡的默认内容）
 * 或 {@link #TARGET_SEQ}（点击序列第 {@link #EXTRA_INDEX} 项的内容；保存会把它变成「自定义模块页」）。
 *
 * <p>保存策略：**编辑的是工作副本**，按「保存」才写盘并通知服务；按「取消」直接丢弃；
 * 有改动却没保存时会二次确认。写盘时**重新读一次 state.json**，只改自己那一项 ——
 * 否则会把服务刚写进去的桌宠位置等改动覆盖回旧值（真 bug 风险）。
 *
 * <p>⚠️ 如实说明：**手势（长按 400ms、跟手、落点高亮）我没法在真机上验证**
 * （ColorOS 拦 {@code am start}、无法注入真实触摸），只能由用户亲测；
 * 但「结构怎么变」全部走 {@link PetBubbleEditModel}（纯函数，{@link SelfTests} 有断言，
 * 也能被 {@code ACTION_TEST_EDIT} 脚本驱动）。
 */
public final class PetBubbleEditorActivity extends Activity implements PetDragListLayout.Listener {

    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_INDEX = "index";
    /**
     * 自检（smoke）模式：把整棵界面树建起来、可选导出预览 PNG，然后**自己关掉**。
     *
     * <p>为什么需要：编辑器的手势我没法在真机上点，但「打开编辑器会不会闪退」「预览有没有真的画出来」
     * 必须验证 —— ColorOS 不会把这类由设置页内部启动的 Activity 顶到前台，所以它能在不打扰用户的前提下
     * 把界面真正构建一遍（构建过程出错就会在 logcat 里留下 FATAL，一眼可见）。
     */
    public static final String EXTRA_SMOKE = "smoke";
    /** smoke 模式下把预览渲染成 PNG 写到共享存储（便于用像素脚本核对）。 */
    public static final String EXTRA_EXPORT = "export";
    /**
     * smoke 模式下**真的走一遍保存**（等价于点「保存」按钮，含重新读盘 → 只改自己那一项 → 写盘 → 通知服务），
     * 用来验证这条路径；关闭编辑器由 {@code saveAndFinish()} 自己完成。
     */
    public static final String EXTRA_SMOKE_SAVE = "smokeSave";
    /** 打开编辑器后直接弹哪个对话框：目前支持 {@code "peak"}（峰谷全局设置）。 */
    public static final String EXTRA_FOCUS = "focus";
    public static final String FOCUS_PEAK = "peak";
    /** 打开编辑器后直接弹「泡泡文字颜色（全局）」（v1.8.0）。 */
    public static final String FOCUS_COLORS = "colors";
    public static final String TARGET_MODULES = "modules";
    public static final String TARGET_SEQ = "seq";

    /** 编辑器保存过东西 → 设置页回来时必须**重新读盘**，否则它内存里的旧 state 会把改动盖掉。 */
    private static volatile boolean savedState;

    public static boolean consumeSavedFlag() {
        boolean v = savedState;
        savedState = false;
        return v;
    }

    /** 调色板：只列本版**真能渲染**的类型（未支持的会如实写在下面，不放点了没反应的按钮）。 */
    /** 可加清单（v1.12.8：包内可见，SelfTests 用它做「两份清单同步」断言）。 */
    /**
         * 可加清单（v1.12.9：唯一权威在 {@link PetBubbleModule#PALETTE}，这里只是本地别名，
         * 避免再出现「两份手写清单」——加了新类型却忘了改 UI 那一份）。
         */
        static final String[][] PALETTE = PetBubbleModule.PALETTE;

    private PetState state;
    /** 脚本/设置页请求的目标（{@link #TARGET_MODULES} / {@link #TARGET_SEQ}）。 */
    private String target = TARGET_MODULES;
    private int index = -1;
    private boolean night;

    /** 序列工作副本（结构 + 候选）；用户没改过就不写盘。 */
    private PetBubbleSeq workingSeq;
    private boolean seqConfigWasDefault = true;
    private String initialSeqSig = "";

    private final List<List<PetBubbleModule>> rows = new ArrayList<>();

    // ---- 峰谷全局设置的工作副本（v1.7.0：把「Whale挂件·峰谷显示」并进编辑器） ----
    // 与模块编辑同一套语义：改的是副本、预览立刻反映，**只有点「保存」才写盘**，取消就丢弃。
    private boolean pkShow;
    private int pkStyle;
    private int pkCountdownFormat;
    private int pkColor;
    private int pkValley;
    private String pkText;
    private String pkValleyText;
    private String initialPeakSignature = "";

    // ---- 泡泡文字颜色（全局）的工作副本（v1.8.0：从「设置 → Whale挂件 → 泡泡外观」搬进来） ----
    private int txTextColor;
    private int txHintColor;
    private String initialTxSignature = "";

    private FrameLayout root;
    private LinearLayout globalCard;
    private FlowLayout targetBar;
    /** 并列项的候选芯片栏（v1.11.0：把权重直接摆出来）。 */
    private FlowLayout candidateBar;
    private FlowLayout previewStateBar;
    private PetDragListLayout list;
    private FrameLayout previewBox;
    private TextView targetNote;
    private TextView statusText;
    private TextView dataSourceText;
    private TextView dragHint;

    // ---------------------------------------------------------------- 生命周期

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PetPaths.init(this);
        state = PetState.load();
        night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        Intent intent = getIntent();
        target = intent == null ? TARGET_MODULES : intent.getStringExtra(EXTRA_TARGET);
        if (target == null) target = TARGET_MODULES;
        index = intent == null ? -1 : intent.getIntExtra(EXTRA_INDEX, -1);

        loadAllTargets();
        loadPeakWorkingCopy();
        initialPeakSignature = peakSignature();
        loadTextColorWorkingCopy();
        // 脚本验证用（--ei textColor/--ei hintColor）：只覆盖**工作副本**，
        // 不写盘、不改用户配置 —— 用来让 smoke 的导出 PNG 能验证「文字颜色真的驱动渲染」。
        if (intent != null && intent.hasExtra("textColor")) {
            txTextColor = intent.getIntExtra("textColor", txTextColor) & 0xFFFFFF;
            Log.write("编辑器：文字颜色工作副本被脚本覆盖为 #"
                    + String.format(Locale.US, "%06X", txTextColor) + "（仅影响预览/导出，不写盘）");
        }
        if (intent != null && intent.hasExtra("hintColor")) {
            txHintColor = intent.getIntExtra("hintColor", txHintColor) & 0xFFFFFF;
        }
        initialTxSignature = txSignature();

        curTarget = resolveInitialTarget(target, index);
        // 初始目标是并列项时要先选好候选，否则「槽位」是空的（会误报「没有模块」）
        PetBubbleSeq.Item initItem = itemAt(curTarget);
        curCandidate = (initItem != null && initItem.isChoice() && !initItem.options.isEmpty()) ? 0 : -1;
        loadRowsFromSlot();
        snapshotInitialSignatures();

        buildUi();
        rebuildAll();

        Log.write("泡泡编辑器打开：目标=" + targetLabel(curTarget)
                + "（共 " + (workingSeq.items.size() + 1) + " 个可切换目标）"
                + "；默认泡泡=" + PetBubbleEditModel.describe(
                PetBubbleEditModel.rowsOf(defaultMods))
                + "；点击序列=" + workingSeq.describe());

        String focus = intent == null ? null : intent.getStringExtra(EXTRA_FOCUS);
        if (FOCUS_PEAK.equals(focus)) {
            // 从设置页「峰谷显示」进来的：直接把峰谷对话框弹出来（少点一步）
            root.postDelayed(new Runnable() {
                @Override public void run() { openPeakSettings(); }
            }, 260);
        } else if (FOCUS_COLORS.equals(focus)) {
            // 从设置页「泡泡外观 → 文字颜色」进来的
            root.postDelayed(new Runnable() {
                @Override public void run() { openTextColors(); }
            }, 260);
        }

        if (intent != null && intent.getBooleanExtra(EXTRA_SMOKE, false)) {
            final boolean export = intent.getBooleanExtra(EXTRA_EXPORT, false);
            root.postDelayed(new Runnable() {
                @Override public void run() {
                    Log.write("编辑器 smoke：界面树已构建（目标芯片 " + targetBar.getChildCount()
                            + " 个 · 排列框 " + list.getChildCount() + " 行 · 候选栏 "
                            + candidateBar.getChildCount() + " 个候选 · 预览 "
                            + previewBox.getChildCount() + " 个子视图 · 仍是字面 ** 的 TextView = "
                                                        + rawMarkerLeft + "（期望 0）");
                    Log.write("编辑器 smoke：可添加的模块类型="
                                                + java.util.Arrays.toString(PetBubbleModule.TYPE_NAMES));
                                        Log.write("编辑器 smoke：顶部布局实测=" + targetLayoutReport());
                    // 把预览里每个模块**解析后**的文本打出来：能据此判断预览用的是真实数据还是占位值
                    PetBubble pb = buildPreviewBubble();
                    StringBuilder sb = new StringBuilder();
                    for (PetBubbleModule m : pb.modules) {
                        sb.append('「').append(m.contentOf(pb)).append("」/");
                    }
                    RuntimeData rt = RuntimeData.read();
                    Log.write("编辑器 smoke：预览内容=" + sb
                            + " 数据源=" + (rt.available
                            ? "runtime.json（服务快照，connected=" + rt.connected
                            + " 余额=" + rt.balance + " 消费=" + rt.cost + " 今日=" + rt.today + "）"
                            : "占位值（读不到 runtime.json）"));
                    Log.write("编辑器 smoke：峰谷（全局）=" + peakSummary()
                            + " ｜ 文字颜色（全局）=" + txSummary()
                            + " ｜ 泡泡大小=" + PetState.bubbleScaleLabel(state.bubbleScalePercent)
                            + " ｜ 脏=" + dirty());
                    if (export) {
                                            exportPreviewPng();
                                            exportUiPng();   // v1.12.5：顺带把整页界面也导出来
                                        }
                    if (intent.getBooleanExtra(EXTRA_SMOKE_SAVE, false)) {
                        Log.write("编辑器 smoke：按要求走一遍保存（等价于点「保存」按钮）");
                        saveAndFinish();
                        return;
                    }
                    Log.write("编辑器 smoke：完成，自动关闭（不写盘、不改配置）");
                    finish();
                }
            }, 900);
        }
    }

    /** smoke 模式：把预览视图渲染成 PNG 写到共享存储（写不了就如实记日志，不假装成功）。 */
    /**
     * smoke 专用（v1.12.5）：把**整个界面**（root）画成 PNG 写到 /sdcard/Download/dshpet_editor_ui.png。
     *
     * <p>为什么要这个：像“候选 1 的框被上面横切”这种**纯视觉**问题，靠布局坐标和用户截图都不够 ——
     * 有了这张自截图就能直接做像素取证（芯片之间的间隔、有没有被裁）。
     */
    private void exportUiPng() {
        try {
            if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
                Log.write("编辑器 smoke：界面尚未布局（"
                        + (root == null ? "no-root" : (root.getWidth() + "x" + root.getHeight()))
                        + "），跳过 UI 导出");
                return;
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(bmp));
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "Download");
            java.io.File out = new java.io.File(dir, "dshpet_editor_ui.png");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            Log.write("编辑器 smoke：界面已导出 " + out.getAbsolutePath() + " "
                    + bmp.getWidth() + "x" + bmp.getHeight() + "（" + out.length() + " 字节）");
        } catch (Exception e) {
            Log.write("编辑器 smoke：导出界面 PNG 失败（多半是共享存储权限）: " + e);
        }
    }
    private void exportPreviewPng() {
        try {
            if (previewBox.getChildCount() == 0) {
                Log.write("编辑器 smoke：预览没有子视图，跳过导出");
                return;
            }
            View v = previewBox.getChildAt(0);
            if (v.getWidth() <= 0 || v.getHeight() <= 0) {
                Log.write("编辑器 smoke：预览尚未布局（" + v.getWidth() + "x" + v.getHeight()
                        + "），跳过导出");
                return;
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    v.getWidth(), v.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            v.draw(new android.graphics.Canvas(bmp));
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "Download");
            java.io.File out = new java.io.File(dir, "dshpet_editor_preview.png");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            Log.write("编辑器 smoke：预览已导出 " + out.getAbsolutePath() + " "
                    + bmp.getWidth() + "x" + bmp.getHeight() + "（" + out.length() + " 字节）");
        } catch (Exception e) {
            Log.write("编辑器 smoke：导出预览 PNG 失败（多半是共享存储权限）: " + e);
        }
    }

    @Override public void onBackPressed() {
        confirmCancel();
    }
// ---------------------------------------------------------------- v1.9.0：多目标工作副本

    /**
     * 载入**全部可编辑目标**（默认泡泡 + 各次点击）。
     *
     * <p>为什么这么改（用户 2026-10-05 反馈「编辑器有好几个入口、显示的泡泡内容都不一样、搞蒙了」）：
     * 上游本来就是**一个编辑器**里管「第 N 次点击的内容」；之前我把它拆成「全局模块编辑器」
     * 与「序列项编辑器」两个入口，结果用户改了其中一个、在另一个里看到的又是老内容。
     * 现在全部收进同一个页面：顶部切换目标，底部一次保存。
     */


    // ---------------------------------------------------------------- v1.11.0：真实槽位模型

    /**
     * 当前编辑的**槽位** = (第几次点击, 候选序号)。
     *
     * <p>为什么重做（用户 2026-10-05 用截图反馈「编辑器预览与真机不一致」）：
     * 旧模型把「目标内容」当成一个平铺的模块列表，目标自己没内容时会**回落到内置默认序列同下标的内容**做占位 ——
     * 于是当「第 2 个」是**并列候选**（内容存在 {@code options[].item} 里、自身的 {@code modules} 是空的）时，
     * 编辑器显示的是**默认序列第 2 项那段文字**，而真机显示的是候选里的峰谷倒计时页 —— **预览与真机必然对不上**。
     *
     * <p>新模型：**直接编辑序列/默认配置里的真实对象**，一个模块都不凭空造：
     * <ul>
     *   <li>{@code curTarget < 0} → 默认泡泡（模块存在 {@link #defaultMods}）</li>
     *   <li>{@code curTarget >= 0} 且该项不是并列 → 直接编辑该项的 {@code modules}</li>
     *   <li>并列项 → 必须先选一个**候选**（{@link #curCandidate}），编辑该候选自己那项的 {@code modules}；
     *       候选与**权重**在目标栏下面可见、可改</li>
     *   <li>「默认页」的项被改过内容时，保存时自动升级为「自定义模块页」（否则渲染端会忽略 modules）</li>
     * </ul>
     * 槽位没有内容时**就是空的**（预览显示真实渲染结果），不再拿别处的内容冒充。
     */
    private int curTarget = -1;
    private int curCandidate = -1;
    /** 默认泡泡（所有泡泡的默认内容）的工作副本。 */
    private List<PetBubbleModule> defaultMods = new ArrayList<>();
    /** 各槽位的初始内容签名（脏检查）：键见 {@link #slotKey}。 */
    private final java.util.LinkedHashMap<String, String> initialSig = new java.util.LinkedHashMap<>();

    private void loadAllTargets() {
        workingSeq = PetBubbleSeq.fromJson(state.bubbleSeqJson);
        if (workingSeq.parseFailed) {
            Log.write("编辑器：序列配置解析失败 → 用内置推荐结构做工作副本（不会覆盖原配置）");
            workingSeq = PetBubbleSeq.recommendedSeq();
            seqConfigWasDefault = true;
        } else {
            seqConfigWasDefault = workingSeq.items.isEmpty();
            if (seqConfigWasDefault) workingSeq = PetBubbleSeq.recommendedSeq();
        }
        defaultMods = PetBubbleModule.listFromJson(state.bubbleModulesJson);
        initialSeqSig = seqStructureSignature();
        snapshotInitialSignatures();
    }

    private PetBubbleSeq.Item itemAt(int t) {
        if (workingSeq == null || t < 0 || t >= workingSeq.items.size()) return null;
        return workingSeq.items.get(t);
    }

    /** 并列项的第 c 个候选（不是并列项或越界 → null）。 */
    private PetBubbleSeq.Item candidateAt(int t, int c) {
        PetBubbleSeq.Item it = itemAt(t);
        if (it == null || !it.isChoice() || c < 0 || c >= it.options.size()) return null;
        PetBubbleSeq.Option o = it.options.get(c);
        return o == null ? null : o.item;
    }

    /** 当前槽位里那个「装有 modules 的项」；默认泡泡没有项（返回 null）。 */
    private PetBubbleSeq.Item slotItem() {
        if (curTarget < 0) return null;
        PetBubbleSeq.Item it = itemAt(curTarget);
        if (it == null) return null;
        if (!it.isChoice()) return it;
        return candidateAt(curTarget, curCandidate);
    }

    /** 当前槽位的模块列表（**真实引用**，改它就等于改配置）。 */
    private List<PetBubbleModule> slotModules() {
        if (curTarget < 0) return defaultMods;
        PetBubbleSeq.Item s = slotItem();
        return s == null ? null : s.modules;
    }

    /** 当前槽位能不能编辑（并列项在没选候选时不能）。 */
    private boolean slotEditable() { return slotModules() != null; }

    private String slotKey(int t, int c) {
        return t < 0 ? "d" : (c < 0 ? ("i" + t) : ("c" + t + "_" + c));
    }

    private static String sigOf(List<PetBubbleModule> mods) {
        return PetBubbleEditModel.signature(PetBubbleEditModel.rowsOf(mods));
    }

    /** 快照所有槽位的初始内容签名（用于判断「有没有改动」）。 */
    private void snapshotInitialSignatures() {
        initialSig.clear();
        initialSig.put("d", sigOf(defaultMods));
        for (int i = 0; i < workingSeq.items.size(); i++) {
            PetBubbleSeq.Item it = workingSeq.items.get(i);
            if (it.isChoice()) {
                for (int c = 0; c < it.options.size(); c++) {
                    PetBubbleSeq.Item ci = candidateAt(i, c);
                    initialSig.put(slotKey(i, c), ci == null ? "" : sigOf(ci.modules));
                }
            } else {
                initialSig.put(slotKey(i, -1), sigOf(it.modules));
            }
        }
    }

    /** 某个槽位相对「打开编辑器时」是否被改过。 */
    private boolean slotDirty(int t, int c) {
        List<PetBubbleModule> mods;
        if (t < 0) {
            mods = defaultMods;
        } else {
            PetBubbleSeq.Item it = itemAt(t);
            if (it == null) return false;
            PetBubbleSeq.Item s = it.isChoice() ? candidateAt(t, c) : it;
            if (s == null) return false;
            mods = s.modules;
        }
        String init = initialSig.get(slotKey(t, c));
        return init == null || !init.equals(sigOf(mods));
    }

    /** 槽位键 → 是否被改过（供 seqTouched 用）。 */
    private boolean slotKeyDirty(String key) {
        if (key.startsWith("i")) {
            return slotDirty(Integer.parseInt(key.substring(1)), -1);
        }
        if (key.startsWith("c")) {
            String[] p = key.substring(1).split("_");
            return slotDirty(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        }
        return false;
    }

    /**
     * 目标芯片的名字 —— **按「怎么触发」来叫**（v1.10.0），并列项再标出候选数（v1.11.0）。
     *
     * <p>真实语义（与上游一致，已核源码）：点桌宠弹第 1 个；**点「泡泡」**推进到第 2、第 3 个；
     * 已是最后一个时再点泡泡就收起。
     */
    private String targetLabel(int t) {
        if (t < 0) return "默认泡泡";
        String base = t == 0 ? "第 1 个（点桌宠）"
                : (t == 1 ? "第 2 个（点泡泡）" : ("第 " + (t + 1) + " 个（再点泡泡）"));
        PetBubbleSeq.Item it = itemAt(t);
        if (it != null) {
            if (it.isChoice()) return base + " · 并列" + it.options.size() + "选1";
            if (PetBubbleSeq.KIND_NORMAL.equals(it.kind)) return base + " · 默认页";
        }
        return base;
    }

    /** 候选芯片的名字 —— **把权重写在脸上**（用户反馈「权重入口太隐蔽」）。 */
    private String candidateLabel(int t, int c) {
        PetBubbleSeq.Item it = itemAt(t);
        if (it == null || c < 0 || c >= it.options.size()) return "候选";
        PetBubbleSeq.Option o = it.options.get(c);
        return "候选" + (c + 1) + "（权重 " + PetBubbleSeq.weightOf(o) + "）"
                + (o.item == null ? "（空）" : "：" + o.item.describe());
    }

    /** 把当前槽位的内容导进 {@link #rows}（**不做任何占位**）。 */
    private void loadRowsFromSlot() {
        List<PetBubbleModule> mods = slotModules();
        rows.clear();
        if (mods != null) rows.addAll(PetBubbleEditModel.rowsOf(mods));
        if (rows.isEmpty()) {
            Log.write("编辑器：" + targetLabel(curTarget)
                    + (curCandidate >= 0 ? (" ·候选" + (curCandidate + 1)) : "")
                    + " 没有模块（预览显示真实结果，不造假内容）");
        }
    }

    /** 把 {@link #rows} 写回当前槽位（**真实对象**）。 */
    private void writeBackCurrent() {
        List<PetBubbleModule> mods = slotModules();
        if (mods == null) return;
        mods.clear();
        mods.addAll(PetBubbleEditModel.toModules(rows));
    }

    /** 切换编辑目标（并列项默认选第 1 个候选）。 */
    private void switchTarget(int t) {
        if (t == curTarget && curCandidate >= 0) return;
        writeBackCurrent();
        curTarget = t;
        PetBubbleSeq.Item it = itemAt(t);
        curCandidate = (it != null && it.isChoice() && !it.options.isEmpty()) ? 0 : -1;
        loadRowsFromSlot();
        Log.write("编辑器：切换目标 → " + targetLabel(t)
                + (curCandidate >= 0 ? ("（正在编辑 " + candidateLabel(t, curCandidate) + "）") : ""));
        rebuildAll();
    }

    /** 切换并列项的候选（内容/预览都跟着切）。 */
    private void switchCandidate(int c) {
        if (c == curCandidate) return;
        writeBackCurrent();
        curCandidate = c;
        loadRowsFromSlot();
        Log.write("编辑器：切换到 " + candidateLabel(curTarget, c));
        rebuildAll();
    }

    /** 序列**结构**签名（项数/类型/权重/候选树）。 */
    private String seqStructureSignature() {
        if (workingSeq == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(workingSeq.tapAdvance ? "T" : "F").append('|');
        for (PetBubbleSeq.Item it : workingSeq.items) {
            sb.append(it.kind).append(':').append(it.ttlMs).append(':');
            for (PetBubbleSeq.Option o : it.options) sb.append(PetBubbleSeq.weightOf(o)).append(',');
            sb.append(';');
        }
        return sb.toString();
    }

    /** 序列是否需要写盘（结构变过，或任一「点击项/候选」的内容变过）。 */
    private boolean seqTouched() {
        if (!initialSeqSig.equals(seqStructureSignature())) return true;
        for (String k : initialSig.keySet()) {
            if (k.equals("d")) continue;
            if (slotKeyDirty(k)) return true;
        }
        return false;
    }

    /** 默认泡泡的内容有没有改过。 */
    private boolean defaultDirty() {
        String init = initialSig.get("d");
        return init == null || !init.equals(sigOf(defaultMods));
    }

    /** 解析「当前要编辑哪个目标」（脚本/设置页可用 extras 指定）。 */
    private int resolveInitialTarget(String targetReq, int indexReq) {
        if (TARGET_SEQ.equals(targetReq) && indexReq >= 0 && indexReq < workingSeq.items.size()) {
            return indexReq;
        }
        return -1;
    }

    /** 保存前：把「默认页」里被改过内容的项升级为「自定义模块页」（否则渲染端会忽略 modules）。 */
    private void upgradeEditedKinds() {
        for (int i = 0; i < workingSeq.items.size(); i++) {
            PetBubbleSeq.Item it = workingSeq.items.get(i);
            if (it.isChoice()) {
                for (int c = 0; c < it.options.size(); c++) {
                    PetBubbleSeq.Item ci = candidateAt(i, c);
                    if (ci != null && PetBubbleSeq.KIND_NORMAL.equals(ci.kind)
                            && slotDirty(i, c) && !ci.modules.isEmpty()) {
                        ci.kind = PetBubbleSeq.KIND_CUSTOM;
                        Log.write("编辑器：候选" + (c + 1) + "（第 " + (i + 1)
                                + " 项）原本是「默认页」→ 已转为「自定义模块页」");
                    }
                }
            } else if (PetBubbleSeq.KIND_NORMAL.equals(it.kind) && slotDirty(i, -1)
                    && !it.modules.isEmpty()) {
                it.kind = PetBubbleSeq.KIND_CUSTOM;
                Log.write("编辑器：第 " + (i + 1) + " 项原本是「默认页」→ 已转为「自定义模块页」");
            }
        }
    }



    /**
     * 把「Whale挂件 · 峰谷显示」并进编辑器的原因（用户 2026-10-05 提出）：
     * 峰谷其实是**泡泡内容的一部分**，在设置页里单开一张卡 + 在模块里又有一套，用户要改一个
     * 「显示样式」得先想清楚「这次改的是全局还是模块」——太绕。现在把它做成编辑器内的
     * 「峰谷（全局）」对话框：**全局给默认值，模块可以自己钉死覆盖**，两者在同一个页面里就能看明白。
     */
    private void loadPeakWorkingCopy() {
        pkShow = state.peakShow;
        pkStyle = PeakValley.clampStyle(state.peakStyle);
        pkCountdownFormat = PeakValley.clampCountdownFormat(state.countdownFormat);
        pkColor = state.peakColor & 0xFFFFFF;
        pkValley = state.valleyColor & 0xFFFFFF;
        pkText = state.peakTextCustom == null ? "" : state.peakTextCustom;
        pkValleyText = state.valleyTextCustom == null ? "" : state.valleyTextCustom;
    }

    /** 峰谷设置的「结构签名」（用于脏检查：只改了峰谷、模块没动，也要提示「有改动未保存」）。 */
    private String peakSignature() {
        return (pkShow ? "1" : "0") + "|" + pkStyle + "|" + pkCountdownFormat + "|"
                + String.format(Locale.US, "%06X", pkColor) + "|"
                + String.format(Locale.US, "%06X", pkValley) + "|" + pkText + "|" + pkValleyText;
    }

    /** 峰谷设置的当前摘要（设置页与编辑器都显示它）。 */
    private String peakSummary() {
        boolean count = PeakValley.isCountStyle(pkStyle);
        return (pkShow ? "峰谷行开" : "峰谷行关")
                + " · " + PeakValley.styleDisplayName(pkStyle)
                + (count ? "" : "（高峰「" + (pkText.isEmpty() ? "高峰时段" : pkText)
                + "」/ 空闲「" + (pkValleyText.isEmpty() ? "空闲时段" : pkValleyText) + "」）")
                + " · 峰 #" + String.format(Locale.US, "%06X", pkColor)
                + " / 谷 #" + String.format(Locale.US, "%06X", pkValley)
                + " · 倒计时 " + PeakValley.countdownFormatName(pkCountdownFormat);
    }

    /** 「峰谷（全局）」设置对话框：改的是工作副本，保存才写盘。 */
    private void openPeakSettings() {
        Log.write("编辑器：打开「峰谷（全局设置）」对话框（当前 " + peakSummary() + "）");
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, dp(6), pad, dp(6));

        col.addView(note("峰谷规则与原插件一致：工作日 9:00–12:00 / 14:00–18:00 为高峰，其余（含周末与"
                + "法定节假日全天）为空闲。这里的值是**全局默认**；峰谷模块里若钉死了自己的样式/颜色，"
                + "则以模块为准（模块默认「跟随全局」）。保存后对**没有用模块**的经典四行泡泡同样生效。"));

        col.addView(dialogRow("峰谷行：" + (pkShow ? "✓ 显示" : "不显示"),
                "只影响「经典四行」泡泡（没用模块时）；用了峰谷模块的泡泡由模块自己画那一行",
                new Runnable() {
                    @Override public void run() {
                        pkShow = !pkShow;
                        afterPeakEdit();
                    }
                }));
        col.addView(dialogRow("显示样式：" + PeakValley.styleDisplayName(pkStyle),
                "5 种（默认 / 梁文峰谷 / !?强强?! / 简洁 / 倒计时）", new Runnable() {
                    @Override public void run() {
                        new AlertDialog.Builder(PetBubbleEditorActivity.this)
                                .setTitle("峰谷显示样式（全局默认）")
                                .setItems(PeakValley.STYLE_NAMES,
                                        new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        pkStyle = PeakValley.clampStyle(which);
                                        afterPeakEdit();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                }));
        col.addView(dialogRow("倒计时格式：" + PeakValley.countdownFormatName(pkCountdownFormat),
                "仅在「倒计时」样式或含 {countdown} 的模块里用到", new Runnable() {
                    @Override public void run() {
                        new AlertDialog.Builder(PetBubbleEditorActivity.this)
                                .setTitle("倒计时格式")
                                .setItems(PeakValley.CD_FORMAT_NAMES,
                                        new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        pkCountdownFormat = PeakValley.clampCountdownFormat(which);
                                        afterPeakEdit();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                }));
        col.addView(dialogRow("高峰颜色 / 空闲颜色："
                        + String.format(Locale.US, "#%06X", pkColor) + " / "
                        + String.format(Locale.US, "#%06X", pkValley),
                "默认 #E0433F / #2FA24C（上游值）；改高峰", new Runnable() {
                    @Override public void run() {
                        pickColor(pkColor, "高峰颜色", String.format(Locale.US, "#%06X", pkColor),
                                new Consumer<String>() {
                                    @Override public void accept(String hex) {
                                        int v = PetBubbleModule.parseHex(hex);
                                        if (v < 0) {
                                            toast("看不懂这个颜色");
                                            return;
                                        }
                                        pkColor = v;
                                        afterPeakEdit();
                                    }
                                });
                    }
                }));
        col.addView(dialogRow("空闲颜色： " + String.format(Locale.US, "#%06X", pkValley),
                "点一下改空闲色", new Runnable() {
                    @Override public void run() {
                        pickColor(pkValley, "空闲颜色", String.format(Locale.US, "#%06X", pkValley),
                                new Consumer<String>() {
                                    @Override public void accept(String hex) {
                                        int v = PetBubbleModule.parseHex(hex);
                                        if (v < 0) {
                                            toast("看不懂这个颜色");
                                            return;
                                        }
                                        pkValley = v;
                                        afterPeakEdit();
                                    }
                                });
                    }
                }));
        col.addView(dialogRow("高峰时的文字：" + (pkText.isEmpty() ? "（未设置 →「高峰时段」）" : pkText),
                "只有「默认」样式用它", new Runnable() {
                    @Override public void run() {
                        askText("高峰时的文字", pkText, "留空 = 用「高峰时段」", new Consumer<String>() {
                            @Override public void accept(String s) {
                                pkText = s == null ? "" : s;
                                afterPeakEdit();
                            }
                        });
                    }
                }));
        col.addView(dialogRow("空闲时的文字：" + (pkValleyText.isEmpty() ? "（未设置 →「空闲时段」）" : pkValleyText),
                "只有「默认」样式用它", new Runnable() {
                    @Override public void run() {
                        askText("空闲时的文字", pkValleyText, "留空 = 用「空闲时段」", new Consumer<String>() {
                            @Override public void accept(String s) {
                                pkValleyText = s == null ? "" : s;
                                afterPeakEdit();
                            }
                        });
                    }
                }));
        col.addView(dialogRow("↺ 恢复峰谷默认", "样式「默认」、上游配色、两态文字清空", new Runnable() {
            @Override public void run() {
                pkShow = true;
                pkStyle = PeakValley.STYLE_DEFAULT;
                pkCountdownFormat = PeakValley.CD_SMART;
                pkColor = 0xE0433F;
                pkValley = 0x2FA24C;
                pkText = "";
                pkValleyText = "";
                afterPeakEdit();
            }
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle("峰谷（全局）")
                .setView(sv)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 改完峰谷设置：刷新入口卡 + 预览 + 脏状态（不写盘）。 */
    private void afterPeakEdit() {
        rebuildGlobalCard();
        rebuildPreview();
        refreshStatusLine();
    }

    // ---------------------------------------------------------------- 泡泡文字颜色（全局）

    /**
     * 从「设置 → Whale挂件 → 泡泡外观」搬进来的**正文色 / 提示行颜色**（用户 2026-10-05 要求）。
     *
     * <p>为什么放这里：这两项本来就是「模块没自己指定颜色时的兜底色」，跟模块颜色是一回事，
     * 分在两个页面改最容易搞混（他原话：「不用单开了」）。字段仍写在 state 上
     * （{@code bubbleTextColor} / {@code bubbleHintColor}），所以经典四行泡泡照样生效。
     */
    private void loadTextColorWorkingCopy() {
        txTextColor = state.bubbleTextColor & 0xFFFFFF;
        txHintColor = state.bubbleHintColor & 0xFFFFFF;
    }

    private String txSignature() {
        return String.format(Locale.US, "%06X|%06X", txTextColor, txHintColor);
    }

    private String txSummary() {
        return "正文 #" + String.format(Locale.US, "%06X", txTextColor)
                + " ｜ 提示行 #" + String.format(Locale.US, "%06X", txHintColor);
    }

    /** 「泡泡文字颜色（全局）」对话框：工作副本，保存才写盘。 */
    private void openTextColors() {
        Log.write("编辑器：打开「泡泡文字颜色（全局）」对话框（" + txSummary() + "）");
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, dp(6), pad, dp(6));
        col.addView(note("这两项是**模块没自己指定颜色时**的兜底色（也是「经典四行」泡泡的正文/提示色）。"
                + "模块里单独设了颜色就以模块为准。改完按「保存」才生效。"));

        col.addView(dialogRow("正文颜色：" + String.format(Locale.US, "#%06X", txTextColor),
                "标题 / 金额 / 没设色的模块都用它", new Runnable() {
                    @Override public void run() {
                        pickPlainHex(txTextColor, "正文颜色", new Consumer<String>() {
                            @Override public void accept(String hex) {
                                txTextColor = PetBubbleModule.parseHex(hex);
                                afterTxEdit();
                            }
                        });
                    }
                }));
        col.addView(dialogRow("提示行颜色：" + String.format(Locale.US, "#%06X", txHintColor),
                "「经典四行」里那行小字（连接状态等）的颜色", new Runnable() {
                    @Override public void run() {
                        pickPlainHex(txHintColor, "提示行颜色", new Consumer<String>() {
                            @Override public void accept(String hex) {
                                txHintColor = PetBubbleModule.parseHex(hex);
                                afterTxEdit();
                            }
                        });
                    }
                }));
        col.addView(dialogRow("↺ 恢复上游默认", "正文 #536BA9、提示行 #9FB0D9（WhaleWidget 原值）",
                new Runnable() {
                    @Override public void run() {
                        txTextColor = 0x536BA9;
                        txHintColor = 0x9FB0D9;
                        afterTxEdit();
                    }
                }));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle("泡泡文字颜色（全局）")
                .setView(sv)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 改完文字颜色：刷入口卡 + 预览 + 脏状态（不写盘）。 */
    private void afterTxEdit() {
        rebuildGlobalCard();
        rebuildPreview();
        refreshStatusLine();
    }

    // ---------------------------------------------------------------- 目标栏 / 预览（v1.9.0）

    /** 重建顶部「编辑目标」芯片栏：默认泡泡 + 各次点击；并列项下面再给一排**候选（带权重）**。 */
    private void rebuildTargetBar() {
        if (targetBar == null) return;
        targetBar.removeAllViews();
        for (int t = -1; t < workingSeq.items.size(); t++) {
            final int target2 = t;
            boolean cur = t == curTarget;
            TextView chip = new TextView(this);
            chip.setText((cur ? "✓ " : "") + targetLabel(t));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTextColor(cur ? Color.WHITE : textColor());
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setBackground(roundRect(cur ? accent()
                    : (night ? Color.rgb(52, 56, 61) : Color.rgb(238, 241, 245)), dp(14)));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchTarget(target2); }
            });
            targetBar.addView(chip);
        }
        // 并列项：候选 + 权重直接摆出来（用户反馈「权重入口太隐蔽」）
        if (candidateBar != null) {
            candidateBar.removeAllViews();
            PetBubbleSeq.Item it = itemAt(curTarget);
            if (it != null && it.isChoice()) {
                for (int c = 0; c < it.options.size(); c++) {
                    final int cand = c;
                    boolean cur = c == curCandidate;
                    TextView chip = new TextView(this);
                    chip.setText((cur ? "✓ " : "") + candidateLabel(curTarget, c));
                    chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    chip.setTextColor(cur ? Color.WHITE : textColor());
                    chip.setPadding(dp(10), dp(6), dp(10), dp(6));
                    chip.setBackground(roundRect(cur ? accent()
                            : (night ? Color.rgb(52, 56, 61) : Color.rgb(238, 241, 245)), dp(12)));
                    chip.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { switchCandidate(cand); }
                    });
                    candidateBar.addView(chip);
                }
                TextView edit = new TextView(this);
                edit.setText("✎ 改权重 / 加候选");
                edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                edit.setTextColor(accent());
                edit.setPadding(dp(10), dp(6), dp(10), dp(6));
                edit.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        PetBubbleSeq.Item item = itemAt(curTarget);
                        if (item != null) openChoiceManager(item);
                    }
                });
                candidateBar.addView(edit);
            }
        }
        if (targetNote != null) {
            PetBubbleSeq.Item it = itemAt(curTarget);
            String noteText;
            if (curTarget >= 0 && it != null && it.isChoice() && curCandidate < 0) {
                noteText = "这一项是并列候选，但里面一个候选都没有。点上面的「✎ 改权重 / 加候选」加一个。";
            } else if (curTarget >= 0 && it != null && it.isChoice()) {
                noteText = "「" + targetLabel(curTarget) + "」是**并列候选**：每轮按键重抽一个候选显示。"
                        + "现在编辑的是 " + candidateLabel(curTarget, curCandidate)
                        + "（预览与真机一致）；想改权重点上面那个「✎」";
            } else if (curTarget >= 0 && it != null && PetBubbleSeq.KIND_NORMAL.equals(it.kind)
                    && it.modules.isEmpty()) {
                noteText = "「" + targetLabel(curTarget) + "」是**默认页**：渲染经典四行（标题/余额/状态/峰谷）。"
                        + "在下面加模块就会自动转成「自定义模块页」。";
            } else if (curTarget < 0) {
                noteText = defaultMods.isEmpty()
                        ? "默认泡泡**没有模块**：会渲染经典四行。加模块就变成自定义内容。"
                        : "默认泡泡：" + PetBubbleEditModel.count(rows) + " 个模块 / " + rows.size()
                        + " 行（没有用点击序列时才走这里）";
            } else {
                noteText = "正在编辑「" + targetLabel(curTarget) + "」：" + PetBubbleEditModel.count(rows)
                        + " 个模块 / " + rows.size() + " 行";
            }
            targetNote.setText(RichText.bold(noteText));   // v1.12.2：v1.12.1 漏了这一处
        }
    }

    /** 预览用的峰/谷状态：null = 按时段；TRUE = 强制高峰；FALSE = 强制空闲。 */
    private Boolean previewPeakOverride = null;

    /**
     * 预览状态栏（v1.9.0，用户反馈「峰/谷颜色改了没看出来」）。
     *
     * <p>峰谷的颜色/底色是**按当前时段二选一**的：你在空闲时段改「高峰底色」，屏幕上当然看不出变化。
     * 所以这里给一个强制切换，让两态都能立刻看到 —— 这也是「设置了半天没反应」的正解。
     */
    private void rebuildPreviewStateBar() {
        if (previewStateBar == null) return;
        previewStateBar.removeAllViews();
        final Boolean[] values = {null, Boolean.TRUE, Boolean.FALSE};
        final String[] names = {"按真实时段", "强制高峰", "强制空闲"};
        for (int i = 0; i < values.length; i++) {
            boolean cur = (previewPeakOverride == null && values[i] == null)
                    || (previewPeakOverride != null && previewPeakOverride.equals(values[i]));
            TextView chip = new TextView(this);
            chip.setText((cur ? "✓ " : "") + names[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setTextColor(cur ? Color.WHITE : textColor());
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setBackground(roundRect(cur ? accent()
                    : (night ? Color.rgb(52, 56, 61) : Color.rgb(238, 241, 245)), dp(12)));
            final Boolean v = values[i];
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    previewPeakOverride = v;
                    Log.write("编辑器：预览峰谷状态 → " + (v == null ? "按真实时段"
                            : (v ? "强制高峰" : "强制空闲")));
                    rebuildPreviewStateBar();
                    rebuildPreview();
                }
            });
            previewStateBar.addView(chip);
        }
    }

    /**
     * 弹一个**真实的悬浮泡泡**，内容用当前目标的**工作副本**（不写盘）。
     *
     * <p>用户反馈「设置里的预览泡泡是自欺欺人：不显示我编辑的内容」。原因是那个按钮走的是
     * 服务的默认页（已保存配置），而用户刚编辑的东西还在编辑器里。现在改为：由编辑器把
     * 工作副本通过控制广播交给服务渲染 —— 真正「所见即所得」，而且**不落盘**。
     */
    private void popRealPreview() {
        try {
            writeBackCurrent();
            String json = PetBubbleModule.listToJson(PetBubbleEditModel.toModules(rows));
            Intent i = new Intent(PetControlReceiver.ACTION_CONTROL);
            i.setPackage(getPackageName());
            i.putExtra("token", PetPaths.controlToken());
            i.putExtra("action", PetService.ACTION_TEST_EDIT);
            i.putExtra("fromJson", json);
            i.putExtra("show", true);
            i.putExtra("save", false);
            sendBroadcast(i);
            Log.write("编辑器：弹出真实泡泡预览（" + targetLabel(curTarget) + "，"
                    + PetBubbleEditModel.count(rows) + " 个模块，不写盘）");
            toast("已弹出真实泡泡（用的是当前编辑内容，不会写盘）");
        } catch (Exception e) {
            Log.write("编辑器：弹真实泡泡失败: " + e);
            toast("弹出失败：" + e);
        }
    }

    // ---------------------------------------------------------------- 序列管理（并入编辑器）

    /** 把各目标的内容同步回序列工作副本（做结构性改动前必须调用，否则会丢改动）。 */

    /** 序列结构改动后：重建各目标的工作副本，并尽量保持当前目标。 */

    /**
     * 「点击序列」管理（v1.9.0：把原四层嵌套的序列编辑器压成一页）。
     *
     * <p>这里能做的：增删「第 N 次点击」、切类型（默认页 / 自定义模块页 / 并列候选）、
     * 改并列候选的权重、上移下移、恢复默认序列。**内容编辑请在上面的目标栏切过去改**。
     */
    private void openSeqManager() {
        writeBackCurrent();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, dp(6), pad, dp(6));
        col.addView(note("点桌宠第 1 次会显示「第 1 次点击」的内容；再点会推进到下一项，末项再点收起。"
                + "「并列候选」是每轮按权重抽一个（内容在各自候选里）。"));

        for (int i = 0; i < workingSeq.items.size(); i++) {
            final int idx = i;
            final PetBubbleSeq.Item it = workingSeq.items.get(i);
            col.addView(dialogRow("第 " + (i + 1) + " 次点击：" + it.describe(),
                    "点一下切换类型（默认页 / 自定义模块页 / 并列候选）", new Runnable() {
                        @Override public void run() {
                            new AlertDialog.Builder(PetBubbleEditorActivity.this)
                                    .setTitle("第 " + (idx + 1) + " 次点击的类型")
                                    .setItems(new String[]{"默认页（余额，经典四行）",
                                                    "自定义模块页（在上面目标栏里拖模块）",
                                                    "并列候选（每轮按权重抽一个）"},
                                            new android.content.DialogInterface.OnClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d, int which) {
                                            String kind = which == 0 ? PetBubbleSeq.KIND_NORMAL
                                                    : which == 1 ? PetBubbleSeq.KIND_CUSTOM
                                                    : PetBubbleSeq.KIND_CHOICE;
                                            it.kind = kind;
                                            if (PetBubbleSeq.KIND_CHOICE.equals(kind) && it.options.isEmpty()) {
                                                it.options.add(new PetBubbleSeq.Option(1,
                                                        PetBubbleSeq.newItem(PetBubbleSeq.KIND_NORMAL)));
                                                it.options.add(new PetBubbleSeq.Option(1,
                                                        PetBubbleSeq.newItem(PetBubbleSeq.KIND_NORMAL)));
                                            }
                                            afterStructureChange();
                                            afterStructureChange();
                                            openSeqManager();
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    }));
            // 并列候选：权重可调
            if (it.isChoice()) {
                col.addView(note("　　· 并列候选（" + it.options.size() + " 个）："
                        + choiceWeightsText(it) + "　点下面「改权重」调整"));
                col.addView(dialogRow("　　改权重", "每轮按权重随机抽一个（相对值）",
                        new Runnable() {
                            @Override public void run() { openChoiceManager(it); }
                        }));
            }
            col.addView(dialogRow("　　↑ 上移 ｜ ↓ 下移 ｜ ✕ 删除这一项",
                    "顺序就是点桌宠时推进的顺序", new Runnable() {
                        @Override public void run() {
                            new AlertDialog.Builder(PetBubbleEditorActivity.this)
                                    .setTitle("第 " + (idx + 1) + " 次点击")
                                    .setItems(new String[]{"↑ 上移", "↓ 下移", "✕ 删除"},
                                            new android.content.DialogInterface.OnClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d, int which) {
                                            if (which == 0 && !PetBubbleSeq.moveUp(workingSeq.items, idx)) {
                                                toast("已经在最前");
                                                return;
                                            }
                                            if (which == 1 && !PetBubbleSeq.moveDown(workingSeq.items, idx)) {
                                                toast("已经在最后");
                                                return;
                                            }
                                            if (which == 2) {
                                                if (workingSeq.items.size() <= 1) {
                                                    toast("至少保留一项");
                                                    return;
                                                }
                                                PetBubbleSeq.removeAt(workingSeq.items, idx);
                                            }
                                            afterStructureChange();
                                            afterStructureChange();
                                            openSeqManager();
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    }));
        }
        col.addView(dialogRow("➕ 新增一次点击", "追加到最后（可再切类型）", new Runnable() {
            @Override public void run() {
                new AlertDialog.Builder(PetBubbleEditorActivity.this)
                        .setTitle("新增「第 " + (workingSeq.items.size() + 1) + " 次点击」")
                        .setItems(new String[]{"默认页（余额）", "自定义模块页", "并列候选"},
                                new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                String kind = which == 0 ? PetBubbleSeq.KIND_NORMAL
                                        : which == 1 ? PetBubbleSeq.KIND_CUSTOM
                                        : PetBubbleSeq.KIND_CHOICE;
                                workingSeq.items.add(PetBubbleSeq.newItem(kind));
                                afterStructureChange();
                                afterStructureChange();
                                openSeqManager();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        }));
        col.addView(dialogRow("↺ 恢复内置默认序列", "2 项：余额页 → 并列候选页（峰谷倒计时 / 消费）",
                new Runnable() {
                    @Override public void run() {
                        workingSeq = PetBubbleSeq.defaultSeq();
                        afterStructureChange();
                        openSeqManager();
                    }
                }));
        col.addView(dialogRow("点桌宠是否推进序列（tapAdvance）",
                workingSeq.tapAdvance
                        ? "✓ 开：点桌宠 = 推进下一项"
                        : "关（默认）：点桌宠只续时 / 回到第 1 项", new Runnable() {
                    @Override public void run() {
                        workingSeq.tapAdvance = !workingSeq.tapAdvance;
                        rebuildAll();
                    }
                }));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle("点击序列（" + workingSeq.items.size() + " 项）")
                .setView(sv)
                .setPositiveButton("完成", null)
                .show();
    }

    private String choiceWeightsText(PetBubbleSeq.Item it) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < it.options.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append("w=").append(PetBubbleSeq.weightOf(it.options.get(i)));
        }
        return sb.toString();
    }

    /**
     * 并列项的候选管理（v1.11.0）：改权重 / 加候选 / 删候选。
     *
     * <p>用户反馈「按权重来的吧？你这写的太模糊了！而且这个权重改变我没找到入口啊，太隐蔽了」→
     * 现在候选和权重**直接摆在目标栏下面**，这里负责改权重与增删候选。
     */
    private void openChoiceManager(final PetBubbleSeq.Item it) {
        writeBackCurrent();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, dp(6), pad, dp(6));
        col.addView(note("「并列候选」= 每轮点泡泡时**按权重随机抽一个**候选显示（权重越大越常出现）。"
                + "改某个候选的内容：点上面的候选芯片切过去，再到 ② 里加/拖模块。"));
        for (int i = 0; i < it.options.size(); i++) {
            final PetBubbleSeq.Option o = it.options.get(i);
            final int idx = i;
            col.addView(dialogRow("候选 " + (i + 1) + "：权重 " + PetBubbleSeq.weightOf(o),
                    "内容：" + (o.item == null ? "（空）" : o.item.describe()), new Runnable() {
                        @Override public void run() {
                            new AlertDialog.Builder(PetBubbleEditorActivity.this)
                                    .setTitle("候选 " + (idx + 1))
                                    .setItems(new String[]{"改权重", "🗑 删除这个候选"},
                                            new android.content.DialogInterface.OnClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d, int which) {
                                            if (which == 0) {
                                                askNumber("候选权重（正整数，最小按 1 算）",
                                                        PetBubbleSeq.weightOf(o), new IntConsumer() {
                                                            @Override public void accept(int v) {
                                                                o.w = Math.max(1, v);
                                                                Log.write("编辑器：候选 " + (idx + 1)
                                                                        + " 权重 → " + o.w);
                                                                afterStructureChange();
                                                                openChoiceManager(it);
                                                            }
                                                        });
                                            } else {
                                                if (it.options.size() <= 1) {
                                                    toast("至少保留 1 个候选");
                                                    return;
                                                }
                                                PetBubbleSeq.removeAt(it.options, idx);
                                                if (curCandidate >= it.options.size()) {
                                                    curCandidate = it.options.size() - 1;
                                                }
                                                loadRowsFromSlot();
                                                afterStructureChange();
                                                openChoiceManager(it);
                                            }
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    }));
        }
        col.addView(dialogRow("➕ 加一个候选（自定义模块页）",
                "默认带一句文字，切过去就能改", new Runnable() {
                    @Override public void run() {
                        it.options.add(new PetBubbleSeq.Option(1,
                                PetBubbleSeq.newItem(PetBubbleSeq.KIND_CUSTOM)));
                        afterStructureChange();
                        openChoiceManager(it);
                    }
                }));
        col.addView(dialogRow("➕ 加一个候选（随机文字页）",
                "从上游全部 13 句语录里等概率抽一句（「好模型...↓」那批）", new Runnable() {
                    @Override public void run() {
                        it.options.add(new PetBubbleSeq.Option(1,
                                PetBubbleSeq.randomItem(PetBubbleModule.presetLinesAll())));
                        afterStructureChange();
                        openChoiceManager(it);
                    }
                }));
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle("并列候选（" + it.options.size() + " 个）")
                .setView(sv)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 序列结构改过（增删项/候选、改权重、换顺序）后：把当前内容写回 + 重建界面（**不重快照**，改动仍算未保存）。 */
    private void afterStructureChange() {
        writeBackCurrent();
        rebuildAll();
    }
    // ---------------------------------------------------------------- 帮助 / 高级

    private void openHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("【本版未支持的模块类型】\n");
        for (String s : PetBubbleModule.UNSUPPORTED_TYPES) sb.append("· ").append(s).append('\n');
        sb.append("\n【跑马灯配色（").append(PetBubbleGradients.size()).append(" 套，与上游同名）】\n");
        String[] names = PetBubbleGradients.names();
        for (int i = 0; i < names.length; i++) {
            sb.append(names[i]).append(i == names.length - 1 ? "" : "、");
        }
        sb.append("\n\n【当前配置一览】\n");
        sb.append("默认泡泡：").append(PetBubbleEditModel.describe(
                PetBubbleEditModel.rowsOf(defaultMods))).append('\n');
        sb.append("点击序列：").append(workingSeq.describe()).append('\n');
        sb.append("峰谷（全局）：").append(peakSummary()).append('\n');
        sb.append("文字颜色（全局）：").append(txSummary()).append('\n');
        sb.append("泡泡大小：").append(PetState.bubbleScaleLabel(state.bubbleScalePercent));
        sb.append("\n\n【导入导出 JSON（高级）】\n可以用「编辑 JSON」把手写的模块/序列粘进来，也能复制出去备份。");

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, dp(6), pad, dp(6));
        TextView tv = new TextView(this);
        tv.setText(RichText.bold(sb.toString()));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tv.setTextColor(textColor());
                tv.setTextIsSelectable(true);
        col.addView(tv);
        col.addView(dialogRow("✎ 编辑默认泡泡的模块 JSON",
                "粘贴上游/别处的模块 JSON（会先校验，解析不了就不改动）", new Runnable() {
                    @Override public void run() {
                        final String cur = PetBubbleModule.listToJson(
                                PetBubbleEditModel.toModules(PetBubbleEditModel.rowsOf(
                                        defaultMods)));
                        askText("默认泡泡模块 JSON", cur, "[{\"type\":\"text\",...}]", new Consumer<String>() {
                            @Override public void accept(String s) {
                                String t = s == null ? "" : s.trim();
                                if (t.isEmpty()) {
                                    defaultMods.clear();
                                } else {
                                    java.util.List<PetBubbleModule> parsed =
                                            PetBubbleModule.listFromJson(t);
                                    if (parsed.isEmpty()) {
                                        toast("JSON 解析不了，已忽略（未改动）");
                                        return;
                                    }
                                    defaultMods.clear();
                                    defaultMods.addAll(parsed);
                                }
                                loadRowsFromSlot();
                                rebuildAll();
                                toast("已载入 JSON（点「保存」才写盘）");
                            }
                        });
                    }
                }));
        col.addView(dialogRow("✎ 编辑点击序列 JSON", "形状与上游一致：{v, items, tapAdvance}",
                new Runnable() {
                    @Override public void run() {
                        askText("点击序列 JSON", workingSeq.toJson(),
                                "{\"v\":1,\"tapAdvance\":false,\"items\":[...]}",
                                new Consumer<String>() {
                                    @Override public void accept(String s) {
                                        String t = s == null ? "" : s.trim();
                                        if (t.isEmpty()) {
                                            workingSeq = PetBubbleSeq.defaultSeq();
                                            afterStructureChange();
                                            return;
                                        }
                                        PetBubbleSeq parsed = PetBubbleSeq.fromJson(t);
                                        if (parsed.parseFailed || parsed.items.isEmpty()) {
                                            toast("JSON 解析不了（或没有 items），已忽略");
                                            return;
                                        }
                                        workingSeq = parsed;
                                        afterStructureChange();
                                        toast("已载入序列（点「保存」才写盘）");
                                    }
                                });
                    }
                }));

        col.addView(dialogRow("⚙ 峰谷默认值（「经典四行」用）",
                peakSummary() + "\n用模块的泡泡由模块自己决定（峰谷模块的 ✎ 里选样式/颜色）",
                new Runnable() {
                    @Override public void run() { openPeakSettings(); }
                }));
        col.addView(dialogRow("🎨 文字颜色默认值（「经典四行」用）",
                txSummary() + "\n用模块的泡泡请在 ✎ 里给每个模块单独设色",
                new Runnable() {
                    @Override public void run() { openTextColors(); }
                }));
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle("帮助与高级")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 纯色选择器（不含跑马灯；用于全局正文/提示行这类「只能纯色」的位置）。 */
    private void pickPlainHex(final int current, String title, final Consumer<String> consumer) {
        final String[] names = {
                "正文蓝 #536BA9（上游默认）", "提示蓝 #9FB0D9（上游默认）", "白 #FFFFFF", "黑 #000000",
                "藏青 #203170", "灰 #8A94A6", "峰色 #E0433F", "谷色 #2FA24C",
        };
        final int[] values = {0x536BA9, 0x9FB0D9, 0xFFFFFF, 0x000000, 0x203170, 0x8A94A6, 0xE0433F, 0x2FA24C};
        String currentHex = String.format(Locale.US, "#%06X", current & 0xFFFFFF);
        String[] items = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            items[i] = names[i] + (String.format(Locale.US, "#%06X", values[i])
                    .equalsIgnoreCase(currentHex) ? "　✓ 当前" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(title + "（当前 " + currentHex + "）")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        consumer.accept(String.format(Locale.US, "#%06X", values[which]));
                    }
                })
                .setNeutralButton("自定义…", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        askText(title + "（#RRGGBB）",
                                String.format(Locale.US, "%06X", current & 0xFFFFFF), "6 位十六进制",
                                new Consumer<String>() {
                                    @Override public void accept(String s) {
                                        String t = s == null ? "" : s.trim();
                                        if (PetBubbleModule.parseHex(t) < 0) {
                                            toast("看不懂这个颜色：请输入 #RRGGBB");
                                            return;
                                        }
                                        consumer.accept(t.startsWith("#") ? t : "#" + t);
                                    }
                                });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 重建「⚙ 全局设置」卡（v1.10.0 瘦身：只剩「点击序列」与「帮助与高级」）。 */
    private void rebuildGlobalCard() {
        if (globalCard == null) return;
        globalCard.removeAllViews();
        TextView title = new TextView(this);
        title.setText("⚙ 其它（内容都在上面改，这里只放两件不常动的）");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(accent());
        title.setPadding(dp(4), dp(10), dp(4), 0);
        globalCard.addView(title);
        globalCard.addView(seqEntryRow());
        globalCard.addView(helpEntryRow());
    }

    private View txEntryRow() {
        return dialogRow("🎨 泡泡文字颜色（全局）", txSummary()
                + "\n正文色 / 提示行色 / 恢复上游默认（模块自己设过颜色的不受影响）",
                new Runnable() {
                    @Override public void run() { openTextColors(); }
                });
    }

    private View seqEntryRow() {
        return dialogRow("🧩 点击序列（" + workingSeq.items.size() + " 项）",
                workingSeq.describe()
                        + "\n增删「第 N 次点击」/ 切类型 / 并列候选权重（原「序列编辑器」已并到这里）",
                new Runnable() {
                    @Override public void run() { openSeqManager(); }
                });
    }

    private View helpEntryRow() {
        return dialogRow("❓ 帮助与高级",
                "未支持的类型 / " + PetBubbleGradients.size() + " 套跑马灯配色名 / 导入导出 JSON / 当前配置一览"
                        + "\n（「经典四行」泡泡的外观默认值也收在这里 —— 用模块的泡泡不受影响）",
                new Runnable() {
                    @Override public void run() { openHelp(); }
                });
    }

    private View peakEntryRow() {
        return dialogRow("峰谷（全局设置）",
                peakSummary() + "\n点这里改：显示样式 / 倒计时格式 / 峰谷颜色 / 两态文字 / 峰谷行开关",
                new Runnable() {
                    @Override public void run() { openPeakSettings(); }
                });
    }

    // ---------------------------------------------------------------- 界面

    // ---- v1.12.1：编辑器在前台时把桌宠/泡泡临时收起（悬浮窗会盖住编辑界面：用户截图里副标题被立绘截断） ----
    @Override protected void onResume() {
        super.onResume();
        setPetEditorMode(true);
    }
    @Override protected void onPause() {
        setPetEditorMode(false);
        super.onPause();
    }
    @Override protected void onStop() {
        setPetEditorMode(false);   // v1.12.3：切到别的 App/任务列表时也要让桌宠立刻回来
        super.onStop();
    }
    @Override protected void onDestroy() {
        setPetEditorMode(false);
        super.onDestroy();
    }
    /**
     * 通知服务进入/退出「编辑器模式」。
     *
     * <p>桌宠没在跑就**不发**：否则 startService 会把服务拉起来，反而在编辑时冒出桌宠。
     * 通知失败只写日志，不假装成功。
     */
    private void setPetEditorMode(boolean on) {
        try {
            if (!petServiceLooksRunning()) return;
            startService(new Intent(this, PetService.class)
                    .setAction(PetService.ACTION_EDITOR_MODE)
                    .putExtra("on", on));
        } catch (Exception e) {
            Log.write("编辑器模式通知服务失败（on=" + on + "）: " + e);
        }
    }
    /** 桌宠是否在跑（读服务每秒写一次的状态快照；读不到就当没在跑）。 */
    private boolean petServiceLooksRunning() {
        String text = CredentialStore.readText(PetPaths.statusFile());
        if (text == null || text.trim().isEmpty()) return false;
        try {
            return new JSONObject(text).optBoolean("running", false);
        } catch (Exception e) {
            return false;
        }
    }
    /**
     * smoke 专用（v1.12.4）：把顶部「目标栏 / 候选栏 / 各候选芯片」的**实际布局坐标**打出来，
     * 用来证明「候选芯片没有被上面那排切掉」（gap > 0 且互不重叠）。
     */
    private String targetLayoutReport() {
        if (targetBar == null) return "（目标栏不存在）";
        StringBuilder sb = new StringBuilder();
        sb.append("目标栏 y[").append(targetBar.getTop()).append("..").append(targetBar.getBottom())
                .append("] h=").append(targetBar.getHeight());
        if (candidateBar != null) {
            sb.append(" ｜ 候选栏 y[").append(candidateBar.getTop()).append("..")
                    .append(candidateBar.getBottom()).append("] h=").append(candidateBar.getHeight())
                    .append(" gap=").append(candidateBar.getTop() - targetBar.getBottom()).append("px");
            for (int i = 0; i < candidateBar.getChildCount(); i++) {
                View c = candidateBar.getChildAt(i);
                sb.append(" ｜ 候选").append(i + 1).append(" y[").append(c.getTop())
                        .append("..").append(c.getBottom()).append("]");
            }
        }
        return sb.toString();
    }
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(bg());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        // ---- 顶部标题栏 ----
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(10));
        header.setBackgroundColor(bg());
        TextView title = new TextView(this);
        title.setText("泡泡内容编辑");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(textColor());
        header.addView(title);
        TextView sub = new TextView(this);
        sub.setText("默认泡泡 / 各次点击 / 峰谷 / 文字颜色 / 序列结构");
        sub.append(" · " + MainActivity.VERSION);
        sub.append("（打开编辑器时桌宠会临时让开；详细说明见「❓ 帮助与高级」）");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(subColor());
        sub.setPadding(0, dp(3), 0, 0);
        header.addView(sub);
        dragHint = new TextView(this);
        dragHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dragHint.setTextColor(accent());
        dragHint.setPadding(0, dp(4), 0, 0);
        dragHint.setVisibility(View.GONE);
        header.addView(dragHint);
        column.addView(header);

        // ---- 滚动内容 ----
        PetDragListLayout.DragScrollView scroll = new PetDragListLayout.DragScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(4), dp(14), dp(24));
        // ① 编辑目标（v1.9.0：把「默认泡泡」与「第 N 次点击」合成同一个编辑器，顶部切换）
        content.addView(sectionTitle("① 编辑目标（点一下切换，内容/预览/保存都跟着切）"));
        LinearLayout targetWrap = new LinearLayout(this);
        targetWrap.setOrientation(LinearLayout.VERTICAL);
        targetWrap.setBackground(roundRect(cardBg(), dp(12)));
        targetWrap.setPadding(dp(10), dp(10), dp(10), dp(10));
        targetBar = new FlowLayout(this, dp(6));
        targetWrap.addView(targetBar);
        candidateBar = new FlowLayout(this, dp(6));
        // v1.12.4：FlowLayout 以前忽略 padding → 这 6dp 从未生效（候选 1 贴着上一排，看起来被切）。
                // 现在真的生效，并加到 10dp，分隔一眼可见。
                candidateBar.setPadding(0, dp(10), 0, 0);
        targetWrap.addView(candidateBar);
        targetNote = new TextView(this);
        targetNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        targetNote.setTextColor(subColor());
        targetNote.setPadding(dp(2), dp(8), dp(2), 0);
        targetWrap.addView(targetNote);
        content.addView(targetWrap);

        // ② 内容（v1.10.0 瘦身：调色板收成一行按钮，屏幕不再花花绿绿）
        content.addView(sectionTitle("② 泡泡内容（长按 ⠿ 拖整行 ｜ 长按芯片拖单个模块）"));
        LinearLayout addCard = new LinearLayout(this);
        addCard.setOrientation(LinearLayout.VERTICAL);
        addCard.setBackground(roundRect(cardBg(), dp(12)));
        addCard.setPadding(dp(4), dp(2), dp(4), dp(2));
        addCard.addView(dialogRow("➕ 添加模块", paletteSummary(),
                new Runnable() {
                    @Override public void run() { pickTypeToAdd(); }
                }));
        content.addView(addCard);
        list = new PetDragListLayout(this);
        list.setListener(this);
        list.setOverlayParent(root);
        LinearLayout listCard = new LinearLayout(this);
        listCard.setOrientation(LinearLayout.VERTICAL);
        listCard.setBackground(roundRect(cardBg(), dp(12)));
        listCard.setPadding(dp(6), dp(6), dp(6), dp(6));
        listCard.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(listCard);
        content.addView(note("拖到某行的**上边缘**=插到这一行之前；**下边缘**=之后；"
                + "行内**左半 / 右半**=与这一行**并排**（上游同款四态）。"
                + "每行最多 " + PetBubbleModule.MAX_PER_ROW + " 个、每泡最多 "
                + PetBubbleModule.MAX_ROWS + " 行（上游硬约束）。"));

        // ④ 预览
        content.addView(sectionTitle("③ 实时预览（与真泡泡同一套渲染）"));
        LinearLayout stateWrap = new LinearLayout(this);
        stateWrap.setOrientation(LinearLayout.VERTICAL);
        stateWrap.setBackground(roundRect(cardBg(), dp(12)));
        stateWrap.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewStateBar = new FlowLayout(this, dp(6));
        stateWrap.addView(previewStateBar);
        content.addView(stateWrap);
        rebuildPreviewStateBar();
        previewBox = new PreviewBox(this);
        content.addView(previewBox);
        dataSourceText = note("");
        content.addView(dataSourceText);
        content.addView(dialogRow("🫧 弹一个真实泡泡（用当前编辑内容）",
                "用悬浮窗渲染当前目标的**工作副本**，所见即所得；不会写盘",
                new Runnable() {
                    @Override public void run() { popRealPreview(); }
                }));
        content.addView(note("预览按比例放大显示（真实泡泡画布约 " + Math.round(realCanvasWidth())
                + "px 宽 · 当前大小 " + PetState.bubbleScaleLabel(state.bubbleScalePercent)
                + "），所以这里看到的效果与桌面上一致。"));

        // ④ 其它（v1.10.0 瘦身：峰谷/文字颜色这两项对用模块的泡泡是多余的，已收进「帮助与高级」）
        globalCard = new LinearLayout(this);
        globalCard.setOrientation(LinearLayout.VERTICAL);
        globalCard.setBackground(roundRect(cardBg(), dp(12)));
        globalCard.setPadding(dp(12), dp(4), dp(12), dp(8));
        content.addView(globalCard);
        rebuildGlobalCard();

        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.attach(list);
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column.addView(bottomBar());
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /** 兜底扫描后仍含字面 {@code **} 的 TextView 数（正常 0；smoke 会打日志核对）。 */
    private int rawMarkerLeft = 0;
    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(12));
        bar.setBackgroundColor(bg());

        statusText = new TextView(this);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusText.setTextColor(subColor());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(statusText);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(6), 0, 0);
        buttons.addView(barButton("取消", false, new Runnable() {
            @Override public void run() { confirmCancel(); }
        }), weight1());
        buttons.addView(barButton("恢复默认", false, new Runnable() {
            @Override public void run() { restoreDefault(); }
        }), weight1());
        buttons.addView(barButton("保存", true, new Runnable() {
            @Override public void run() { saveAndFinish(); }
        }), weight1());
        column.addView(buttons);
        bar.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private LinearLayout.LayoutParams weight1() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(6));
        return lp;
    }

    private TextView barButton(String label, boolean primary, final Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(primary ? Color.WHITE : textColor());
        tv.setPadding(dp(10), dp(11), dp(10), dp(11));
        tv.setBackground(roundRect(primary ? accent()
                : (night ? Color.rgb(42, 45, 49) : Color.rgb(236, 239, 243)), dp(10)));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return tv;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(accent());
        tv.setPadding(dp(2), dp(18), dp(2), dp(8));
        return tv;
    }

    private TextView note(String text) {
        TextView tv = new TextView(this);
        tv.setText(RichText.bold(text));   // v1.12.1：**强调** 渲染成真加粗（以前把星号画在屏幕上）
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(subColor());
        tv.setPadding(dp(2), dp(6), dp(2), dp(2));
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    // ---------------------------------------------------------------- 重建

    private void rebuildAll() {
        rebuildTargetBar();
        rebuildRows();
        rebuildPreviewStateBar();
        rebuildPreview();
        rebuildGlobalCard();
        refreshStatusLine();
        // v1.12.2 兜底：扫一遍视图树，页面上不该再出现字面的 markdown 星号
        rawMarkerLeft = boldRemainingMarkers(root);
    }
    /**
     * 兜底（v1.12.2）：把视图树里任何仍带字面 {@code **} 的 TextView 就地转成加粗，
     * 并返回“处理完还剩几个”。
     *
     * <p>为什么要兜底：显示文案的 setText 调用点不止 note()/dialogRow() 两处，
     * v1.12.1 就漏了「编辑目标」下面那段说明（用户第二张截图里星号依旧）。
     * 本方法幂等：没有标记的文本一个字都不会动。
     */
    private int boldRemainingMarkers(View view) {
        if (view == null) return 0;
        int left = 0;
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            if (cs != null && cs.toString().contains("**")) {
                tv.setText(RichText.bold(cs.toString()));
                cs = tv.getText();
            }
            if (cs != null && cs.toString().contains("**")) left++;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) left += boldRemainingMarkers(g.getChildAt(i));
        }
        return left;
    }

    private void rebuildRows() {
        list.removeAllViews();
        list.setRows(rows);
        if (!slotEditable()) {
            // 「并列候选」项：不给拖拽（避免把候选结构弄丢），只提示去哪儿改
            list.addView(note("（这一项是「并列候选」，但里面还没有候选 —— "
                    + "点顶部候选栏的「✎ 改权重 / 加候选」加一个）"
                    + ""));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            list.addView(buildRowView(i, rows.get(i)), lp);
        }
        if (rows.isEmpty()) {
            list.addView(note("（没有任何模块 → 泡泡会退回「经典四行」渲染）"));
        }
    }

    private View buildRowView(final int rowIdx, final List<PetBubbleModule> row) {
        LinearLayout rowView = new LinearLayout(this);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        rowView.setGravity(Gravity.CENTER_VERTICAL);
        rowView.setBackground(roundRect(night ? Color.rgb(38, 41, 45) : Color.rgb(243, 245, 248), dp(10)));
        rowView.setPadding(dp(4), dp(5), dp(6), dp(5));
        // 行的空白处不参与拖拽（只有 ⠿ 手柄能拖整行，避免误触）
        rowView.setTag(new PetDragListLayout.Slot(rowIdx, PetDragListLayout.NO_DRAG_COL));

        TextView handle = new TextView(this);
        handle.setText("⠿");
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        handle.setTextColor(accent());
        handle.setPadding(dp(8), dp(2), dp(8), dp(2));
        handle.setTag(new PetDragListLayout.Slot(rowIdx, PetBubbleEditModel.HANDLE));
        rowView.addView(handle);

        FlowLayout chips = new FlowLayout(this, dp(6));
        for (int j = 0; j < row.size(); j++) {
            chips.addView(buildChip(rowIdx, j, row.get(j)));
        }
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rowView.addView(chips, chipsLp);

        TextView add = new TextView(this);
        add.setText("＋");
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        add.setTextColor(accent());
        add.setPadding(dp(10), dp(4), dp(10), dp(4));
        add.setTag(new PetDragListLayout.Slot(rowIdx, PetDragListLayout.NO_DRAG_COL));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickTypeForRow(rowIdx, row); }
        });
        rowView.addView(add);
        return rowView;
    }

    private View buildChip(final int rowIdx, final int col, final PetBubbleModule m) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(roundRect(night ? Color.rgb(52, 56, 61) : Color.WHITE, dp(8)));
        chip.setPadding(dp(8), dp(4), dp(4), dp(4));
        chip.setTag(new PetDragListLayout.Slot(rowIdx, col));

        TextView name = new TextView(this);
        name.setText(chipLabel(m));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTextColor(textColor());
        chip.addView(name);

        chip.addView(chipAction("✎", new Runnable() {
            @Override public void run() { openModuleEditor(rowIdx, col, m); }
        }));
        chip.addView(chipAction("✕", new Runnable() {
            @Override public void run() {
                PetBubbleEditModel.removeModule(rows, rowIdx, col);
                Log.write("编辑器：删除模块（行 " + (rowIdx + 1) + " 第 " + (col + 1) + " 个）");
                rebuildAll();
            }
        }));
        return chip;
    }

    private TextView chipAction(String label, final Runnable action) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(subColor());
        tv.setPadding(dp(7), dp(2), dp(7), dp(2));
        // ✎ / ✕ 不参与拖拽：长按它们不会拾起芯片（避免「想删结果拖起来」）
        tv.setTag(new PetDragListLayout.Slot(-1, PetDragListLayout.NO_DRAG_COL));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return tv;
    }

    private static String chipLabel(PetBubbleModule m) {
        String label = PetBubbleModule.typeLabel(m.type);
        if (PetBubbleModule.TYPE_TEXT.equals(m.type) && m.text != null && !m.text.isEmpty()) {
            String t = m.text;
            return label + "：" + (t.length() > 8 ? t.substring(0, 8) + "…" : t);
        }
        if (PetBubbleModule.TYPE_PEAK.equals(m.type) && m.peakStyle >= 0) {
            return label + "（" + PeakValley.styleDisplayName(m.peakStyle) + "）";
        }
        return label;
    }

    // ---------------------------------------------------------------- 调色板


    private void addFromPalette(String key) {
        if (!slotEditable()) {
            toast("先给这一项加一个候选（顶部「✎ 改权重 / 加候选」）");
            return;
        }
        PetBubbleModule m = moduleForPalette(key);
        int at = PetBubbleEditModel.addRow(rows, m, -1);
        if (at < 0) {
            toast("已经到上限：一个泡泡最多 " + PetBubbleModule.MAX_ROWS + " 行");
            return;
        }
        Log.write("编辑器：从调色板加入「" + PetBubbleModule.typeLabel(m.type) + "」→ 第 " + (at + 1) + " 行");
        rebuildAll();
    }

    private static PetBubbleModule moduleForPalette(String key) {
        if ("peak-count".equals(key)) {
            PetBubbleModule m = PetBubbleModule.newOf(PetBubbleModule.TYPE_PEAK);
            m.peakStyle = PeakValley.STYLE_COUNT;
            m.size = 23;
            m.bold = true;
            m.ul = true;
            return m;
        }
        return PetBubbleModule.newOf(key);
    }

    /**
     * 「➕ 添加模块」（v1.10.0：替代原来的调色板芯片墙 —— 用户反馈「内容太乱、眼花缭乱」）。
     */
    /**
     * 「➕ 添加模块」的副标题（v1.12.1）：**直接按可加清单生成** ——
     * 以前那行是手写的，既漏了「随机语句」和 v1.12.0 新加的「今日已用」，
     * 还指向一个根本不存在的「＋新建模块」模块库（点开只会是空的，属于不实文案）。
     */
    private String paletteSummary() {
        StringBuilder sb = new StringBuilder();
        for (String[] p : PALETTE) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(p[0]);
        }
        return sb.toString();
    }
    private void pickTypeToAdd() {
        if (!slotEditable()) {
            toast("先给这一项加一个候选（顶部「✎ 改权重 / 加候选」）");
            return;
        }
        final String[] names = new String[PALETTE.length];
        for (int i = 0; i < PALETTE.length; i++) names[i] = PALETTE[i][0];
        new AlertDialog.Builder(this)
                .setTitle("加一个模块到「" + targetLabel(curTarget) + "」末尾")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        addFromPalette(PALETTE[which][1]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 「随机语句」的行编辑。
      *
      * <p>v1.12.0 按用户要求简化：**每句等概率，权重不再出现在界面上**
      * （{@code w} 字段仍然写回 JSON，只为跟上游互通；抽取时不看它）。
      * 用户仍然可以自由改文字 / 加句 / 删句 / 改字号档，也能一键套用上游的全部 13 句语录。
     */
    private void openLinesEditor(final PetBubbleModule m) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col.setPadding(pad, dp(6), pad, dp(6));
        col.addView(note("每轮显示哪一句 = **每句等概率**（v1.12.0 起不用权重了；尽量不连续两次同一句）。"
                + "字号档留 0 = 用模块自己的字号档。"));
        for (int i = 0; i < m.lines.size(); i++) {
            final PetBubbleModule.Line ln = m.lines.get(i);
            final int idx = i;
            col.addView(dialogRow("第 " + (i + 1) + " 句：" + ln.describe(),
                    "点开改文字 / 改字号档 / 删除", new Runnable() {
                        @Override public void run() {
                            new AlertDialog.Builder(PetBubbleEditorActivity.this)
                                    .setTitle("第 " + (idx + 1) + " 句")
                                    .setItems(new String[]{"✎ 改文字", "🔢 改字号档",
                                                    "🗑 删除这一句"},
                                            new android.content.DialogInterface.OnClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d, int which) {
                                            if (which == 0) {
                                                askText("句子文字", ln.text, "例如：好模型...↓",
                                                        new Consumer<String>() {
                                                            @Override public void accept(String s) {
                                                                ln.text = s == null ? "" : s;
                                                                afterModuleEdit();
                                                                openLinesEditor(m);
                                                            }
                                                        });
                                            } else if (which == 1) {
                                                askNumber("字号档（0 = 跟随模块；1–50）",
                                                        Math.max(0, ln.size), new IntConsumer() {
                                                            @Override public void accept(int v) {
                                                                ln.size = Math.max(0, Math.min(50, v));
                                                                afterModuleEdit();
                                                                openLinesEditor(m);
                                                            }
                                                        });
                                            } else {
                                                if (m.lines.size() <= 1) {
                                                    toast("至少留一句（否则泡泡会空）");
                                                    return;
                                                }
                                                m.lines.remove(idx);
                                                afterModuleEdit();
                                                openLinesEditor(m);
                                            }
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    }));
        }
        col.addView(dialogRow("➕ 加一句", "等概率（权重固定 1）", new Runnable() {
            @Override public void run() {
                askText("新句子", "", "例如：没吃饱喵", new Consumer<String>() {
                    @Override public void accept(String s) {
                        String t = s == null ? "" : s.trim();
                        if (t.isEmpty()) {
                            toast("不能是空句子");
                            return;
                        }
                        m.lines.add(new PetBubbleModule.Line(t, 1));   // v1.12.0：等概率，权重只是个兼容字段
                        afterModuleEdit();
                        openLinesEditor(m);
                    }
                });
            }
        }));
        col.addView(dialogRow("↺ 套用上游语录（全部 13 句）",
                "上游出厂的全套文案（大字组 3 句 + 小字组 10 句），逐条取自源码 L7366 起",
                new Runnable() {
                    @Override public void run() {
                        m.lines.clear();
                        m.lines.addAll(PetBubbleModule.presetLinesAll());
                        afterModuleEdit();
                        openLinesEditor(m);
                    }
                }));
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        new AlertDialog.Builder(this)
                .setTitle("随机语句（" + m.lines.size() + " 句）")
                .setView(sv)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 「＋」：往这一行再加一个模块（行内上限按上游的 6 个）。 */
    private void pickTypeForRow(final int rowIdx, final List<PetBubbleModule> row) {
        final String[] names = new String[PALETTE.length];
        for (int i = 0; i < PALETTE.length; i++) names[i] = PALETTE[i][0];
        new AlertDialog.Builder(this)
                .setTitle("往这一行加模块（当前 " + row.size() + "/"
                        + PetBubbleModule.MAX_PER_ROW + "）")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        PetBubbleModule m = moduleForPalette(PALETTE[which][1]);
                        if (!PetBubbleEditModel.addToRow(rows, rowIdx, m)) {
                            toast("这一行已经放满 " + PetBubbleModule.MAX_PER_ROW + " 个了");
                            return;
                        }
                        Log.write("编辑器：行 " + (rowIdx + 1) + " 末尾加入「"
                                + PetBubbleModule.typeLabel(m.type) + "」");
                        rebuildAll();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 预览

    private void rebuildPreview() {
        if (previewBox == null) return;
        previewBox.removeAllViews();
        // 预览用的样式：把「文字颜色」工作副本临时写进 state。
        // ⚠️ 这里改 state 只影响预览 —— 保存走的是 saveAndFinish() 里**重新读盘**的 fresh 对象，
        // 所以不会把工作副本当成品写盘（工作副本只在点「保存」时才落盘）。
        state.bubbleTextColor = txTextColor & 0xFFFFFF;
        state.bubbleHintColor = txHintColor & 0xFFFFFF;
        PetBubble bubble = buildPreviewBubble();
        PetBubbleView view = new PetBubbleView(this, bubble, PetBubbleStyle.from(state), null);
        view.setStaticMode(true);
        previewBox.addView(view);
        previewBox.requestLayout();
        if (dataSourceText != null) dataSourceText.setText(RichText.bold(previewDataLabel()));
    }

    private PetBubble buildPreviewBubble() {
        RuntimeData rt = RuntimeData.read();
        long nowSec = System.currentTimeMillis() / 1000L;
        int style = PeakValley.clampStyle(pkStyle);        // 用「峰谷工作副本」，改完立刻能看到
        boolean realPeak = rt.available ? rt.peakNow : PeakValley.isPeak(nowSec);
        // v1.9.0：预览可以强制「高峰 / 空闲」，否则在空闲时段改高峰配色根本看不出效果
        boolean peak = previewPeakOverride != null ? previewPeakOverride : realPeak;
        boolean count = PeakValley.isCountStyle(style);
        String statusWord = PeakValley.statusText(style, peak, pkText, pkValleyText);
        String countdown = PeakValley.countdownText(nowSec, pkCountdownFormat);

        PetBubble bubble = new PetBubble("preview", WhaleBubbleSpec.DEFAULT_LABEL,
                rt.balance, rt.status);
        bubble.label = WhaleBubbleSpec.DEFAULT_LABEL;
        bubble.amount = rt.balance;
        bubble.hint = rt.status;
        bubble.peakStyleGlobal = style;
        bubble.peakIsPeak = peak;
        bubble.peakCountdown = count;
        bubble.peakText = pkShow ? (count ? countdown : statusWord) : "";
        bubble.peakColor = 0xFF000000 | (PeakValley.rowColor(peak, pkColor, pkValley) & 0xFFFFFF);
        bubble.putToken("status", statusWord);
        bubble.putToken("countdown", countdown);
        bubble.putToken("balance_ds", rt.balance);
        bubble.putToken("cost_ds", rt.cost);
        bubble.putToken("today_ds", rt.today);
        bubble.putToken("expense_ds", rt.today);
        bubble.arrowsUp = rt.bubbleBelow;   // v1.12.6：与真机一致（气泡在桌宠下方时箭头朝上）
        bubble.putToken("status_text", rt.status);
        bubble.modules.addAll(PetBubbleEditModel.toModules(rows));
        PetBubbleModule.assignMarqueeDurations(bubble.modules);
        return bubble;
    }

    private String previewDataLabel() {
        RuntimeData rt = RuntimeData.read();
        if (!rt.available) {
            return "预览数据：**读不到服务快照（files/runtime.json）** → 余额/消费用「--」占位，"
                    + "峰谷按当前真实时间算。启动一次桌宠就会有真数据。";
        }
        String when = rt.updatedAt > 0
                ? String.format(Locale.US, "%tH:%tM:%tS", rt.updatedAt, rt.updatedAt, rt.updatedAt)
                : "未知";
        return "预览数据：余额 " + rt.balance + " · 累计消费 " + rt.cost
                        + " · 今日已用 " + rt.today + " · 状态「" + rt.status + "」"
                + " ｜ 服务最近一次取数 " + when
                + (rt.connected ? "" : "（服务当前**未连接**，显示的是占位值）");
    }

    /** 真实泡泡画布宽度（与服务 showBubble 的算式一致：立绘宽 × 0.80 × 用户缩放）。 */
    private float realCanvasWidth() {
        float sidePx = dp(state.sideDp());
        float windowW = PetLayout.windowWidth(sidePx);
        float windowH = PetLayout.windowHeight(sidePx);
        android.graphics.RectF sprite = PetLayout.spriteRect(windowW, windowH);
        return Math.max(120f, sprite.width() * 0.80f * state.bubbleScaleFactor());
    }

    /** 预览容器：高度按泡泡的 1026:700 固定，宽度取整行宽。 */
    private static final class PreviewBox extends FrameLayout {
        PreviewBox(Context context) {
            super(context);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            if (w <= 0) w = 720;
            int h = Math.round(WhaleBubbleSpec.popHeight(w));
            int childW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
            int childH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(childW, childH);
            }
            setMeasuredDimension(w, h);
        }
    }

    /** 服务写的真实数据快照（读不到就是 available=false，界面会如实标注）。 */
    private static final class RuntimeData {
        boolean available;
        boolean connected;
        String balance = "--";
        String cost = "--";
        String today = "--";   // v1.12.0：今日已用（本地账本）
        boolean bubbleBelow;    // v1.12.6：真机泡泡是否画在桌宠下方（预览据此把 ↓ 转成 ↑）
        String status = "";
        boolean peakNow;
        long updatedAt;

        static RuntimeData read() {
            RuntimeData d = new RuntimeData();
            String text = CredentialStore.readText(PetPaths.runtimeFile());
            if (text == null || text.trim().isEmpty()) return d;
            try {
                JSONObject o = new JSONObject(text);
                d.available = true;
                d.connected = o.optBoolean("connected", false);
                d.balance = o.optString("balance", "--");
                d.cost = o.optString("cost", "--");
                d.today = o.optString("today", "--");
                d.bubbleBelow = o.optBoolean("bubbleBelow", false);
                d.status = o.optString("status", "");
                d.peakNow = o.optBoolean("peakNow", false);
                d.updatedAt = o.optLong("updatedAt", 0);
            } catch (Exception e) {
                Log.write("runtime.json 解析失败（预览退化为占位值）: " + e);
            }
            return d;
        }
    }

    // ---------------------------------------------------------------- 模块字段编辑

    private void openModuleEditor(final int rowIdx, final int col, final PetBubbleModule m) {
        LinearLayout col1 = new LinearLayout(this);
        col1.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        col1.setPadding(pad, dp(6), pad, dp(6));

        col1.addView(dialogRow("类型：" + PetBubbleModule.typeLabel(m.type),
                "点击切换（切换不会丢已有参数）", new Runnable() {
                    @Override public void run() { pickModuleType(m); }
                }));
        if (PetBubbleModule.TYPE_TEXT.equals(m.type)) {
            col1.addView(dialogRow("文字内容：" + shortText(m.text),
                    "文本模块的正文（可含 {status} / {countdown} / {balance_ds} / {cost_ds} / {today_ds} / {expense_ds} / {status_text}）",
                    new Runnable() {
                        @Override public void run() {
                            askText("文字内容", m.text, "例如：DeepSeek 余额", new Consumer<String>() {
                                @Override public void accept(String s) {
                                    m.text = s == null ? "" : s;
                                    afterModuleEdit();
                                }
                            });
                        }
                    }));
        }
                // 随机语句模块：句子 / 字号档在这里改（v1.12.0 去掉了权重，等概率抽取）
        if (PetBubbleModule.TYPE_RANDOM.equals(m.type)) {
            col1.addView(dialogRow("🎲 随机语句（" + m.lines.size() + " 句）",
                    m.linesSummary() + "\n点开改句子 / 改字号档 / 加句 / 套用上游语录",
                    new Runnable() {
                        @Override public void run() { openLinesEditor(m); }
                    }));
        }
        col1.addView(dialogRow("整句模板 tpl：" + (m.tpl.isEmpty() ? "（未设置）" : shortText(m.tpl)),
                "非空时覆盖内置文案（例如把「余额」改成「还剩」）", new Runnable() {
                    @Override public void run() {
                        askText("整句模板", m.tpl, "留空 = 用内置文案", new Consumer<String>() {
                            @Override public void accept(String s) {
                                m.tpl = s == null ? "" : s;
                                afterModuleEdit();
                            }
                        });
                    }
                }));
        col1.addView(dialogRow("字号档：" + m.size + "（" + Math.round(m.fontU()) + "u）",
                "上游公式：档 1 → 40u，档 50 → 240u", new Runnable() {
                    @Override public void run() {
                        pickSize(m);
                    }
                }));
        // v1.7.0（用户要求）：✎ 里直接给一个**字号滑块**，拖动就实时看到大小变化
        col1.addView(dialogRow("↔ 字号滑块（实时预览）",
                "当前 " + m.size + " 档 → " + Math.round(m.fontU()) + "u；拖动即改，松手刷新列表",
                new Runnable() {
                    @Override public void run() { openSizeSlider(m); }
                }));
        // v1.7.0（用户要求）：字体族（原来只有「尺寸」，没有「字体」）
        col1.addView(dialogRow("字体：" + (m.fontFamily == null || m.fontFamily.isEmpty()
                        ? "系统默认（sans-serif）" : m.fontFamily),
                "sans-serif / serif / monospace / 轻体 / 窄体… 加粗斜体仍独立生效",
                new Runnable() {
                    @Override public void run() { pickFontFamily(m); }
                }));
        col1.addView(dialogRow("样式：" + styleSummary(m), "加粗 / 斜体 / 下划线，点一下切换",
                new Runnable() {
                    @Override public void run() { pickStyleFlags(m); }
                }));
        // v1.8.0（用户要求）：纯色与「跑马灯」合并到同一个选择器里，不再各占一行
        col1.addView(dialogRow("文字颜色：" + m.slotSummary(PetBubbleModule.SLOT_TEXT),
                "点开可选：常用纯色 / " + PetBubbleGradients.size() + " 套跑马灯渐变 / 清除 / 自定义",
                new Runnable() {
                    @Override public void run() {
                        pickColorOrScheme(m, PetBubbleModule.SLOT_TEXT, "文字颜色（纯色或跑马灯）",
                                "清除（跟随泡泡正文色）", "");
                    }
                }));
        col1.addView(dialogRow("底色：" + m.slotSummary(PetBubbleModule.SLOT_BG),
                "贴着文字的圆角底色块（纯色或跑马灯，二选一）", new Runnable() {
                    @Override public void run() {
                        pickColorOrScheme(m, PetBubbleModule.SLOT_BG, "底色（纯色或跑马灯）",
                                "无底色", "");
                    }
                }));

        if (PetBubbleModule.TYPE_PEAK.equals(m.type)) {
            col1.addView(dialogRow("峰谷样式：" + PetBubbleModule.peakStyleLabel(m.peakStyle),
                    "默认「跟随全局」；钉死后设置里的「显示样式」对它无效", new Runnable() {
                        @Override public void run() { pickPeakStyle(m); }
                    }));
            col1.addView(dialogRow("⚙ 峰谷（全局）设置…",
                    "模块「跟随全局」时用的那份默认值（显示样式/倒计时格式/峰谷色/两态文字）",
                    new Runnable() {
                        @Override public void run() { openPeakSettings(); }
                    }));
            // v1.8.0：峰谷的 4 个颜色槽位也各自「纯色或跑马灯」一行（原来 6 行）
            col1.addView(dialogRow("高峰文字：" + m.slotSummary(PetBubbleModule.SLOT_PEAK_TEXT),
                    "默认 #e0433f（上游值）；峰谷跑马灯也在这里选", new Runnable() {
                        @Override public void run() {
                            pickColorOrScheme(m, PetBubbleModule.SLOT_PEAK_TEXT, "高峰文字颜色",
                                    "恢复上游默认 #E0433F", "#E0433F");
                        }
                    }));
            col1.addView(dialogRow("空闲文字：" + m.slotSummary(PetBubbleModule.SLOT_OFF_TEXT),
                    "默认 #2fa24c（上游值）", new Runnable() {
                        @Override public void run() {
                            pickColorOrScheme(m, PetBubbleModule.SLOT_OFF_TEXT, "空闲文字颜色",
                                    "恢复上游默认 #2FA24C", "#2FA24C");
                        }
                    }));
            col1.addView(dialogRow("高峰底色：" + m.slotSummary(PetBubbleModule.SLOT_PEAK_BG),
                    "默认 #fbe7e6（上游值）", new Runnable() {
                        @Override public void run() {
                            pickColorOrScheme(m, PetBubbleModule.SLOT_PEAK_BG, "高峰底色",
                                    "恢复上游默认 #FBE7E6", "#FBE7E6");
                        }
                    }));
            col1.addView(dialogRow("空闲底色：" + m.slotSummary(PetBubbleModule.SLOT_OFF_BG),
                    "默认 #e4f3e7（上游值）", new Runnable() {
                        @Override public void run() {
                            pickColorOrScheme(m, PetBubbleModule.SLOT_OFF_BG, "空闲底色",
                                    "恢复上游默认 #E4F3E7", "#E4F3E7");
                        }
                    }));
        }

        col1.addView(dialogRow("🗑 删除这个模块", "也可以用芯片上的 ✕", new Runnable() {
            @Override public void run() {
                PetBubbleEditModel.removeModule(rows, rowIdx, col);
                rebuildAll();
            }
        }));

        ScrollView sv = new ScrollView(this);
        sv.addView(col1);
        new AlertDialog.Builder(this)
                .setTitle("编辑模块 · " + PetBubbleModule.typeLabel(m.type))
                .setView(sv)
                .setPositiveButton("完成", null)
                .show();
    }

    private void afterModuleEdit() {
        rebuildRows();
        rebuildPreview();
        refreshStatusLine();
    }

    private static String shortText(String s) {
        if (s == null || s.isEmpty()) return "（空）";
        return s.length() > 12 ? s.substring(0, 12) + "…" : s;
    }

    private static String styleSummary(PetBubbleModule m) {
        List<String> on = new ArrayList<>();
        if (m.bold) on.add("粗体");
        if (m.italic) on.add("斜体");
        if (m.ul) on.add("下划线");
        return on.isEmpty() ? "常规" : String.join("+", on);
    }

    private View dialogRow(String title, String sub, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(6), dp(11), dp(6), dp(11));
        TextView tv = new TextView(this);
        tv.setText(RichText.bold(title));   // v1.12.1：同 note（行标题也允许 **强调**）
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(textColor());
        row.addView(tv);
        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(RichText.bold(sub));     // v1.12.1：副标题同理
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            s.setTextColor(subColor());
            s.setPadding(0, dp(3), 0, 0);
            row.addView(s);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return row;
    }

    private void pickModuleType(final PetBubbleModule m) {
        final String[] names = new String[PALETTE.length];
        for (int i = 0; i < PALETTE.length; i++) names[i] = PALETTE[i][0];
        new AlertDialog.Builder(this)
                .setTitle("模块类型")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        PetBubbleModule fresh = moduleForPalette(PALETTE[which][1]);
                        m.type = fresh.type;
                        if (PetBubbleModule.TYPE_PEAK.equals(fresh.type)) {
                            m.peakStyle = fresh.peakStyle;
                            if (fresh.ul) m.ul = true;
                        }
                        if (PetBubbleModule.TYPE_TEXT.equals(m.type) && m.text.isEmpty()) m.text = "文字";
                        afterModuleEdit();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickSize(final PetBubbleModule m) {
        final int[] levels = {1, 3, 5, 7, 10, 14, 18, 23, 30, 40, 50};
        final String[] names = new String[levels.length + 1];
        for (int i = 0; i < levels.length; i++) {
            names[i] = "档 " + levels[i] + "（" + Math.round(PetBubbleModule.fontU(levels[i])) + "u）";
        }
        names[levels.length] = "自定义…";
        new AlertDialog.Builder(this)
                .setTitle("字号档（当前 " + m.size + " → " + Math.round(m.fontU()) + "u）")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == levels.length) {
                            askNumber("字号档（1–50）", m.size, new IntConsumer() {
                                @Override public void accept(int v) {
                                    m.size = Math.max(1, Math.min(50, v));
                                    afterModuleEdit();
                                }
                            });
                            return;
                        }
                        m.size = levels[which];
                        afterModuleEdit();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 字号滑块（v1.7.0 用户要求「✎ 里加调整字体大小」）。
     *
     * <p>拖动就改 {@code m.size} 并**立刻重绘预览**（实时看到大小变化）；松手时再刷新一遍列表与行高。
     * 档位语义与上游完全一致：档 1 → 40u，档 50 → 240u（{@code u = round(40 + (档-1)*200/49)}）。
     */
    private void openSizeSlider(final PetBubbleModule m) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(6), pad, dp(6));

        final TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTextColor(textColor());
        label.setText("档 " + m.size + " → " + Math.round(m.fontU()) + "u");
        box.addView(label);

        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(49);                                  // 档 1…50
        bar.setProgress(Math.max(0, Math.min(49, m.size - 1)));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int progress, boolean fromUser) {
                m.size = progress + 1;
                label.setText("档 " + m.size + " → " + Math.round(m.fontU()) + "u"
                        + (fromUser ? "" : "（未改动）"));
                rebuildPreview();                        // 实时预览
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar b) { }

            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {
                Log.write("编辑器：字号滑块 → 档 " + m.size + "（" + Math.round(m.fontU()) + "u）");
                afterModuleEdit();                       // 松手后连芯片/行高一并刷新
            }
        });
        box.addView(bar);

        TextView hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(subColor());
        hint.setText("上限 50 档 = 240u（上游最大字号）；想精确输入可以回到上一层的「字号档」选「自定义…」。"
                + "字号是**每个模块各自**的，行内并排的两个模块可以不一样大。");
        hint.setPadding(0, dp(6), 0, 0);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("调整字号（实时预览）")
                .setView(box)
                .setPositiveButton("完成", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        afterModuleEdit();
                    }
                })
                .show();
    }

    /**
     * 颜色 + 跑马灯的**统一选择器**（v1.8.0，用户要求：「文字跑马灯」不该单开一行）。
     *
     * <p>一个槽位只有一行；点开后是一个列表：
     * {@code [清除/恢复默认] + 常用纯色 + N 套跑马灯 + [自定义纯色…]}。
     * **纯色与跑马灯互斥**（{@link PetBubbleModule#setPlain}/{@link PetBubbleModule#setScheme} 里保证），
     * 所以不会出现「既设了色又设了渐变、渲染端要猜」的状态。
     *
     * @param resetLabel 第一项（清除/恢复默认）的名字
     * @param resetHex   第一项要写回的纯色；空串 = 清除（回落到跟随默认）
     */
    private void pickColorOrScheme(final PetBubbleModule m, final int slot, String title,
                                   String resetLabel, final String resetHex) {
        final String[] plainNames = {
                "白 #FFFFFF", "藏青 #203170", "正文蓝 #536BA9", "灰 #8A94A6",
                "峰色 #E0433F", "谷色 #2FA24C", "峰底 #FBE7E6", "谷底 #E4F3E7",
                "浅黄 #FFE08A", "薄荷 #A8E6CF", "樱粉 #FFC2D1",
        };
        final String[] plainHex = {
                "#FFFFFF", "#203170", "#536BA9", "#8A94A6",
                "#E0433F", "#2FA24C", "#FBE7E6", "#E4F3E7",
                "#FFE08A", "#A8E6CF", "#FFC2D1",
        };
        final String[] schemes = PetBubbleGradients.names();
        final String current = m.slotSummary(slot);
        final String[] items = new String[1 + plainNames.length + schemes.length + 1];
        int k = 0;
        items[k++] = resetLabel;
        for (int i = 0; i < plainNames.length; i++) {
            boolean on = plainHex[i].equalsIgnoreCase(m.plainOf(slot));
            items[k++] = (on ? "✓ " : "") + "纯色 " + plainNames[i];
        }
        for (String s : schemes) {
            boolean on = s.equalsIgnoreCase(m.schemeOf(slot));
            items[k++] = (on ? "✓ " : "") + "跑马灯 " + s;
        }
        items[k] = "自定义纯色（#RRGGBB）…";
        new AlertDialog.Builder(this)
                .setTitle(title + "（当前 " + current + "）")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            m.setPlain(slot, resetHex);
                            Log.write("编辑器：颜色槽「" + PetBubbleModule.slotLabel(slot)
                                    + "」→ " + m.slotSummary(slot));
                            afterModuleEdit();
                            return;
                        }
                        if (which == items.length - 1) {
                            askText("自定义纯色", m.plainOf(slot), "#RRGGBB", new Consumer<String>() {
                                @Override public void accept(String s) {
                                    String t = s == null ? "" : s.trim();
                                    if (PetBubbleModule.parseHex(t) < 0) {
                                        toast("看不懂这个颜色：请输入 #RRGGBB");
                                        return;
                                    }
                                    m.setPlain(slot, t.startsWith("#") ? t : "#" + t);
                                    afterModuleEdit();
                                }
                            });
                            return;
                        }
                        int idx = which - 1;
                        if (idx < plainNames.length) {
                            m.setPlain(slot, plainHex[idx]);
                        } else {
                            m.setScheme(slot, schemes[idx - plainNames.length]);
                        }
                        Log.write("编辑器：颜色槽「" + PetBubbleModule.slotLabel(slot)
                                + "」→ " + m.slotSummary(slot));
                        afterModuleEdit();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 字体族选择（v1.7.0 新增；字段本来就是上游同名 {@code fontFamily}，以前没做 UI）。 */
    private void pickFontFamily(final PetBubbleModule m) {
        final String[][] options = {
                {"系统默认（sans-serif）", ""},
                {"细体（sans-serif-light）", "sans-serif-light"},
                {"中等（sans-serif-medium）", "sans-serif-medium"},
                {"窄体（sans-serif-condensed）", "sans-serif-condensed"},
                {"小大写（sans-serif-smallcaps）", "sans-serif-smallcaps"},
                {"衬线（serif）", "serif"},
                {"等宽（monospace）", "monospace"},
                {"手写（cursive）", "cursive"},
        };
        String[] names = new String[options.length + 1];
        for (int i = 0; i < options.length; i++) {
            boolean current = (m.fontFamily == null ? "" : m.fontFamily).equals(options[i][1]);
            names[i] = options[i][0] + (current ? "　✓ 当前" : "");
        }
        names[options.length] = "自定义字体名…";
        new AlertDialog.Builder(this)
                .setTitle("字体（当前：" + (m.fontFamily == null || m.fontFamily.isEmpty()
                        ? "系统默认" : m.fontFamily) + "）")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == options.length) {
                            askText("自定义字体名", m.fontFamily, "例如 sans-serif-medium",
                                    new Consumer<String>() {
                                        @Override public void accept(String s) {
                                            m.fontFamily = s == null ? "" : s.trim();
                                            afterModuleEdit();
                                        }
                                    });
                            return;
                        }
                        m.fontFamily = options[which][1];
                        Log.write("编辑器：字体 → " + (m.fontFamily.isEmpty() ? "系统默认" : m.fontFamily));
                        afterModuleEdit();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickStyleFlags(final PetBubbleModule m) {
        final String[] names = {
                (m.bold ? "✓ " : "") + "加粗",
                (m.italic ? "✓ " : "") + "斜体",
                (m.ul ? "✓ " : "") + "下划线",
        };
        new AlertDialog.Builder(this)
                .setTitle("字体样式")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) m.bold = !m.bold;
                        if (which == 1) m.italic = !m.italic;
                        if (which == 2) m.ul = !m.ul;
                        afterModuleEdit();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void pickPeakStyle(final PetBubbleModule m) {
        final String[] names = new String[PeakValley.STYLE_NAMES.length + 1];
        names[0] = "跟随全局（当前：" + PeakValley.styleDisplayName(PeakValley.clampStyle(pkStyle)) + "）";
        System.arraycopy(PeakValley.STYLE_NAMES, 0, names, 1, PeakValley.STYLE_NAMES.length);
        new AlertDialog.Builder(this)
                .setTitle("峰谷显示样式")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        m.peakStyle = which == 0 ? PetBubbleModule.STYLE_FOLLOW_GLOBAL
                                : PeakValley.clampStyle(which - 1);
                        afterModuleEdit();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 颜色：常用色板 + 自定义 #RRGGBB + 「清除」（回落到跟随正文色）。 */
    private void pickColor(final int current, String title, final String original,
                           final Consumer<String> consumer) {
        final String[] names = {
                "白 #FFFFFF", "藏青 #203170", "正文蓝 #536BA9", "灰 #8A94A6",
                "峰色 #E0433F", "谷色 #2FA24C", "峰底 #FBE7E6", "谷底 #E4F3E7",
                "浅黄 #FFE08A", "薄荷 #A8E6CF", "樱粉 #FFC2D1", "清除（用默认）"
        };
        final int[] values = {
                0xFFFFFF, 0x203170, 0x536BA9, 0x8A94A6,
                0xE0433F, 0x2FA24C, 0xFBE7E6, 0xE4F3E7,
                0xFFE08A, 0xA8E6CF, 0xFFC2D1, -1
        };
        String currentHex = String.format(Locale.US, "#%06X", current & 0xFFFFFF);
        String[] items = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            if (values[i] < 0) {
                items[i] = names[i];
            } else {
                String hex = String.format(Locale.US, "#%06X", values[i]);
                items[i] = names[i] + (hex.equalsIgnoreCase(currentHex) ? "　✓ 当前" : "");
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(title + "（当前 " + (original == null || original.isEmpty() ? "无" : original) + "）")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        consumer.accept(values[which] < 0 ? ""
                                : String.format(Locale.US, "#%06X", values[which]));
                    }
                })
                .setNeutralButton("自定义…", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        askText(title + "（#RRGGBB）",
                                String.format(Locale.US, "%06X", current & 0xFFFFFF), "6 位十六进制",
                                new Consumer<String>() {
                                    @Override public void accept(String s) {
                                        String t = s == null ? "" : s.trim().replace("#", "");
                                        if (PetBubbleModule.parseHex(t) < 0) {
                                            toast("看不懂这个颜色：请输入 #RRGGBB");
                                            return;
                                        }
                                        consumer.accept("#" + t.toUpperCase(Locale.US));
                                    }
                                });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askText(String title, String initial, String hint, final Consumer<String> consumer) {
        final EditText field = new EditText(this);
        field.setText(initial == null ? "" : initial);
        field.setHint(hint == null ? "" : hint);
        field.setHintTextColor(subColor());
        field.setTextColor(textColor());
        field.setSingleLine(false);
        LinearLayout wrap = new LinearLayout(this);
        int pad = dp(18);
        wrap.setPadding(pad, dp(4), pad, 0);
        wrap.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        consumer.accept(field.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askNumber(String title, int initial, final IntConsumer consumer) {
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(initial));
        field.setTextColor(textColor());
        LinearLayout wrap = new LinearLayout(this);
        int pad = dp(18);
        wrap.setPadding(pad, dp(4), pad, 0);
        wrap.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            consumer.accept(Integer.parseInt(field.getText().toString().trim()));
                        } catch (Exception e) {
                            toast("请输入整数");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 底部动作

    private boolean dirty() {
        if (!peakSignature().equals(initialPeakSignature)
                || !txSignature().equals(initialTxSignature)) {
            return true;
        }
        if (seqTouched()) return true;      // 序列结构或任一「点击项」内容改过
        return defaultDirty();               // 默认泡泡的内容
    }

    private void refreshStatusLine() {
        if (statusText == null) return;
        int n = rows.size();
        StringBuilder sb = new StringBuilder();
        sb.append(dirty() ? "● 有改动未保存" : "○ 未改动");
        sb.append(" · 正在编辑：").append(targetLabel(curTarget));
        sb.append(" · ").append(PetBubbleEditModel.count(rows)).append(" 个模块 / ").append(n).append(" 行");
        sb.append(" · 点击序列 ").append(workingSeq.items.size()).append(" 项");
        if (n > PetBubbleModule.MAX_ROWS) {
            sb.append("（⚠ 超过 ").append(PetBubbleModule.MAX_ROWS).append(" 行，超出的不会被渲染）");
        }
        statusText.setText(sb.toString());
    }

    private void confirmCancel() {
        if (!dirty()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("放弃改动？")
                .setMessage("有未保存的修改，取消会把它丢掉。")
                .setPositiveButton("放弃", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        Log.write("编辑器：取消（丢弃改动）");
                        finish();
                    }
                })
                .setNegativeButton("继续编辑", null)
                .show();
    }

    /**
     * 「恢复默认」= 一键回到**推荐结构**（v1.11.0）：
     * 默认泡泡 → 经典四行；点击序列 → ①余额 ②并列候选(峰谷倒计时 w2 / 累计消费 w1) ③随机文字。
     */
    private void restoreDefault() {
        writeBackCurrent();
        defaultMods = PetBubbleModule.defaultPreset();
        workingSeq = PetBubbleSeq.recommendedSeq();
        curTarget = -1;
        curCandidate = -1;
        loadRowsFromSlot();
        Log.write("编辑器：恢复默认 → 默认泡泡=经典四行；点击序列=" + workingSeq.describe());
        rebuildAll();
        toast("已恢复推荐结构（①余额 ②并列候选 ③随机文字）—— 还没保存");
    }

    private void saveAndFinish() {
        writeBackCurrent();
        upgradeEditedKinds();          // 把被改过的「默认页」升级为「自定义模块页」
        List<PetBubbleModule> defMods = defaultMods;
        if (defMods == null) defMods = new ArrayList<>();
        String defJson = PetBubbleModule.listToJson(defMods);
        // 重新读盘：只改自己这几项，避免把服务刚写的桌宠位置等改动覆盖回旧值
        PetState fresh = PetState.load();
        // 峰谷（全局）也在本页编辑：一并写入工作副本的值
        fresh.peakShow = pkShow;
        fresh.peakStyle = PeakValley.clampStyle(pkStyle);
        fresh.countdownFormat = PeakValley.clampCountdownFormat(pkCountdownFormat);
        fresh.peakColor = pkColor & 0xFFFFFF;
        fresh.valleyColor = pkValley & 0xFFFFFF;
        fresh.peakTextCustom = pkText == null ? "" : pkText;
        fresh.valleyTextCustom = pkValleyText == null ? "" : pkValleyText;
        // 泡泡文字颜色（全局）也在本页编辑
        fresh.bubbleTextColor = txTextColor & 0xFFFFFF;
        fresh.bubbleHintColor = txHintColor & 0xFFFFFF;
        fresh.bubbleModulesJson = defJson;
        if (seqTouched()) {
            fresh.bubbleSeqJson = workingSeq.toJson();
            Log.write("编辑器：点击序列已保存（" + workingSeq.items.size() + " 项）→ "
                    + workingSeq.toJson());
        } else {
            Log.write("编辑器：点击序列没被改过 → 保持原样（"
                    + (seqConfigWasDefault ? "仍是「空 = 内置默认序列」" : "原自定义配置") + "）");
        }
        fresh.save();
        savedState = true;
        startService(new Intent(this, PetService.class).setAction(PetService.ACTION_APPLY));
        Log.write("编辑器：已保存 → 默认泡泡=" + PetBubbleEditModel.describe(
                PetBubbleEditModel.rowsOf(defMods)) + "；JSON=" + defJson);
        Log.write("编辑器：峰谷（全局）已保存 → " + peakSummary());
        Log.write("编辑器：文字颜色（全局）已保存 → " + txSummary());
        initialPeakSignature = peakSignature();
        initialTxSignature = txSignature();
        initialSeqSig = seqStructureSignature();
        snapshotInitialSignatures();
        toast("已保存：默认泡泡 " + defMods.size() + " 个模块 · 点击序列 "
                + workingSeq.items.size() + " 项");
        finish();
    }

    // ---------------------------------------------------------------- 拖拽回调

    @Override public void onStructureChanged() {
        rebuildAll();
    }

    @Override public void onDropApplied(boolean changed, String message) {
        if (!changed) toast(message);
        refreshStatusLine();
    }

    @Override public void onDragStateChanged(boolean dragging) {
        if (dragHint == null) return;
        dragHint.setVisibility(dragging ? View.VISIBLE : View.GONE);
        dragHint.setText(dragging ? "拖拽中：行上/下边缘=另起一行，行内左/右半=并排" : "");
    }

    // ---------------------------------------------------------------- 小工具

    private int bg() { return night ? Color.rgb(18, 19, 21) : Color.rgb(247, 248, 250); }
    private int cardBg() { return night ? Color.rgb(30, 32, 35) : Color.WHITE; }
    private int textColor() { return night ? Color.rgb(230, 232, 234) : Color.rgb(26, 28, 30); }
    private int subColor() { return night ? Color.rgb(150, 155, 160) : Color.rgb(122, 130, 138); }
    private int accent() { return night ? Color.rgb(150, 180, 210) : Color.rgb(74, 107, 138); }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}