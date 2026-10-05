package com.dsh.balancepet;

import org.json.JSONObject;

/**
 * 本地记账内核（v1.12.0）。
 *
 * <p>为什么必须自己记（2026-10-05 核实，两处证据）：
 * <ul>
 *   <li>上游插件的余额接口 {@code https://api.deepseek.com/user/balance} **只返回
 *       {@code balance_infos[0].total_balance}**（whale/lib/index.js L100），**没有任何「今日已用」字段**；</li>
 *   <li>我们自己的 {@code BalanceClient} 也去找过 {@code total_costs}（那是 DSH 账号接口的字段），
 *       普通 API 路径没有它 → 所以「累计消费」一直显示 {@code --}。</li>
 * </ul>
 * 上游插件同样是自己记账：{@code lib/accounting.mjs} 的 {@code daySummary()} 把余额**下降**累计成
 * 「已观测消费」（label 就叫这个）、上升单独算充值/赠金 —— 本类与之同口径，但做成**纯函数 + JSON**，
 * 便于离线自检与持久化。
 *
 * <p>口径（如实写清，避免用户误解数字）：
 * <ul>
 *   <li>**第一次观测只作为基准**，不把已有余额当成消费；</li>
 *   <li>余额**下降** → 累加到「今日」与「累计」；</li>
 *   <li>余额**上升**（充值/赠金） → 只累加「充值」，**不冲抵**已有消费；</li>
 *   <li>按**北京时间**分日：换日时「今日」清零，「累计」与「充值」保留；</li>
 *   <li>若接口恰好给了 {@code total_costs}（DSH 账号路径）→ 用它**播种**累计值（只看一次）。</li>
 * </ul>
 */
public final class SpendLedger {

    /** 上一次观测到的余额（分）；null = 还没有基准。 */
    public Long lastFen;
    /** 上一次观测所属的北京日期（yyyy-MM-dd）。 */
    public String dayKey = "";
    /** 今日已用（分，观测口径）。 */
    public long todayFen;
    /** 累计已用（分，观测口径）。 */
    public long totalFen;
    /** 累计充值/赠金（分，观测口径）。 */
    public long topupFen;
    /** 是否已用接口的 total_costs 播种过累计值。 */
    public boolean seeded;

    public SpendLedger() {}

    public static SpendLedger fromJson(String json) {
        SpendLedger l = new SpendLedger();
        if (json == null || json.trim().isEmpty()) return l;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("lastFen") && !o.isNull("lastFen")) l.lastFen = o.optLong("lastFen");
            l.dayKey = o.optString("dayKey", "");
            l.todayFen = Math.max(0, o.optLong("todayFen", 0));
            l.totalFen = Math.max(0, o.optLong("totalFen", 0));
            l.topupFen = Math.max(0, o.optLong("topupFen", 0));
            l.seeded = o.optBoolean("seeded", false);
        } catch (Exception e) {
            Log.write("账本 JSON 解析失败（按空账本处理）: " + e);
        }
        return l;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            if (lastFen != null) o.put("lastFen", (long) lastFen);
            o.put("dayKey", dayKey == null ? "" : dayKey);
            o.put("todayFen", todayFen);
            o.put("totalFen", totalFen);
            o.put("topupFen", topupFen);
            if (seeded) o.put("seeded", true);
        } catch (Exception e) {
            Log.write("账本序列化失败: " + e);
        }
        return o.toString();
    }

    /**
     * 用一次余额观测更新账本。
     *
     * @param fen      本次观测到的余额（分）；null = 本次没有数据（直接返回 false）
     * @param beijingDay 本次观测所属的北京日期（yyyy-MM-dd）
     * @return 账本是否发生了变化（调用方据此决定要不要写盘）
     */
    public boolean observe(Long fen, String beijingDay) {
        if (fen == null) return false;
        boolean changed = false;
        String day = beijingDay == null ? "" : beijingDay;

        // 跨日：今日清零（累计与充值保留）
        if (!day.isEmpty() && !day.equals(dayKey)) {
            if (!dayKey.isEmpty() || todayFen != 0) {
                Log.write("账本：新的一天（" + dayKey + " → " + day + "）→ 今日已用清零，累计保留 "
                        + yuan(totalFen));
            }
            dayKey = day;
            todayFen = 0;
            changed = true;
        }

        if (lastFen == null) {
            lastFen = fen;                     // 第一次只做基准
            changed = true;
            Log.write("账本：建立基准 " + yuan(fen) + "（已有余额不计入消费；从下一次取数开始记）");
            return changed;
        }

        long delta = lastFen - fen;             // >0 消费，<0 充值
        if (delta > 0) {
            todayFen += delta;
            totalFen += delta;
            changed = true;
            Log.write("账本：观测到消费 " + yuan(delta) + " → 今日 " + yuan(todayFen)
                    + " / 累计 " + yuan(totalFen));
        } else if (delta < 0) {
            topupFen += -delta;
            changed = true;
            Log.write("账本：观测到充值/赠金 " + yuan(-delta) + "（不冲抵已有消费）→ 累计充值 "
                    + yuan(topupFen));
        }
        lastFen = fen;
        return changed;
    }

    /**
     * 用接口给的累计消费“播种”（只在第一次、且本地还没有任何观测数据时）。
     *
     * @return 是否真的种下了
     */
    public boolean seedFromApi(Long totalCostsFen) {
        if (seeded || totalCostsFen == null || totalFen > 0 || todayFen > 0) return false;
        totalFen = Math.max(0, totalCostsFen);
        seeded = true;
        Log.write("账本：用接口 total_costs 播种累计消费 " + yuan(totalFen));
        return true;
    }

    public boolean hasData() { return lastFen != null; }

    /** 分 → 「¥x.xx」用的数字串（不带货币符号，交给模板拼）。 */
    public static String yuan(long fen) {
        return String.format(java.util.Locale.US, "%.2f", fen / 100.0);
    }

    /** 今日已用（元，两位小数）。 */
    public String todayYuan() { return yuan(todayFen); }

    /** 累计已用（元，两位小数）。 */
    public String totalYuan() { return yuan(totalFen); }

    /** 累计充值/赠金（元，两位小数）。 */
    public String topupYuan() { return yuan(topupFen); }

    /** 一行摘要（日志/设置页用）。 */
    public String describe() {
        return "今日 " + todayYuan() + " / 累计 " + totalYuan() + " / 充值 " + topupYuan()
                + "（基线 " + (lastFen == null ? "无" : yuan(lastFen)) + "，日期 " + dayKey + "）";
    }
}
