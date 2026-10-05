package com.dsh.balancepet;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 持久化状态。对应 macOS 版的 PetState（state.json）。
 * 额外保留两项 Android 侧必需设置：离线模式、账号接口路径覆盖。
 */
public final class PetState {
    public PetCharacter character = PetCharacter.DEEPSEEK;
    /**
         * v1.13.0：正在使用的**自定义角色** id。非空时优先于 {@link #character}；
         * 指向的角色被删掉时自动回落到内置角色（见 {@link PetCharacters#current}）。
         */
        public String customCharacterId = "";
        /** v1.13.0：自定义角色索引，JSON 数组：[{id,name,file}]；坏掉按空处理。 */
        public String customCharactersJson = "";
    public int sizeIndex = 1;            // 索引到 PetLayout.SIZE_PRESETS_DP
    /** > 0 表示使用自定义尺寸（dp，含义同样是身体高度）；选预设档位时会被清零。 */
    public float customSideDp = 0f;

    /** 拖动行为：0 = 自由停放；1 = 松手吸附左下角（原版行为）；2 = 吸附最近边缘。 */
    public static final int DRAG_FREE = 0;
    public static final int DRAG_CORNER = 1;
    public static final int DRAG_EDGE = 2;

    /** 渲染模式：0 = 单窗口（流畅，透明区也会吃点击）；1 = 轮廓多窗口（透明处真穿透）。 */
    public static final int MODE_SINGLE = 0;
    public static final int MODE_OUTLINE = 1;
    public int windowMode = MODE_SINGLE;

    public int dragMode = DRAG_FREE;     // 移植版默认自由拖动
    public boolean snapOnRelease = false; // 兼容旧字段/旧版本读取
    public boolean soundOn = true;
    /** 连击音效节流（毫秒）；0 = 忠于原版（每次扣费都响）。 */
    public int soundThrottleMs = 0;
    /** 自定义音效文件路径（空 = 用内置 hit.mp3）。 */
    public String soundPath = "";
    /** 动画目标帧率：0 = 跟随屏幕刷新率；也可显式 30/60/90/120。 */
    public int targetFps = PetTuning.TARGET_FPS_FOLLOW;

    /**
     * 蓝色大肥鱼「未连接时显示抱盆图」——v1.3.1 的默认行为。
     * 关掉则退回 Windows 原版的画法：继续用平板图，显示 "--" 与状态点。
     */
    public boolean offlineArtOnDisconnect = true;

    // ---- Whale 挂件：泡泡外观（可自定义；默认值取自其源码） ----
    public int bubbleFillColor = 0xFFFFFF;
    public int bubbleFillAlpha = 255;
    public int bubbleStrokeColor = 0x203170;
    public int bubbleStrokeAlpha = 255;
    public int bubbleTextColor = 0x536BA9;
    public int bubbleHintColor = 0x9FB0D9;

    // ---- Whale 挂件：峰谷显示（v1.5.0，对应上游的 peak 模块） ----
    /** 是否在泡泡里显示峰谷行（关掉则泡泡与 v1.4.1 完全一样）。 */
    public boolean peakShow = true;
    /** 显示样式：{@link PeakValley#STYLE_DEFAULT} / LIANGWEN / QIANGQIANG / COUNT / MINI。 */
    public int peakStyle = PeakValley.STYLE_DEFAULT;
    /** 高峰配色（默认取自上游倒计时模块 #e0433f）。 */
    public int peakColor = 0xE0433F;
    /** 空闲配色（默认取自上游倒计时模块 #2fa24c）。 */
    public int valleyColor = 0x2FA24C;
    /**
     * 「默认」样式下的两态自定义文字（空 = 用上游默认「高峰时段 / 空闲时段」）。
     * 其余样式忠于上游文案，不使用这两个值。
     */
    public String peakTextCustom = "";
    public String valleyTextCustom = "";
    /**
     * 倒计时显示格式（用户可选，v1.5.1）：
     * {@link PeakValley#CD_SMART}（默认，超 24h 折天） / {@link PeakValley#CD_UPSTREAM}（原样，忠于上游） /
     * {@link PeakValley#CD_SMART_TARGET}（智能 + 标注目标时刻）。
     */
    public int countdownFormat = PeakValley.CD_SMART;

    // ---- Whale 挂件：模块化泡泡内容（v1.5.2） ----
    /**
     * 泡泡内容模块（上游 {@code modules[]} 的 JSON 数组字符串）。
     * **空字符串 = 用经典四行**（v1.5.1 观感不变）。
     */
    public String bubbleModulesJson = "";

    // ---- Whale 挂件：点击序列（v1.5.4 三期） ----
    /**
     * 点击序列。形状与上游一致：{@code {v:1, items:[...], tapAdvance:bool}}。
     * **空字符串（或 items 为空）= 用内置默认序列**（余额页 → 并列候选页）。
     */
    public String bubbleSeqJson = "";

    // ---- Whale 挂件：泡泡留存时间（v1.5.6 用户要求可自定义） ----
    /**
     * 泡泡自动收起时间（毫秒）。**0 = 不自动收起**（常驻，点一下才关）。
     * 默认 5000，对齐上游 {@code BUBBLE_MS}（早期本工程误写成 6000，现已改正）。
     */
    public int bubbleTtlMs = 5000;

    // ---- 一次性迁移标记（v1.5.9） ----
    /**
     * 是否已做过「峰谷模块样式跟随全局」迁移。
     *
     * <p>背景：≤1.5.8 的默认模块预设把峰谷模块写成 {@code peakStyle:0}（默认样式），
     * 而它**与用户手选的「默认样式」无法区分**。渲染时模块样式优先 → 用户在设置里改
     * 「显示样式」对模块化泡泡完全无效（用户 2026-10-04 报告的 bug）。
     * 迁移只做一次：{@code peakStyle:0 → -1}（跟随全局）。当全局样式也是默认时，观感完全一样。
     */
    public boolean peakStyleMigrated = false;

    /**
     * 一次性迁移标记：**不持久化**，仅用于告诉调用方「本次 load 改过东西，请写盘」。
     */
    public transient boolean migrationApplied = false;

    // ---------------------------------------------------------------- 一次性迁移（v1.5.9）

    /**
     * 把模块 JSON 里峰谷模块的 {@code peakStyle:0}（旧版本写死的「默认」）改成「跟随全局」。
     * 幂等；解析不了就**原样返回**（不破坏用户配置）。
     */
    private static String migrateModulesJson(String json) {
        if (json == null || json.trim().isEmpty()) return json;
        java.util.List<PetBubbleModule> list = PetBubbleModule.listFromJson(json);
        if (list.isEmpty()) return json;
        int n = migrateModuleList(list);
        if (n == 0) return json;
        Log.write("状态迁移：" + n + " 个峰谷模块的「默认」样式 → 改为「跟随全局」（修「改显示样式无效」）");
        return PetBubbleModule.listToJson(list);
    }

    private static int migrateModuleList(java.util.List<PetBubbleModule> list) {
        int n = 0;
        if (list == null) return 0;
        for (PetBubbleModule m : list) {
            if (m != null && PetBubbleModule.TYPE_PEAK.equals(m.type)
                    && m.peakStyle == PeakValley.STYLE_DEFAULT) {
                m.peakStyle = PetBubbleModule.STYLE_FOLLOW_GLOBAL;
                n++;
            }
        }
        return n;
    }

    /** 序列 JSON 里的峰谷模块也要迁移（含并列候选里的项）。 */
    private static String migrateSeqJson(String json) {
        if (json == null || json.trim().isEmpty()) return json;
        PetBubbleSeq seq = PetBubbleSeq.fromJson(json);
        if (seq.parseFailed || seq.items.isEmpty()) return json;
        int n = 0;
        for (PetBubbleSeq.Item it : seq.items) n += migrateSeqItem(it);
        if (n == 0) return json;
        Log.write("状态迁移：" + n + " 个峰谷模块（序列里）→ 改为「跟随全局」");
        return seq.toJson();
    }

    private static int migrateSeqItem(PetBubbleSeq.Item it) {
        if (it == null) return 0;
        int n = migrateModuleList(it.modules);
        for (PetBubbleSeq.Option o : it.options) {
            if (o != null) n += migrateSeqItem(o.item);
        }
        return n;
    }
    /** 留存时间取值范围（毫秒）：0 = 常驻；上限 60 秒。 */
    public static final int BUBBLE_TTL_MIN = 0;
    public static final int BUBBLE_TTL_MAX = 60000;

    /** 把任意值夹到合法区间（0 原样保留，表示常驻）。 */
    public static int clampBubbleTtl(int ms) {
        if (ms <= 0) return 0;
        return Math.min(BUBBLE_TTL_MAX, ms);
    }

    /** 人类可读的留存时长。 */
    public static String bubbleTtlLabel(int ms) {
        if (ms <= 0) return "不自动收起（常驻）";
        if (ms % 1000 == 0) return (ms / 1000) + " 秒";
        return String.format(java.util.Locale.US, "%.1f 秒", ms / 1000.0);
    }

    // ---- Whale 挂件：按压形变与按压音效（默认值取自其源码） ----
    /** 按压形变（SQUISH = scaleY(.88) scaleX(1.05)，底部中心为轴）。 */
    public boolean pressSquashOn = true;

    // ---- Whale 挂件：双击行为（用户要求在设置里二选一） ----
    /** 双击 = 扣费效果（原版「双击托盘图标 → 手动触发一次扣费动画」的语义）。 */
    public static final int DOUBLE_TAP_HIT = 0;
    /** 双击 = 挤压效果（完整做一次按压形变再回弹，不扣费）。 */
    public static final int DOUBLE_TAP_SQUASH = 1;
    /** 双击桌宠做什么；默认忠于原版的扣费效果。 */
    public int doubleTapAction = DOUBLE_TAP_HIT;

    // ---- 长按桌宠（原版右键菜单的等价物） ----
    /** 长按 = 打开设置菜单（默认，等价原版右键菜单）。 */
    public static final int LONG_PRESS_MENU = 0;
    /** 长按 = 不响应（避免误触；长按仍会有按压形变与音效）。 */
    public static final int LONG_PRESS_OFF = 1;
    public int longPressAction = LONG_PRESS_MENU;

    // ---- Whale 挂件：桌宠贴近屏幕顶部时的泡泡行为（用户指定做成选项） ----
    /** 贴近顶部时把泡泡等比缩小（默认）。 */
    public static final int BUBBLE_TOP_SHRINK = 0;
    /** 贴近顶部时干脆不弹泡泡（用户备选方案）。 */
    public static final int BUBBLE_TOP_HIDE = 1;
    /**
     * 贴近顶部时把泡泡**画到桌宠下方**（形状垂直翻转、尾巴朝上指着桌宠；文字位置已补偿）。
     * 这样既不缩小、也不遮挡桌宠头部 —— 不破坏整体设计。
     */
    public static final int BUBBLE_TOP_BELOW = 2;

    public int bubbleTopMode = BUBBLE_TOP_SHRINK;

    /** 夹取「贴近顶部」策略。 */
    public static int clampBubbleTopMode(int mode) {
        return (mode == BUBBLE_TOP_HIDE || mode == BUBBLE_TOP_BELOW) ? mode : BUBBLE_TOP_SHRINK;
    }

    // ---- Whale 挂件：泡泡大小（v1.7.0；用户点名「自定义泡泡大小」） ----
    /**
     * 泡泡画布缩放（百分比）。100 = 上游原样（画布宽 = 立绘宽 × 0.8）。
     *
     * <p>为什么是「整体缩放」而不是「只改画布」：泡泡里的一切都用
     * {@code unit = 画布宽 / 1026} 换算（字号、圆角、底色块内边距…），所以只要按比例缩放画布宽，
     * **形状与文字会一起等比缩放**，观感与原比例完全一致，不会出现「框变大了字没变大」。
     */
    public int bubbleScalePercent = 100;
    public static final int BUBBLE_SCALE_MIN = 50;
    public static final int BUBBLE_SCALE_MAX = 200;

    public static int clampBubbleScale(int percent) {
        return Math.max(BUBBLE_SCALE_MIN, Math.min(BUBBLE_SCALE_MAX, percent));
    }

    /** 缩放系数（1.0 = 原样）。 */
    public float bubbleScaleFactor() {
        return clampBubbleScale(bubbleScalePercent) / 100f;
    }

    public static String bubbleScaleLabel(int percent) {
        int p = clampBubbleScale(percent);
        if (p == 100) return "100%（原版大小）";
        return p + "%" + (p > 100 ? "（更大）" : "（更小）");
    }

    public static String bubbleTopModeLabel(int mode) {
        if (mode == BUBBLE_TOP_HIDE) return "不显示泡泡";
        if (mode == BUBBLE_TOP_BELOW) return "显示在桌宠下方";
        return "等比缩小泡泡";
    }
    /** 按压音效组：duck=小黄鸭(Ya1/Ya2，默认) / fx1=音效1(D1/D2) / off=关闭。 */
    public String pressSoundSet = "duck";
    /** 按压音效音量 0…1。 */
    public double pressVolume = 0.8;
    public double pollSeconds = 30;      // 1 ~ 600 秒（可自定义）
    public Float originX;
    public Float originY;
    public boolean offline = false;      // 等价 DSHPET_OFFLINE=1
    public String apiPath = "";          // 等价 DSHPET_API_PATH
    public boolean autoStart = false;     // 开机自动启动
    public double volume = 0.7;          // 与原版一致：0.7

    /** 实际生效的尺寸（dp）：自定义优先，否则用预设档位。 */
    public float sideDp() {
        if (customSideDp >= PetLayout.MIN_SIDE_DP) return customSideDp;
        int i = Math.min(PetLayout.SIZE_PRESETS_DP.length - 1, Math.max(0, sizeIndex));
        return PetLayout.SIZE_PRESETS_DP[i];
    }

    public boolean useCustomSize() {
        return customSideDp >= PetLayout.MIN_SIDE_DP;
    }

    public static PetState load() {
        PetState s = new PetState();
        String text = CredentialStore.readText(PetPaths.stateFile());
        if (text == null) return s;
        try {
            JSONObject o = new JSONObject(text);
            s.character = PetCharacter.fromId(o.optString("character", null));
                        s.customCharacterId = o.optString("customCharacterId", "");
                        s.customCharactersJson = o.optString("customCharactersJson", "");
            int idx = o.optInt("sizeIndex", 1);
            if (idx >= 0 && idx < PetLayout.SIZE_PRESETS_DP.length) s.sizeIndex = idx;
            boolean legacySnap = o.optBoolean("snapOnRelease", false);
            int mode = o.optInt("dragMode", -1);
            if (mode >= DRAG_FREE && mode <= DRAG_EDGE) {
                s.dragMode = mode;
            } else {
                // 旧配置：只有 snapOnRelease 布尔值
                s.dragMode = legacySnap ? DRAG_CORNER : DRAG_FREE;
            }
            s.snapOnRelease = s.dragMode == DRAG_CORNER;
            s.soundOn = o.optBoolean("soundOn", true);
            s.soundThrottleMs = Math.max(0, o.optInt("soundThrottleMs", 0));
            s.soundPath = o.optString("soundPath", "");
            int fps = o.optInt("targetFps", PetTuning.TARGET_FPS_FOLLOW);
            s.targetFps = (fps == 30 || fps == 60 || fps == 90 || fps == 120)
                    ? fps : PetTuning.TARGET_FPS_FOLLOW;
            s.offlineArtOnDisconnect = o.optBoolean("offlineArtOnDisconnect", true);
            s.bubbleFillColor = o.optInt("bubbleFillColor", 0xFFFFFF);
            s.bubbleFillAlpha = Math.min(255, Math.max(0, o.optInt("bubbleFillAlpha", 255)));
            s.bubbleStrokeColor = o.optInt("bubbleStrokeColor", 0x203170);
            s.bubbleStrokeAlpha = Math.min(255, Math.max(0, o.optInt("bubbleStrokeAlpha", 255)));
            s.bubbleTextColor = o.optInt("bubbleTextColor", 0x536BA9);
            s.bubbleHintColor = o.optInt("bubbleHintColor", 0x9FB0D9);
            s.peakShow = o.optBoolean("peakShow", true);
            s.peakStyle = PeakValley.clampStyle(o.optInt("peakStyle", PeakValley.STYLE_DEFAULT));
            s.peakColor = o.optInt("peakColor", 0xE0433F) & 0xFFFFFF;
            s.valleyColor = o.optInt("valleyColor", 0x2FA24C) & 0xFFFFFF;
            s.peakTextCustom = o.optString("peakTextCustom", "");
            s.valleyTextCustom = o.optString("valleyTextCustom", "");
            s.countdownFormat = PeakValley.clampCountdownFormat(
                    o.optInt("countdownFormat", PeakValley.CD_SMART));
            s.bubbleModulesJson = o.optString("bubbleModulesJson", "");
            s.bubbleSeqJson = o.optString("bubbleSeqJson", "");
            s.bubbleTtlMs = clampBubbleTtl(o.optInt("bubbleTtlMs", 5000));
            s.pressSquashOn = o.optBoolean("pressSquashOn", true);
            s.doubleTapAction = o.optInt("doubleTapAction", DOUBLE_TAP_HIT) == DOUBLE_TAP_SQUASH
                    ? DOUBLE_TAP_SQUASH : DOUBLE_TAP_HIT;
            s.longPressAction = o.optInt("longPressAction", LONG_PRESS_MENU) == LONG_PRESS_OFF
                    ? LONG_PRESS_OFF : LONG_PRESS_MENU;
            s.bubbleTopMode = clampBubbleTopMode(o.optInt("bubbleTopMode", BUBBLE_TOP_SHRINK));
            s.bubbleScalePercent = clampBubbleScale(o.optInt("bubbleScalePercent", 100));
            String set = o.optString("pressSoundSet", "duck");
            s.pressSoundSet = ("duck".equals(set) || "fx1".equals(set) || "off".equals(set)) ? set : "duck";
            double pv = o.optDouble("pressVolume", 0.8);
            if (pv >= 0 && pv <= 1) s.pressVolume = pv;
            double poll = o.optDouble("pollSeconds", 30);
            if (!Double.isNaN(poll) && !Double.isInfinite(poll)) {
                s.pollSeconds = Math.min(600, Math.max(1, poll));
            }
            double custom = o.optDouble("customSideDp", 0);
            if (custom >= PetLayout.MIN_SIDE_DP && custom <= PetLayout.MAX_SIDE_DP) {
                s.customSideDp = (float) custom;
            }
            // 一次性迁移（v1.5.9）：旧版本的峰谷模块样式「默认(0)」→「跟随全局(-1)」
            s.peakStyleMigrated = o.optBoolean("peakStyleMigrated", false);
            if (!s.peakStyleMigrated) {
                String beforeModules = s.bubbleModulesJson;
                String beforeSeq = s.bubbleSeqJson;
                s.bubbleModulesJson = migrateModulesJson(s.bubbleModulesJson);
                s.bubbleSeqJson = migrateSeqJson(s.bubbleSeqJson);
                s.peakStyleMigrated = true;
                if (!beforeModules.equals(s.bubbleModulesJson) || !beforeSeq.equals(s.bubbleSeqJson)) {
                    s.migrationApplied = true;   // 调用方（服务/设置页）看到就写盘
                }
            }
            s.offline = o.optBoolean("offline", false);
            s.apiPath = o.optString("apiPath", "");
            s.autoStart = o.optBoolean("autoStart", false);
            int wm = o.optInt("windowMode", MODE_SINGLE);
            s.windowMode = (wm == MODE_OUTLINE) ? MODE_OUTLINE : MODE_SINGLE;
            double vol = o.optDouble("volume", 0.7);
            if (vol >= 0 && vol <= 1) s.volume = vol;
            if (o.has("originX") && o.has("originY")) {
                double x = o.optDouble("originX", Double.NaN);
                double y = o.optDouble("originY", Double.NaN);
                if (!Double.isNaN(x) && !Double.isNaN(y)) {
                    s.originX = (float) x;
                    s.originY = (float) y;
                }
            }
        } catch (Exception e) {
            Log.write("state.json 无法解析，使用默认值: " + e);
        }
        return s;
    }

    public void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("character", character.id);
                        o.put("customCharacterId", customCharacterId == null ? "" : customCharacterId);
                        o.put("customCharactersJson", customCharactersJson == null ? "" : customCharactersJson);
            o.put("sizeIndex", Math.min(PetLayout.SIZE_PRESETS_DP.length - 1, Math.max(0, sizeIndex)));
            o.put("dragMode", dragMode);
            o.put("snapOnRelease", dragMode == DRAG_CORNER);
            o.put("soundOn", soundOn);
            o.put("soundThrottleMs", Math.max(0, soundThrottleMs));
            o.put("soundPath", soundPath == null ? "" : soundPath);
            o.put("targetFps", targetFps);
            o.put("offlineArtOnDisconnect", offlineArtOnDisconnect);
            o.put("bubbleFillColor", bubbleFillColor & 0xFFFFFF);
            o.put("bubbleFillAlpha", Math.min(255, Math.max(0, bubbleFillAlpha)));
            o.put("bubbleStrokeColor", bubbleStrokeColor & 0xFFFFFF);
            o.put("bubbleStrokeAlpha", Math.min(255, Math.max(0, bubbleStrokeAlpha)));
            o.put("bubbleTextColor", bubbleTextColor & 0xFFFFFF);
            o.put("bubbleHintColor", bubbleHintColor & 0xFFFFFF);
            o.put("peakShow", peakShow);
            o.put("peakStyle", PeakValley.clampStyle(peakStyle));
            o.put("peakColor", peakColor & 0xFFFFFF);
            o.put("valleyColor", valleyColor & 0xFFFFFF);
            o.put("peakTextCustom", peakTextCustom == null ? "" : peakTextCustom);
            o.put("valleyTextCustom", valleyTextCustom == null ? "" : valleyTextCustom);
            o.put("countdownFormat", PeakValley.clampCountdownFormat(countdownFormat));
            o.put("bubbleModulesJson", bubbleModulesJson == null ? "" : bubbleModulesJson);
            o.put("bubbleSeqJson", bubbleSeqJson == null ? "" : bubbleSeqJson);
            o.put("bubbleTtlMs", clampBubbleTtl(bubbleTtlMs));
            o.put("peakStyleMigrated", peakStyleMigrated);
            o.put("pressSquashOn", pressSquashOn);
            o.put("doubleTapAction", doubleTapAction);
            o.put("longPressAction", longPressAction);
            o.put("bubbleTopMode", bubbleTopMode);
            o.put("bubbleScalePercent", clampBubbleScale(bubbleScalePercent));
            o.put("pressSoundSet", pressSoundSet == null ? "duck" : pressSoundSet);
            o.put("pressVolume", pressVolume);
            o.put("pollSeconds", (Double.isNaN(pollSeconds) || Double.isInfinite(pollSeconds))
                    ? 30 : Math.min(600, Math.max(1, pollSeconds)));
            o.put("customSideDp", (double) Math.min(PetLayout.MAX_SIDE_DP,
                    Math.max(0f, customSideDp)));
            o.put("offline", offline);
            o.put("apiPath", apiPath == null ? "" : apiPath);
            o.put("autoStart", autoStart);
            o.put("windowMode", windowMode);
            o.put("volume", volume);
            if (originX != null && originY != null) {
                o.put("originX", (double) originX);
                o.put("originY", (double) originY);
            }
            File file = PetPaths.stateFile();
            File temp = new File(file.getParentFile(), "state.json.tmp");
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!temp.renameTo(file)) {
                temp.delete();
            }
        } catch (Exception e) {
            Log.write("保存 state.json 失败: " + e);
        }
    }
}