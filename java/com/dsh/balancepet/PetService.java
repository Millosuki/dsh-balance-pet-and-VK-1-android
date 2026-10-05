package com.dsh.balancepet;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 桌宠主体。对应 macOS 版的 PetController：
 * 窗口几何、菜单、轮询、音效、状态快照都在这里。
 *
 * Android 侧的关键差异（详见 PetAssets 注释）：
 *   - 「逐像素穿透」改为「轮廓矩形多窗口」，每个窗口都可触摸，透明处无窗口覆盖；
 *   - 「右键菜单」改为长按呼出；
 *   - 「菜单栏 ¥」改为常驻通知（带 刷新 / 测试扣费 / 设置 / 退出 动作）。
 */
public final class PetService extends Service {

    public static final String ACTION_START = "com.dsh.balancepet.START";
    public static final String ACTION_STOP = "com.dsh.balancepet.STOP";
    public static final String ACTION_REFRESH = "com.dsh.balancepet.REFRESH";
    public static final String ACTION_ONE_HIT = "com.dsh.balancepet.ONE_HIT";
    public static final String ACTION_RELOAD = "com.dsh.balancepet.RELOAD";
    /** 设置界面改完 state.json 后，用它让服务重新贴合窗口 */
    public static final String ACTION_APPLY = "com.dsh.balancepet.APPLY";
    /** 远程触发离线自检（结果写日志，便于无界面验证） */
    public static final String ACTION_SELFTEST = "com.dsh.balancepet.SELFTEST";
    /** 把桌宠拉回左下角（自由拖动模式下用得上） */
    public static final String ACTION_SNAP = "com.dsh.balancepet.SNAP";
    /** 触发一次连续扣费演示（--ei fen N），用于压测渲染/动画 */
    public static final String ACTION_DEMO = "com.dsh.balancepet.DEMO";
    /** 重新装载音效（换过文件后） */
    public static final String ACTION_RELOAD_SOUND = "com.dsh.balancepet.RELOAD_SOUND";
    /** 试听一次音效 */
    public static final String ACTION_TEST_SOUND = "com.dsh.balancepet.TEST_SOUND";
    /** 设置动画帧率（--ei fps 0/30/60/90/120） */
    public static final String ACTION_SET_FPS = "com.dsh.balancepet.SET_FPS";
    /** 切换「未连接时显示抱盆图」（--ez on true/false） */
    public static final String ACTION_SET_OFFLINE_ART = "com.dsh.balancepet.SET_OFFLINE_ART";
    /** 切换离线模式（--ez on true/false），便于不改凭证地验证离线表现 */
    public static final String ACTION_SET_OFFLINE = "com.dsh.balancepet.SET_OFFLINE";
    /** 直接弹一个余额泡泡（脚本验证用；正常路径是单击桌宠） */
    public static final String ACTION_TEST_BUBBLE = "com.dsh.balancepet.TEST_BUBBLE";
    /** 把桌宠移到指定屏幕坐标（--ei x/--ei y），脚本验证用 */
    public static final String ACTION_SET_POS = "com.dsh.balancepet.SET_POS";
    /** 脚本设置泡泡外观（--ei fill/--ei alpha/--ei stroke），便于像素级验证 */
    public static final String ACTION_SET_BUBBLE_STYLE = "com.dsh.balancepet.SET_BUBBLE_STYLE";
    /** 试听按压音（按一下→松开） */
    public static final String ACTION_TEST_PRESS = "com.dsh.balancepet.TEST_PRESS";
    /** 脚本触发「双击＝挤压效果」的那套形变（用于远程验证，不必往屏幕上注入触摸）。 */
    public static final String ACTION_TEST_SQUEEZE = "com.dsh.balancepet.TEST_SQUEEZE";
    /** 脚本模拟「点角色」（驱动点击序列状态机，无需真实触摸）。 */
    public static final String ACTION_TEST_TAP = "com.dsh.balancepet.TEST_TAP";
    /** 脚本模拟「点泡泡」（推进序列 / 收起）。 */
    public static final String ACTION_TEST_BUBBLE_CLICK = "com.dsh.balancepet.TEST_BUBBLE_CLICK";
    /**
     * 脚本驱动「模块化编辑器」的语义（v1.6.0）。
     *
     * <p>为什么需要：编辑器界面在真机上**我点不了**（ColorOS 拦 {@code am start}、无法注入触摸），
     * 所以「拖拽排序 / 跨行并排 / 插入位置」这些只能通过这条通道验证 —— 它调用的是编辑器**同一份**
     * {@link PetBubbleEditModel} 代码，不是另写一套。
     *
     * <pre>
     * --es ops "pair:0,0&gt;1,R;drop:2,row&gt;0:A"  编辑命令（; 分隔，语法见 PetBubbleEditModel.exec）
     * --es fromJson "[...]"                     起点模块 JSON（默认 = 当前配置；"default" = 内置预设）
     * --ez show true                            改完弹一个泡泡（截图取证用，默认 true）
     * --ez save true                            改完写进 state.json（默认 **false**，不污染用户配置）
     * </pre>
     */
    public static final String ACTION_TEST_EDIT = "com.dsh.balancepet.TEST_EDIT";
    /** 切换渲染模式（--ei mode 0/1），便于脚本化对比性能 */
    public static final String ACTION_MODE = "com.dsh.balancepet.MODE";
    /** 编辑器模式（v1.12.1）：{@code --ez on true/false} —— 编辑器在前台时把桌宠/泡泡临时收起。 */
    public static final String ACTION_EDITOR_MODE = "com.dsh.balancepet.EDITOR_MODE";
        /** v1.13.1：脚本化切角色（{@code --es id whale}，也能切自定义角色）。 */
        public static final String ACTION_SET_CHARACTER = "com.dsh.balancepet.SET_CHARACTER";
    public static final String ACTION_STATE_CHANGED = "com.dsh.balancepet.STATE_CHANGED";

    private static final int NOTIFICATION_ID = 4711;
    private static final String CHANNEL_ID = "dsh_pet";
    private static final long TICK_MS = 33;            // 30fps 兜底（原版节奏）
    private static final long TICK_FAST_MS = 16;       // 动画时尽量跟屏幕刷新（≈60fps）
    private static final long IDLE_MS = 120;           // 空闲时的低频心跳（仍能按时轮询）
    private static final long STATUS_INTERVAL_MS = 1000;
    private static final float CORNER_MARGIN_DP = 14f; // 原版左下角 14pt
    private static final long LONG_PRESS_MS = 480;
    /** 跑马灯的 vsync 跳帧计数（v1.9.0：90Hz 屏上每 3 帧画 2 帧 = 60fps）。 */
    private int marqueeFrameSkip = 0;
    private static final long DOUBLE_TAP_MS = 280;
    /** 「双击 = 挤压效果」时按住不动的时间（回弹动画另算 220ms）。 */
    private static final long SQUEEZE_HOLD_MS = 400;
    /** 泡泡在「屏幕上方空间不足」时允许等比缩小的下限（形状与文字同比例，尾巴仍钉在桌宠头顶）。 */
    private static final float MIN_BUBBLE_SCALE = 0.55f;
    /** 峰谷行的刷新周期（毫秒）。上游 bubbleCountdownTick 用的也是 1000ms。 */
    private static final long PEAK_TICK_MS = 1000L;
    /** 轮廓窗口数量硬上限（厂商窗口守卫实测会在几十个窗口时强杀应用）。 */
    private static final int MAX_WINDOWS = 16;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PetModel model = new PetModel();
    private PetState state;
    /**
     * 本地账本（v1.12.0）。
     *
     * <p>DeepSeek 余额接口（{@code /user/balance}）**只返回余额**，没有任何「今日已用/累计消费」字段
     * （2026-10-05 核实：官方文档 schema 与上游插件 {@code API_TEMPLATES.deepseek} 都只有
     * {@code balance_infos[0].total_balance}）→ 这两个数字只能自己观测余额下降来记，
     * 与上游 {@code accounting.mjs} 的「已观测消费」同口径。
     */
    private SpendLedger ledger = new SpendLedger();
    private PollSchedule schedule;
    private CredentialStore.Credential credential;
    private PetAssets.Artwork artwork;

    private WindowManager windowManager;
    private final List<Band> bands = new ArrayList<>();
    /** 被「编辑器模式」临时收起的窗口（按原可见性还原；空 = 没在编辑器模式）。 */
    private final java.util.List<View> editorHidden = new java.util.ArrayList<>();
    private final java.util.List<Integer> editorHiddenVis = new java.util.ArrayList<>();
    /** 进入编辑器模式的时刻（看门狗用：编辑器异常退出也不能让桌宠一直不回来）。 */
    private long editorModeSince = 0L;
    /** 最近一次泡泡是否画在桌宠下方（v1.12.6；runtime.json 会给编辑器预览用它决定箭头方向）。 */
    private boolean lastBubbleBelow;
    private PetRenderer renderer;
    private PetSceneView sceneView;
    private WindowManager.LayoutParams sceneParams;
    private PetOverlayView floatView;         // 轮廓模式下独立的飘字/绿环层
    private WindowManager.LayoutParams floatParams;

    private View menuView;

    // ---- Whale 挂件交互（第 1 步：单击出泡）----
    private PetBubbleView bubbleView;
    private WindowManager.LayoutParams bubbleParams;
    private float bubbleCanvasW, bubbleCanvasH, bubbleOriginX, bubbleOriginY;
    private int bubbleToken = 0;

    // ---- 峰谷行（v1.5.0） ----
    /**
     * 测试用「模拟时刻」偏移量（秒）：由控制广播设置，让脚本能在任意时段取证
     * （周末 / 法定节假日 / 高峰边界…）。0 = 使用真实系统时间。
     */
    private long fakeOffsetSec = 0L;
    /** 上一次写进泡泡的峰谷行文案 / 颜色 / 峰谷状态，用于判断是否真的需要重绘。 */
    private String lastPeakText = null;
    private int lastPeakColor = 0;
    private boolean lastPeakIsPeak = false;

    // ---- 点击序列（v1.5.4 三期） ----
    /** 当前生效的序列（缓存；设置变更时置 null 重载）。 */
    private PetBubbleSeq seqCache;
    /** 下一个要显示的项下标（对应上游 bubbleSeqIdx）。 */
    private int seqIdx = 0;
    /** 是否处于「手动轮」（对应上游 bubbleRoundOn）；事件泡泡不算手动轮。 */
    private boolean roundOn = false;
    private final java.util.Random seqRnd = new java.util.Random();

    private float sidePx, windowW, windowH;
    private int posX, posY;
    private int screenW, screenH;
    /** 顶部安全区（刘海/挖孔/状态栏）高度：泡泡与桌宠都不要钻到它下面去。 */
    private int safeTopInset = 0;

    private long lastFrameNanos;
    private long lastStatusMs;
    private boolean running;
    private boolean screenInteractive = true;
    /** 屏幕刷新率（Hz），随显示模式变化刷新。 */
    private float displayHz = 60f;
    /** 屏幕支持的最大刷新率（用于「跟随屏幕」时申请高刷）。 */
    private float displayMaxHz = 60f;
    // 帧率监控（每秒统计一次）
    private int framesThisSecond = 0;
    private int measuredFps = 0;
    private float avgWorkMs = 0f;
    private float avgVsyncMs = 0f;

    private SoundPool soundPool;
    private int soundId;
    /** 压测静音窗口（秒，elapsedRealtime 基准）。 */
    private double soundMuteUntil = 0;
    private long lastHitSoundMs = 0;

    // ---- 按压音效（WhaleWidget：press/release 两个音，松开音按"按压音剩余时长-40ms"排期） ----
    private static final long RELEASE_LEAD_MS = 40;      // 源码常量
    private android.media.MediaPlayer pressPlayer;
    private android.media.MediaPlayer releasePlayer;
    private boolean pressing = false;
    private boolean pressEnded = false;
    private boolean releasePlayed = false;
    private String loadedPressSet = "";

    /** 分带窗口的外扩量（按压形变期间避免内容被窗口边界裁切）。 */
    private boolean bandsInflated = false;

    private float touchDownRawX, touchDownRawY;
    private int touchDownPosX, touchDownPosY;
    private long touchDownTime;
    private boolean dragging;
    private boolean longPressFired;
    private long lastTapTime;
    private ValueAnimator snapAnimator;

    private final class Band {
        final View view;
        final WindowManager.LayoutParams params;
        final RectF rect;

        Band(View view, WindowManager.LayoutParams params, RectF rect) {
            this.view = view;
            this.params = params;
            this.rect = rect;
        }
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenInteractive = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenInteractive = true;
                // 睡眠唤醒后不要补播一大串动画（与原版 didWake 一致）
                lastFrameNanos = System.nanoTime();
                schedule.wake(now());
                Log.write("屏幕点亮，唤醒轮询");
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) { keepWindowVisible(); }
    };

    // ---------------------------------------------------------------- 生命周期

    @Override public void onCreate() {
        super.onCreate();
        PetPaths.init(this);
        Log.write("PetService.onCreate 开始");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        state = PetState.load();
        if (state.migrationApplied) {
            state.save();
            Log.write("状态迁移已写盘（v1.5.9：峰谷模块样式改为跟随全局）");
        }
        // 账本单独一个文件（不塞进 state.json）：编辑器保存 state 时不会连带覆盖它
        ledger = SpendLedger.fromJson(CredentialStore.readText(PetPaths.ledgerFile()));
        Log.write("账本载入：" + ledger.describe());
        sidePx = dp(state.sideDp());
        windowW = PetLayout.windowWidth(sidePx);
        windowH = PetLayout.windowHeight(sidePx);
        measureScreen();
        measureDisplayRefresh();

        credential = CredentialStore.resolve(state.offline, state.apiPath);
        schedule = new PollSchedule(state.pollSeconds);
        model.setCredentialSource(credential == null ? null : credential.shortDescription());
        model.onHit = new Runnable() {
            @Override public void run() { playHit(); }
        };

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        initSound();
        loadPressSounds();
        startForegroundInternal();
        buildOverlay();

        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, screenFilter);
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm != null) dm.registerDisplayListener(displayListener, handler);

        running = true;
        lastFrameNanos = System.nanoTime();
        Choreographer.getInstance().postFrameCallback(frameCallback);
        Log.write(String.format(Locale.US,
                "桌宠启动：角色=%s 尺寸=%.0fpx 窗口=%.0fx%.0f 位置=(%d,%d) 凭证=%s 屏幕=%.0fHz 目标=%s",
                PetCharacters.current(state).id, sidePx, windowW, windowH, posX, posY,
                credential == null ? "无" : credential.shortDescription(),
                displayHz, state.targetFps <= 0 ? "跟随屏幕" : (state.targetFps + "fps")));
        if (credential == null) {
            Log.write("没有可用凭证：可长按桌宠 → 设置 API Key，或放到 Download/DSHPet/apikey.txt");
        }
        // 远程控制令牌写进日志，方便 adb / Shizuku 脚本取用（普通应用读不到别人的日志）
        Log.write("远程控制令牌：token=" + PetPaths.controlToken());
        poll(true, true);
        writeStatus(true);
        writeRuntimeSnapshot();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(action)) {
            reloadCredentialIfNeeded();
            poll(true, true);
        } else if (ACTION_ONE_HIT.equals(action)) {
            model.playOneHit();
        } else if (ACTION_RELOAD.equals(action)) {
            reloadCredential();
        } else if (ACTION_APPLY.equals(action)) {
            applyStateFromDisk();
        } else if (ACTION_SELFTEST.equals(action)) {
            Log.write("=== 离线自检开始（远程触发）===");
            String report = SelfTests.run(this);
            for (String line : report.split("\n")) Log.write("自检 | " + line);
            Log.write("=== 离线自检结束 ===");
        } else if (ACTION_SNAP.equals(action)) {
            snapToCorner(true);
        } else if (ACTION_DEMO.equals(action)) {
            int fen = intent.getIntExtra("fen", 10);
            model.playDemo(fen);
            // 压测用：--ez mute true 让这段演示静音，避免打扰正在用手机的人
            if (intent.getBooleanExtra("mute", false)) {
                soundMuteUntil = now() + Math.max(3, fen * model.stepInterval + 1.5);
                Log.write("演示连续扣费 " + fen + " 次（已静音）");
            } else {
                Log.write("演示连续扣费 " + fen + " 次");
            }
        } else if (ACTION_MODE.equals(action)) {
            setWindowMode(intent.getIntExtra("mode", PetState.MODE_SINGLE));
        } else if (ACTION_EDITOR_MODE.equals(action)) {
            setEditorMode(intent.getBooleanExtra("on", false));
        } else if (ACTION_SET_CHARACTER.equals(action)) {
            setCharacterById(intent.getStringExtra("id"));
        } else if (ACTION_RELOAD_SOUND.equals(action)) {
            state = PetState.load();
            loadSound();
        } else if (ACTION_TEST_SOUND.equals(action)) {
            playHit();
            Log.write("试听音效：" + soundSourceName);
        } else if (ACTION_SET_FPS.equals(action)) {
            state.targetFps = intent.getIntExtra("fps", PetTuning.TARGET_FPS_FOLLOW);
            state.save();
            measureDisplayRefresh();
            Log.write("动画帧率 → " + (state.targetFps <= 0 ? "跟随屏幕" : state.targetFps + " fps")
                    + "（屏幕 " + Math.round(displayHz) + "Hz）");
        } else if (ACTION_SET_OFFLINE_ART.equals(action)) {
            state.offlineArtOnDisconnect = intent.getBooleanExtra("on", true);
            state.save();
            buildOverlay();
            Log.write("未连接时显示抱盆图 → " + (state.offlineArtOnDisconnect ? "开（v1.3.1 行为）" : "关（平板图 + --）"));
        } else if (ACTION_SET_OFFLINE.equals(action)) {
            state.offline = intent.getBooleanExtra("on", true);
            state.save();
            credential = CredentialStore.resolve(state.offline, state.apiPath);
            schedule.reload(now());
            model.resetForCredentialChange();
            model.setCredentialSource(credential == null ? null : credential.shortDescription());
            if (credential == null) model.setNoCredential();
            buildOverlay();
            Log.write("离线模式 → " + (state.offline ? "开" : "关"));
            poll(true, true);
        } else if (ACTION_TEST_BUBBLE.equals(action)) {
            // --es fakeTime "2026-10-05 10:30"：让脚本在任意时段弹泡泡取证（可省略）
            if (intent.hasExtra("fakeTime")) applyFakeTime(intent.getStringExtra("fakeTime"));
            showBalanceBubble();
        } else if (ACTION_SET_POS.equals(action)) {
            int x = intent.getIntExtra("x", posX);
            int y = intent.getIntExtra("y", posY);
            moveWindows(x, y);
            persistOrigin();
            writeStatus(true);
            Log.write("桌宠移到 (" + x + "," + y + ")（脚本设置）");
        } else if (ACTION_SET_BUBBLE_STYLE.equals(action)) {
            state.bubbleFillColor = intent.getIntExtra("fill", state.bubbleFillColor) & 0xFFFFFF;
            state.bubbleFillAlpha = Math.max(0, Math.min(255,
                    intent.getIntExtra("alpha", state.bubbleFillAlpha)));
            state.bubbleStrokeColor = intent.getIntExtra("stroke", state.bubbleStrokeColor) & 0xFFFFFF;
            if (intent.hasExtra("topMode")) {
                state.bubbleTopMode = PetState.clampBubbleTopMode(
                        intent.getIntExtra("topMode", state.bubbleTopMode));
            }
            if (intent.hasExtra("peakShow")) {
                state.peakShow = intent.getBooleanExtra("peakShow", state.peakShow);
            }
            if (intent.hasExtra("peakStyle")) {
                state.peakStyle = PeakValley.clampStyle(intent.getIntExtra("peakStyle", state.peakStyle));
            }
            if (intent.hasExtra("peakColor")) {
                state.peakColor = intent.getIntExtra("peakColor", state.peakColor) & 0xFFFFFF;
            }
            if (intent.hasExtra("valleyColor")) {
                state.valleyColor = intent.getIntExtra("valleyColor", state.valleyColor) & 0xFFFFFF;
            }
            if (intent.hasExtra("peakText")) {
                state.peakTextCustom = intent.getStringExtra("peakText");
            }
            if (intent.hasExtra("valleyText")) {
                state.valleyTextCustom = intent.getStringExtra("valleyText");
            }
            if (intent.hasExtra("cdFormat")) {
                state.countdownFormat = PeakValley.clampCountdownFormat(
                        intent.getIntExtra("cdFormat", state.countdownFormat));
            }
            if (intent.hasExtra("modulesJson")) {
                String v = intent.getStringExtra("modulesJson");
                String t = v == null ? "" : v.trim();
                if (t.isEmpty() || "clear".equalsIgnoreCase(t)) {
                    state.bubbleModulesJson = "";
                    Log.write("模块：已清空 → 回到经典四行");
                } else if ("default".equalsIgnoreCase(t)) {
                    state.bubbleModulesJson = PetBubbleModule.listToJson(PetBubbleModule.defaultPreset());
                } else {
                    // 先解析校验：解析不出来就**不动配置**，避免把用户的设置写坏
                    List<PetBubbleModule> parsed = PetBubbleModule.listFromJson(t);
                    if (parsed.isEmpty()) {
                        Log.write("模块 JSON 无法解析（或为空），已忽略本次设置（配置未改动）");
                    } else {
                        state.bubbleModulesJson = PetBubbleModule.listToJson(parsed);
                    }
                }
            }
            if (intent.hasExtra("seqJson")) {
                String v = intent.getStringExtra("seqJson");
                String t = v == null ? "" : v.trim();
                if (t.isEmpty() || "clear".equalsIgnoreCase(t)) {
                    state.bubbleSeqJson = "";
                    Log.write("序列：已清空 → 回到内置默认序列");
                } else if ("default".equalsIgnoreCase(t)) {
                    state.bubbleSeqJson = PetBubbleSeq.defaultSeq().toJson();
                } else {
                    PetBubbleSeq parsed = PetBubbleSeq.fromJson(t);
                    if (parsed.parseFailed || parsed.items.isEmpty()) {
                        Log.write("序列 JSON 无法解析（或 items 为空），已忽略本次设置（配置未改动）");
                    } else {
                        state.bubbleSeqJson = parsed.toJson();
                    }
                }
            }
            if (intent.hasExtra("tapAdvance")) {
                state.bubbleSeqJson = PetBubbleSeq.withTapAdvance(state.bubbleSeqJson,
                        intent.getBooleanExtra("tapAdvance", false));
            }
            if (intent.hasExtra("ttlMs")) {
                state.bubbleTtlMs = PetState.clampBubbleTtl(
                        intent.getIntExtra("ttlMs", state.bubbleTtlMs));
            }
            if (intent.hasExtra("scale")) {   // v1.7.0：泡泡大小（百分比）
                state.bubbleScalePercent = PetState.clampBubbleScale(
                        intent.getIntExtra("scale", state.bubbleScalePercent));
            }
            state.save();
            Log.write("泡泡外观（脚本设置）：" + PetBubbleStyle.from(state).describe()
                    + " 顶部策略=" + PetState.bubbleTopModeLabel(state.bubbleTopMode)
                    + " 峰谷显示=" + (state.peakShow ? "开" : "关")
                    + " 样式=" + PeakValley.styleDisplayName(state.peakStyle)
                    + " 倒计时格式=" + PeakValley.countdownFormatName(state.countdownFormat)
                    + " 模块=" + PetBubbleModule.listFromJson(state.bubbleModulesJson).size() + "个"
                    + " 序列=" + PetBubbleSeq.fromJson(state.bubbleSeqJson).items.size() + "项"
                    + " 留存=" + PetState.bubbleTtlLabel(state.bubbleTtlMs)
                    + " 大小=" + PetState.bubbleScaleLabel(state.bubbleScalePercent));
            // 序列/模块参数可能刚被改过：必须让缓存的序列失效，否则会继续用旧序列（踩过）
            seqCache = null;
            if (intent.hasExtra("fakeTime")) {
                applyFakeTime(intent.getStringExtra("fakeTime"));
            } else {
                refreshPeakRowIfVisible();
            }
        } else if (ACTION_TEST_PRESS.equals(action)) {
            // --ei hold <ms>：按住时长，便于脚本在「完全压扁」状态下截图取证（默认 600ms）
            final int hold = Math.max(100, intent.getIntExtra("hold", 600));
            pressDown();
            handler.postDelayed(new Runnable() {
                @Override public void run() { pressUp(); }
            }, hold);
            Log.write("试听按压音（按下 → " + hold + "ms 后松开）");
        } else if (ACTION_TEST_SQUEEZE.equals(action)) {
            playSqueeze();
        } else if (ACTION_TEST_TAP.equals(action)) {
            Log.write("脚本：模拟点角色（驱动点击序列）");
            onCharacterTap();
        } else if (ACTION_TEST_BUBBLE_CLICK.equals(action)) {
            Log.write("脚本：模拟点泡泡（推进序列 / 收起）");
            advanceOrHide();
        } else if (ACTION_TEST_EDIT.equals(action)) {
            runScriptedEdit(intent);
        }
        return START_STICKY;
    }

    /**
     * 执行一次脚本化的「模块编辑」（{@link #ACTION_TEST_EDIT}）。
     *
     * <p>默认**不写盘**（{@code save=false}）：脚本验证不该污染用户的真实配置。
     */
    private void runScriptedEdit(Intent intent) {
        String ops = intent == null ? null : intent.getStringExtra("ops");
        if (ops == null) ops = "";
        String from = intent.getStringExtra("fromJson");
        String base;
        if (from == null) base = state.bubbleModulesJson;
        else if ("default".equalsIgnoreCase(from.trim())) base = PetBubbleModule.listToJson(PetBubbleModule.defaultPreset());
        else base = from;

        List<List<PetBubbleModule>> rows = PetBubbleEditModel.rowsFromJson(base);
        Log.write("TEST_EDIT：起点=" + PetBubbleEditModel.describe(rows));
        int ok = 0;
        if (ops.trim().isEmpty()) {
            Log.write("TEST_EDIT：没有 ops → 直接按 fromJson 渲染（编辑器「弹真实泡泡」走这条路）");
        } else {
            ok = PetBubbleEditModel.execAll(rows, ops);
        }
        String json = PetBubbleEditModel.rowsToJson(rows);
        Log.write("TEST_EDIT：ops=\"" + ops + "\" → 成功 " + ok + " 条；结果="
                + PetBubbleEditModel.describe(rows));
        Log.write("TEST_EDIT：生成的模块 JSON=" + json);

        boolean save = intent.getBooleanExtra("save", false);
        boolean show = intent.getBooleanExtra("show", true);
        if (save) {
            state.bubbleModulesJson = json;
            state.save();
            Log.write("TEST_EDIT：已写盘（模块=" + PetBubbleEditModel.count(rows) + " 个）");
        }
        if (show) {
            PetBubble bubble = new PetBubble("balance", WhaleBubbleSpec.DEFAULT_LABEL,
                    model.isConnected() ? model.realString() : WhaleBubbleSpec.OFFLINE_AMOUNT,
                    statusHint());
            bubble.modules.addAll(PetBubbleEditModel.toModules(rows));
            applyPeakRow(bubble, nowSec());
            assignMarqueeDurations(bubble);
            if (bubble.hasModules()) logModuleLayout(bubble);
            showBubble(bubble, state.bubbleTtlMs);
        }
    }

    /** 设置界面改完 state.json 后调用：重新读盘并重建窗口 / 调度 / 凭证。 */
    private void applyStateFromDisk() {
        if (!running) return;
        state = PetState.load();
        if (state.migrationApplied) {
            state.save();
            Log.write("状态迁移已写盘（v1.5.9：峰谷模块样式改为跟随全局）");
        }
        sidePx = dp(state.sideDp());
        windowW = PetLayout.windowWidth(sidePx);
        windowH = PetLayout.windowHeight(sidePx);
        schedule.setInterval(state.pollSeconds, now());
        credential = CredentialStore.resolve(state.offline, state.apiPath);
        // 关键顺序：必须在 buildOverlay() 之前把「未连接」状态落实，
        // 否则选图时用的还是旧的 connected=true → 切到离线不会换成抱盆图（曾出现过的 bug）。
        if (credential == null) {
            model.setNoCredential();
        } else {
            model.setCredentialSource(credential.shortDescription());
        }
        loadSound();                       // 音效设置可能变了（换了文件 / 音量）
        loadPressSounds();                 // 按压音效组/音量也可能变了
        seqCache = null;                   // 点击序列可能被改过：下次用新配置
        refreshPeakRowIfVisible();         // 峰谷样式/配色/开关也可能变了：立刻按新设置改写
        buildOverlay();
        keepWindowVisibleOnSizeChange();
        poll(true, true);
        writeStatus(true);
        writeRuntimeSnapshot();
        Log.write("设置已应用（角色/尺寸/间隔/音效等）");
    }

    @Override public void onDestroy() {
        running = false;
        stopPeakTicker();   // 峰谷 ticker 不能比服务生命周期长（否则会打到已清空的 bubbleView）
        try { Choreographer.getInstance().removeFrameCallback(frameCallback); } catch (Exception ignored) { }
        if (renderer != null) {
            renderer.release();
            renderer = null;
        }
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm != null) dm.unregisterDisplayListener(displayListener);
        removeAllWindows();
        if (snapAnimator != null) snapAnimator.cancel();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        releasePressPlayers();
        if (state != null) {
            state.originX = (float) posX;
            state.originY = (float) posY;
            state.save();
        }
        writeStatus(false);
        Log.write("桌宠已退出");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- 窗口几何

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void measureScreen() {
        safeTopInset = measureSafeTopInset();
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = windowManager == null
                    ? null : windowManager.getMaximumWindowMetrics().getBounds();
            if (bounds != null) {
                screenW = bounds.width();
                screenH = bounds.height();
                return;
            }
        }
        android.view.Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        display.getRealMetrics(metrics);
        screenW = metrics.widthPixels;
        screenH = metrics.heightPixels;
    }

    /**
     * 顶部安全区（刘海 / 挖孔 / 状态栏）高度。
     * 实测本机：DisplayCutout{insets=Rect(0,107,0,0)}，所以泡泡与桌宠的顶部边界都按 107px 算。
     * 读不到就按 0 处理——宁可少一层保护，也不要因为读不到而完全不显示。
     */
    private int measureSafeTopInset() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsets insets =
                        windowManager.getCurrentWindowMetrics().getWindowInsets();
                return insets.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.displayCutout()
                                | android.view.WindowInsets.Type.statusBars()).top;
            }
        } catch (Throwable t) {
            Log.write("顶部安全区读取失败（按 0 处理）: " + t);
        }
        return 0;
    }

    private void placeInitialOrRestore() {
        int margin = dp(CORNER_MARGIN_DP);
        int defaultX = margin;
        int defaultY = screenH - Math.round(windowH) - margin;
        if (state.originX != null && state.originY != null) {
            posX = Math.round(state.originX);
            posY = Math.round(state.originY);
            if (posX < -windowW * 0.5f || posX > screenW - windowW * 0.2f
                    || posY < -windowH * 0.5f || posY > screenH - windowH * 0.2f) {
                // 屏幕变化后旧位置已不可用 → 回到左下角
                posX = defaultX;
                posY = defaultY;
            }
        } else {
            posX = defaultX;
            posY = defaultY;
        }
    }

    private void keepWindowVisible() {
        if (!running) return;
        int oldScreenW = screenW, oldScreenH = screenH;
        measureScreen();
        measureDisplayRefresh();
        boolean moved = screenW != oldScreenW || screenH != oldScreenH;
        if (!moved) return;
        Log.write(String.format(Locale.US, "屏幕尺寸变化 %dx%d → %dx%d，重建窗口",
                oldScreenW, oldScreenH, screenW, screenH));
        buildOverlay();
        state.originX = (float) posX;
        state.originY = (float) posY;
        state.save();
    }

    /**
     * 是否改用抱盆图：仅「蓝色大肥鱼 + 未连接 + 开关打开」。
     * 逐条对应上游 PetView.showsOfflineArtwork 与 ArtworkSelfTests 的断言，纯函数便于自检。
     */
    static boolean shouldUseOfflineArt(PetCharacter character, boolean connected, boolean enabled) {
        return enabled && character == PetCharacter.DEEPSEEK && !connected;
    }

    /**
         * 当前要画的立绘：抱盆图（仅内置蓝色大肥鱼未连接）→ 自定义角色图片 → 内置角色素材。
         */
        private PetAssets.Artwork loadArtwork(PetCharacters.Current cur) {
            if (showsOfflineArtwork()) return PetAssets.get(PetCharacter.OFFLINE_ASSET, true);
            if (cur.isCustom()) return PetAssets.get(cur.file, false);
            return PetAssets.get(cur.assetName, false);
        }
        private boolean showsOfflineArtwork() {
            // 自定义角色没有抱盆图；只有内置的蓝色大肥鱼未连接时才换图
            boolean custom = state.customCharacterId != null && !state.customCharacterId.isEmpty();
            return !custom && shouldUseOfflineArt(state.character, model.isConnected(), state.offlineArtOnDisconnect);
        }

    private void buildOverlay() {
        removeAllWindows();
        placeInitialOrRestore();
        final PetCharacters.Current cur = PetCharacters.current(state);
                artwork = loadArtwork(cur);
        if (artwork == null) {
            Log.write("立绘装载失败，桌宠窗口为空");
            return;
        }
        PetAssets.releaseOthers(artwork.assetName);

        // 渲染器跨重建复用（预缩放位图很贵，不要每次都重算）
        if (renderer == null || !renderer.matches(artwork, windowW, windowH, cur.hasTablet())) {
                    if (renderer != null) renderer.release();
                    renderer = new PetRenderer(model, artwork, windowW, windowH,
                            cur.hasTablet(), cur.tabletCorners());
        }

        if (state.windowMode == PetState.MODE_SINGLE) buildSingleWindow();
        else buildOutlineWindows();

        // 每次都打印当前立绘与判定依据，避免"用了缓存所以没日志"导致无法验证
        Log.write("当前立绘：" + artwork.assetName
                        + "（角色=" + cur.id + "/" + cur.displayName
                + "，已连接=" + model.isConnected()
                + "，抱盆图开关=" + state.offlineArtOnDisconnect
                + "，离线模式=" + state.offline + "）");
    }

    /** 单窗口模式：1 个悬浮窗画完全部内容。 */
    private void buildSingleWindow() {
        sceneView = new PetSceneView(this, renderer);
        sceneView.setOnTouchListener(touchListener);
        sceneParams = new WindowManager.LayoutParams(
                Math.max(1, Math.round(windowW)), Math.max(1, Math.round(windowH)),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        sceneParams.gravity = Gravity.TOP | Gravity.LEFT;
        sceneParams.x = posX;
        sceneParams.y = posY;
        applyFrameRate(sceneParams);
        try {
            windowManager.addView(sceneView, sceneParams);
            Log.write("单窗口模式：1 个窗口（流畅，透明区也会吃点击）");
        } catch (Exception e) {
            Log.write("添加桌宠窗口失败: " + e);
            sceneView = null;
        }
    }

    /** 轮廓模式：每个穿透矩形一个窗口，透明处真穿透。 */
    private void buildOutlineWindows() {
        List<RectF> rects = artwork.screenRects(windowW, windowH);
        // 防线：ColorOS 有窗口数量守卫，超限会「Too many windows」直接强杀应用
        if (rects.size() > MAX_WINDOWS) {
            Log.write("警告：穿透矩形 " + rects.size() + " 个超过上限 " + MAX_WINDOWS
                    + "，按面积保留最大的若干个");
            java.util.Collections.sort(rects, new java.util.Comparator<RectF>() {
                @Override public int compare(RectF a, RectF b) {
                    return Float.compare(b.width() * b.height(), a.width() * a.height());
                }
            });
            rects = new ArrayList<>(rects.subList(0, MAX_WINDOWS));
        }
        int index = 0;
        for (RectF rect : rects) {
            int w = Math.max(1, Math.round(rect.width()));
            int h = Math.max(1, Math.round(rect.height()));
            PetBandView view = new PetBandView(this, renderer, rect);
            view.setOnTouchListener(touchListener);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = posX + Math.round(rect.left);
            lp.y = posY + Math.round(rect.top);
            applyFrameRate(lp);
            try {
                windowManager.addView(view, lp);
                bands.add(new Band(view, lp, rect));
                index++;
            } catch (Exception e) {
                Log.write("添加轮廓窗口失败: " + e);
            }
        }
        Log.write("轮廓模式：" + index + " 个窗口（" + artwork.assetName + "，透明处穿透）");
    }

    private void removeAllWindows() {
        for (Band band : bands) {
            try { windowManager.removeView(band.view); } catch (Exception ignored) { }
        }
        bands.clear();
        if (sceneView != null) {
            try { windowManager.removeView(sceneView); } catch (Exception ignored) { }
            sceneView = null;
            sceneParams = null;
        }
        hideFloatLayer();
        hideMenu();
        destroyBubbleWindow();
    }

    private void moveWindows(int x, int y) {
        posX = x;
        posY = y;
        if (sceneView != null && sceneParams != null) {
            sceneParams.x = posX;
            sceneParams.y = posY;
            try { windowManager.updateViewLayout(sceneView, sceneParams); } catch (Exception ignored) { }
        }
        for (Band band : bands) {
            band.params.x = posX + Math.round(band.rect.left);
            band.params.y = posY + Math.round(band.rect.top);
            try { windowManager.updateViewLayout(band.view, band.params); } catch (Exception ignored) { }
        }
        if (floatView != null && floatParams != null) {
            floatParams.x = posX;
            floatParams.y = posY;
            try { windowManager.updateViewLayout(floatView, floatParams); } catch (Exception ignored) { }
        }
        syncBubblePosition();
    }

    private void invalidateAll() {
        if (sceneView != null) sceneView.invalidate();
        for (Band band : bands) band.view.invalidate();
        if (floatView != null) floatView.invalidate();
    }

    /**
     * 只重绘「真正在动的那一层」：
     * 单窗口模式只有一个 view；轮廓模式把「角色层（抖动/受击/绿环）」与「飘字层」分开。
     */
    private void invalidateForFrame(boolean characterAnimating, boolean floatAnimating) {
        if (sceneView != null) {
            if (characterAnimating || floatAnimating) sceneView.invalidate();
            return;
        }
        if (characterAnimating) {
            for (Band band : bands) band.view.invalidate();
            if (floatView != null) floatView.invalidate();   // 绿环在飘字层
        } else if (floatAnimating && floatView != null) {
            floatView.invalidate();
        }
    }

    /** 松手吸附左下角：0.16 秒缓出（对应原版 NSAnimationContext 0.16s easeOut）。 */
    private void snapToCorner(boolean animated) {
        animateTo(dp(CORNER_MARGIN_DP),
                screenH - Math.round(windowH) - dp(CORNER_MARGIN_DP), animated, true);
    }

    /** 吸附到最近的左/右边缘，纵向位置保留（手机屏上更实用）。 */
    private void snapToEdge(boolean animated) {
        int margin = dp(CORNER_MARGIN_DP);
        int leftX = margin;
        int rightX = screenW - Math.round(windowW) - margin;
        int center = posX + Math.round(windowW / 2f);
        int targetX = (center < screenW / 2) ? leftX : rightX;
        int targetY = clampY(posY);
        animateTo(targetX, targetY, animated, true);
    }

    /**
     * 移动（可带动画），persist 为 true 时在终点写入并保存位置。
     * 修正了原版的一个移植坑：之前把「松手瞬间」的坐标存进 state，
     * 导致吸附动画结束后重启会回到丢下的位置而不是角落里。
     */
    private void animateTo(final int targetX, final int targetY, boolean animated, final boolean persist) {
        if (snapAnimator != null) snapAnimator.cancel();
        if (!animated) {
            moveWindows(targetX, targetY);
            if (persist) persistOrigin();
            return;
        }
        final int fromX = posX, fromY = posY;
        snapAnimator = ValueAnimator.ofFloat(0f, 1f);
        snapAnimator.setDuration(160);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                moveWindows(Math.round(fromX + (targetX - fromX) * t),
                        Math.round(fromY + (targetY - fromY) * t));
            }
        });
        snapAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                moveWindows(targetX, targetY);
                if (persist) persistOrigin();
                writeStatus(true);
            }
        });
        snapAnimator.start();
    }

    private void persistOrigin() {
        state.originX = (float) posX;
        state.originY = (float) posY;
        state.save();
    }

    /**
     * 自由拖动时的边界钳制：保证「至少 64dp 的立绘」留在屏幕内，
     * 否则桌宠会被拖到屏幕外再也抓不回来。坐标以窗口左上角为准。
     */
    private int[] clampPosition(int x, int y) {
        RectF sprite = PetLayout.spriteRect(windowW, windowH);
        int keep = Math.min(dp(64), Math.round(sprite.width() * 0.5f));
        int minX = keep - Math.round(sprite.right);
        int maxX = screenW - keep - Math.round(sprite.left);
        int minY = Math.max(keep - Math.round(sprite.bottom),
                safeTopInset - Math.round(sprite.top));   // 别把立绘顶到刘海/状态栏下面
        int maxY = screenH - keep - Math.round(sprite.top);
        if (minX > maxX) minX = maxX = Math.min(Math.max(x, 0), Math.max(0, screenW - 1));
        if (minY > maxY) minY = maxY = Math.min(Math.max(y, 0), Math.max(0, screenH - 1));
        return new int[]{Math.min(Math.max(x, minX), maxX), Math.min(Math.max(y, minY), maxY)};
    }

    private int clampY(int y) {
        return clampPosition(posX, y)[1];
    }

    private void setDragMode(int mode) {
        state.dragMode = Math.min(PetState.DRAG_EDGE, Math.max(PetState.DRAG_FREE, mode));
        state.snapOnRelease = state.dragMode == PetState.DRAG_CORNER;
        state.save();
        Log.write("拖动行为 → " + dragModeName(state.dragMode));
    }

    private static String dragModeName(int mode) {
        switch (mode) {
            case PetState.DRAG_CORNER: return "吸附左下角";
            case PetState.DRAG_EDGE: return "吸附最近边缘";
            default: return "自由拖动";
        }
    }

    /** 切换渲染模式：单窗口（流畅） / 轮廓穿透（精确）。 */
    private void setWindowMode(int mode) {
        state.windowMode = (mode == PetState.MODE_OUTLINE)
                ? PetState.MODE_OUTLINE : PetState.MODE_SINGLE;
        state.save();
        buildOverlay();
        writeStatus(true);
        Log.write("渲染模式 → " + renderModeName(state.windowMode));
    }

    private static String renderModeName(int mode) {
        return mode == PetState.MODE_OUTLINE ? "轮廓穿透" : "单窗口";
    }

    /** 刷新间隔的显示文案（1 秒 ~ 10 分钟，允许非整分钟值）。 */
    static String intervalLabel(double seconds) {
        if (seconds < 60) return ((int) seconds) + " 秒";
        if (Math.abs(seconds % 60) < 0.5) return ((int) (seconds / 60)) + " 分钟";
        return ((int) seconds) + " 秒（" + String.format(Locale.US, "%.1f", seconds / 60.0) + " 分）";
    }

    // ---------------------------------------------------------------- 触摸 / 拖动

    private final View.OnTouchListener touchListener = new View.OnTouchListener() {
        @Override public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownRawX = event.getRawX();
                    touchDownRawY = event.getRawY();
                    touchDownPosX = posX;
                    touchDownPosY = posY;
                    touchDownTime = SystemClock.uptimeMillis();
                    dragging = false;
                    longPressFired = false;
                    // WhaleWidget：按下即形变 + 按压音（拖动时也保持压扁）
                    if (state.pressSquashOn) pressDown();
                    // 静止长按不会产生 MOVE 事件，必须自己定时
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - touchDownRawX;
                    float dy = event.getRawY() - touchDownRawY;
                    if (!dragging && Math.hypot(dx, dy) >= dp(2)) {
                        dragging = true;
                        handler.removeCallbacks(longPressRunnable);
                    }
                    if (dragging) {
                        int[] clamped = clampPosition(Math.round(touchDownPosX + dx),
                                Math.round(touchDownPosY + dy));
                        moveWindows(clamped[0], clamped[1]);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    handler.removeCallbacks(longPressRunnable);
                    if (state.pressSquashOn) pressUp();      // 回弹 + 松开音（按剩余时长排期）
                    long nowMs = SystemClock.uptimeMillis();
                    if (dragging) {
                        // 先钳制边界，再按拖动行为决定落点
                        int[] clamped = clampPosition(posX, posY);
                        if (clamped[0] != posX || clamped[1] != posY) {
                            moveWindows(clamped[0], clamped[1]);
                        }
                        if (state.dragMode == PetState.DRAG_CORNER) {
                            snapToCorner(true);
                        } else if (state.dragMode == PetState.DRAG_EDGE) {
                            snapToEdge(true);
                        } else {
                            persistOrigin();
                            writeStatus(true);
                        }
                    } else if (!longPressFired) {
                        if (model.demoRemaining() > 0) {
                            // 正在连击演示：点一下就能停（避免"一直在扣"无从下手）
                            model.cancelDemo();
                        } else if (nowMs - lastTapTime < DOUBLE_TAP_MS) {
                            // 双击：行为可选（设置 → Whale挂件 · 泡泡外观 → 双击桌宠）
                            lastTapTime = 0;
                            handler.removeCallbacks(singleTapRunnable);
                            if (state.doubleTapAction == PetState.DOUBLE_TAP_SQUASH) {
                                playSqueeze();
                            } else {
                                // 默认＝原版语义：「双击托盘图标 = 手动触发一次扣费动画」
                                model.playOneHit();
                            }
                        } else {
                            // 单击：出手（WhaleWidget 的点击交互）。
                            // 与双击共用 280ms 判定窗口，所以单击会有轻微延迟——这是功能取舍。
                            lastTapTime = nowMs;
                            handler.postDelayed(singleTapRunnable, DOUBLE_TAP_MS);
                        }
                    }
                    dragging = false;
                    return true;
                }

                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    if (state.pressSquashOn) pressUp();
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        }
    };

    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            if (!running || renderer == null) return;   // 服务已销毁：延迟任务可能比生命周期长
            if (state.longPressAction == PetState.LONG_PRESS_OFF) {
                Log.write("长按桌宠：已关闭（设置 → 通用 → 长按桌宠）");
                return;
            }
            if (dragging || longPressFired) return;
            longPressFired = true;
            int x = Math.round(touchDownRawX);
            int y = Math.round(touchDownRawY);
            showMenu(x, y);
        }
    };

    /**
     * 单击（延迟 280ms 以区分双击）——语义**照抄上游 {@code whaleClick}（L13646–13676）**：
     * <ul>
     *   <li>当前无泡泡 → 开新轮，从第 1 项开始</li>
     *   <li>「点按角色推进队列」（tapAdvance）开 → 等价于点泡泡（推进一项）</li>
     *   <li>关（默认）：正显示第 1 项 → **只重置留存计时**（续时，不换内容）；
     *       第 2 项及以后 → **回到序列开头**</li>
     * </ul>
     */
    private final Runnable singleTapRunnable = new Runnable() {
        @Override public void run() {
            if (!running || renderer == null) return;   // 服务已销毁：延迟任务可能比生命周期长
            onCharacterTap();
        }
    };

    /** 点角色（上游 {@code whaleClick}）。单独抽出来是为了能被脚本动作直接驱动测试。 */
    private void onCharacterTap() {
        PetBubbleSeq seq = sequence();
        if (bubbleView == null) {
            Log.write("单击桌宠：开新轮，从第 1 项开始（序列共 " + seq.size() + " 项）");
            roundOn = true;
            seqIdx = 0;
            showSeqNext();
            return;
        }
        if (seq.tapAdvance) {
            Log.write("单击桌宠：tapAdvance 已开 → 推进序列");
            advanceOrHide();
            return;
        }
        if (!roundOn) {
            Log.write("单击桌宠：非手动轮（事件泡泡）→ 忽略");
            return;
        }
        if (seqIdx <= 1) {
            Log.write("单击桌宠：正显示第 1 项 → 仅续时（对应上游「重置留存计时」）");
            resetBubbleTtl();
            return;
        }
        Log.write("单击桌宠：已过第 1 项 → 回到序列开头");
        seqIdx = 0;
        showSeqNext();
    }

    // ---------------------------------------------------------------- 点击序列

    /** 当前生效的序列：空配置 → 内置默认序列（并继承 JSON 里的 tapAdvance）。 */
    private PetBubbleSeq sequence() {
        if (seqCache != null) return seqCache;
        PetBubbleSeq parsed = PetBubbleSeq.fromJson(state.bubbleSeqJson);
        if (parsed.parseFailed) {
            Log.write("序列配置解析失败 → 回落到内置默认序列（原配置未被覆盖）");
        }
        if (parsed.items.isEmpty()) {
            PetBubbleSeq def = PetBubbleSeq.defaultSeq();
            def.tapAdvance = parsed.tapAdvance;
            seqCache = def;
            Log.write("序列：使用内置默认序列（" + def.describe() + "）");
        } else {
            seqCache = parsed;
            Log.write("序列：使用自定义配置（" + parsed.describe() + "）");
        }
        return seqCache;
    }

    /**
     * 显示序列的下一项（对应上游 {@code bubbleShowSeqNext}，L12430–12446）：
     * 取 {@code seq[seqIdx]} → {@code seqIdx++} → 是 {@code choice} 就按权重抽一个 → 渲染。
     */
    private void showSeqNext() {
        PetBubbleSeq seq = sequence();
        if (seqIdx >= seq.size()) {
            Log.write("序列：已到末项 → 收起");
            seqIdx = 0;
            roundOn = false;
            hideBubble();
            return;
        }
        PetBubbleSeq.Item step = seq.items.get(seqIdx);
        seqIdx++;
        PetBubbleSeq.Item item = PetBubbleSeq.pick(step, seqRnd);
        if (item == null) {
            Log.write("序列：该项抽不出内容 → 收起");
            seqIdx = 0;
            roundOn = false;
            hideBubble();
            return;
        }
        showSeqItem(item, seqIdx);
    }

    /** 渲染序列项：{@code custom} 用自带模块；{@code random} 本版未支持 → 按默认页处理并写日志。 */
    private void showSeqItem(PetBubbleSeq.Item item, int ordinal) {
        PetBubble bubble = new PetBubble(
                "balance",
                WhaleBubbleSpec.DEFAULT_LABEL,
                model.isConnected() ? model.realString() : WhaleBubbleSpec.OFFLINE_AMOUNT,
                statusHint());
        if (PetBubbleSeq.KIND_CUSTOM.equals(item.kind) && !item.modules.isEmpty()) {
            bubble.modules.addAll(item.modules);
        } else {
            if (PetBubbleSeq.KIND_RANDOM.equals(item.kind)) {
                Log.write("序列：random（随机语句）本版未支持 → 已按默认页显示");
            }
            // 默认页：沿用 v1.5.2 的行为（装了模块就用模块）
            bubble.modules.addAll(PetBubbleModule.listFromJson(state.bubbleModulesJson));
        }
        applyPeakRow(bubble, nowSec());
        assignMarqueeDurations(bubble);
        if (bubble.hasModules()) logModuleLayout(bubble);
        Log.write(String.format(Locale.US, "序列：显示第 %d 项（%s）", ordinal, item.describe()));
        showBubble(bubble, item.ttlMs > 0 ? item.ttlMs : state.bubbleTtlMs);
    }

    /** 点泡泡（对应上游 {@code bubbleNext}）：还有下一项就推进，否则收起。 */
    private void advanceOrHide() {
        PetBubbleSeq seq = sequence();
        if (roundOn && seqIdx < seq.size()) {
            showSeqNext();
            return;
        }
        Log.write(roundOn
                ? "序列：已是末项 → 收起（下次点角色从第 1 项开始）"
                : "序列：非手动轮 → 收起泡泡");
        seqIdx = 0;
        roundOn = false;
        hideBubble();
    }

    /**
     * 重置泡泡留存计时（对应上游「点角色仅重置留存计时」）。
     * 注意：这里不重新播入场动画，只把 TTL 往后推。
     */
    private void resetBubbleTtl() {
        if (bubbleView == null) return;
        handler.removeCallbacks(bubbleTtlRunnable);
        int ttl = PetState.clampBubbleTtl(state.bubbleTtlMs);
        if (ttl <= 0) {
            Log.write("泡泡留存计时：已设为常驻，无需续时");
            return;
        }
        handler.postDelayed(bubbleTtlRunnable, ttl);
        Log.write("泡泡留存计时已重置（+" + ttl + "ms，" + PetState.bubbleTtlLabel(ttl) + "）");
    }

    // ---------------------------------------------------------------- 泡泡（Whale 挂件交互）

    /** 当前用于峰谷判定的时刻（epoch 秒；脚本可用「模拟时刻」把它整体平移）。 */
    private long nowSec() {
        return System.currentTimeMillis() / 1000L + fakeOffsetSec;
    }

    /**
     * 脚本「模拟时刻」：{@code --es fakeTime "2026-10-05 10:30"}（按北京时间解析）。
     * 传 {@code clear} / {@code off} / 空串则恢复真实时间。
     *
     * <p>实现是**整体平移**（offset = 目标 − 真实），不是冻结时间 ——
     * 这样倒计时仍然会逐秒走动，脚本才能验证「倒计时真的在跑」而不是一个死数字。
     * 解析失败时**什么都不改**（fail-closed），并如实写日志。
     */
    private void applyFakeTime(String spec) {
        if (spec == null) return;
        String s = spec.trim();
        if (s.isEmpty() || "clear".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) {
            fakeOffsetSec = 0L;
            Log.write("模拟时刻：已清除（回到真实系统时间）");
            refreshPeakRowIfVisible();
            return;
        }
        try {
            String datePart = s;
            String timePart = "00:00";
            int split = -1;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == ' ' || s.charAt(i) == 'T') { split = i; break; }
            }
            if (split > 0) {
                datePart = s.substring(0, split);
                timePart = s.substring(split + 1).trim();
            }
            String[] d = datePart.split("-");
            String[] hm = timePart.split(":");
            int y = Integer.parseInt(d[0].trim());
            int mo = Integer.parseInt(d[1].trim());
            int da = Integer.parseInt(d[2].trim());
            int hh = (hm.length > 0 && !hm[0].trim().isEmpty()) ? Integer.parseInt(hm[0].trim()) : 0;
            int mi = (hm.length > 1 && !hm[1].trim().isEmpty()) ? Integer.parseInt(hm[1].trim()) : 0;
            long target = PeakValley.fromBeijing(y, mo, da, hh, mi, 0);
            fakeOffsetSec = target - System.currentTimeMillis() / 1000L;
            long now = nowSec();
            Log.write(String.format(Locale.US,
                    "模拟时刻 → %04d-%02d-%02d %02d:%02d（北京）；偏移 %+d 秒；等效现在=%s %02d:%02d:%02d 峰谷=%s",
                    y, mo, da, hh, mi, fakeOffsetSec, PeakValley.bjDateKey(now),
                    PeakValley.bjHour(now), PeakValley.bjMinute(now), PeakValley.bjSecond(now),
                    PeakValley.isPeak(now) ? "高峰" : "空闲"));
            refreshPeakRowIfVisible();
        } catch (Exception e) {
            Log.write("模拟时刻解析失败（格式应为 YYYY-MM-DD HH:MM）: " + spec + " / " + e);
        }
    }

    /**
     * 一行日志，把峰谷判定的全部输入输出摊开，便于用 logcat 取证。
     * 例：{@code 峰谷：peak=true style=梁文峰谷 文案=梁文峰 颜色=#E0433F 倒计时=01:23:45 …}
     */
    private String describePeak(long sec) {
        int style = PeakValley.clampStyle(state.peakStyle);
        boolean peak = PeakValley.isPeak(sec);
        long next = PeakValley.nextChangeAt(sec);
        return String.format(Locale.US,
                "峰谷：peak=%s style=%s 文案=%s 颜色=#%06X 倒计时=%s 北京=%s %02d:%02d:%02d 下次切换=%s",
                peak, PeakValley.styleDisplayName(style),
                PeakValley.isCountStyle(style)
                        ? PeakValley.countdownText(sec, state.countdownFormat)
                        : PeakValley.statusText(style, peak, state.peakTextCustom, state.valleyTextCustom),
                (peak ? state.peakColor : state.valleyColor) & 0xFFFFFF,
                PeakValley.countdownText(sec, state.countdownFormat),
                PeakValley.bjDateKey(sec),
                PeakValley.bjHour(sec), PeakValley.bjMinute(sec), PeakValley.bjSecond(sec),
                next < 0 ? "无（12 天内）" : (PeakValley.bjDateKey(next)
                        + String.format(Locale.US, " %02d:%02d:%02d",
                        PeakValley.bjHour(next), PeakValley.bjMinute(next), PeakValley.bjSecond(next))));
    }

    /**
     * 把一次成功的取数喂给本地账本（v1.12.0）。
     *
     * <p>口径与上游 {@code accounting.mjs} 的「已观测消费」一致：**余额下降**算消费、
     * **上升**算充值/赠金（不冲抵已有消费）；第一次观测只建立基准；按北京时间跨日清零「今日」。
     * 账本有变化就立刻落盘（写失败只写日志，不假装成功）。
     */
    private void observeLedger(BalanceClient.BalanceReading reading) {
        if (reading == null) return;
        Integer cents = reading.totalCents();
        if (cents == null) return;                 // 非 CNY / 越界：不记账，也不动基准
        boolean changed = ledger.observe(cents.longValue(), PeakValley.bjDateKey(nowSec()));
        Double apiTotal = reading.spentCny();      // 只有 DSH 账号路径才可能给 total_costs
        if (apiTotal != null && !apiTotal.isNaN() && !apiTotal.isInfinite()) {
            changed |= ledger.seedFromApi(Math.round(apiTotal * 100));
        }
        if (changed) saveLedger();
    }
    /** 账本落盘（原子写）。失败只写日志 —— 内存里仍然是对的，重启会丢，如实告知。 */
    private void saveLedger() {
        try {
            java.io.File file = PetPaths.ledgerFile();
            java.io.File temp = new java.io.File(file.getParentFile(), "ledger.json.tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(temp);
            try {
                out.write(ledger.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Exception e) {
            Log.write("写 ledger.json 失败（账本只在内存里更新，重启会丢）: " + e);
        }
    }
    /** 今日已用（占位符 {@code {today_ds}} / {@code {expense_ds}}）；还没建立基准时显示 {@code --}。 */
    private String todayString() {
        return ledger.hasData() ? ledger.todayYuan() : "--";
    }
    /** 组装泡泡时把当前峰谷状态写进那一行（含占位符 token）。 */
    private void applyPeakRow(PetBubble bubble, long sec) {
        int style = PeakValley.clampStyle(state.peakStyle);
        bubble.peakStyleGlobal = style;   // 模块「跟随全局」时要用它
        boolean peak = PeakValley.isPeak(sec);
        boolean count = PeakValley.isCountStyle(style);
        String text = "";
        int color = 0xFF203170;
        if (state.peakShow) {
            text = count ? PeakValley.countdownText(sec, state.countdownFormat)
                    : PeakValley.statusText(style, peak, state.peakTextCustom, state.valleyTextCustom);
            color = 0xFF000000 | (PeakValley.rowColor(peak, state.peakColor, state.valleyColor) & 0xFFFFFF);
        }
        bubble.peakCountdown = count;
        bubble.peakText = text;
        bubble.peakColor = color;
        bubble.peakIsPeak = peak;
        // 占位符（上游 token map 的子集；未知占位符会原样保留，不会被悄悄抹掉）
        int tokenStyle = peakStyleForTokens(bubble);
        bubble.putToken("status", PeakValley.statusText(tokenStyle, peak, state.peakTextCustom, state.valleyTextCustom));
        bubble.putToken("countdown", PeakValley.countdownText(sec, state.countdownFormat));
        bubble.putToken("balance_ds", model.isConnected() ? model.realString() : WhaleBubbleSpec.OFFLINE_AMOUNT);
        bubble.putToken("cost_ds", costString());
        // 今日已用（v1.12.0）：接口没有这个字段，值来自本地账本；
        // {expense_ds} 是上游用的名字（上游 today 模块 tpl = "今日已用 {expense_ds}"），两个名字都给。
        bubble.putToken("today_ds", todayString());
        bubble.putToken("expense_ds", todayString());
        bubble.putToken("status_text", statusHint());
        lastPeakText = text;
        lastPeakColor = color;
        lastPeakIsPeak = peak;
    }

    /** 模块里若含峰谷模块，{@code {status}}/{@code {countdown}} 按该模块**实际生效**的样式生成。 */
    private int peakStyleForTokens(PetBubble bubble) {
        if (bubble != null) {
            for (PetBubbleModule m : bubble.modules) {
                if (m != null && PetBubbleModule.TYPE_PEAK.equals(m.type)) {
                    // 模块钉死了就用它自己的；没钉死（-1）就跟随全局
                    return m.effectivePeakStyle(state.peakStyle);
                }
            }
        }
        return PeakValley.clampStyle(state.peakStyle);
    }

    /**
     * 累计消费（占位符 {@code {cost_ds}}）。
     * v1.12.0 起**优先用本地账本**（观测口径）—— 普通 API Key 路径的余额接口根本没有
     * {@code total_costs}（那是 DSH 账号接口的字段），所以账本就是唯一来源；
     * 只有在账本还没建立基准时才回落到接口字段。
     */
    private String costString() {
        if (ledger.hasData()) return ledger.totalYuan();   // 本地账本（观测口径）
        Double spent = model.spentCny();
        if (spent == null || spent.isNaN() || spent.isInfinite()) return "--";
        return String.format(Locale.US, "%.2f", spent);
    }

    /**
     * 峰谷行的 1 秒 ticker（对应上游 {@code bubbleCountdownTick}）。
     *
     * <p>与上游一致的两条语义：
     * <ul>
     *   <li><b>倒计时样式</b>：每条 tick 都改写文案与配色；</li>
     *   <li><b>其余样式</b>：只有峰谷状态**真的变了**才改写（不做无意义重绘）。</li>
     * </ul>
     * 泡泡没了就自动停（对应上游「两个行集合都空 → clearInterval」）。
     */
    private final Runnable peakTickRunnable = new Runnable() {
        @Override public void run() {
            if (!running || bubbleView == null) return;   // 泡泡已不在：ticker 自销
            if (updatePeakRow()) bubbleView.refresh();
            handler.postDelayed(this, PEAK_TICK_MS);
        }
    };

    private void startPeakTicker() {
        handler.removeCallbacks(peakTickRunnable);
        handler.postDelayed(peakTickRunnable, PEAK_TICK_MS);
    }

    private void stopPeakTicker() {
        handler.removeCallbacks(peakTickRunnable);
    }

    /**
     * 按当前时刻刷新泡泡里的峰谷行（改的是 PetBubble 的字段 + 一次 invalidate，**不重建窗口、不重播动画**）。
     *
     * @return 是否发生了可见变化（true 才需要重绘）
     */
    private boolean updatePeakRow() {
        if (bubbleView == null) return false;
        PetBubble bubble = bubbleView.content();
        if (bubble == null || !state.peakShow) return false;
        long sec = nowSec();
        int style = PeakValley.clampStyle(state.peakStyle);
        bubble.peakStyleGlobal = style;   // 用户在设置里改了样式：已显示的泡泡也要跟着换
        boolean peak = PeakValley.isPeak(sec);
        boolean count = PeakValley.isCountStyle(style);
        String text = count ? PeakValley.countdownText(sec, state.countdownFormat)
                : PeakValley.statusText(style, peak, state.peakTextCustom, state.valleyTextCustom);
        int color = 0xFF000000 | (PeakValley.rowColor(peak, state.peakColor, state.valleyColor) & 0xFFFFFF);
        if (!count && peak == lastPeakIsPeak && lastPeakText != null) return false;   // 状态没变：跳过
        boolean visible = !text.equals(lastPeakText) || color != lastPeakColor;
        bubble.peakCountdown = count;
        bubble.peakText = text;
        bubble.peakColor = color;
        bubble.peakIsPeak = peak;
        int tokenStyle = peakStyleForTokens(bubble);
        bubble.putToken("status", PeakValley.statusText(tokenStyle, peak, state.peakTextCustom, state.valleyTextCustom));
        bubble.putToken("countdown", PeakValley.countdownText(sec, state.countdownFormat));
        lastPeakText = text;
        lastPeakColor = color;
        lastPeakIsPeak = peak;
        if (visible) {
            Log.write(String.format(Locale.US, "峰谷行刷新 → %s（颜色=#%06X%s）",
                    text, color & 0xFFFFFF, count ? "，倒计时样式" : ""));
        }
        return visible;
    }

    /** 设置变更后立刻按新样式改写（没有泡泡就什么都不做）。 */
    private void refreshPeakRowIfVisible() {
        if (bubbleView == null) return;
        if (!state.peakShow) {
            PetBubble bubble = bubbleView.content();
            if (bubble != null) bubble.peakText = "";
            bubbleView.refresh();
            stopPeakTicker();
            return;
        }
        lastPeakText = null;              // 强制重算（忽略「状态没变就跳过」的优化）
        if (updatePeakRow()) bubbleView.refresh();
        startPeakTicker();
    }

    /** 单击桌宠：弹出余额泡泡（第 1 步；v1.5.0 起带峰谷行，v1.5.2 起支持模块化内容）。 */
    private void showBalanceBubble() {
        PetBubble bubble = new PetBubble(
                "balance",
                WhaleBubbleSpec.DEFAULT_LABEL,
                model.isConnected() ? model.realString() : WhaleBubbleSpec.OFFLINE_AMOUNT,
                statusHint());
        // v1.5.2：装了模块就走模块渲染；空字符串 = 保持经典四行（升级后观感不变）
        bubble.modules.addAll(PetBubbleModule.listFromJson(state.bubbleModulesJson));
        applyPeakRow(bubble, nowSec());
        assignMarqueeDurations(bubble);
        if (bubble.hasModules()) logModuleLayout(bubble);
        showBubble(bubble);
    }

    /**
     * 把「模块 → 行」的分组结果写一行日志（**只在弹泡泡时写一次**，不在绘制期写，
     * 否则每帧一条会把 pet.log 刷爆）。
     */
    private void logModuleLayout(PetBubble bubble) {
        List<List<PetBubbleModule>> rows = PetBubbleModule.groupRows(bubble.modules);
        StringBuilder sb = new StringBuilder();
        sb.append("模块布局：").append(bubble.modules.size()).append(" 个模块 / ")
                .append(rows.size()).append(" 行");
        for (int i = 0; i < rows.size(); i++) {
            sb.append(" ｜ 行").append(i + 1).append(": ");
            List<PetBubbleModule> row = rows.get(i);
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) sb.append(" + ");
                PetBubbleModule m = row.get(j);
                sb.append('「').append(m.contentOf(bubble)).append("」[").append(m.describe()).append(']');
            }
        }
        Log.write(sb.toString());
    }

    /** 泡泡里那行小字：优先错误，其次离线模式，最后是连接状态。 */
    private String statusHint() {
        if (model.lastError() != null) return clip(model.lastError());
        if (state.offline) return "离线模式";
        return clip(model.statusText());
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= 14 ? text : text.substring(0, 14) + "…";
    }

    /**
     * 弹出泡泡。几何按 WhaleWidget 的 pop 层推导：
     *   pop 层宽高比 1026:700；两个尾巴椭圆在 viewBox 的 y=561/646（≈80%/92%），
     *   所以把画布上沿放在宠物头顶上方 86% 处，尾巴正好落在头顶附近。
     */
    private void showBubble(PetBubble content) {
        showBubble(content, state.bubbleTtlMs);
    }

    /** 带自定义留存时长的版本（序列项可用 {@code ttlMs} 覆盖默认值）。 */
    private void showBubble(PetBubble content, long ttlMs) {
        destroyBubbleWindow();   // 换新泡泡时直接摘掉旧的（不播退场）
        RectF sprite = PetLayout.spriteRect(windowW, windowH);
        float scaleFactor = state.bubbleScaleFactor();          // v1.7.0：用户可调泡泡大小
        float canvasW = Math.max(120f, sprite.width() * 0.80f * scaleFactor);
        float canvasH = WhaleBubbleSpec.popHeight(canvasW);

        // 垂直：始终画在宠物上方（忠于原版），尾巴（viewBox y646/700）落在宠物头顶。
        //
        // 上方空间不够时的策略（用户反馈迭代结论）：
        //   ✗ 把画布顶部钳进屏幕 → 泡泡整体被下压，尾巴穿进宠物头部/脸，
        //     看起来像「泡泡跑到桌宠下面」。也绝不做垂直翻转：翻转会镜像形状而文字不镜像。
        //   ✓ 等比缩小整个泡泡（形状与文字同比例），尾巴仍然钉在桌宠头顶。
        int margin = dp(8);
        int topMargin = Math.max(margin, safeTopInset);   // 刘海/挖孔/状态栏：泡泡不要钻到额头下面
        float tailAnchor = WhaleBubbleSpec.TAIL2_CY / WhaleBubbleSpec.VIEW_H;   // ≈0.923
        float lift = canvasH * (tailAnchor - 0.02f);
        float canvasHFull = canvasH;
        boolean shrunk = false;
        boolean clamped = false;
        // 贴着屏幕顶部（上方空间不足以完整放下泡泡）时的行为，用户可配：
        //   0 = 等比缩小（默认）  1 = 干脆不弹  2 = 画到桌宠下方（形状翻转）
        boolean tooHigh = posY + sprite.top - lift < topMargin;
        if (tooHigh && state.bubbleTopMode == PetState.BUBBLE_TOP_HIDE) {
            Log.write("泡泡：桌宠贴近屏幕顶部，按设置不显示"
                    + "（Whale挂件 → 泡泡位置 可改成「缩小」或「显示在下方」）");
            return;
        }
        // 方案 2：画到桌宠下方（形状垂直翻转、尾巴朝上指着桌宠）。放得下就用它 —— 既不缩小、也不遮头。
        boolean placeBelow = false;
        float belowTop = 0f;
        if (tooHigh && state.bubbleTopMode == PetState.BUBBLE_TOP_BELOW) {
            float tailAnchorFlipped = 1f - tailAnchor;   // 翻转后尾巴在画布顶部附近（≈0.077）
            belowTop = sprite.bottom - canvasH * (tailAnchorFlipped - 0.02f);
            if (posY + belowTop + canvasH <= screenH - margin) {
                placeBelow = true;
            } else {
                Log.write(String.format(Locale.US,
                        "泡泡：贴顶且下方也放不下（底部 %.0f > 上限 %d）→ 回落到等比缩小",
                        posY + belowTop + canvasH, screenH - margin));
            }
        }
        if (tooHigh && !placeBelow) {
            float avail = posY + sprite.top - topMargin;   // 屏幕坐标系里「立绘顶边以上」的可用高度
            float scale = Math.min(1f, avail / Math.max(1f, lift));
            if (scale < MIN_BUBBLE_SCALE) scale = MIN_BUBBLE_SCALE;
            if (scale < 0.999f) {
                canvasW = Math.max(120f, canvasW * scale);
                canvasH = WhaleBubbleSpec.popHeight(canvasW);
                lift = canvasH * (tailAnchor - 0.02f);
                shrunk = true;
            }
        }

        // 水平钳制：别让泡泡出屏
        float left = sprite.centerX() - canvasW / 2f;
        float screenLeft = posX + left;
        if (screenLeft < margin) left += margin - screenLeft;
        if (posX + left + canvasW > screenW - margin) {
            left -= (posX + left + canvasW) - (screenW - margin);
        }

        float top = placeBelow ? belowTop : (sprite.top - lift);
        if (!placeBelow && posY + top < topMargin) {   // 缩到下限仍不够（桌宠几乎贴到屏幕外）→只能钳到安全区内
            top = topMargin - posY;
            clamped = true;
        }
        if (posY + top + canvasH > screenH - margin) {
            top = screenH - margin - posY - canvasH;
        }

        bubbleCanvasW = canvasW;
        bubbleCanvasH = canvasH;
        bubbleOriginX = left;
        bubbleOriginY = top;

        final int token = ++bubbleToken;
        bubbleView = new PetBubbleView(this, content, PetBubbleStyle.from(state), new Runnable() {
            @Override public void run() {
                if (token != bubbleToken) return;
                // 对应上游 bubbleNext()：还有下一项就推进，否则收起
                Log.write("泡泡被点击 → 推进序列 / 收起");
                advanceOrHide();
            }
        });
        lastBubbleBelow = placeBelow;   // v1.12.6：给 runtime.json / 编辑器预览判断箭头方向
                content.arrowsUp = placeBelow;   // v1.12.6：画在桌宠下方时，把「↓」类箭头转成「↑」类
                bubbleView.setFlipped(placeBelow);   // 画在下方时形状垂直翻转（文字位置在 drawTexts/drawModules 里已补偿）
        bubbleParams = new WindowManager.LayoutParams(
                Math.max(1, Math.round(canvasW)), Math.max(1, Math.round(canvasH)),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.LEFT;
        bubbleParams.x = posX + Math.round(left);
        bubbleParams.y = posY + Math.round(top);
        applyFrameRate(bubbleParams);
        try {
            windowManager.addView(bubbleView, bubbleParams);
        } catch (Exception e) {
            bubbleView = null;
            bubbleParams = null;
            Log.write("泡泡窗口添加失败: " + e);
            return;
        }
        Log.write(String.format(Locale.US, "泡泡显示：%s 文本=%s 画布=%.0fx%.0f 位置=(%d,%d)%s",
                        content.describe(), content.textSummary(), canvasW, canvasH, bubbleParams.x, bubbleParams.y,
                (scaleFactor == 1f ? "" : String.format(Locale.US, "（大小 %.0f%%）", scaleFactor * 100))
                        + (clamped ? "（缩到下限仍不够，已钳到屏幕内）"
                        : (placeBelow ? "（贴顶：已画到桌宠下方，形状翻转 / 文字位置已补偿 / 箭头已转向上）"
                        : (shrunk ? String.format(Locale.US,
                        "（上方空间不足，已等比缩到 %.0f%% 并把尾巴钉在桌宠头顶）",
                        100f * canvasH / Math.max(1f, canvasHFull)) : "")))));
        bubbleShownAtMs = System.currentTimeMillis();
        if (ttlMs > 0) {
            handler.postDelayed(bubbleTtlRunnable, ttlMs);
        } else {
            Log.write("泡泡留存：已设为常驻（不自动收起，点一下才关）");
        }
        // 峰谷行每秒原地刷新（上游 bubbleCountdownTick）；没有峰谷行就不必起 ticker
        stopPeakTicker();
        if (content.hasPeakLine()) {
            Log.write(describePeak(nowSec()));
            startPeakTicker();
        }
    }

    private final Runnable bubbleTtlRunnable = new Runnable() {
        @Override public void run() {
            if (bubbleView != null) {
                Log.write("泡泡到期自动收起");
                hideBubble();
            }
        }
    };

    private void hideBubble() {
        handler.removeCallbacks(bubbleTtlRunnable);
        stopPeakTicker();
        // 对应上游 hideBubble：seqIdx 归零，下次点角色从第 1 项开始
        seqIdx = 0;
        roundOn = false;
        if (bubbleView == null) return;
        if (bubbleView.isClosing()) return;
        // 不是立刻消失：走 WhaleWidget 的退场时序（文字 .16s 淡出，形状 .1/.2/.3s 依次缩回）
        bubbleView.startClose();
        Log.write("泡泡开始渐隐退场");
    }

    /** 退场播完（或需要立刻换新泡泡/重建窗口）时，才真正把窗口摘掉。 */
    private void destroyBubbleWindow() {
        handler.removeCallbacks(bubbleTtlRunnable);
        stopPeakTicker();
        bubbleToken++;
        if (bubbleView != null) {
            try { windowManager.removeView(bubbleView); } catch (Exception ignored) { }
            bubbleView = null;
            bubbleParams = null;
        }
    }

    /**
     * 泡泡是否需要「按动画帧率」持续重绘与推进。
     *
     * <p>除了入场/退场，还必须包含**跑马灯**（二期）：否则泡泡静止时帧循环会掉到空闲档
     * （{@code IDLE_MS = 120ms}），跑马灯会卡成约 8fps —— 与上次「按压形变看不见」
     * 是同一个坑：绘制期状态 + 按需重绘，新增动画必须同步进判定集合。
     */
    private boolean bubbleAnimating() {
        if (bubbleView == null) return false;
        return bubbleView.opening() || bubbleView.isClosing() || bubbleView.marqueeAnimating();
    }

    /**
     * 给配了跑马灯的模块随机一个时长。
     *
     * <p>上游 {@code bubbleMarqueeDur() = round(1500 + random()*3000)}（L12991），
     * **每次渲染各随机**；本工程在「组装泡泡时」随机一次，避免逐帧重随机导致渐变乱跳。
     */
    private void assignMarqueeDurations(PetBubble bubble) {
        int count = PetBubbleModule.assignMarqueeDurations(bubble.modules);
        if (count > 0) {
            Log.write("跑马灯：本泡泡 " + count + " 个模块带动画（时长 1500–4500ms 各自随机）");
        }
    }

    private long bubbleShownAtMs = 0;

    /** 宠物移动时泡泡跟着走。 */
    private void syncBubblePosition() {
        if (bubbleView == null || bubbleParams == null) return;
        bubbleParams.x = posX + Math.round(bubbleOriginX);
        bubbleParams.y = posY + Math.round(bubbleOriginY);
        try { windowManager.updateViewLayout(bubbleView, bubbleParams); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- 飘字层

    /**
     * 飘字层：只在轮廓模式下单独成窗；单窗口模式下飘字直接画在主窗口里。
     * 几何固定（= 整个桌宠矩形），只在「有飘字 / 有绿环」时存在。
     */
    private void syncFloatLayer() {
        if (sceneView != null) {      // 单窗口模式：不需要额外的层
            hideFloatLayer();
            return;
        }
        boolean needed = !model.floating.isEmpty() || model.topupTime() > 0;
        if (!needed) {
            hideFloatLayer();
            return;
        }
        if (floatView == null) {
            floatView = new PetOverlayView(this, renderer);
            floatParams = new WindowManager.LayoutParams(
                    Math.max(1, Math.round(windowW)), Math.max(1, Math.round(windowH)),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            floatParams.gravity = Gravity.TOP | Gravity.LEFT;
            floatParams.x = posX;
            floatParams.y = posY;
            applyFrameRate(floatParams);
            try {
                windowManager.addView(floatView, floatParams);
            } catch (Exception e) {
                floatView = null;
                floatParams = null;
                Log.write("添加飘字层失败: " + e);
            }
        }
    }

    private void hideFloatLayer() {
        if (floatView != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) { }
            floatView = null;
            floatParams = null;
        }
    }

    // ---------------------------------------------------------------- 菜单

    private void showMenu(int touchX, int touchY) {
        hideMenu();
        List<PetMenuView.Item> items = buildMenu();
        final PetMenuView menu = new PetMenuView(this, items, new Runnable() {
            @Override public void run() { hideMenu(); }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            windowManager.addView(menu, lp);
            menu.setAnchor(touchX, touchY);
            menuView = menu;
        } catch (Exception e) {
            Log.write("打开菜单失败: " + e);
        }
    }

    private void hideMenu() {
        if (menuView != null) {
            try { windowManager.removeView(menuView); } catch (Exception ignored) { }
            menuView = null;
        }
    }

    private List<PetMenuView.Item> buildMenu() {
        List<PetMenuView.Item> items = new ArrayList<>();
        String status = model.isConnected()
                ? "余额 ¥" + model.realString() + " · 已连接"
                : "余额 -- · " + (model.lastError() == null ? model.statusText() : model.lastError());
        items.add(PetMenuView.Item.header(status));
        items.add(PetMenuView.Item.header("帧率 " + measuredFps + " fps · 每帧 "
                + String.format(Locale.US, "%.1f", avgWorkMs) + " ms · 屏幕 "
                + Math.round(displayHz) + " Hz"));
        if (model.credentialSource() != null) {
            items.add(PetMenuView.Item.header("凭证：" + model.credentialSource()));
        }
        if (ledger.hasData()) {
            // v1.12.0：本地记账（观测口径）——数字只包含「本机开始记账之后」观测到的变化
            items.add(PetMenuView.Item.header("今日已用 ¥" + ledger.todayYuan()
                    + " · 累计 ¥" + ledger.totalYuan() + "（本机观测）"));
        } else if (model.spentCny() != null) {
            items.add(PetMenuView.Item.header(String.format(Locale.US, "累计消费 ¥%.2f", model.spentCny())));
        }
        items.add(PetMenuView.Item.separator());

        PetMenuView.Item refresh = PetMenuView.Item.action("立即刷新余额", new Runnable() {
            @Override public void run() { poll(true, true); }
        });
        // 与原版一致：请求在飞或处于 429 退避期时该项不可用
        refresh.enabled = schedule.canRefreshAt(now());
        items.add(refresh);
        items.add(PetMenuView.Item.action("测试一次扣费", new Runnable() {
            @Override public void run() { model.playOneHit(); }
        }));
        PetMenuView.Item cancelDemo = PetMenuView.Item.action("停止连续扣费演示", new Runnable() {
            @Override public void run() { model.cancelDemo(); }
        });
        cancelDemo.enabled = model.demoRemaining() > 0;
        items.add(cancelDemo);

        int[] demoCents = {5, 10, 20, 50, 100};
        List<PetMenuView.Item> demo = new ArrayList<>();
        for (final int fen : demoCents) {
            demo.add(PetMenuView.Item.action(
                    String.format(Locale.US, "-%.2f（%d 次）", fen / 100.0, fen),
                    new Runnable() {
                        @Override public void run() { model.playDemo(fen); }
                    }));
        }
        items.add(PetMenuView.Item.submenu("演示连续扣费", demo));

        List<PetMenuView.Item> characters = new ArrayList<>();
        PetCharacters.Current nowChar = PetCharacters.current(state);
                for (final PetCharacters.Current c : PetCharacters.all(state)) {
                    String label = c.displayName + (c.hasTablet() ? "" : "（不显示余额）")
                            + (c.isCustom() ? " ·自定义" : "");
                    PetMenuView.Item item = PetMenuView.Item.check(label,
                            c.id.equals(nowChar.id), new Runnable() {
                                @Override public void run() { setCharacter(c); }
                            });
                    characters.add(item);
                }
        items.add(PetMenuView.Item.submenu("切换角色", characters));

        List<PetMenuView.Item> sizes = new ArrayList<>();
        for (int i = 0; i < PetLayout.SIZE_PRESETS_DP.length; i++) {
            final int index = i;
            sizes.add(PetMenuView.Item.check(
                    PetLayout.SIZE_PRESET_NAMES[i] + "（" + (int) PetLayout.SIZE_PRESETS_DP[i] + "dp）",
                    !state.useCustomSize() && i == state.sizeIndex, new Runnable() {
                        @Override public void run() { setSize(index); }
                    }));
        }
        sizes.add(PetMenuView.Item.separator());
        sizes.add(PetMenuView.Item.action(
                state.useCustomSize()
                        ? "自定义…（当前 " + (int) state.customSideDp + "dp）"
                        : "自定义…",
                new Runnable() {
                    @Override public void run() { openSettings("size"); }
                }));
        items.add(PetMenuView.Item.submenu("尺寸", sizes));

        List<PetMenuView.Item> offlineItems = new ArrayList<>();
        offlineItems.add(PetMenuView.Item.check("抱盆图（v1.3.1 行为）",
                state.offlineArtOnDisconnect, new Runnable() {
                    @Override public void run() {
                        state.offlineArtOnDisconnect = true;
                        state.save();
                        buildOverlay();
                        Log.write("未连接时显示抱盆图 → 开");
                    }
                }));
        offlineItems.add(PetMenuView.Item.check("平板图 + “--”（Windows 原版）",
                !state.offlineArtOnDisconnect, new Runnable() {
                    @Override public void run() {
                        state.offlineArtOnDisconnect = false;
                        state.save();
                        buildOverlay();
                        Log.write("未连接时显示抱盆图 → 关");
                    }
                }));
        items.add(PetMenuView.Item.submenu("未连接显示（" 
                + (state.offlineArtOnDisconnect ? "抱盆图" : "平板图") + "）", offlineItems));

        List<PetMenuView.Item> renderItems = new ArrayList<>();
        renderItems.add(PetMenuView.Item.check("单窗口（流畅）",
                state.windowMode == PetState.MODE_SINGLE, new Runnable() {
                    @Override public void run() { setWindowMode(PetState.MODE_SINGLE); }
                }));
        renderItems.add(PetMenuView.Item.check("轮廓穿透（精确）",
                state.windowMode == PetState.MODE_OUTLINE, new Runnable() {
                    @Override public void run() { setWindowMode(PetState.MODE_OUTLINE); }
                }));
        items.add(PetMenuView.Item.submenu("渲染模式（" + renderModeName(state.windowMode) + "）", renderItems));

        List<PetMenuView.Item> dragItems = new ArrayList<>();
        dragItems.add(PetMenuView.Item.check("自由拖动（停在原位）",
                state.dragMode == PetState.DRAG_FREE, new Runnable() {
                    @Override public void run() { setDragMode(PetState.DRAG_FREE); }
                }));
        dragItems.add(PetMenuView.Item.check("吸附左下角（原版行为）",
                state.dragMode == PetState.DRAG_CORNER, new Runnable() {
                    @Override public void run() { setDragMode(PetState.DRAG_CORNER); }
                }));
        dragItems.add(PetMenuView.Item.check("吸附最近边缘",
                state.dragMode == PetState.DRAG_EDGE, new Runnable() {
                    @Override public void run() { setDragMode(PetState.DRAG_EDGE); }
                }));
        items.add(PetMenuView.Item.submenu("拖动行为（" + dragModeName(state.dragMode) + "）", dragItems));
        items.add(PetMenuView.Item.check("音效", state.soundOn, new Runnable() {
            @Override public void run() {
                state.soundOn = !state.soundOn;
                if (!state.soundOn && soundPool != null) soundPool.autoPause();
                state.save();
            }
        }));

        final double[] intervals = {1, 5, 10, 30, 60, 300, 600};
        List<PetMenuView.Item> intervalItems = new ArrayList<>();
        for (final double seconds : intervals) {
            intervalItems.add(PetMenuView.Item.check(intervalLabel(seconds),
                    Math.abs(seconds - state.pollSeconds) < 0.5, new Runnable() {
                        @Override public void run() {
                            state.pollSeconds = seconds;
                            schedule.setInterval(seconds, now());
                            state.save();
                        }
                    }));
        }
        intervalItems.add(PetMenuView.Item.separator());
        intervalItems.add(PetMenuView.Item.action(
                "自定义…（当前 " + intervalLabel(state.pollSeconds) + "）", new Runnable() {
                    @Override public void run() { openSettings("interval"); }
                }));
        items.add(PetMenuView.Item.submenu("刷新间隔", intervalItems));

        items.add(PetMenuView.Item.separator());
        items.add(PetMenuView.Item.action("设置 API Key…", new Runnable() {
            @Override public void run() { openSettings("key"); }
        }));
        items.add(PetMenuView.Item.action("重新读取凭证", new Runnable() {
            @Override public void run() { reloadCredential(); }
        }));
        items.add(PetMenuView.Item.action("吸附回左下角", new Runnable() {
            @Override public void run() { snapToCorner(true); }
        }));
        items.add(PetMenuView.Item.action("打开设置界面", new Runnable() {
            @Override public void run() { openSettings(null); }
        }));
        items.add(PetMenuView.Item.action("查看日志", new Runnable() {
            @Override public void run() { openSettings("log"); }
        }));
        items.add(PetMenuView.Item.separator());
        items.add(PetMenuView.Item.action("退出", new Runnable() {
            @Override public void run() { stopSelf(); }
        }));
        return items;
    }

    private void openSettings(String section) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (section != null) intent.putExtra(MainActivity.EXTRA_SECTION, section);
        startActivity(intent);
    }

    // ---------------------------------------------------------------- 动作

    private void setCharacter(PetCharacters.Current c) {
            if (c == null) return;
            PetCharacters.Current now = PetCharacters.current(state);
            if (now.id.equals(c.id)) return;
            if (c.isCustom()) PetCharacters.selectCustom(state, c.id);
            else PetCharacters.selectBuiltIn(state, c.builtIn);
            state.save();
            buildOverlay();
            syncFloatLayer();
            writeStatus(true);
            Log.write("切换角色 → " + c.displayName + "（" + c.id + "）"
                    + (c.hasTablet() ? "" : "，纯形象：不在角色身上显示余额"));
        }

    /**
         * 按 id 切角色（脚本用）：先找自定义角色、再找内置角色。
         */
        private void setCharacterById(String id) {
            if (id == null || id.isEmpty()) {
                Log.write("切换角色：缺少 id");
                return;
            }
            for (PetCharacters.Current c : PetCharacters.all(state)) {
                if (c.id.equals(id)) {
                    setCharacter(c);
                    return;
                }
            }
            Log.write("切换角色：没有这个角色 " + id);
        }
        private void setSize(int index) {
        state.sizeIndex = Math.min(PetLayout.SIZE_PRESETS_DP.length - 1, Math.max(0, index));
        state.customSideDp = 0f;              // 选预设档位即退出自定义
        applySide();
    }

    /** 应用自定义尺寸（dp）。 */
    private void setCustomSize(float sideDp) {
        float clamped = PetLayout.clampSideDp(sideDp, screenW,
                getResources().getDisplayMetrics().density);
        state.customSideDp = clamped;
        applySide();
        Log.write("自定义尺寸 → " + clamped + "dp");
    }

    /** 尺寸变化后重建窗口（保持左上角，重新贴合轮廓）。 */
    private void applySide() {
        sidePx = dp(state.sideDp());
        windowW = PetLayout.windowWidth(sidePx);
        windowH = PetLayout.windowHeight(sidePx);
        state.originX = (float) posX;
        state.originY = (float) posY;
        buildOverlay();
        keepWindowVisibleOnSizeChange();
        state.save();
        writeStatus(true);
    }

    private void keepWindowVisibleOnSizeChange() {
        int[] clamped = clampPosition(posX, posY);
        moveWindows(clamped[0], clamped[1]);
        persistOrigin();
    }

    private void reloadCredential() {
        credential = CredentialStore.resolve(state.offline, state.apiPath);
        schedule.reload(now());
        model.resetForCredentialChange();
        // 换凭证后余额基准失效（可能是另一个账号的钱包）→ 只清基准，不清累计（累计是本机观测到的历史）
        if (ledger.hasData()) {
            ledger.lastFen = null;
            saveLedger();
            Log.write("账本：凭证变化 → 清除余额基准（下次取数重新建立），累计与充值保留");
        }
        model.setCredentialSource(credential == null ? null : credential.shortDescription());
        if (credential == null) model.setNoCredential();
        buildOverlay();
        poll(true, true);
        Log.write("重新读取凭证：" + (credential == null ? "无" : credential.shortDescription()));
    }

    private void reloadCredentialIfNeeded() {
        if (credential == null) reloadCredential();
    }

    /**
     * 编辑器模式（v1.12.1）：打开泡泡编辑器时把桌宠与泡泡**临时收起来**。
     *
     * <p>为什么需要：桌宠是悬浮窗，永远盖在 Activity 之上 —— 用户截图里编辑器副标题就被立绘截断了。
     * 这里只改**可见性**并记住原值，退出编辑器时精确还原；另有 90 秒看门狗兜底
     * （防止编辑器被系统杀掉后桌宠一直不回来）。
     */
    private void setEditorMode(boolean on) {
        if (on) {
            if (!editorHidden.isEmpty()) return;      // 已经在编辑器模式
            for (View v : petWindows()) {
                if (v == null || v.getVisibility() != View.VISIBLE) continue;
                editorHidden.add(v);
                editorHiddenVis.add(v.getVisibility());
                v.setVisibility(View.GONE);
            }
            editorModeSince = SystemClock.elapsedRealtime();
            Log.write("编辑器模式：桌宠与泡泡已临时收起（隐藏 " + editorHidden.size() + " 个窗口）");
            return;
        }
        if (editorHidden.isEmpty()) return;
        int restored = 0;
        for (int i = 0; i < editorHidden.size(); i++) {
            try {
                editorHidden.get(i).setVisibility(editorHiddenVis.get(i));
                restored++;
            } catch (Exception e) {
                Log.write("编辑器模式：还原第 " + i + " 个窗口失败: " + e);
            }
        }
        editorHidden.clear();
        editorHiddenVis.clear();
        editorModeSince = 0L;
        invalidateAll();
        Log.write("编辑器模式：已还原桌宠显示（" + restored + " 个窗口）");
    }
    /** 承载桌宠/泡泡的全部窗口视图（单窗口模式 / 轮廓分带 / 飘字层 / 泡泡）。 */
    private java.util.List<View> petWindows() {
        java.util.List<View> list = new java.util.ArrayList<>();
        if (sceneView != null) list.add(sceneView);
        for (Band b : bands) {
            if (b != null && b.view != null) list.add(b.view);
        }
        if (floatView != null) list.add(floatView);
        if (bubbleView != null) list.add(bubbleView);
        return list;
    }
    /** 看门狗：编辑器模式超过 90 秒没被续期就自动还原（编辑器被系统杀掉/切走也不会让桌宠消失）。 */
    private void editorModeWatchdog() {
        if (editorHidden.isEmpty() || editorModeSince <= 0) return;
        if (SystemClock.elapsedRealtime() - editorModeSince < 90 * 1000L) return;
            Log.write("编辑器模式：超过 90 秒没收到退出通知 → 自动还原桌宠（看门狗）");
        setEditorMode(false);
    }
    // ---------------------------------------------------------------- 轮询

    private static double now() {
        return SystemClock.elapsedRealtime() / 1000.0;
    }

    private void poll(boolean snap, boolean force) {
        if (credential == null) {
            // 没有凭证时只标记一次「未配置」，不要每帧重设（否则会清掉演示动画）
            if (!"未配置".equals(model.statusText())) {
                model.setNoCredential();
                model.setCredentialSource(null);
                invalidateAll();
                // 立绘取决于「是否连接」，状态一变就要重新选图并重建轮廓窗口
                refreshArtworkIfNeeded();
                writeStatus(true);
            }
            return;
        }
        final Long request = schedule.begin(now(), force);
        if (request == null) return;
        final CredentialStore.Credential cred = credential;
        new Thread(new Runnable() {
            @Override public void run() {
                BalanceClient.BalanceReading reading = null;
                BalanceClient.FetchError failure = null;
                try {
                    reading = BalanceClient.fetch(cred, 20);
                } catch (BalanceClient.FetchError e) {
                    failure = e;
                } catch (Throwable t) {
                    failure = new BalanceClient.FetchError.Transport(String.valueOf(t));
                }
                final BalanceClient.BalanceReading resultReading = reading;
                final BalanceClient.FetchError resultFailure = failure;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (!schedule.finish(request, resultFailure, now())) return;  // 旧凭证结果丢弃
                        if (resultReading != null) {
                            boolean wasDisconnected = !model.isConnected();
                            model.apply(resultReading, snap || wasDisconnected);
                            observeLedger(resultReading);   // v1.12.0：本地记账（观测余额下降 → 今日/累计）
                            Double normal = resultReading.normalCny();
                            Double bonus = resultReading.bonusCny();
                            Log.write(String.format(Locale.US, "poll ok: CNY %.4f (normal %.4f + bonus %.4f)",
                                    resultReading.totalCents() / 100.0,
                                    normal == null ? 0 : normal, bonus == null ? 0 : bonus));
                        } else if (resultFailure != null) {
                            model.fail(resultFailure);
                        }
                        refreshArtworkIfNeeded();
                        invalidateAll();
                        updateNotification();
                        writeStatus(true);
                        writeRuntimeSnapshot();   // 编辑器预览要用的真实数据（余额/消费/状态）
                    }
                });
            }
        }, "dshpet-poll").start();
    }

    /** 蓝色大肥鱼在离线/在线之间切换时要换图（抱盆图 ↔ 平板图）。 */
    private void refreshArtworkIfNeeded() {
        PetCharacters.Current curForArt = PetCharacters.current(state);
                String expected = showsOfflineArtwork() ? PetCharacter.OFFLINE_ASSET : curForArt.cacheKey();
        if (artwork == null || !expected.equals(artwork.assetName)) {
            buildOverlay();
        }
    }

    // ---------------------------------------------------------------- 主循环

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            editorModeWatchdog();   // v1.12.1：编辑器异常退出也能让桌宠回来
            long workStart = SystemClock.uptimeMillis();
            double dt = Math.max(0, (frameTimeNanos - lastFrameNanos) / 1e9);
            lastFrameNanos = frameTimeNanos;
            if (!screenInteractive) {
                scheduleNextFrame(IDLE_MS);
                return;
            }

            boolean spriteWasAnimating = model.hasShake() || model.flashTime() > 0
                    || model.topupTime() > 0;
            boolean floatWasAlive = !model.floating.isEmpty();
            boolean bubbleWasAnimating = bubbleAnimating();
            model.tick(dt);
            // 按压形变必须按帧推进（绘制期才推进会导致「没人重绘就没人推进」的自锁，
            // 表现是单击看不到挤压、只有双击扣费抖动时才顺带看到）。
            renderer.updatePress();
            boolean spriteAnimating = model.hasShake() || model.flashTime() > 0
                    || model.topupTime() > 0;
            boolean floatAlive = !model.floating.isEmpty();
            boolean pressAnimating = renderer.pressAnimating();

            // 只重绘在动的层（角色层 / 飘字层分开判断）；按压形变属于角色层
            invalidateForFrame(spriteWasAnimating || spriteAnimating || pressAnimating,
                    floatWasAlive || floatAlive);
            if (bubbleView != null) {
                if (bubbleView.isClosing() && bubbleView.closeFinished()) {
                    destroyBubbleWindow();
                } else if (bubbleView.opening() || bubbleView.isClosing()) {
                    bubbleView.invalidate();
                } else if (bubbleView.marqueeAnimating()) {
                    // v1.9.0：只有跑马灯在动时，每 3 个 vsync 只画 2 个 = 60fps（90Hz 屏上），
                    // 仍然对齐 vsync 所以不会抖；顺带省下约 1/3 的绘制与合成。
                    marqueeFrameSkip = (marqueeFrameSkip + 1) % 3;
                    if (marqueeFrameSkip != 0) bubbleView.invalidate();
                }
            }
            // 按压回弹结束后收掉分带窗口的外扩
            if (!renderer.pressAnimating() && bandsInflated) setBandsInflated(false);
            syncFloatLayer();

            if (credential != null) poll(false, false);

            long nowMs = SystemClock.uptimeMillis();
            if (nowMs - lastStatusMs >= STATUS_INTERVAL_MS) {
                lastStatusMs = nowMs;
                measuredFps = framesThisSecond;
                framesThisSecond = 0;
                writeStatus(true);
                if (measuredFps > 0 || avgWorkMs > 0) {
                    Log.write(String.format(Locale.US,
                            "性能：实测 %.0f fps（帧间隔 %.1fms）· 逻辑耗时 %.1fms · 屏幕 %.0fHz · 目标 %s",
                            1000.0 / Math.max(1.0, avgVsyncMs), avgVsyncMs, avgWorkMs, displayHz,
                            state.targetFps <= 0 ? "跟随屏幕" : state.targetFps + "fps"));
                }
            }
            // 动画帧率：跟随屏幕刷新率（本机 90Hz）或用户显式指定的档位。
            // 用「本帧耗时」补偿，避免延迟叠加造成节奏漂移。
            long spent = Math.max(0, SystemClock.uptimeMillis() - workStart);
            boolean animating = model.needsAnimationFrame() || bubbleAnimating()
                    || renderer.pressAnimating();
            long target;
            long delay;
            if (animating) {
                target = framePeriodMs();
                framesThisSecond++;
                avgWorkMs = ema(avgWorkMs, spent);
                avgVsyncMs = ema(avgVsyncMs, (float) (dt * 1000));
                if (targetMatchesDisplay()) {
                    // 目标就是屏幕上限：每个 vsync 都渲染（不再按固定延时跳帧，避免与 vsync 边界错位）
                    delay = 0;
                } else {
                    delay = Math.max(3, target - spent);
                }
            } else {
                delay = PetTuning.IDLE_TICK_MS;
            }
            scheduleNextFrame(delay);
        }
    };

    /**
     * 向系统申请窗口刷新率——这是能否真跑到 90fps 的关键：
     * 普通悬浮窗默认不申请高刷，ColorOS 会把它压到 60Hz。
     */
    private float desiredRefreshRate() {
        float maxHz = displayMaxHz > 1f ? displayMaxHz : 60f;
        if (state.targetFps > 0) return Math.min(state.targetFps, maxHz);
        return maxHz;   // 跟随屏幕 = 申请屏幕支持的最高刷新率（本机 90Hz）
    }

    private void applyFrameRate(WindowManager.LayoutParams lp) {
        // 只用公开 API：preferredRefreshRate（API21+）。
        // 注：WindowManager.LayoutParams.setFrameRate 是隐藏 API，不能用。
        lp.preferredRefreshRate = desiredRefreshRate();
    }

    private void scheduleNextFrame(long delayMs) {
        if (delayMs <= 0) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, delayMs);
        }
    }

    private static long nowMs() {
        return SystemClock.uptimeMillis();
    }

    /** 读取当前屏幕刷新率与屏幕支持的最大刷新率（本机 60 / 90）。 */
    private void measureDisplayRefresh() {
        try {
            android.view.Display display = windowManager.getDefaultDisplay();
            float hz = display.getRefreshRate();
            if (hz > 1f && hz < 300f) displayHz = hz;
            float max = hz;
            android.view.Display.Mode[] modes = display.getSupportedModes();
            if (modes != null) {
                for (android.view.Display.Mode mode : modes) {
                    if (mode.getRefreshRate() > max && mode.getRefreshRate() < 300f) {
                        max = mode.getRefreshRate();
                    }
                }
            }
            if (max > 1f && max < 300f) displayMaxHz = max;
        } catch (Exception e) {
            displayHz = 60f;
            displayMaxHz = 60f;
        }
    }

    /**
     * 当前动画帧间隔：目标帧率（0 = 跟随屏幕）与屏幕最大刷新率取较小值。
     */
    private long framePeriodMs() {
        float maxHz = displayMaxHz > 1f ? displayMaxHz : 60f;
        int target = state.targetFps;
        float fps = (target <= 0) ? maxHz : Math.min(target, maxHz);
        long period = Math.round(1000f / Math.max(1f, fps));
        return Math.max(3, period);
    }

    /** 目标帧率是否已经等于屏幕上限（等于时每帧都渲染，不再按固定延时跳帧）。 */
    private boolean targetMatchesDisplay() {
        float maxHz = displayMaxHz > 1f ? displayMaxHz : 60f;
        return state.targetFps <= 0 || state.targetFps >= maxHz * 0.9f;
    }

    private static float ema(float current, float sample) {
        return current == 0 ? sample : current * 0.85f + sample * 0.15f;
    }

    // ---------------------------------------------------------------- 按压形变与按压音效

    /** 装载按压/松开音（组：duck=小黄鸭 Ya1/Ya2，fx1=音效1 D1/D2，off=关闭）。 */
    private void loadPressSounds() {
        releasePressPlayers();
        loadedPressSet = state.pressSoundSet == null ? "duck" : state.pressSoundSet;
        if ("off".equals(loadedPressSet)) {
            Log.write("按压音效：已关闭");
            return;
        }
        int pressRes = "fx1".equals(loadedPressSet) ? R.raw.d1 : R.raw.ya1;
        int releaseRes = "fx1".equals(loadedPressSet) ? R.raw.d2 : R.raw.ya2;
        pressPlayer = createPlayer(pressRes);
        releasePlayer = createPlayer(releaseRes);
        Log.write("按压音效装载：" + ("fx1".equals(loadedPressSet) ? "音效1(D1/D2)" : "小黄鸭(Ya1/Ya2)"));
    }

    private android.media.MediaPlayer createPlayer(int resId) {
        try {
            android.media.MediaPlayer player = android.media.MediaPlayer.create(this, resId);
            if (player != null) {
                player.setVolume((float) state.pressVolume, (float) state.pressVolume);
            }
            return player;
        } catch (Exception e) {
            Log.write("按压音效装载失败: " + e);
            return null;
        }
    }

    private void releasePressPlayers() {
        handler.removeCallbacks(releaseTask);
        if (pressPlayer != null) { pressPlayer.release(); pressPlayer = null; }
        if (releasePlayer != null) { releasePlayer.release(); releasePlayer = null; }
        pressing = false;
        pressEnded = false;
        releasePlayed = false;
    }

    private boolean pressSoundMuted() {
        return "off".equals(state.pressSoundSet) || !state.soundOn
                || now() < soundMuteUntil || pressPlayer == null || releasePlayer == null;
    }

    /** 按下：形变 + 按压音（对应 pressDown()）。 */
    private void pressDown() {
        // 延迟任务（TEST_PRESS / 挤压回弹）可能比服务生命周期长：
        // 服务已销毁或渲染器未就绪时必须直接返回，否则 renderer 为 null 会闪退
        // （实测崩溃：java.lang.NullPointerException on PetRenderer.setPress，PetService.pressUp）。
        if (renderer == null || !running) {
            pressing = false;
            return;
        }
        renderer.setPress(true);
        setBandsInflated(true);
        pressing = true;
        invalidateAll();     // 立刻画出形变的第一帧（不能等下一次「有动画」的重绘）
        Log.write("按压形变：按下 → 目标 scaleX1.05 scaleY0.88（220ms cubic-bezier(.34,1.56,.64,1) 回弹）");
        playPress();
    }

    /** 松开：回弹形变 + 松开音（对应源码 pressUp 的两个分支）。 */
    private void pressUp() {
        if (renderer == null || !running) {     // 同上：生命周期已结束，直接收摊
            pressing = false;
            squeezing = false;
            return;
        }
        renderer.setPress(false);
        pressing = false;
        invalidateAll();     // 回弹同样要按帧画出来
        Log.write("按压形变：松开 → 回弹到 scaleX1.00 scaleY1.00");
        if (pressSoundMuted()) {
            pressEnded = true;
            return;
        }
        if (pressEnded) {
            playRelease();       // 按压音已放完（长按）→ 立刻放松开音
            return;
        }
        int duration = 0, position = 0;
        try {
            duration = pressPlayer.getDuration();
            position = pressPlayer.getCurrentPosition();
        } catch (Exception ignored) { }
        if (duration > 0) {
            long remain = Math.max(0, duration - position - RELEASE_LEAD_MS);
            playReleaseAt(remain);
        }
        // 时长未知 → 交给按压音的完成回调兜底（与源码一致）
    }

    /**
     * 双击 = 挤压效果（用户可选行为）：完整做一次「按下 → 停 400ms → 回弹」，
     * 不触发扣费动画、不写账本。与跟手按压共用同一套形变（WhaleWidget 的 SQUISH）。
     */
    private void playSqueeze() {
        squeezing = true;
        pressDown();
        handler.removeCallbacks(squeezeReleaseRunnable);
        handler.postDelayed(squeezeReleaseRunnable, SQUEEZE_HOLD_MS);
        Log.write("双击桌宠：挤压效果（按住 " + SQUEEZE_HOLD_MS + "ms 后回弹，不扣费）");
    }

    private boolean squeezing = false;

    private final Runnable squeezeReleaseRunnable = new Runnable() {
        @Override public void run() {
            squeezing = false;
            pressUp();
        }
    };

    private void playPress() {
        if (pressSoundMuted()) {
            pressEnded = true;
            return;
        }
        pressEnded = false;
        releasePlayed = false;
        try {
            if (pressPlayer.isPlaying()) pressPlayer.pause();
            pressPlayer.seekTo(0);
            pressPlayer.setVolume((float) state.pressVolume, (float) state.pressVolume);
            pressPlayer.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(android.media.MediaPlayer mp) {
                    pressEnded = true;
                    // 已经是"按完即松"（时长未知的兜底）：立刻补松开音
                    if (!pressing && !releasePlayed) playRelease();
                }
            });
            pressPlayer.start();
            Log.write("按压音效：press（" + loadedPressSet + "）");
        } catch (Exception e) {
            pressEnded = true;
            Log.write("按压音播放失败: " + e);
        }
    }

    private void playRelease() {
        if (releasePlayed || releasePlayer == null || !state.soundOn
                || now() < soundMuteUntil) return;
        releasePlayed = true;
        try {
            if (releasePlayer.isPlaying()) releasePlayer.pause();
            releasePlayer.seekTo(0);
            releasePlayer.setVolume((float) state.pressVolume, (float) state.pressVolume);
            releasePlayer.start();
            Log.write("按压音效：release（" + loadedPressSet + "）");
        } catch (Exception e) {
            Log.write("松开音播放失败: " + e);
        }
    }

    private void playReleaseAt(long delayMs) {
        handler.removeCallbacks(releaseTask);
        handler.postDelayed(releaseTask, Math.max(0, delayMs));
        Log.write("松开音排期：" + Math.max(0, delayMs) + "ms 后（按压音剩余-40ms）");
    }

    private final Runnable releaseTask = new Runnable() {
        @Override public void run() { playRelease(); }
    };

    /**
     * 分带模式下按压形变会导致内容被窗口边界裁切 → 形变期间把每个带窗口外扩。
     * 只做两次 updateViewLayout（按下/结束），不影响常态穿透精度。
     */
    private void setBandsInflated(boolean inflate) {
        if (bands.isEmpty() || bandsInflated == inflate) return;
        // 「双击 = 挤压效果」时即使关掉了跟手按压，也要临时外扩，
        // 否则形变会被分带窗口的边界裁掉（表现为挤压时立绘缺角）。
        if (!state.pressSquashOn && !squeezing) return;
        float amount = sidePx * 0.08f;
        for (Band band : bands) {
            int w = Math.max(1, Math.round(band.rect.width() + (inflate ? amount * 2 : 0)));
            int h = Math.max(1, Math.round(band.rect.height() + (inflate ? amount * 2 : 0)));
            band.params.width = w;
            band.params.height = h;
            band.params.x = posX + Math.round(band.rect.left - (inflate ? amount : 0));
            band.params.y = posY + Math.round(band.rect.top - (inflate ? amount : 0));
            try { windowManager.updateViewLayout(band.view, band.params); } catch (Exception ignored) { }
        }
        bandsInflated = inflate;
        Log.write("分带窗口" + (inflate ? "外扩" : "恢复") + "（按压形变）");
    }

    // ---------------------------------------------------------------- 音效

    private void initSound() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(PetTuning.SOUND_STREAMS)
                .setAudioAttributes(attributes)
                .build();
        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override public void onLoadComplete(SoundPool pool, int sampleId, int status) {
                Log.write("音效就绪 soundId=" + sampleId + " status=" + status
                        + " 来源=" + soundSourceName);
            }
        });
        loadSound();
    }

    /**
     * 装载音效。顺序与 {@link PetPaths#resolveSoundFile} 一致：
     *   自定义（设置界面导入 / Download/DSHPet 下的 hit.mp3 等） → 内置 hit.mp3。
     * 正是原版「换个文件命名为 hit.mp3 覆盖即可」的用法。
     */
    private void loadSound() {
        if (soundPool == null) return;
        File file = PetPaths.resolveSoundFile(state.soundPath);
        soundId = 0;
        if (file != null) {
            soundSourceName = file.getAbsolutePath();
            soundId = soundPool.load(soundSourceName, 1);
        }
        if (soundId == 0) {
            soundSourceName = "内置 hit.mp3";
            soundId = soundPool.load(this, R.raw.hit, 1);
        }
        Log.write("装载音效：" + soundSourceName);
    }

    private String soundSourceName = "(未装载)";

    /** 原版是 4 个 MCI 槽位可叠加播放；SoundPool maxStreams=4 等价。 */
    private void playHit() {
        if (!state.soundOn || soundPool == null || soundId == 0) return;
        double nowSec = now();
        if (nowSec < soundMuteUntil) return;                 // 压测静音窗口
        long nowMs = SystemClock.uptimeMillis();
        // 可选节流：连击时最多每 soundThrottleMs 毫秒响一次（默认 0 = 忠于原版）
        if (state.soundThrottleMs > 0 && nowMs - lastHitSoundMs < state.soundThrottleMs) return;
        lastHitSoundMs = nowMs;
        float volume = (float) Math.min(1, Math.max(0, state.volume));
        soundPool.play(soundId, volume, volume, 1, 0, 1f);
    }

    // ---------------------------------------------------------------- 通知

    private void startForegroundInternal() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "桌宠状态",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("DSH 余额桌宠的运行状态与常用操作");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Notification notification = buildNotification();
        // targetSdk 32：不需要声明前台服务类型（类型强制要求始于 targetSdk 34）
        startForeground(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification() {
        Intent settings = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent settingsIntent = PendingIntent.getActivity(this, 1, settings,
                PendingIntent.FLAG_UPDATE_CURRENT | flagImmutable());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String text = model.isConnected()
                ? "¥" + model.realString() + " · 已连接"
                : "¥-- · " + (model.lastError() == null ? model.statusText() : model.lastError());
        builder.setSmallIcon(R.drawable.ic_yen)
                .setContentTitle("DSH 余额桌宠")
                .setContentText(text)
                .setContentIntent(settingsIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(0, "刷新", servicePendingIntent(2, ACTION_REFRESH))
                .addAction(0, "测试扣费", servicePendingIntent(3, ACTION_ONE_HIT))
                .addAction(0, "设置", settingsIntent)
                .addAction(0, "退出", servicePendingIntent(4, ACTION_STOP));
        return builder.build();
    }

    private int flagImmutable() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private PendingIntent servicePendingIntent(int requestCode, String action) {
        Intent intent = new Intent(this, PetService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | flagImmutable());
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Log.write("更新通知失败: " + e);
        }
    }

    // ---------------------------------------------------------------- 状态快照
    /**
     * 写「编辑器预览」用的真实数据快照（v1.6.0）。
     *
     * <p>编辑器是另一个 activity，读不到服务的内存；但它与服务同进程、共享私有目录，
     * 所以服务把最近一次真实数据落到 {@code files/runtime.json}，编辑器读了就能在预览里
     * 显示**真余额 / 真累计消费 / 真状态文字**（读不到就显示 {@code --} 并标注「未连接」，
     * 绝不假装有数据 —— 这是用户明确要求过的纪律）。
     */
    private void writeRuntimeSnapshot() {
        try {
            JSONObject o = new JSONObject();
            o.put("connected", model.isConnected());
            o.put("balance", model.isConnected() ? model.realString() : WhaleBubbleSpec.OFFLINE_AMOUNT);
            o.put("cost", costString());
            o.put("today", todayString());          // v1.12.0：今日已用（本地账本）
            o.put("bubbleBelow", lastBubbleBelow);   // v1.12.6：泡泡是否画在桌宠下方（编辑器预览据此翻箭头）
            o.put("ledger", ledger.describe());     // 取证用：基准/日期/今日/累计/充值
            o.put("status", statusHint());
            long sec = nowSec();
            o.put("peakNow", PeakValley.isPeak(sec));
            o.put("peakStyle", PeakValley.clampStyle(state.peakStyle));
            o.put("updatedAt", System.currentTimeMillis());
            o.put("fakeTimeOffsetSec", fakeOffsetSec);
            java.io.File file = PetPaths.runtimeFile();
            java.io.File temp = new java.io.File(file.getParentFile(), "runtime.json.tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(temp);
            try {
                out.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Exception e) {
            Log.write("写 runtime.json 失败（编辑器预览会退化成占位值）: " + e);
        }
    }

    /** 每秒原子写一次，方便在别的进程里查看桌宠是否在跑、窗口在哪。 */
    private void writeStatus(boolean isRunning) {
        try {
            JSONObject o = new JSONObject();
            o.put("running", isRunning);
            o.put("character", PetCharacters.current(state).id);
                        o.put("characterName", PetCharacters.current(state).displayName);
            o.put("asset", artwork == null ? "" : artwork.assetName);
            o.put("bands", bands.size());
            o.put("connected", model.isConnected());
            o.put("display", model.displayString());
            o.put("real", model.realString());
            o.put("statusText", model.statusText());
            o.put("lastError", model.lastError() == null ? "" : model.lastError());
            o.put("credential", model.credentialSource() == null ? "" : model.credentialSource());
            o.put("spentCny", model.spentCny() == null ? -1 : model.spentCny());
            o.put("windowX", posX);
            o.put("windowY", posY);
            o.put("windowW", windowW);
            o.put("windowH", windowH);
            o.put("sidePx", sidePx);
            o.put("screenW", screenW);
            o.put("screenH", screenH);
            o.put("pollSeconds", state.pollSeconds);
            o.put("soundOn", state.soundOn);
            o.put("snapOnRelease", state.snapOnRelease);
            o.put("offline", state.offline);
            // 性能监控（每秒刷新）：实测动画帧率 / 屏幕刷新率 / 目标档位 / 每帧耗时
            o.put("fps", measuredFps);
            o.put("displayHz", displayHz);
            o.put("targetFps", state.targetFps);
            o.put("frameWorkMs", avgWorkMs);
            o.put("vsyncMs", avgVsyncMs);
            o.put("windowMode", state.windowMode);
            o.put("drawMode", sceneView != null ? "single" : "outline");
            o.put("updatedAt", System.currentTimeMillis() / 1000.0);
            File file = PetPaths.statusFile();
            File temp = new File(file.getParentFile(), "status.json.tmp");
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (Exception e) {
            Log.write("写 status.json 失败: " + e);
        }
    }

    /** 供设置界面读取的简短状态。 */
    public static String describeState() {
        String text = CredentialStore.readText(PetPaths.statusFile());
        if (text == null) return "桌宠未运行";
        try {
            JSONObject o = new JSONObject(text);
            if (!o.optBoolean("running", false)) return "桌宠未运行";
            return (o.optBoolean("connected", false) ? "已连接" : o.optString("statusText", "离线"))
                    + " · 余额 ¥" + o.optString("display", "--")
                    + " · 角色 " + o.optString("characterName", "?");
        } catch (Exception e) {
            return "状态无法解析";
        }
    }

    /** 帧率监控文案（每秒从 status.json 读一次，数据来自实际渲染计数）。 */
    public static String describePerformance() {
        String text = CredentialStore.readText(PetPaths.statusFile());
        if (text == null) return "桌宠未运行";
        try {
            JSONObject o = new JSONObject(text);
            if (!o.optBoolean("running", false)) return "桌宠未运行";
            int fps = o.optInt("fps", 0);
            float hz = (float) o.optDouble("displayHz", 0);
            int target = o.optInt("targetFps", 0);
            float work = (float) o.optDouble("frameWorkMs", 0);
            float vsync = (float) o.optDouble("vsyncMs", 0);
            String mode = "single".equals(o.optString("drawMode", "")) ? "单窗口" : "轮廓";
            return String.format(Locale.US,
                    "实测动画 %d fps\n屏幕 %.0f Hz · 目标 %s\n每帧耗时 %.1f ms · 帧间隔 %.1f ms\n渲染模式：%s",
                    fps, hz, target <= 0 ? "跟随屏幕" : (target + " fps"), work, vsync, mode);
        } catch (Exception e) {
            return "状态无法解析";
        }
    }
}