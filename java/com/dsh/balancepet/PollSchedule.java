package com.dsh.balancepet;

/**
 * 请求调度：用经过时间而非动画帧计时；同一时刻最多一个请求在飞；
 * 重新读取凭证会让旧结果作废。
 *
 * 逐条移植自 dsh-balance-pet-macos/Sources/PollSchedule.swift。
 */
public final class PollSchedule {
    private double interval;
    private double nextPollAt = 0;
    private double retryNotBefore = 0;
    private long generation = 0;
    private Long inFlight;      // null 表示无请求在飞
    private double backoff;

    public PollSchedule(double intervalSeconds) {
        this.interval = clampInterval(intervalSeconds);
        this.backoff = this.interval;
    }

    private static double clampInterval(double seconds) {
        if (!isFinite(seconds)) seconds = 30;
        // 移植版放宽到 1 秒 ~ 10 分钟（原版 10~300 秒）；过密会被 429 退避自动兜住
        return Math.min(600, Math.max(1, seconds));
    }

    private static boolean isFinite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    public double interval() { return interval; }
    public double nextPollAt() { return nextPollAt; }
    public long generation() { return generation; }
    public boolean inFlight() { return inFlight != null; }

    public boolean canRefreshAt(double now) {
        return inFlight == null && now >= retryNotBefore;
    }

    /** 返回本次请求代号；null 表示本次不应发起（间隔未到/已有请求/退避中）。 */
    public Long begin(double now, boolean force) {
        if (!canRefreshAt(now)) return null;
        if (!force && now < nextPollAt) return null;
        inFlight = generation;
        return generation;
    }

    /**
     * 结束请求。返回 false 表示结果属于已被替换的凭证，必须丢弃。
     * error: null 表示成功；非 null 时若为 429 则按 Retry-After 退避。
     */
    public boolean finish(long request, BalanceClient.FetchError error, double now) {
        if (inFlight == null || inFlight != request) return false;
        inFlight = null;
        if (request != generation) {
            nextPollAt = Math.max(now, retryNotBefore);
            return false;
        }
        if (error instanceof BalanceClient.FetchError.RateLimited) {
            Double retry = ((BalanceClient.FetchError.RateLimited) error).retryAfter;
            if (retry != null && isFinite(retry) && retry >= 0) {
                backoff = Math.max(10, retry);
            } else {
                backoff = Math.min(300, Math.max(interval, backoff * 2));
            }
            retryNotBefore = now + backoff;
            nextPollAt = retryNotBefore;
        } else {
            backoff = interval;
            retryNotBefore = 0;
            nextPollAt = now + interval;
        }
        return true;
    }

    public void setInterval(double seconds, double now) {
        interval = clampInterval(seconds);
        nextPollAt = Math.max(now + interval, retryNotBefore);
    }

    public void reload(double now) {
        generation++;
        backoff = interval;
        retryNotBefore = 0;
        nextPollAt = now;
    }

    public void wake(double now) {
        nextPollAt = Math.max(now, retryNotBefore);
    }
}