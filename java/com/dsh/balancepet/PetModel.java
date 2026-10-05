package com.dsh.balancepet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 余额账本 + 动画状态。刻意把「服务器计价」和「动画」分开：
 * 菜单里的演示绝不能消耗真实扣费，也不能把没变化的轮询当成充值。
 *
 * 逐条移植自 dsh-balance-pet-macos/Sources/PetModel.swift。
 */
public final class PetModel {

    public static final class FloatLabel {
        public final String text;
        public final int color;
        public double age;
        public float x;
        FloatLabel(String text, int color) { this.text = text; this.color = color; this.x = 0.5f; }
    }

    // ---- 计价 ----
    private Integer bookedCents;          // 账面基准（分）
    private Integer realCents;            // 接口刚读到的真实余额（分）
    private Double spentCny;              // 累计消费（元），可空

    private boolean connected = false;
    private String statusText = "连接中…";
    private String lastError;
    private String credentialSource;
    private long lastUpdate;

    private int pendingSteps = 0;         // 待播的真实扣费次数
    private int demoRemaining = 0;        // 演示剩余次数
    private int demoOffset = 0;           // 演示显示偏移（分）
    private double demoRestoreTime = 0;

    private static final int MAX_PENDING_STEPS = 400;   // 原版：400 次（4.00 元）以上直接对齐
    private static final int MAX_DEMO_STEPS = 200;
    private static final int MAX_FLOATS = 60;

    // ---- 动画 ----
    public final List<FloatLabel> floating = new ArrayList<>();
    /**
     * 受击抖动改为「最多 3 层脉冲叠加」，对应 Windows 原版的做法：
     * 连击时不是把上一层的计时重置（那样看起来是抽一下再抽一下），
     * 而是叠加上去并衰减，最后对总位移取硬上限，避免把角色甩出屏幕。
     */
    private final List<Double> impulses = new ArrayList<>();
    private static final int MAX_IMPULSES = 3;
    private double flashTime = 0;
    private double topupTime = 0;
    private double clock = 0;
    private double stepCooldown = 0;
    private final float[] shakeBuffer = new float[2];

    public final double stepInterval = 0.2;   // 两次扣费间隔
    public final double hitDuration = 0.55;   // 受击动画时长
    public final double floatLifetime = 0.95; // 飘字存活

    /** 每次真实/演示扣费播一次，用于触发音效。 */
    public Runnable onHit;

    // 颜色（与 Swift 版的 systemRed / systemGreen 一致）
    public static final int RED = 0xFFFF3B30;
    public static final int GREEN = 0xFF34C759;

    // ---- 查询 ----

    public Integer bookedCents() { return bookedCents; }
    public Integer realCents() { return realCents; }
    public Double spentCny() { return spentCny; }
    public boolean isConnected() { return connected; }
    public String statusText() { return statusText; }
    public String lastError() { return lastError; }
    public String credentialSource() { return credentialSource; }
    public long lastUpdate() { return lastUpdate; }
    public int pendingSteps() { return pendingSteps; }
    public int demoRemaining() { return demoRemaining; }

    /** 是否还有抖动在播（叠加层里只要有一层没结束就算）。 */
    public boolean hasShake() {
        for (Double start : impulses) {
            if (clock - start <= hitDuration) return true;
        }
        return false;
    }

    public double flashTime() { return flashTime; }
    public double topupTime() { return topupTime; }
    public int impulseCount() { return impulses.size(); }

    /** 平板上要打印的数字（分）。演示偏移只影响显示，且钳到 0 不为负。 */
    public Integer displayedCents() {
        if (bookedCents == null) return null;
        long value = (long) bookedCents - demoOffset;
        long clamped = Math.max(Math.min(0, bookedCents), value);
        return (int) clamped;
    }

    public String displayString() {
        Integer v = displayedCents();
        return v == null ? "--" : fenString(v);
    }

    public String realString() {
        return realCents == null ? "--" : fenString(realCents);
    }

    public boolean needsAnimationFrame() {
        return pendingSteps > 0 || demoRemaining > 0 || demoRestoreTime > 0
                || !floating.isEmpty() || hasShake() || flashTime > 0 || topupTime > 0;
    }

    // ---- 服务器读数 ----

    /** snap = 丢弃排队动画与演示偏移（首次读数 / 手动刷新）。 */
    public void apply(BalanceClient.BalanceReading reading, boolean snap) {
        Integer cents = reading.totalCents();
        if (cents == null) {
            fail(new BalanceClient.FetchError.Parse("余额数值超出可显示范围"));
            return;
        }
        Integer previousReal = realCents;
        realCents = cents;
        Double spent = reading.spentCny();
        spentCny = (spent != null && !spent.isNaN() && !spent.isInfinite()) ? spent : null;
        connected = true;
        statusText = "已连接";
        lastError = null;
        lastUpdate = System.currentTimeMillis();

        if (snap || bookedCents == null || previousReal == null) {
            forceSnapToReal();
            return;
        }

        if (cents > previousReal) {
            // 按「相邻两次服务器读数」比较，不看滞后的动画或演示显示。
            forceSnapToReal();
            topupTime = 0.9;
            long credit = (long) cents - previousReal;
            appendLabel("+" + fenString(credit), GREEN);
        } else if (cents < bookedCents) {
            long steps = (long) bookedCents - cents;
            if (steps > MAX_PENDING_STEPS) {
                forceSnapToReal();
                Log.write("balance jump exceeds animation limit; snapping");
            } else {
                // 重新推导欠款：重复轮询不会重播已经播过的钱，新消耗会续进队列。
                pendingSteps = (int) steps;
            }
        } else {
            pendingSteps = 0;
        }
    }

    public void fail(BalanceClient.FetchError error) {
        connected = false;
        lastError = error.describe();
        statusText = "离线";
        Log.write("poll failed: " + error.describe());
    }

    /** 换凭证前先把旧账号作废。 */
    public void resetForCredentialChange() {
        bookedCents = null;
        realCents = null;
        spentCny = null;
        connected = false;
        statusText = "连接中…";
        lastError = null;
        credentialSource = null;
        lastUpdate = 0;
        clearQueue();
        floating.clear();
        impulses.clear();
        flashTime = 0;
        topupTime = 0;
    }

    public void setNoCredential() {
        resetForCredentialChange();
        lastError = "找不到凭证";
        statusText = "未配置";
    }

    public void setCredentialSource(String source) { credentialSource = source; }

    public void forceSnapToReal() {
        bookedCents = realCents;
        clearQueue();
    }

    private void clearQueue() {
        pendingSteps = 0;
        demoRemaining = 0;
        demoOffset = 0;
        demoRestoreTime = 0;
        stepCooldown = 0;
    }

    public void playOneHit() { playDemo(1); }

    /** 取消正在播放的演示（点一下桌宠即可停下连击）。 */
    public void cancelDemo() {
        if (demoRemaining <= 0 && demoOffset == 0) return;
        demoRemaining = 0;
        demoRestoreTime = hitDuration;   // 0.55 秒后把显示偏移清零，回到真实余额
        Log.write("演示已取消，显示回到真实余额");
    }

    /** 演示扣费：只影响显示偏移，且总队列有上限。 */
    public void playDemo(int times) {
        int count = Math.max(1, Math.min(times, MAX_DEMO_STEPS));
        demoRemaining = Math.min(MAX_DEMO_STEPS, demoRemaining + count);
        demoRestoreTime = 0;
    }

    // ---- 每帧 ----

    public void tick(double dt) {
        if (Double.isNaN(dt) || dt < 0) return;
        // 睡眠唤醒不应该突然播几百次音效：帧步长上限 0.1 秒。
        double elapsed = Math.min(dt, 0.1);
        clock += elapsed;
        // 抖动脉冲：过期的移除（最多同时 3 层）
        for (java.util.Iterator<Double> it = impulses.iterator(); it.hasNext(); ) {
            if (clock - it.next() > hitDuration) it.remove();
        }
        flashTime = Math.max(0, flashTime - elapsed);
        topupTime = Math.max(0, topupTime - elapsed);

        for (FloatLabel f : floating) f.age += elapsed;
        for (Iterator<FloatLabel> it = floating.iterator(); it.hasNext(); ) {
            if (it.next().age >= floatLifetime) it.remove();
        }

        if (demoRestoreTime > 0) {
            demoRestoreTime = Math.max(0, demoRestoreTime - elapsed);
            if (demoRestoreTime == 0) demoOffset = 0;
        }

        if (pendingSteps <= 0 && demoRemaining <= 0) {
            stepCooldown = Math.max(0, stepCooldown - elapsed);
            return;
        }

        stepCooldown -= elapsed;
        if (stepCooldown > 1e-9) return;
        // 保留小数帧余量：直接重置成 0.2 会让每次扣费被拉长到 13 帧。
        stepCooldown = Math.max(0, stepCooldown + stepInterval);

        if (pendingSteps > 0 && bookedCents != null && realCents != null && bookedCents > realCents) {
            bookedCents = bookedCents - 1;   // 安全：booked 严格高于 real
            pendingSteps--;
        } else if (demoRemaining > 0) {
            demoRemaining--;
            if (demoOffset < Integer.MAX_VALUE) demoOffset++;
            if (demoRemaining == 0) demoRestoreTime = hitDuration;
        } else {
            pendingSteps = 0;
            return;
        }

        shakeTimeRemoved();
        flashTime = hitDuration;
        appendLabel("-0.01", RED);
        if (onHit != null) onHit.run();
    }

    /** 叠一层抖动脉冲（最多 3 层，旧的自动丢弃）。 */
    private void shakeTimeRemoved() {
        impulses.add(clock);
        while (impulses.size() > MAX_IMPULSES) impulses.remove(0);
    }

    private void appendLabel(String text, int color) {
        floating.add(new FloatLabel(text, color));
        while (floating.size() > MAX_FLOATS) floating.remove(0);
    }

    // ---- 视图辅助 ----

    /**
     * 0…1 的受击强度：多层叠加后取上限 1（红闪用）。
     * 单层内部：8% 时间内冲到峰值，其余线性衰减。
     */
    public double impact() {
        double sum = 0;
        for (Double start : impulses) {
            double elapsed = clock - start;
            if (elapsed < 0 || elapsed > hitDuration) continue;
            if (elapsed < 0.08) sum += Math.min(1, elapsed / 0.08);
            else sum += Math.max(0, 1 - (elapsed - 0.08) / (hitDuration - 0.08));
        }
        return Math.min(1, sum);
    }

    /**
     * 本帧的抖动位移（像素）。多层脉冲正弦叠加后钳到 ±amplitudeCap，
     * 保证连击时位移不会无限累加把角色甩出屏幕。
     */
    public float[] shakeOffset(float amplitudeCap) {
        double sx = 0, sy = 0;
        for (Double start : impulses) {
            double elapsed = clock - start;
            if (elapsed < 0 || elapsed > hitDuration) continue;
            double decay = Math.max(0, 1 - elapsed / hitDuration);
            sx += Math.sin(elapsed * 24) * decay;
            sy += Math.cos(elapsed * 19) * 0.875 * decay;
        }
        sx = Math.max(-1, Math.min(1, sx));
        sy = Math.max(-1, Math.min(1, sy));
        shakeBuffer[0] = (float) (sx * amplitudeCap);
        shakeBuffer[1] = (float) (sy * amplitudeCap);
        return shakeBuffer;
    }

    /** 整数格式化：避免浮点误差，并正确处理 Int.MIN。 */
    public static String fenString(long fen) {
        long magnitude = Math.abs(fen);
        long fraction = magnitude % 100;
        return (fen < 0 ? "-" : "") + (magnitude / 100) + "." + (fraction < 10 ? "0" : "") + fraction;
    }
}