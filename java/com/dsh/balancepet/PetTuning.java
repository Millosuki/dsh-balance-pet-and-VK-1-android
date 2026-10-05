package com.dsh.balancepet;

/**
 * 与上游对齐的全部「魔法数字」集中在这里，方便上游升级时一次性核对。
 * 每项都标注了来源，改行为请改这里，不要散落到各处。
 *
 * 来源标注：
 *   macOS-x  = dsh-balance-pet-macos/Sources/x.swift
 *   win      = 原版（Windows版）/DSH余额桌宠/dsh_pet.ps1 + 说明文档.md
 */
public final class PetTuning {

    private PetTuning() {}

    // ---------------- 扣费节奏（macOS PetModel / win 说明文档 §三.五） ----------------
    /** 两次扣费之间的间隔（秒）。win：0.2；macOS：0.2。 */
    public static final double STEP_INTERVAL = 0.2;
    /** 一次受伤动画时长（秒）。 */
    public static final double HIT_DURATION = 0.55;
    /** 飘字存活时间（秒）。 */
    public static final double FLOAT_LIFETIME = 0.95;
    /** 单次轮询最多排队多少次扣费：macOS 400（4.00 元），win 40（0.40 元）。 */
    public static final int MAX_PENDING_STEPS = 400;
    /** 演示队列上限（macOS 200）。 */
    public static final int MAX_DEMO_STEPS = 200;
    /** 同时最多几层受伤叠加（win：3 层 + 总位移硬上限）。 */
    public static final int MAX_IMPULSES = 3;

    // ---------------- 受击表现（macOS PetView.draw / win §四） ----------------
    /** 红色受击层的峰值不透明度（macOS：0.45 * impact）。 */
    public static final double TINT_PEAK = 0.45;
    /** 受击叠色。 */
    public static final int TINT_R = 255, TINT_G = 26, TINT_B = 36;
    /** 抖动单层振幅上限（dp）与随尺寸的比例（macOS：min(3.2, side*0.025)）。 */
    public static final float SHAKE_AMPLITUDE_DP = 3.2f;
    public static final float SHAKE_AMPLITUDE_RATIO = 0.025f;
    /** 多层叠加后的总位移硬上限（dp / 比例）。 */
    public static final float SHAKE_CAP_DP = 5f;
    public static final float SHAKE_CAP_RATIO = 0.04f;
    /** 抖动正弦频率（x/y）。 */
    public static final double SHAKE_FREQ_X = 24, SHAKE_FREQ_Y = 19;
    /** y 方向振幅系数。 */
    public static final double SHAKE_Y_SCALE = 0.875;
    /** 受击强度爬升段占比（前 8% 冲到峰值）。 */
    public static final double IMPACT_ATTACK = 0.08;

    // ---------------- 充值绿环（macOS PetView） ----------------
    public static final double TOPUP_DURATION = 0.9;
    public static final double TOPUP_ALPHA = 0.8;
    public static final float TOPUP_STROKE_RATIO = 0.015f;
    public static final float TOPUP_INSET_BASE = 0.03f;
    public static final float TOPUP_INSET_GROW = 0.06f;

    // ---------------- 飘字（macOS PetView.drawFloating） ----------------
    public static final float FLOAT_FONT_RATIO = 0.080f;
    public static final double FLOAT_ALPHA_POW = 1.6;
    public static final float FLOAT_SHADOW_RATIO = 0.022f;
    public static final double FLOAT_SHADOW_ALPHA = 0.4;
    public static final float FLOAT_TOP_GAP = 0.10f;
    public static final int MAX_FLOATS = 60;

    // ---------------- 轮询（macOS PollSchedule / win DSHPET_POLL_MS） ----------------
    /** 默认间隔（秒）：macOS 30；win 是 2000ms。 */
    public static final double POLL_DEFAULT_SECONDS = 30;
    /** 间隔范围：上游 macOS 是 10~300 秒；本移植版按用户要求放宽到 1~600 秒。 */
    public static final double POLL_MIN_SECONDS = 1;
    public static final double POLL_MAX_SECONDS = 600;
    /** 429 退避上限（秒）。 */
    public static final double BACKOFF_MAX_SECONDS = 300;
    /** Retry-After 上限（秒）。 */
    public static final double RETRY_AFTER_MAX_SECONDS = 86400;

    // ---------------- 尺寸（macOS PetController.sizePresets / win DSHPET_CM） ----------------
    /** macOS：110/150/210/280 pt；Android 屏窄，等比例缩小为 dp。 */
    public static final float[] SIZE_PRESETS_DP = PetLayout.SIZE_PRESETS_DP;
    public static final float MIN_SIDE_DP = PetLayout.MIN_SIDE_DP;
    public static final float MAX_SIDE_DP = PetLayout.MAX_SIDE_DP;

    // ---------------- 音效（macOS PetController.playHit / win DSHPET_VOLUME） ----------------
    /** 播放槽位数：win 预开 4 个 MCI 别名；macOS 4 个 NSSound；这里 SoundPool maxStreams=4。 */
    public static final int SOUND_STREAMS = 4;
    /** 音量：macOS 0.7；win DSHPET_VOLUME 默认 80（0.8）。 */
    public static final double SOUND_VOLUME_DEFAULT = 0.7;

    // ---------------- 动画帧率（Android 独有，上游是 30fps 定时器） ----------------
    /** 0 = 跟随屏幕刷新率；可选 30/60/90/120。 */
    public static final int TARGET_FPS_FOLLOW = 0;
    /** 上游节奏：macOS 1/30 秒、win DSHPET_TICK_MS=33。 */
    public static final long LEGACY_TICK_MS = 33;
    /** 空闲心跳（毫秒）。 */
    public static final long IDLE_TICK_MS = 120;

    // ---------------- 轮廓穿透（Android 独有；上游是逐像素判定） ----------------
    /** 纵向分带数。 */
    public static final int BANDS = 11;
    /** 列采样步长（图像像素）。 */
    public static final int COLUMN_STEP = 8;
    /** 一列在带内至少有这么多不透明像素才算有内容。 */
    public static final int COLUMN_MIN_PIXELS = 2;
    /** 忽略太窄的列段。 */
    public static final int MIN_RUN_WIDTH = 16;
    /** 带内最多保留几段。 */
    public static final int MAX_RUNS_PER_BAND = 2;
    /** 最终轮廓矩形上限（同时受厂商窗口守卫约束）。 */
    public static final int MAX_RECTS = 12;
    /** 轮廓窗口数量硬上限（ColorOS 超限会强杀应用）。 */
    public static final int MAX_WINDOWS = 16;
    /** 判定不透明的 alpha 阈值（与上游一致：> 8/255）。 */
    public static final int ALPHA_THRESHOLD = 8;
}