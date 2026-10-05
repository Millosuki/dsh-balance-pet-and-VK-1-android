package com.dsh.balancepet;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * DeepSeek 峰谷时段判定（照抄 dsh-whale-widget 0.3.17）。
 *
 * <p>出处（逐条核实过，不是估的）：
 * <ul>
 *   <li>宿主侧 {@code /root/work/whale/lib/index.js} L446–555：{@code PEAK_HOURS} /
 *       {@code WEEKEND_VALLEY_FROM_SEC} / {@code HOLIDAY_VALLEY} / {@code isPeakTime} /
 *       {@code nextPeakChangeAt}</li>
 *   <li>前端 {@code assets/whale-widget.js} L12994–13129：5 种显示样式与倒计时文案</li>
 * </ul>
 *
 * <p>规则（判定顺序必须与上游一致）：
 * <ol>
 *   <li>周末（北京周六/周日）全天谷 —— 自北京时间 2026-08-23 00:00 起</li>
 *   <li>法定节假日（放假日期清单）全天谷 —— 自北京时间 2026-09-19 00:00 起</li>
 *   <li>工作日 9:00–12:00 与 14:00–18:00 为高峰（左闭右开）</li>
 *   <li>其余为谷</li>
 * </ol>
 *
 * <p>本类**全部是纯函数**（输入 epoch 秒），不依赖设备时区、不读任何状态，
 * 因此可以在 {@link SelfTests} 里直接断言。北京日历一律用「epoch 秒 + 8h 平移 + 纯算术」
 * 计算（与 JS 侧 {@code new Date(sec*1000 + 8*3600*1000)} 再用 {@code getUTCDay/getUTCHours}
 * 读的口径完全等价），不使用 {@code TimeZone} 数据库，避免时区库差异导致结果漂移。
 */
public final class PeakValley {

    private PeakValley() {}

    // ------------------------------------------------------------------ 常量（照抄上游）

    /** 北京时间相对 UTC 的偏移（秒）。 */
    public static final long BJ_OFFSET = 8 * 3600L;

    /** 高峰时段（北京小时，左闭右开）。上游 {@code PEAK_HOURS = [[9,12],[14,18]]}。 */
    public static final int[][] PEAK_HOURS = {{9, 12}, {14, 18}};

    /** 判定「下一个切换点」时扫描的小时边界。上游 {@code [0, 9, 12, 14, 18]}。 */
    private static final int[] EDGES = {0, 9, 12, 14, 18};

    /** 扫描天数上限。上游注释：最长假期（春节 9 天）也只到第 9 天，扫 12 天留足余量。 */
    public static final int SCAN_DAYS = 12;

    /**
     * 法定节假日「放假」日期清单（北京日历日，{@code YYYY-MM-DD}）。
     *
     * <p>上游 {@code HOLIDAY_VALLEY}（L499–509），只列**放假**的日期：
     * 2026 年的调休上班日（1/4、2/14、2/28、5/9、9/20、10/10）全部落在周末，
     * 按「周末也算谷价」的规则本身就是谷价，因此无需单列。
     *
     * <p>⚠️ **每年 11 月国务院公布次年安排后，必须在这里补下一年的日期**
     * （上游为此专门写了探针 {@code _peak-holiday-check.mjs}）。
     */
    public static final String[] HOLIDAY_VALLEY = {
            // 元旦 1/1–1/3（1/4 周日上班）
            "2026-01-01", "2026-01-02", "2026-01-03",
            // 春节 2/15–2/23（9 天）
            "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19",
            "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
            // 清明 4/4–4/6
            "2026-04-04", "2026-04-05", "2026-04-06",
            // 劳动节 5/1–5/5（5/9 周六上班）
            "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
            // 端午 6/19–6/21
            "2026-06-19", "2026-06-20", "2026-06-21",
            // 中秋 9/25–9/27
            "2026-09-25", "2026-09-26", "2026-09-27",
            // 国庆 10/1–10/7（9/20 周日、10/10 周六上班）
            "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04",
            "2026-10-05", "2026-10-06", "2026-10-07",
    };

    private static final Set<String> HOLIDAY_SET =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(HOLIDAY_VALLEY)));

    /** 周末全天谷价生效时刻 = 北京时间 2026-08-23 00:00。 */
    public static final long WEEKEND_VALLEY_FROM_SEC = fromBeijing(2026, 8, 23, 0, 0, 0);

    /** 法定节假日全天谷价生效时刻 = 北京时间 2026-09-19 00:00。 */
    public static final long HOLIDAY_VALLEY_FROM_SEC = fromBeijing(2026, 9, 19, 0, 0, 0);

    // ------------------------------------------------------------------ 5 种显示样式

    /** 默认：高峰时段 / 空闲时段（两态文字均可自定义）。 */
    public static final int STYLE_DEFAULT = 0;
    /** 梁文峰谷：梁文峰 / 梁文谷。 */
    public static final int STYLE_LIANGWEN = 1;
    /** !?强强?!：!?峰峰?! / !?谷谷?!。 */
    public static final int STYLE_QIANGQIANG = 2;
    /** 倒计时：HH:MM:SS（到下次切换）。 */
    public static final int STYLE_COUNT = 3;
    /** 简洁(峰/谷)：峰 / 谷。 */
    public static final int STYLE_MINI = 4;

    /** 样式下拉里的名字。顺序与上游 {@code BUBBLE_PEAK_STYLE_OPTS} 完全一致。 */
    public static final String[] STYLE_NAMES = {
            "默认（高峰时段/空闲时段）", "梁文峰谷", "!?强强?!", "倒计时", "简洁(峰/谷)",
    };

    /** 默认样式的两态文字（上游 L13036：{@code return peak ? '高峰时段' : '空闲时段'}）。 */
    public static final String DEFAULT_PEAK_TEXT = "高峰时段";
    public static final String DEFAULT_VALLEY_TEXT = "空闲时段";

    /** 峰/谷配色默认值：取自上游倒计时快捷模块（L8880）{@code peakColor:'#e0433f' offColor:'#2fa24c'}。 */
    public static final int DEFAULT_PEAK_COLOR = 0xFFE0433F;
    public static final int DEFAULT_VALLEY_COLOR = 0xFF2FA24C;

    public static boolean isValidStyle(int style) {
        return style >= STYLE_DEFAULT && style <= STYLE_MINI;
    }

    public static int clampStyle(int style) {
        return isValidStyle(style) ? style : STYLE_DEFAULT;
    }

    public static String styleDisplayName(int style) {
        return STYLE_NAMES[clampStyle(style)];
    }

    /** 该样式是否由倒计时引擎逐秒输出（上游 {@code bubbleIsPeakCount}）。 */
    public static boolean isCountStyle(int style) {
        return clampStyle(style) == STYLE_COUNT;
    }

    /**
     * 峰谷状态字（上游 {@code bubblePeakText}，L13029–13037）。
     * {@code count} 样式不在此生成（由倒计时引擎输出 HH:MM:SS）。
     */
    public static String statusText(int style, boolean peak) {
        switch (clampStyle(style)) {
            case STYLE_LIANGWEN:
                return peak ? "梁文峰" : "梁文谷";
            case STYLE_QIANGQIANG:
                return peak ? "!?峰峰?!" : "!?谷谷?!";
            case STYLE_MINI:
                return peak ? "峰" : "谷";
            default:
                return peak ? DEFAULT_PEAK_TEXT : DEFAULT_VALLEY_TEXT;
        }
    }

    /** 带两态自定义文字的版本（默认样式专用；其余样式忽略自定义值，忠于上游）。 */
    public static String statusText(int style, boolean peak, String customPeak, String customValley) {
        int s = clampStyle(style);
        if (s == STYLE_DEFAULT) {
            String t = peak ? customPeak : customValley;
            if (t != null && !t.isEmpty()) return t;
        }
        return statusText(s, peak);
    }

    /** 该样式在「当前时刻」应该显示的那行文字（count → 倒计时；其余 → 状态字）。 */
    public static String rowText(int style, long sec, String customPeak, String customValley) {
        if (isCountStyle(style)) return countdownText(sec);
        return statusText(style, isPeak(sec), customPeak, customValley);
    }

    /** 该样式在当前状态下的行颜色（count 与其它样式一致地按峰/谷取色）。 */
    public static int rowColor(boolean peak, int peakColor, int valleyColor) {
        return peak ? peakColor : valleyColor;
    }

    // ------------------------------------------------------------------ 北京日历（纯算术）

    /** 北京当日序号（1970-01-01 北京日为 0）。 */
    public static long bjDayIndex(long sec) {
        return Math.floorDiv(sec + BJ_OFFSET, 86400L);
    }

    /** 北京小时 0–23。 */
    public static int bjHour(long sec) {
        return (int) Math.floorMod(Math.floorDiv(sec + BJ_OFFSET, 3600L), 24L);
    }

    /** 北京分钟 0–59。 */
    public static int bjMinute(long sec) {
        return (int) Math.floorMod(Math.floorDiv(sec + BJ_OFFSET, 60L), 60L);
    }

    /** 北京秒 0–59。 */
    public static int bjSecond(long sec) {
        return (int) Math.floorMod(sec + BJ_OFFSET, 60L);
    }

    /** 北京星期：0 = 周日 … 6 = 周六（等价 JS {@code getUTCDay()} 在 +8h 平移坐标系里的值）。 */
    public static int bjDayOfWeek(long sec) {
        // 1970-01-01 是周四（=4）
        return (int) Math.floorMod(bjDayIndex(sec) + 4L, 7L);
    }

    /** 北京日历日字符串 {@code YYYY-MM-DD}（等价 JS {@code toISOString().slice(0,10)}）。 */
    public static String bjDateKey(long sec) {
        int[] ymd = civilFromDays(bjDayIndex(sec));
        return String.format(Locale.US, "%04d-%02d-%02d", ymd[0], ymd[1], ymd[2]);
    }

    /**
     * 北京日历时刻 → epoch 秒（纯算术，与 {@link #bjDateKey} 互为逆运算）。
     *
     * @param month 1–12；{@code hour} 0–23
     */
    public static long fromBeijing(int year, int month, int day, int hour, int minute, int second) {
        long days = daysFromCivil(year, month, day);
        return days * 86400L + hour * 3600L + minute * 60L + second - BJ_OFFSET;
    }

    // Howard Hinnant 的 civil-date 算法（proleptic Gregorian，与 JS 的 Date 口径一致）。
    // 单独验证方式见 SelfTests：把若干天与已知的星期/日期互相对照。

    /** 天数序号 → {年, 月(1–12), 日}。 */
    static int[] civilFromDays(long z) {
        z += 719468L;
        long era = Math.floorDiv(z, 146097L);
        long doe = z - era * 146097L;                                   // [0, 146096]
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; // [0, 399]
        long y = yoe + era * 400L;
        long doy = doe - (365L * yoe + yoe / 4 - yoe / 100);             // [0, 365]
        long mp = (5 * doy + 2) / 153;                                   // [0, 11]
        long d = doy - (153 * mp + 2) / 5 + 1;                           // [1, 31]
        long m = mp < 10 ? mp + 3 : mp - 9;                              // [1, 12]
        return new int[]{(int) (m <= 2 ? y + 1 : y), (int) m, (int) d};
    }

    /** {年, 月(1–12), 日} → 天数序号。 */
    static long daysFromCivil(int year, int month, int day) {
        long y = year - (month <= 2 ? 1 : 0);
        long era = Math.floorDiv(y, 400L);
        long yoe = y - era * 400L;                                       // [0, 399]
        long mp = month > 2 ? month - 3 : month + 9;                     // [0, 11]
        long doy = (153 * mp + 2) / 5 + day - 1;                         // [0, 365]
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;                // [0, 146096]
        return era * 146097L + doe - 719468L;
    }

    // ------------------------------------------------------------------ 峰谷判定

    public static boolean isHoliday(long sec) {
        return HOLIDAY_SET.contains(bjDateKey(sec));
    }

    /**
     * 是否处于高峰时段。判定顺序与上游 {@code isPeakTime}（L522–537）完全一致。
     */
    public static boolean isPeak(long sec) {
        // ① 周末全天谷（2026-08-23 起）
        if (sec >= WEEKEND_VALLEY_FROM_SEC) {
            int dow = bjDayOfWeek(sec);
            if (dow == 0 || dow == 6) return false;
        }
        // ② 法定节假日全天谷（2026-09-19 起）
        if (sec >= HOLIDAY_VALLEY_FROM_SEC && isHoliday(sec)) return false;
        // ③ 工作日高峰时段
        int h = bjHour(sec);
        for (int[] range : PEAK_HOURS) {
            if (h >= range[0] && h < range[1]) return true;
        }
        // ④ 其余为谷
        return false;
    }

    /**
     * 下一个峰谷切换时刻（epoch 秒）。与 {@link #isPeak} 完全同源。
     *
     * @return 切换时刻；{@code -1} 表示 {@link #SCAN_DAYS} 天内没有切换点
     *         （当前规则下不会发生，上游同样返回 null 并由前端兜底）
     */
    public static long nextChangeAt(long sec) {
        boolean current = isPeak(sec);
        long bjDay0 = Math.floorDiv(sec + BJ_OFFSET, 86400L) * 86400L;   // 北京当日 00:00
        for (int d = 0; d <= SCAN_DAYS; d++) {
            for (int edge : EDGES) {
                long cand = bjDay0 + d * 86400L + edge * 3600L - BJ_OFFSET;
                if (cand <= sec + 1) continue;
                if (isPeak(cand) != current) return cand;
            }
        }
        return -1L;
    }

    /**
     * 倒计时文案（上游口径）：{@code HH:MM:SS}，**小时不折天**。
     *
     * <p>⚠️ 这是**上游原样行为**（{@code bubbleCountdownText}，L13119–13129）：
     * 长假期间会显示 {@code 143:00:00} 这种值。此方法刻意保持逐字节兼容，
     * 因为 {@code /root/work/pv} 的「与上游 JS 逐点对比」回归依赖它。
     * 用户可见的显示格式请用 {@link #countdownText(long, int)}（可配置）。
     */
    public static String countdownText(long sec) {
        return countdownText(sec, CD_UPSTREAM);
    }

    // ---------------------------------------------------------------- 倒计时显示格式（用户可选）

    /**
     * 智能（默认）：不足 24 小时显示 {@code HH:MM:SS}（与上游一致）；
     * 满 24 小时起折成 {@code N天HH:MM:SS}。
     *
     * <p>为什么加这一档：上游「小时不折天」在长假期间会显示 {@code 143:00:00}，
     * 用户实测反馈「不合理」。**这是有意偏离上游**，故做成选项而不是直接改掉。
     */
    public static final int CD_SMART = 0;
    /** 原样：{@code HH:MM:SS}，小时不折天（100% 忠于上游）。 */
    public static final int CD_UPSTREAM = 1;
    /** 智能 + 标注目标时刻，如 {@code 3天12:56:35（至 10-08 09:00）}。 */
    public static final int CD_SMART_TARGET = 2;

    public static final String[] CD_FORMAT_NAMES = {
            "智能（超过 24 小时折成「天」）",
            "原样（HH:MM:SS，忠于原插件）",
            "智能 + 标注目标时刻",
    };

    public static int clampCountdownFormat(int format) {
        return (format >= CD_SMART && format <= CD_SMART_TARGET) ? format : CD_SMART;
    }

    public static String countdownFormatName(int format) {
        return CD_FORMAT_NAMES[clampCountdownFormat(format)];
    }

    /**
     * 倒计时文案（按用户选择的格式）。扫不到切换点时兜底 24 小时
     * （与上游 {@code sec + 86400} 一致，避免卡在 00:00:00）。
     */
    public static String countdownText(long sec, int format) {
        long cand = nextChangeAt(sec);
        if (cand < 0) cand = sec + 86400L;
        long remain = Math.max(0L, cand - sec);
        long hh = remain / 3600L;
        long mm = (remain % 3600L) / 60L;
        long ss = remain % 60L;
        int f = clampCountdownFormat(format);
        StringBuilder out = new StringBuilder(24);
        if (f != CD_UPSTREAM && hh >= 24L) {
            out.append(hh / 24L).append("天");
            out.append(String.format(Locale.US, "%02d:%02d:%02d", hh % 24L, mm, ss));
        } else {
            out.append(String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss));
        }
        if (f == CD_SMART_TARGET) {
            int[] ymd = civilFromDays(bjDayIndex(cand));
            out.append(String.format(Locale.US, "（至 %02d-%02d %02d:%02d）",
                    ymd[1], ymd[2], bjHour(cand), bjMinute(cand)));
        }
        return out.toString();
    }

    /** 距离下次切换的秒数（≥0）。 */
    public static long secondsToNextChange(long sec) {
        long cand = nextChangeAt(sec);
        if (cand < 0) cand = sec + 86400L;
        return Math.max(0L, cand - sec);
    }
}
