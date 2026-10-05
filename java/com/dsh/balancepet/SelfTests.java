package com.dsh.balancepet;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线自检：把原版仓库里的回归测试搬到 Android 侧可运行时执行。
 * 覆盖账本（逐分扣费/充值/演示不侵蚀真实余额）、调度（含 Retry-After 与凭证代次）、
 * 响应解析（币种/精度/错误码）、凭证 YAML、以及素材与平板安全区。
 *
 * 全部离线，不需要凭证也不联网。
 */
public final class SelfTests {

    private static final List<String> failures = new ArrayList<>();
    private static int passed = 0;

    private static void expect(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(what);
        }
    }

    public static String run(Context context) {
        failures.clear();
        passed = 0;
        long start = System.currentTimeMillis();

        testFenString();
        testLedger();
        testDemoDoesNotTouchRealMoney();
        testPollSchedule();
        testResponseParsing();
        testRetryAfter();
        testCredentials();
        testStateClamp();
        testOfflineArtDecision();
        testPeakValley();
        testEditModel(context);
        testBubbleScaleAndFont();
        testColorSlots();
        testMarqueeAndSeq();
        testRandomLines();
        testSpendLedger();
        testRichText();
        testArrowFlip();
        testModuleTypeListConsistency();
                testCharacterRegistry();
                        testBackupRoundTrip();
        if (context != null) testArtwork(context);
        if (context != null) testChangelogAsset(context);

        StringBuilder sb = new StringBuilder();
        sb.append("通过 ").append(passed).append(" 项");
        if (failures.isEmpty()) {
            sb.append("，全部通过 ✅");
        } else {
            sb.append("，失败 ").append(failures.size()).append(" 项 ❌\n");
            for (String f : failures) sb.append("· ").append(f).append("\n");
        }
        sb.append(String.format(Locale.US, "\n耗时 %d ms", System.currentTimeMillis() - start));
        String report = sb.toString();
        Log.write("自检：" + report.replace("\n", " | "));
        return report;
    }

    private static void testFenString() {
        expect("0.00".equals(PetModel.fenString(0)), "fenString(0)");
        expect("0.01".equals(PetModel.fenString(1)), "fenString(1)");
        expect("-0.01".equals(PetModel.fenString(-1)), "fenString(-1)");
        expect("12.34".equals(PetModel.fenString(1234)), "fenString(1234)");
        expect("-12.34".equals(PetModel.fenString(-1234)), "fenString(-1234)");
        expect("1.00".equals(PetModel.fenString(100)), "fenString(100)");
        expect("0.05".equals(PetModel.fenString(5)), "fenString(5)");
    }

    private static BalanceClient.BalanceReading reading(String cents) {
        return new BalanceClient.BalanceReading(
                new java.math.BigDecimal(cents), java.math.BigDecimal.ZERO, null, "{}");
    }

    /**
     * 推进动画时间。注意 PetModel.tick 内部有 0.1 秒的帧步长上限
     * （防止睡眠唤醒后一次补播几百次扣费），所以「推进 0.2 秒」需要调用两次。
     */
    private static void advance(PetModel model, double seconds) {
        double left = seconds;
        while (left > 1e-9) {
            double step = Math.min(0.1, left);
            model.tick(step);
            left -= step;
        }
    }

    private static void testLedger() {
        PetModel model = new PetModel();
        expect(model.displayString().equals("--"), "初始显示应为 --");

        model.apply(reading("30.00"), true);
        expect("30.00".equals(model.displayString()), "首次读数对齐 30.00");

        // 掉一分：入队但先不改数字
        model.apply(reading("29.99"), false);
        expect("30.00".equals(model.displayString()), "未到 0.2s 前数字不动");
        expect(model.pendingSteps() == 1, "欠款 1 分应入队");
        advance(model, 0.2);
        expect("29.99".equals(model.displayString()), "扣费帧数字才变，且一次只变 0.01");
        expect(model.floating.size() == 1, "扣费应产生一个飘字");
        expect(model.impulseCount() == 1, "单次扣费叠 1 层抖动");

        // 连掉 5 分。原版语义：
        //   1) 新一批欠款的第一笔「当帧」结算（实时性，不压着等下一轮）；
        //   2) 之后每 0.2 秒一笔。
        model.apply(reading("29.94"), false);
        expect(model.pendingSteps() == 5, "欠款 5 分应入队（实得 " + model.pendingSteps() + "）");
        advance(model, 0.1);
        expect("29.98".equals(model.displayString()),
                "新欠款的第一笔当帧结算（实得 " + model.displayString() + "）");
        expect(model.pendingSteps() == 4, "第一笔结算后剩 4 分（实得 " + model.pendingSteps() + "）");

        // 节奏用「统计」验，而不是拿 0.1 秒粗步长去卡帧：
        // 5 分欠款 = 第一笔当帧 + 4 × 0.2 秒 ≈ 0.8 秒内扣完。
        PetModel rhythm = new PetModel();
        rhythm.apply(reading("30.00"), true);
        rhythm.apply(reading("29.95"), false);
        expect(rhythm.pendingSteps() == 5, "欠款 5 分应入队");
        advance(rhythm, 0.5);
        expect("29.97".equals(rhythm.displayString()),
                "0.5 秒内应结算 3 笔（第 1 笔当帧 + 每 0.2 秒一笔），实得 " + rhythm.displayString());
        advance(rhythm, 0.6);
        expect("29.95".equals(rhythm.displayString()),
                "约 1 秒内应把 5 分扣完，实得 " + rhythm.displayString());
        expect(rhythm.pendingSteps() == 0, "队列应清空（实得 " + rhythm.pendingSteps() + "）");

        // 手动刷新 = 立刻对齐，不补播动画
        model.apply(reading("29.94"), true);
        expect("29.94".equals(model.displayString()), "手动刷新立即对齐");
        expect(model.pendingSteps() == 0, "手动刷新应清空队列");

        // 大额跳变超过 400 次 → 直接对齐
        model.apply(reading("20.00"), false);
        expect("20.00".equals(model.displayString()), "超过 400 分的变化直接对齐");

        // 充值：立即显示并飘出到账金额
        model.apply(reading("25.00"), false);
        expect("25.00".equals(model.displayString()), "充值立即对齐");
        boolean greenFound = false;
        for (PetModel.FloatLabel label : model.floating) {
            if (label.color == PetModel.GREEN && label.text.startsWith("+")) greenFound = true;
        }
        expect(greenFound, "充值应飘出绿色 +金额");
    }

    private static void testDemoDoesNotTouchRealMoney() {
        PetModel model = new PetModel();
        model.apply(reading("10.00"), true);
        model.playOneHit();
        advance(model, 0.2);
        expect("9.99".equals(model.displayString()), "演示让屏显下降");
        expect("10.00".equals(model.realString()), "演示不得改真实余额");
        model.forceSnapToReal();
        expect("10.00".equals(model.displayString()), "刷新后演示偏移清零");

        // 演示排队上限
        model.playDemo(1000);
        expect(model.demoRemaining() <= 200, "演示队列上限 200 次");
    }

    private static void testPollSchedule() {
        PollSchedule s = new PollSchedule(30);
        Long first = s.begin(100, false);
        expect(first != null, "首次轮询可发起");
        expect(s.begin(101, true) == null, "手动刷新不能与在飞请求重叠");
        expect(s.finish(first, null, 119), "当前请求的结果被接受");
        expect(s.begin(130, false) == null, "间隔从请求完成时刻开始算");

        Long second = s.begin(149, false);
        expect(second != null, "到达间隔后可再次轮询");
        expect(s.finish(second, new BalanceClient.FetchError.RateLimited(600.0), 150), "429 也能完成请求");
        expect(s.begin(151, true) == null, "手动刷新必须遵守 Retry-After");

        s.setInterval(10, 151);
        expect(s.begin(749, false) == null, "改间隔不能绕过 Retry-After");
        Long third = s.begin(750, false);
        expect(third != null, "退避结束后恢复轮询");

        s.reload(751);
        expect(s.begin(751, true) == null, "换凭证要等在飞请求结束");
        expect(!s.finish(third, new BalanceClient.FetchError.Auth("old account"), 752),
                "旧凭证的响应必须丢弃");
        Long fourth = s.begin(752, true);
        expect(fourth != null && !fourth.equals(third), "新凭证使用新的请求代次");
        s.finish(fourth, new BalanceClient.FetchError.RateLimited(null), 753);
        expect(Math.abs(s.nextPollAt() - 773) < 1e-6, "缺少 Retry-After 时退避翻倍");

        s.wake(760);
        expect(s.begin(760, false) == null, "唤醒不能绕过服务端退避");
        s.wake(900);
        expect(s.begin(900, false) != null, "唤醒可恢复已过期的轮询");
    }

    private static void testResponseParsing() {
        String apiKeyBody = "{\"is_available\":true,\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"29.99\","
                + "\"granted_balance\":\"0.00\",\"topped_up_balance\":\"29.99\"}]}";
        try {
            BalanceClient.BalanceReading r =
                    BalanceClient.parseResponse(200, apiKeyBody, null, CredentialStore.Mode.API_KEY);
            expect(Integer.valueOf(2999).equals(r.totalCents()), "API Key 余额 29.99 → 2999 分");
        } catch (Exception e) {
            expect(false, "API Key 正常响应应当解析成功：" + e);
        }

        String usdOnly = "{\"balance_infos\":[{\"currency\":\"USD\",\"total_balance\":\"5.00\"}]}";
        boolean rejected = false;
        try {
            BalanceClient.parseResponse(200, usdOnly, null, CredentialStore.Mode.API_KEY);
        } catch (BalanceClient.FetchError.Parse e) {
            rejected = true;
        } catch (Exception e) {
            rejected = false;
        }
        expect(rejected, "只有 USD 时必须报错，不能冒充人民币");

        String accountBody = "{\"code\":0,\"data\":{\"biz_code\":0,\"biz_data\":{"
                + "\"normal_wallets\":[{\"currency\":\"CNY\",\"balance\":\"10.005\"}],"
                + "\"bonus_wallets\":[{\"currency\":\"CNY\",\"balance\":\"0.005\"}],"
                + "\"total_costs\":[{\"currency\":\"CNY\",\"amount\":\"1.23\"}]}}}";
        try {
            BalanceClient.BalanceReading r =
                    BalanceClient.parseResponse(200, accountBody, null, CredentialStore.Mode.ACCOUNT);
            expect(Integer.valueOf(1001).equals(r.totalCents()), "钱包合计 10.01 → 1001 分");
            expect(r.spentCny() != null && Math.abs(r.spentCny() - 1.23) < 1e-9, "累计消费 1.23");
        } catch (Exception e) {
            expect(false, "账号响应应当解析成功：" + e);
        }

        boolean authDetected = false;
        try {
            BalanceClient.parseResponse(200, "{\"code\":40003}", null, CredentialStore.Mode.ACCOUNT);
        } catch (BalanceClient.FetchError.Auth e) {
            authDetected = true;
        } catch (Exception ignored) {
        }
        expect(authDetected, "code 40003 应识别为认证失败");

        boolean limited = false;
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("retry-after", "120");
            BalanceClient.parseResponse(429, "", headers, CredentialStore.Mode.API_KEY);
        } catch (BalanceClient.FetchError.RateLimited e) {
            limited = e.retryAfter != null && Math.abs(e.retryAfter - 120) < 1e-9;
        } catch (Exception ignored) {
        }
        expect(limited, "429 应读取 Retry-After: 120");

        boolean badJson = false;
        try {
            BalanceClient.parseResponse(200, "not json", null, CredentialStore.Mode.API_KEY);
        } catch (BalanceClient.FetchError.Parse e) {
            badJson = true;
        } catch (Exception ignored) {
        }
        expect(badJson, "非 JSON 响应应报解析错误");
    }

    private static void testRetryAfter() {
        Double seconds = BalanceClient.retryDelay("120", System.currentTimeMillis());
        expect(seconds != null && Math.abs(seconds - 120) < 1e-9, "Retry-After 秒数解析");
        long now = System.currentTimeMillis();
        Double date = BalanceClient.retryDelay("Wed, 21 Oct 2015 07:28:00 GMT", now);
        expect(date == null || date <= 86400, "Retry-After 日期不超过 24 小时上限");
        Double capped = BalanceClient.retryDelay("999999", now);
        expect(capped != null && capped == 86400, "Retry-After 超过上限按 86400 处理");
    }

    private static void testCredentials() {
        String payloadForm = "deepseek-account-platform/default:\n"
                + "  kind: account\n"
                + "  payload:\n"
                + "    version: 1\n"
                + "    token: abc.def-123\n"
                + "    issuer: https://platform.deepseek.com\n";
        String[] grant = new YamlMini(payloadForm).accountGrant();
        expect(grant != null && "abc.def-123".equals(grant[0])
                && "https://platform.deepseek.com".equals(grant[1]), "payload 形式的账号凭证");

        String legacyForm = "deepseek-account-platform/default:\n"
                + "  token: legacy-token\n"
                + "  issuer: https://api.example.com\n";
        String[] legacy = new YamlMini(legacyForm).accountGrant();
        expect(legacy != null && "legacy-token".equals(legacy[0]), "旧版直接字段形式");

        String keyForm = "DEEPSEEK_API_KEY: \"sk-abc123\"\n";
        expect("sk-abc123".equals(new YamlMini(keyForm).apiKey()), "YAML 中的 API Key");

        String dupKey = "DEEPSEEK_API_KEY: sk-a\nDEEPSEEK_API_KEY: sk-b\n";
        expect(new YamlMini(dupKey).apiKey() == null, "重复 Key 字段应拒绝");

        expect(CredentialStore.accountEndpoint("http://api.example.com", "/api/v0/users/get_user_summary")
                == null, "非 HTTPS issuer 应拒绝");
        expect(CredentialStore.accountEndpoint("https://api.example.com?a=1", "/x") == null,
                "带查询串的 issuer 应拒绝");
        expect(CredentialStore.accountEndpoint("https://api.example.com", "//evil") == null,
                "以 // 开头的路径应拒绝");
        expect(CredentialStore.isValidToken("sk-ok") && !CredentialStore.isValidToken("bad\nkey"),
                "凭证必须是单行可打印 ASCII");
    }

    private static void testStateClamp() {
        // 移植版放宽到 1 秒 ~ 10 分钟（原版 10~300 秒）
        PollSchedule s = new PollSchedule(1);
        expect(Math.abs(s.interval() - 1) < 1e-9, "轮询间隔下限 1 秒");
        PollSchedule s2 = new PollSchedule(9999);
        expect(Math.abs(s2.interval() - 600) < 1e-9, "轮询间隔上限 600 秒");
        PollSchedule s3 = new PollSchedule(45);
        expect(Math.abs(s3.interval() - 45) < 1e-9, "非整分钟的自定义间隔可保留");

        // 自定义尺寸钳制：下限 40dp，上限不超屏宽
        float wide = PetLayout.clampSideDp(5000f, 1080f, 3f);
        expect(wide <= PetLayout.MAX_SIDE_DP + 1e-3, "自定义尺寸不超硬上限");
        expect(wide * PetLayout.ASPECT <= 1080f / 3f + 1e-3, "自定义尺寸不超屏宽（1.5×side）");
        expect(Math.abs(PetLayout.clampSideDp(10f, 1080f, 3f) - PetLayout.MIN_SIDE_DP) < 1e-3,
                "自定义尺寸下限 40dp");
        expect(Math.abs(PetLayout.clampSideDp(150f, 1080f, 3f) - 150f) < 1e-3, "合法自定义尺寸原样保留");
    }

    /**
     * 上游 v1.3.1 的「蓝色大肥鱼未连接时显示抱盆图」决策（PetView.showsOfflineArtwork）。
     * 本移植版做成可关（关掉即 Windows 原版的平板图 + "--"），因此四种组合都要锁死。
     */
    private static void testOfflineArtDecision() {
        expect(PetService.shouldUseOfflineArt(PetCharacter.DEEPSEEK, false, true),
                "大肥鱼未连接 + 开关开 → 用抱盆图（v1.3.1 行为）");
        expect(!PetService.shouldUseOfflineArt(PetCharacter.DEEPSEEK, true, true),
                "大肥鱼已连接 → 用平板图");
        expect(!PetService.shouldUseOfflineArt(PetCharacter.DEEPSEEK, false, false),
                "开关关掉 → 未连接也用平板图 + \"--\"（Windows 原版行为）");
        expect(!PetService.shouldUseOfflineArt(PetCharacter.GPT, false, true),
                "其他角色未连接仍用自己的立绘（上游 ArtworkSelfTests 同款断言）");
        expect(!PetService.shouldUseOfflineArt(PetCharacter.GEMINI, false, true),
                "Gemini 未连接也不换图");
    }

    /**
     * v1.5.0：峰谷判定与显示样式。
     *
     * <p>下面这些期望值**不是猜的**：全部来自「把原插件 0.3.17 的 JS 峰谷源码原样抽出来跑」的实测输出
     * （`/root/work/pv/js.csv`，3003 个采样点，与本工程 `PeakValley` 逐行零差异）。
     * 任何一条挂掉都说明判定逻辑与上游脱钩了。
     */
    private static void testPeakValley() {
        // 1) 北京日历算术（纯算术，不依赖设备时区）
        expect("2026-08-20".equals(PeakValley.bjDateKey(1787184000L)), "bjDateKey(2026-08-20)");
        expect(PeakValley.bjDayOfWeek(1787184000L) == 4, "bjDayOfWeek(2026-08-20)=周四");
        expect(PeakValley.bjHour(1787184000L) == 8, "bjHour(2026-08-20 08:00)=8");
        expect("2026-08-22".equals(PeakValley.bjDateKey(1787364000L)), "bjDateKey(2026-08-22)");
        expect(PeakValley.bjDayOfWeek(1787364000L) == 6, "bjDayOfWeek(2026-08-22)=周六");
        expect(PeakValley.fromBeijing(2026, 8, 20, 8, 0, 0) == 1787184000L, "fromBeijing 往返一致");
        expect(PeakValley.WEEKEND_VALLEY_FROM_SEC == 1787414400L, "周末规则生效=北京 2026-08-23 00:00");
        expect(PeakValley.HOLIDAY_VALLEY_FROM_SEC == 1789747200L, "节假日规则生效=北京 2026-09-19 00:00");
        expect(PeakValley.HOLIDAY_VALLEY.length == 33, "2026 年节假日清单 33 条");

        // 2) 工作日边界（左闭右开）
        expect(!PeakValley.isPeak(1787184000L), "周四 08:00 = 空闲");
        expect(PeakValley.isPeak(1787187600L), "周四 09:00 = 高峰（左闭）");
        expect(PeakValley.isPeak(1787194800L), "周四 11:00 = 高峰");
        expect(!PeakValley.isPeak(1787198400L), "周四 12:00 = 空闲（右开）");
        expect(PeakValley.isPeak(1787205600L), "周四 14:00 = 高峰");
        expect(PeakValley.isPeak(1787216400L), "周四 17:00 = 高峰");
        expect(!PeakValley.isPeak(1787220000L), "周四 18:00 = 空闲（右开）");

        // 3) 周末全天谷（自 2026-08-23 起，之前的历史仍是旧规则）
        expect(PeakValley.isPeak(1787364000L), "周六 10:00 = 高峰（8/23 之前，规则尚未生效）");
        expect(!PeakValley.isPeak(1787446800L), "周日 09:00 = 空闲（周末规则生效首日）");
        expect(!PeakValley.isPeak(1787450400L), "周日 10:00 = 空闲");
        expect(!PeakValley.isPeak(1789869600L), "调休上班的周日（2026-09-20）仍按周末算空闲");

        // 4) 法定节假日全天谷（这几条能区分「节假日规则」与「单纯周末规则」）
        expect(!PeakValley.isHoliday(1789696800L), "2026-09-18 不是节假日");
        expect(PeakValley.isPeak(1789696800L), "2026-09-18 周五 10:00 = 高峰（假期前的普通工作日）");
        expect(PeakValley.isHoliday(1790906400L), "2026-10-02 是法定节假日（国庆）");
        expect(!PeakValley.isPeak(1790906400L), "2026-10-02 周五 10:00 = 空闲（节假日全天谷）");
        expect(PeakValley.isPeak(1791766800L), "2026-10-12 周一 09:00 = 高峰（节后首个工作日）");

        // 5) 下一个切换点与倒计时文案
        expect(PeakValley.nextChangeAt(1787184000L) == 1787187600L, "08:00 的下一切换点 = 09:00");
        expect(PeakValley.nextChangeAt(1787220000L) == 1787274000L, "周四 18:00 的下一切换点 = 周五 09:00");
        expect(PeakValley.nextChangeAt(1787184000L) > 1787184000L, "切换点必须在未来");
        expect("01:00:00".equals(PeakValley.countdownText(1787184000L)), "倒计时文案 = 01:00:00");
        expect(PeakValley.countdownText(1787184000L).matches("\\d{2}:\\d{2}:\\d{2}"),
                "倒计时格式 HH:MM:SS");

        // v1.5.1：倒计时显示格式可切换（用户选的 D 方案：做成选项而不是改死）
        expect("01:00:00".equals(PeakValley.countdownText(1787184000L, PeakValley.CD_SMART)),
                "智能格式：不足 24 小时仍是 HH:MM:SS");
        expect("01:00:00".equals(PeakValley.countdownText(1787184000L, PeakValley.CD_UPSTREAM)),
                "原样格式：不足 24 小时也是 HH:MM:SS");
        // 国庆假期 2026-10-02 10:00 → 下一切换点 10-08 09:00，共 143 小时
        expect("143:00:00".equals(PeakValley.countdownText(1790906400L, PeakValley.CD_UPSTREAM)),
                "原样格式：小时不折天 143:00:00（忠于上游）");
        expect("5天23:00:00".equals(PeakValley.countdownText(1790906400L, PeakValley.CD_SMART)),
                "智能格式：折成 5天23:00:00");
        expect(PeakValley.countdownText(1790906400L, PeakValley.CD_SMART_TARGET)
                        .contains("（至 10-08 09:00）"),
                "智能+目标时刻：标注「至 10-08 09:00」");
        expect(PeakValley.CD_FORMAT_NAMES.length == 3, "倒计时格式共 3 档");
        expect(PeakValley.clampCountdownFormat(99) == PeakValley.CD_SMART, "非法倒计时格式回落到智能");

        // 6) 5 种显示样式（文案照抄上游 BUBBLE_PEAK_STYLE_OPTS / bubblePeakText）
        expect(PeakValley.STYLE_NAMES.length == 5, "显示样式共 5 种");
        expect("高峰时段".equals(PeakValley.statusText(PeakValley.STYLE_DEFAULT, true)), "默认·峰");
        expect("空闲时段".equals(PeakValley.statusText(PeakValley.STYLE_DEFAULT, false)), "默认·谷");
        expect("梁文峰".equals(PeakValley.statusText(PeakValley.STYLE_LIANGWEN, true)), "梁文峰谷·峰");
        expect("梁文谷".equals(PeakValley.statusText(PeakValley.STYLE_LIANGWEN, false)), "梁文峰谷·谷");
        expect("!?峰峰?!".equals(PeakValley.statusText(PeakValley.STYLE_QIANGQIANG, true)), "!?强强?!·峰");
        expect("!?谷谷?!".equals(PeakValley.statusText(PeakValley.STYLE_QIANGQIANG, false)), "!?强强?!·谷");
        expect("峰".equals(PeakValley.statusText(PeakValley.STYLE_MINI, true)), "简洁·峰");
        expect("谷".equals(PeakValley.statusText(PeakValley.STYLE_MINI, false)), "简洁·谷");
        expect("我的高峰".equals(PeakValley.statusText(PeakValley.STYLE_DEFAULT, true,
                "我的高峰", "我的空闲")), "默认样式支持两态自定义文字");
        expect("梁文峰".equals(PeakValley.statusText(PeakValley.STYLE_LIANGWEN, true,
                "我的高峰", "我的空闲")), "非默认样式忽略自定义文字（忠于上游）");
        expect(PeakValley.isCountStyle(PeakValley.STYLE_COUNT), "倒计时样式由倒计时引擎输出");
        expect(!PeakValley.isCountStyle(PeakValley.STYLE_DEFAULT), "默认样式不是倒计时");
        expect(PeakValley.clampStyle(-1) == PeakValley.STYLE_DEFAULT, "非法样式回落到默认");
        expect(PeakValley.clampStyle(99) == PeakValley.STYLE_DEFAULT, "越界样式回落到默认");

        // 7b) v1.5.2：模块（字号档公式 / 行分组 / JSON 往返 / 峰谷配色）
        expect(PetBubbleModule.TYPE_NAMES.length == 7, "模块类型 7 种（v1.11.0 随机语句 + v1.12.0 今日已用）");
        expect(PetBubbleModule.fontU(1) == 40f, "字号档 1 → 40u");
        expect(PetBubbleModule.fontU(50) == 240f, "字号档 50 → 240u");
        expect(PetBubbleModule.fontU(0) == 40f, "字号档越界(0) 同档 1");
        expect(PetBubbleModule.fontU(999) == 240f, "字号档越界(999) 同档 50");
        expect(PetBubbleModule.fontU(7) == 64f, "字号档 7 → 64u（≈ 现有 label 66u）");
        expect(PetBubbleModule.fontU(23) == 130f, "字号档 23 → 130u（≈ 现有 amount 128u）");
        expect(PetBubbleModule.fontU(5) == 56f, "字号档 5 → 56u（= 现有 hint 56u）");

        expect(PetBubbleModule.parseHex("#e0433f") == 0xE0433F, "解析 #e0433f");
        expect(PetBubbleModule.parseHex("#abc") == 0xAABBCC, "解析短写 #abc → #AABBCC");
        expect(PetBubbleModule.parseHex("") == -1, "空色值 → -1（未设置）");
        expect(PetBubbleModule.parseHex("xyz") == -1, "非法色值 → -1");

        // 行分组：不带 row 的模块各自占一行；同 row 值合并成一行
        List<PetBubbleModule> mods = new ArrayList<>();
        PetBubbleModule a1 = new PetBubbleModule();
        a1.type = PetBubbleModule.TYPE_TEXT;
        a1.text = "第一行";
        mods.add(a1);
        PetBubbleModule a2 = new PetBubbleModule();
        a2.type = PetBubbleModule.TYPE_TEXT;
        a2.text = "独占行";
        mods.add(a2);
        PetBubbleModule b1 = new PetBubbleModule();
        b1.type = PetBubbleModule.TYPE_TEXT;
        b1.text = "同排左";
        b1.row = 2;
        mods.add(b1);
        PetBubbleModule b2 = new PetBubbleModule();
        b2.type = PetBubbleModule.TYPE_TEXT;
        b2.text = "同排右";
        b2.row = 2;
        mods.add(b2);
        List<List<PetBubbleModule>> grouped = PetBubbleModule.groupRows(mods);
        expect(grouped.size() == 3, "4 个模块 → 3 行（两个 row=2 的并排）");
        expect(grouped.get(2).size() == 2, "row=2 那行有 2 个模块并排");

        // 默认预设 = 经典四行，4 个模块、4 行
        List<PetBubbleModule> preset = PetBubbleModule.defaultPreset();
        expect(preset.size() == 4, "默认模块预设 4 个模块");
        expect(PetBubbleModule.groupRows(preset).size() == 4, "默认模块预设 4 行（每个独占一行）");

        // JSON 往返（用户可以直接粘上游 JSON）
        String json = PetBubbleModule.listToJson(preset);
        List<PetBubbleModule> back = PetBubbleModule.listFromJson(json);
        expect(back.size() == 4, "模块 JSON 往返：数量一致");
        expect(PetBubbleModule.TYPE_BALANCE.equals(back.get(1).type), "模块 JSON 往返：类型保留");
        expect(back.get(1).size == preset.get(1).size, "模块 JSON 往返：字号档保留");
        expect(back.get(1).bold == preset.get(1).bold, "模块 JSON 往返：加粗保留");
        expect(PetBubbleModule.listFromJson("{不是数组}").isEmpty(), "非法模块 JSON → 空列表（不抛异常）");

        // 上游旧别名 nextpeak ≡ peak + 倒计时样式
        List<PetBubbleModule> alias = PetBubbleModule.listFromJson(
                "[{\"type\":\"nextpeak\"}]");
        expect(alias.size() == 1 && PetBubbleModule.TYPE_PEAK.equals(alias.get(0).type),
                "nextpeak 归一化成 peak");
        expect(PeakValley.isCountStyle(alias.get(0).peakStyle), "nextpeak → 倒计时样式");

        // 峰谷模块的配色/文案随状态走
        PetBubbleModule peakMod = new PetBubbleModule();
        peakMod.type = PetBubbleModule.TYPE_PEAK;
        expect(peakMod.colorOr(0x123456, true) == 0xE0433F, "峰谷模块·高峰色 #e0433f");
        expect(peakMod.colorOr(0x123456, false) == 0x2FA24C, "峰谷模块·空闲色 #2fa24c");
        expect(peakMod.bgOr(true) == 0xFBE7E6, "峰谷模块·高峰底色 #fbe7e6");
        expect(peakMod.bgOr(false) == 0xE4F3E7, "峰谷模块·空闲底色 #e4f3e7");
        expect("{status}".equals(peakMod.defaultTemplate()), "默认样式峰谷模块默认模板 {status}");
        peakMod.peakStyle = PeakValley.STYLE_COUNT;
        expect("{countdown}".equals(peakMod.defaultTemplate()), "倒计时样式峰谷模块默认模板 {countdown}");
        peakMod.peakStyle = PeakValley.STYLE_DEFAULT;
        peakMod.tpl = "现在是{status}";
        expect("现在是{status}".equals(peakMod.defaultTemplate()), "tpl 覆盖内置模板");
        peakMod.tpl = "";

        // 模块内容走占位符解析
        PetBubble mb = new PetBubble("test", "L", "A", "H");
        mb.putToken("balance_ds", "10.62");
        mb.putToken("cost_ds", "3.21");
        PetBubbleModule balMod = new PetBubbleModule();
        balMod.type = PetBubbleModule.TYPE_BALANCE;
        expect("10.62".equals(balMod.contentOf(mb)), "余额模块取 {balance_ds}");
        PetBubbleModule costMod = new PetBubbleModule();
        costMod.type = PetBubbleModule.TYPE_COST;
        expect("3.21".equals(costMod.contentOf(mb)), "累计消费模块取 {cost_ds}");
        expect(!mb.hasModules(), "未装模块时 hasModules()=false（走经典四行）");
        mb.modules.add(balMod);
        expect(mb.hasModules(), "装了模块后 hasModules()=true");

        // 7c) v1.5.3：跑马灯配色（数据逐条抠自上游 CSS，不是估色）
        expect(PetBubbleGradients.size() == 17, "跑马灯配色 17 套（16 + macaron）");
        expect(PetBubbleGradients.isKnown("candy"), "已知方案 candy");
        expect(!PetBubbleGradients.isKnown("nosuch"), "未知方案 nosuch 不被认可");
        expect(PetBubbleGradients.resolve("CANDY").length == 11, "大小写不敏感，candy 11 色");
        expect(PetBubbleGradients.resolve("").length == 10, "空名 → macaron（10 色）");
        expect(PetBubbleGradients.resolve("true").length == 10, "true → macaron");
        expect(PetBubbleGradients.resolve("nosuch").length == 10, "未知名字 → macaron（忠于上游基类行为）");

        // 具体色值抽查（与上游 CSS 逐字对照）
        int[] candyColors = PetBubbleGradients.resolve("candy");
        expect(candyColors[0] == 0xFF91AA && candyColors[1] == 0xFFAA82
                && candyColors[10] == 0xFF91AA, "candy 首/次/尾色正确");
        int[] inkColors = PetBubbleGradients.resolve("ink");
        expect(inkColors[0] == 0x141414 && inkColors[4] == 0xFAFAFA, "ink 由黑到白");
        expect(PetBubbleGradients.resolve("bamboo")[0] == 0x46B455, "bamboo 首色 #46B455");
        expect(PetBubbleGradients.resolve("rouge")[0] == 0x8C192D, "rouge 首色 #8C192D");

        // 全部首尾同色 → 循环无缝（这是上游配色表的设计前提）
        boolean allSeamless = true;
        for (String n : PetBubbleGradients.names()) {
            if (!PetBubbleGradients.seamless(n)) allSeamless = false;
        }
        expect(allSeamless, "17 套配色全部首尾同色（循环处无接缝）");

        // 模块级取色
        PetBubbleModule gradMod = new PetBubbleModule();
        gradMod.type = PetBubbleModule.TYPE_TEXT;
        expect(!gradMod.marqueeActive(false), "未配 rgb 时 marqueeActive=false");
        gradMod.rgb = "candy";
        expect(gradMod.marqueeActive(false), "配了 rgb 后 marqueeActive=true");
        expect(gradMod.textSchemeColors(false) != null && gradMod.bgSchemeColors(false) == null,
                "文字有渐变、底色没有");
        gradMod.bgRgb = "rouge";
        expect(gradMod.bgSchemeColors(false) != null, "底色渐变生效");

        // 峰谷模块按峰/谷取不同的渐变
        PetBubbleModule gradPeak = new PetBubbleModule();
        gradPeak.type = PetBubbleModule.TYPE_PEAK;
        expect(!gradPeak.marqueeActive(true), "峰谷模块默认无跑马灯");
        gradPeak.peakRgb = "lava";
        gradPeak.offRgb = "mint";
        expect(gradPeak.textSchemeColors(true)[0] == 0xFF3C28, "峰谷模块·高峰用 lava 首色");
        expect(gradPeak.textSchemeColors(false)[0] == 0x78E6B4, "峰谷模块·空闲用 mint 首色");

        // 跑马灯时长不参与序列化（每次弹泡重新随机）
        gradPeak.marqueeDurMs = 3000;
        List<PetBubbleModule> gList = new ArrayList<>();
        gList.add(gradPeak);
        expect(!PetBubbleModule.listToJson(gList).contains("marqueeDurMs"),
                "跑马灯时长不写进 JSON");

        // 7d) v1.5.4：点击序列（权重 / 抽取 / JSON 往返 / 默认序列）
        expect(PetBubbleSeq.weightOf(new PetBubbleSeq.Option(0, null)) == 1, "权重 w=0 → 1");
        expect(PetBubbleSeq.weightOf(new PetBubbleSeq.Option(-5, null)) == 1, "权重负数 → 1");
        expect(PetBubbleSeq.weightOf(new PetBubbleSeq.Option(7, null)) == 7, "权重 w=7 → 7");
        expect(PetBubbleSeq.weightOf(null) == 1, "空选项权重 → 1");

        PetBubbleSeq.Item plain = new PetBubbleSeq.Item();
        plain.kind = PetBubbleSeq.KIND_CUSTOM;
        expect(PetBubbleSeq.pick(plain, new java.util.Random(1)) == plain, "非并列项抽取=它自己");
        expect(PetBubbleSeq.pick(null, new java.util.Random(1)) == null, "空项抽取 → null");

        // 并列按权重抽：2:1 在 3000 次里应接近 2:1（固定种子，结果稳定）
        PetBubbleSeq.Item choiceItem = new PetBubbleSeq.Item();
        choiceItem.kind = PetBubbleSeq.KIND_CHOICE;
        PetBubbleSeq.Item candA = new PetBubbleSeq.Item();
        PetBubbleSeq.Item candB = new PetBubbleSeq.Item();
        choiceItem.options.add(new PetBubbleSeq.Option(2, candA));
        choiceItem.options.add(new PetBubbleSeq.Option(1, candB));
        java.util.Random seqRnd = new java.util.Random(20261004L);
        int hitA = 0, hitB = 0;
        for (int i = 0; i < 3000; i++) {
            PetBubbleSeq.Item r = PetBubbleSeq.pick(choiceItem, seqRnd);
            if (r == candA) hitA++;
            else if (r == candB) hitB++;
        }
        expect(hitA + hitB == 3000, "并列抽取每次都有结果");
        double pickRatio = hitA / (double) Math.max(1, hitB);
        expect(pickRatio > 1.6 && pickRatio < 2.4,
                "权重 2:1 实测比例≈2（实测 " + String.format(Locale.US, "%.2f", pickRatio) + "）");

        // 默认序列结构
        PetBubbleSeq defSeq = PetBubbleSeq.defaultSeq();
        expect(defSeq.size() == 3, "默认序列 3 项（①余额 ②并列候选 ③随机文字）");
        expect(!defSeq.tapAdvance, "默认 tapAdvance=false（忠于上游默认）");
        expect(!defSeq.items.get(0).isChoice(), "默认第 1 项不是并列（余额页）");
        expect(defSeq.items.get(1).isChoice() && defSeq.items.get(1).options.size() == 2,
                "默认第 2 项是并列候选（2 选 1）");
        expect(PetBubbleSeq.weightOf(defSeq.items.get(1).options.get(0)) == 2
                        && PetBubbleSeq.weightOf(defSeq.items.get(1).options.get(1)) == 1,
                "默认并列候选的权重是 2:1");
        expect(!defSeq.items.get(2).isChoice(), "默认第 3 项不是并列（随机文字页）");

        // JSON 往返
        String seqJson = defSeq.toJson();
        PetBubbleSeq backSeq = PetBubbleSeq.fromJson(seqJson);
        expect(backSeq.size() == 3, "序列 JSON 往返：项数一致（默认 = 三次点击）");
        expect(PetBubbleSeq.KIND_NORMAL.equals(backSeq.items.get(0).kind),
                "默认序列：第 1 个是余额页（点桌宠）");
        expect(backSeq.items.get(1).isChoice() && backSeq.items.get(1).options.size() == 2,
                "默认序列：第 2 个是并列候选（JSON 往返保留候选）");
        expect(PetBubbleSeq.weightOf(backSeq.items.get(1).options.get(0)) == 2,
                "默认序列：候选权重往返保留（2:1）");
        expect(backSeq.items.get(1).options.get(0).item != null
                        && !backSeq.items.get(1).options.get(0).item.modules.isEmpty(),
                "默认序列：候选内容（峰谷倒计时页）往返保留");
        expect(backSeq.items.get(2).modules.size() == 1
                        && PetBubbleModule.TYPE_RANDOM.equals(backSeq.items.get(2).modules.get(0).type),
                "默认序列：第 3 个是随机语句页（用户要求第 3 次点击显示文字）");
        expect(backSeq.items.get(2).modules.get(0).lines.size() == 13,
                "默认序列：随机语句带全部 13 句上游语录（v1.12.0）");
        expect(PetBubbleSeq.fromJson("").items.isEmpty(), "空配置 items 为空（服务会回落默认序列）");
        expect(PetBubbleSeq.fromJson("{不是JSON}").parseFailed, "非法序列 JSON → parseFailed");

        // tapAdvance 改写（保留其它字段；非法 JSON 不写入）
        String toggled = PetBubbleSeq.withTapAdvance(seqJson, true);
        expect(PetBubbleSeq.tapAdvanceOf(toggled), "withTapAdvance 写入生效");
        expect(PetBubbleSeq.fromJson(toggled).size() == 3, "withTapAdvance 保留 items（默认 3 项）");
        expect(!PetBubbleSeq.tapAdvanceOf(PetBubbleSeq.withTapAdvance("{坏}", true)),
                "非法 JSON 时 withTapAdvance 不写入（原样返回）");

        // 上游 lib 字段按原类型保留（数组不能变成字符串）
        PetBubbleSeq libSeq = PetBubbleSeq.fromJson(
                "{\"v\":1,\"lib\":[{\"type\":\"text\"}],\"items\":[{\"kind\":\"normal\"}],\"tapAdvance\":false}");
        String libOut = libSeq.toJson();
        expect(libOut.contains("\"lib\":[{"), "上游 lib 数组按原类型保留（不是字符串）");

        // 上游旧 kind=random：能解析但渲染时回落默认页（本版未支持）
        PetBubbleSeq randSeq = PetBubbleSeq.fromJson("{\"items\":[{\"kind\":\"random\"}]}");
        expect(randSeq.size() == 1 && PetBubbleSeq.KIND_RANDOM.equals(randSeq.items.get(0).kind),
                "random 项能解析（渲染时回落默认页）");

        // 7e) v1.5.6：泡泡留存时间可配置（用户要求）
        expect(PetState.clampBubbleTtl(-5) == 0, "留存时间负值 → 0（常驻）");
        expect(PetState.clampBubbleTtl(0) == 0, "留存时间 0 → 常驻");
        expect(PetState.clampBubbleTtl(3000) == 3000, "留存时间 3000 原样保留");
        expect(PetState.clampBubbleTtl(999999) == PetState.BUBBLE_TTL_MAX, "超上限 → 60 秒");
        expect("3 秒".equals(PetState.bubbleTtlLabel(3000)), "标签 3000 → 3 秒");
        expect(PetState.bubbleTtlLabel(0).contains("常驻"), "标签 0 → 常驻");
        expect(PetState.bubbleTtlLabel(1500).contains("1.5"), "标签 1500 → 1.5 秒");
        expect(WhaleBubbleSpec.DEFAULT_TTL_MS == 5000,
                "默认留存已对齐上游 BUBBLE_MS=5000（原误写 6000）");
        expect(new PetState().bubbleTtlMs == 5000, "新建 state 默认留存 5000");

        // 7f) v1.5.7：编辑器的纯逻辑（增/删/移/工厂）—— 编辑器本身是 UI，无法在自检里点，
        // 但这几件「改了数据」的活全在这些纯函数里，必须挡住回归
        List<String> moves = new ArrayList<>();
        moves.add("a");
        moves.add("b");
        moves.add("c");
        expect(PetBubbleSeq.moveUp(moves, 1) && "b".equals(moves.get(0)), "moveUp 把第 2 个提到第 1");
        expect(PetBubbleSeq.moveDown(moves, 0) && "b".equals(moves.get(1)), "moveDown 移回原位");
        expect(!PetBubbleSeq.moveUp(moves, 0), "首个不能再上移");
        expect(!PetBubbleSeq.moveDown(moves, moves.size() - 1), "末个不能再下移");
        expect(!PetBubbleSeq.moveUp(moves, 99), "越界上移返回 false");
        expect("a".equals(PetBubbleSeq.removeAt(moves, 0)) && moves.size() == 2,
                "removeAt 删掉并返回该元素");
        expect("b".equals(moves.get(0)), "removeAt 后剩下的元素顺序不变");
        expect(PetBubbleSeq.removeAt(moves, 99) == null, "越界删除返回 null");

        PetBubbleSeq.Item newCustom = PetBubbleSeq.newItem(PetBubbleSeq.KIND_CUSTOM);
        expect(PetBubbleSeq.KIND_CUSTOM.equals(newCustom.kind) && newCustom.modules.size() == 1,
                "新增自定义项自带 1 个模块（不会是一片空白）");
        expect(PetBubbleSeq.newItem(PetBubbleSeq.KIND_CHOICE).options.size() == 2,
                "新增并列项自带 2 个候选");
        expect(PetBubbleSeq.KIND_NORMAL.equals(PetBubbleSeq.newItem(null).kind),
                "newItem(null) → 默认页");
        expect(PetBubbleSeq.newItem(PetBubbleSeq.KIND_NORMAL).modules.isEmpty(),
                "新增默认页不带模块");

        PetBubbleModule newText = PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT);
        expect(PetBubbleModule.TYPE_TEXT.equals(newText.type) && !newText.text.isEmpty(),
                "新增文字模块有默认文字");
        PetBubbleModule newBal = PetBubbleModule.newOf(PetBubbleModule.TYPE_BALANCE);
        expect(newBal.size == 23 && newBal.bold, "新增余额模块默认档 23 且加粗");
        expect(!PetBubbleModule.newOf(PetBubbleModule.TYPE_COST).color.isEmpty(),
                "新增消费模块自带颜色");
        expect(PetBubbleModule.newOf(null) != null, "newOf(null) 不崩");
        expect(PetBubbleModule.newOf(PetBubbleModule.TYPE_PEAK).type.equals(
                PetBubbleModule.TYPE_PEAK), "新增峰谷模块类型正确");

        // 7g) v1.5.9：修「改了显示样式但泡泡不跟着变」的 bug
        // 根因：峰谷模块把自己的 peakStyle 写死成「默认」，而渲染时模块样式优先→全局设置被无视
        expect(PetBubbleModule.STYLE_FOLLOW_GLOBAL == -1, "「跟随全局」哨兵值 = -1");
        PetBubbleModule followMod = new PetBubbleModule();
        followMod.type = PetBubbleModule.TYPE_PEAK;
        expect(followMod.peakStyle == PetBubbleModule.STYLE_FOLLOW_GLOBAL, "峰谷模块默认跟随全局");
        expect(followMod.effectivePeakStyle(PeakValley.STYLE_LIANGWEN) == PeakValley.STYLE_LIANGWEN,
                "跟随全局 → 用全局样式");
        expect("{status}".equals(followMod.defaultTemplate(PeakValley.STYLE_LIANGWEN)),
                "跟随全局 + 非倒计时样式 → 出 {status}");
        expect("{countdown}".equals(followMod.defaultTemplate(PeakValley.STYLE_COUNT)),
                "跟随全局 + 倒计时样式 → 出 {countdown}");
        followMod.peakStyle = PeakValley.STYLE_MINI;
        expect(followMod.effectivePeakStyle(PeakValley.STYLE_LIANGWEN) == PeakValley.STYLE_MINI,
                "钉死样式后忽略全局（保留逐模块能力）");
        expect(PetBubbleModule.clampPeakStyle(-5) == PetBubbleModule.STYLE_FOLLOW_GLOBAL,
                "clampPeakStyle(-5) → 跟随全局");
        expect(PetBubbleModule.clampPeakStyle(99) == PeakValley.STYLE_DEFAULT,
                "clampPeakStyle(99) → 默认样式");
        expect(PetBubbleModule.peakStyleLabel(-1).contains("跟随全局"), "样式标签 -1 → 跟随全局");

        // 默认模块预设里的峰谷模块必须跟随全局 —— 这正是用户遇到的场景
        PetBubbleModule presetPeak = null;
        for (PetBubbleModule pm : PetBubbleModule.defaultPreset()) {
            if (PetBubbleModule.TYPE_PEAK.equals(pm.type)) presetPeak = pm;
        }
        expect(presetPeak != null && presetPeak.peakStyle == PetBubbleModule.STYLE_FOLLOW_GLOBAL,
                "默认模块预设的峰谷模块跟随全局（否则改全局样式没反应）");

        // JSON 往返：钉死的写出、跟随全局不写，读回语义一致
        PetBubbleModule pinnedMod = PetBubbleModule.newOf(PetBubbleModule.TYPE_PEAK);
        pinnedMod.peakStyle = PeakValley.STYLE_QIANGQIANG;
        List<PetBubbleModule> pinList = new ArrayList<>();
        pinList.add(pinnedMod);
        expect(PetBubbleModule.listFromJson(PetBubbleModule.listToJson(pinList)).get(0).peakStyle
                        == PeakValley.STYLE_QIANGQIANG, "JSON 往返：钉死的样式保留");
        List<PetBubbleModule> followList = new ArrayList<>();
        followList.add(PetBubbleModule.newOf(PetBubbleModule.TYPE_PEAK));
        String followJson = PetBubbleModule.listToJson(followList);
        expect(!followJson.contains("peakStyle"), "JSON：跟随全局不写 peakStyle 键");
        expect(PetBubbleModule.listFromJson(followJson).get(0).peakStyle
                        == PetBubbleModule.STYLE_FOLLOW_GLOBAL, "JSON 往返：读回仍是跟随全局");

        // 7h) v1.5.10：桌宠贴顶时的三种策略（新增「显示在桌宠下方」）
        expect(PetState.clampBubbleTopMode(0) == PetState.BUBBLE_TOP_SHRINK, "topMode 0 → 等比缩小");
        expect(PetState.clampBubbleTopMode(1) == PetState.BUBBLE_TOP_HIDE, "topMode 1 → 不显示");
        expect(PetState.clampBubbleTopMode(2) == PetState.BUBBLE_TOP_BELOW, "topMode 2 → 显示在下方");
        expect(PetState.clampBubbleTopMode(99) == PetState.BUBBLE_TOP_SHRINK, "非法 topMode → 缩小");
        expect(PetState.clampBubbleTopMode(-3) == PetState.BUBBLE_TOP_SHRINK, "负数 topMode → 缩小");
        expect(PetState.bubbleTopModeLabel(PetState.BUBBLE_TOP_BELOW).contains("下方"),
                "标签 2 含「下方」");
        expect(PetState.bubbleTopModeLabel(PetState.BUBBLE_TOP_HIDE).contains("不显示"),
                "标签 1 含「不显示」");
        expect(PetState.bubbleTopModeLabel(PetState.BUBBLE_TOP_SHRINK).contains("缩小"),
                "标签 0 含「缩小」");

        // 7i) v1.5.11：模块底色块「出界」修复（裁剪到泡泡轮廓）
        expect(WhaleBubbleSpec.insideShapeEllipse(WhaleBubbleSpec.SHAPE_CX,
                        WhaleBubbleSpec.SHAPE_CY), "泡泡椭圆中心在内");
        expect(WhaleBubbleSpec.insideShapeEllipse(454f, 249f - WhaleBubbleSpec.SHAPE_RY),
                "椭圆正上方顶点在内（r=1 边界）");
        expect(!WhaleBubbleSpec.insideShapeEllipse(454f, 249f - WhaleBubbleSpec.SHAPE_RY - 5f),
                "再往上 5u 就在外了");
        // 这4条是这次 bug 的几何根源：文字框的下部左右角本来就在椭圆之外
        float boxLeft = (0.4425f - 0.33f) * WhaleBubbleSpec.VIEW_W;    // 11.25%
        float boxRight = (0.4425f + 0.33f) * WhaleBubbleSpec.VIEW_W;   // 77.25%
        float boxBottom = (0.36f + 0.32f) * WhaleBubbleSpec.VIEW_H;    // 68%
        expect(!WhaleBubbleSpec.insideShapeEllipse(boxLeft, boxBottom),
                "文字框左下角在椭圆外（所以要裁剪底色块）");
        expect(!WhaleBubbleSpec.insideShapeEllipse(boxRight, boxBottom),
                "文字框右下角在椭圆外");
        expect(WhaleBubbleSpec.insideShapeEllipse(454f, boxBottom),
                "但同一行的中间是在内的（所以只裁角，中间不受影响）");

        // 占位符（{status} / {countdown} / {balance_ds}）
        PetBubble b = new PetBubble("test", "{status}余额", "{balance_ds}", "剩 {countdown}");
        b.putToken("status", "梁文峰");
        b.putToken("countdown", "01:00:00");
        b.putToken("balance_ds", "12.34");
        expect("梁文峰余额".equals(b.labelText()), "占位符 {status}");
        expect("12.34".equals(b.amountText()), "占位符 {balance_ds}");
        expect("剩 01:00:00".equals(b.hintText()), "占位符 {countdown}");
        expect("{unknown}".equals(b.resolve("{unknown}")), "未知占位符原样保留（不静默抹掉）");
        expect("".equals(b.resolve(null)), "null 模板 → 空串");
        expect(b.resolve("没有占位符") == "没有占位符" || "没有占位符".equals(b.resolve("没有占位符")),
                "无占位符时原样返回");
        expect(!b.hasPeakLine(), "未设置峰谷行时 hasPeakLine()=false");
        b.peakText = "高峰时段";
        expect(b.hasPeakLine(), "设置峰谷行后 hasPeakLine()=true");
    }

    /**
     * v1.6.0：模块化编辑器（{@link PetBubbleEditModel}）的**纯逻辑回归**。
     *
     * <p>为什么这些必须在自检里：编辑器的手势我在真机上点不了（ColorOS 拦 am start、
     * 无法注入触摸），能证明「拖拽/并排/插入/上限」正确的只有这一层 —— 而编辑器界面调用的
     * 就是这一层（脚本通道 {@code ACTION_TEST_EDIT} 也是）。
     */
    private static void testEditModel(Context context) {
        // 1) 行分组：不带 row 的模块各自独占一行；同 row 的并排
        List<PetBubbleModule> mods = new ArrayList<>();
        PetBubbleModule a = PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT);
        a.text = "A";
        PetBubbleModule b = PetBubbleModule.newOf(PetBubbleModule.TYPE_BALANCE);
        b.row = 1;
        PetBubbleModule c = PetBubbleModule.newOf(PetBubbleModule.TYPE_COST);
        c.row = 1;
        PetBubbleModule d = PetBubbleModule.newOf(PetBubbleModule.TYPE_PEAK);
        mods.add(a);
        mods.add(b);
        mods.add(c);
        mods.add(d);
        List<List<PetBubbleModule>> rows = PetBubbleEditModel.rowsOf(mods);
        expect(rows.size() == 3, "编辑器：4 个模块（其中 2 个同 row）→ 3 行");
        expect(rows.get(1).size() == 2, "编辑器：同 row 的模块落在同一行");

        // 2) 保存 → 渲染端必须看到同样的行结构（否则「编辑器 3 行、泡泡 2 行」）
        List<PetBubbleModule> flat = PetBubbleEditModel.toModules(rows);
        expect(flat.get(0).row < 0, "编辑器：单模块行不写 row（保持 -1，JSON 干净）");
        expect(flat.get(1).row >= 0 && flat.get(1).row == flat.get(2).row,
                "编辑器：同一行的模块共享显式 row 号");
        expect(PetBubbleModule.groupRows(flat).size() == rows.size(),
                "编辑器：保存后的行数与渲染端一致");

        // 3) 上限：每行 6 个 / 每泡 6 行
        List<List<PetBubbleModule>> r = new ArrayList<>();
        expect(PetBubbleEditModel.addRow(r, PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT), -1) == 0,
                "编辑器：空列表加一行 → 下标 0");
        for (int i = 1; i < PetBubbleModule.MAX_ROWS; i++) {
            PetBubbleEditModel.addRow(r, PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT), -1);
        }
        expect(r.size() == PetBubbleModule.MAX_ROWS, "编辑器：能加到满 6 行");
        expect(PetBubbleEditModel.addRow(r, PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT), -1) == -1,
                "编辑器：第 7 行被拒绝（上游上限）");
        for (int i = 1; i < PetBubbleModule.MAX_PER_ROW; i++) {
            expect(PetBubbleEditModel.addToRow(r, 0, PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT)),
                    "编辑器：行内第 " + (i + 1) + " 个可以加");
        }
        expect(!PetBubbleEditModel.addToRow(r, 0, PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT)),
                "编辑器：行内第 7 个被拒绝（上游上限）");

        // 4) 删除模块：删空的行要消失
        List<List<PetBubbleModule>> r2 = PetBubbleEditModel.rowsFromJson(
                PetBubbleModule.listToJson(PetBubbleModule.defaultPreset()));
        int before = r2.size();
        PetBubbleEditModel.removeModule(r2, 0, 0);
        expect(r2.size() == before - 1, "编辑器：删掉某行唯一的模块 → 该行消失");

        // 5) 整行上下移动
        List<List<PetBubbleModule>> r3 = PetBubbleEditModel.rowsFromJson(
                PetBubbleModule.listToJson(PetBubbleModule.defaultPreset()));
        String s0 = PetBubbleEditModel.signature(r3);
        expect(PetBubbleEditModel.moveRow(r3, 0, 1), "编辑器：整行下移成功");
        expect(!PetBubbleEditModel.signature(r3).equals(s0), "编辑器：整行下移后结构确实变了");
        PetBubbleEditModel.moveRow(r3, 1, 0);
        expect(PetBubbleEditModel.signature(r3).equals(s0), "编辑器：再移回来 → 结构复原");

        // 6) 跨行并排（pairModule）
        List<List<PetBubbleModule>> r4 = PetBubbleEditModel.rowsFromJson(
                PetBubbleModule.listToJson(PetBubbleModule.defaultPreset()));
        int n0 = r4.size();
        expect(PetBubbleEditModel.pairModule(r4, 1, 0, 0, false),
                "编辑器：把第 2 行的模块并入第 1 行右侧");
        expect(r4.size() == n0 - 1 && r4.get(0).size() == 2,
                "编辑器：并排后行数 -1、该行 2 个模块");
        expect(PetBubbleModule.groupRows(PetBubbleEditModel.toModules(r4)).get(0).size() == 2,
                "编辑器：并排结果在渲染端也是「同一行 2 个」");
        expect(!PetBubbleEditModel.pairModule(r4, 0, 0, 0, true),
                "编辑器：同一行内的「并入」被拒绝（本来就在一起）");

        // 7) 落点四态
        float h = 100f;
        float w = 400f;
        expect(PetBubbleEditModel.dropZone(5f, h, 200f, w) == PetBubbleEditModel.DROP_BEFORE,
                "落点：行上边缘 → 插到这一行之前");
        expect(PetBubbleEditModel.dropZone(95f, h, 200f, w) == PetBubbleEditModel.DROP_AFTER,
                "落点：行下边缘 → 插到这一行之后");
        expect(PetBubbleEditModel.dropZone(50f, h, 50f, w) == PetBubbleEditModel.DROP_PAIR_LEFT,
                "落点：行内偏左 → 并入（左）");
        expect(PetBubbleEditModel.dropZone(50f, h, 350f, w) == PetBubbleEditModel.DROP_PAIR_RIGHT,
                "落点：行内偏右 → 并入（右）");

        // 8) applyDrop：真的动了才算动了
        List<List<PetBubbleModule>> r5 = PetBubbleEditModel.rowsFromJson(
                PetBubbleModule.listToJson(PetBubbleModule.defaultPreset()));
        String sA = PetBubbleEditModel.signature(r5);
        expect(PetBubbleEditModel.applyDrop(r5, true, 0, -1, 2, PetBubbleEditModel.DROP_AFTER),
                "拖放：整行拖到第 3 行之后 → 执行成功");
        expect(!PetBubbleEditModel.signature(r5).equals(sA), "拖放：整行拖放改变了结构");
        List<List<PetBubbleModule>> r6 = PetBubbleEditModel.rowsFromJson(
                PetBubbleModule.listToJson(PetBubbleModule.defaultPreset()));
        String sB = PetBubbleEditModel.signature(r6);
        expect(!PetBubbleEditModel.applyDrop(r6, true, 1, -1, 1, PetBubbleEditModel.DROP_BEFORE),
                "拖放：把一行放到它自己身上 → 不算移动");
        expect(PetBubbleEditModel.signature(r6).equals(sB),
                "拖放：无操作时结构签名不变（界面据此显示「没有生效」）");

        // 9) 文本命令（脚本通道 ACTION_TEST_EDIT 用的就是它）
        List<List<PetBubbleModule>> r7 = PetBubbleEditModel.rowsFromJson(
                PetBubbleModule.listToJson(PetBubbleModule.defaultPreset()));
        int base = r7.size();                       // 经典四行 = 4 行
        expect(PetBubbleEditModel.exec(r7, "pair:1,0>0,R"), "编辑命令：pair 执行成功");
        expect(r7.size() == base - 1 && r7.get(0).size() == 2,
                "编辑命令：pair 把第 2 行并入第 1 行 → 并排（行数 -1）");
        expect(PetBubbleEditModel.exec(r7, "new:cost:0"), "编辑命令：new 执行成功");
        expect(r7.size() == base, "编辑命令：new 之后行数回到 4");
        expect(PetBubbleModule.TYPE_COST.equals(r7.get(0).get(0).type),
                "编辑命令：new:cost:0 插到了最前面");
        expect(r7.get(1).size() == 2, "编辑命令：原来的并排行被挤到第 2 行（顺序正确）");
        int ok = PetBubbleEditModel.execAll(r7, "pair:2,0>3,R;new:cost:0");
        expect(ok == 2, "编辑命令：execAll 连续两条都成功（实际 " + ok + " 条）");
        expect(!PetBubbleEditModel.exec(r7, "rm:99,0"), "编辑命令：越界参数被拒绝（不抛异常）");
        expect(!PetBubbleEditModel.exec(r7, "bogus:1"), "编辑命令：未知命令被拒绝");

        // 10) 写回点击序列（含「空配置 → 内置默认序列」这一步）
        List<List<PetBubbleModule>> r8 = PetBubbleEditModel.rowsFromJson(
                PetBubbleModule.listToJson(PetBubbleModule.defaultPreset()));
        String next = PetBubbleEditModel.applySeqModules("", 0, r8);
        expect(next != null, "编辑器：空序列配置也能写回（先物化内置默认序列）");
        if (next != null) {
            PetBubbleSeq seq = PetBubbleSeq.fromJson(next);
            expect(seq.items.size() == PetBubbleSeq.defaultSeq().items.size(),
                    "编辑器：写回后序列项数不变（内置默认 2 项）");
            expect(PetBubbleSeq.KIND_CUSTOM.equals(seq.items.get(0).kind),
                    "编辑器：写回后第 1 项变成「自定义模块页」");
            expect(seq.items.get(0).modules.size() == 4, "编辑器：写回后第 1 项有 4 个模块");
            expect(PetBubbleEditModel.seqItemIsCustom(next, 0),
                    "编辑器：写回后 seqItemIsCustom 为真");
        }
        expect(PetBubbleEditModel.applySeqModules("[]", 0, r8) == null,
                "编辑器：序列 JSON 解析失败 → 返回 null（调用方不得覆盖用户配置）");
        expect(PetBubbleEditModel.applySeqModules("", 99, r8) == null,
                "编辑器：序列项下标越界 → 返回 null（配置不受损）");
        expect(PetBubbleEditModel.modulesOfSeqItem("", 0).isEmpty(),
                "编辑器：内置默认序列第 1 项本来没有模块（编辑器会回落到默认预设）");
        expect(!PetBubbleEditModel.seqItemIsCustom(PetBubbleSeq.defaultSeq().toJson(), 0),
                "编辑器：默认序列第 1 项不是自定义页（保存时会转类型并如实提示）");

        // 11) 预览用的静态模式（真建一个 View，确认「不播动画、不会自动收起」）
        if (context != null) {
            try {
                PetBubble bubble = new PetBubble("preview", "l", "a", "h");
                PetBubbleView v = new PetBubbleView(context, bubble,
                        PetBubbleStyle.from(PetState.load()), null);
                v.setStaticMode(true);
                expect(v.isStaticMode() && !v.opening() && !v.isClosing(),
                        "编辑器预览：静态模式 = 不播入场动画、不自动收起");
                v.startClose();
                expect(!v.isClosing(), "编辑器预览：静态模式下调 startClose 无效");
            } catch (Throwable t) {
                expect(false, "编辑器预览：静态模式构造失败 " + t);
            }
        }
    }

    /**
     * v1.7.0：用户点名的两项 —— ① 自定义泡泡大小 ② 模块字号/字体。
     *
     * <p>泡泡大小是**整体等比缩放**（画布宽 × 系数），因为泡泡里的一切都按 {@code unit = 宽/1026} 换算，
     * 所以字号、圆角、底色块内边距会一起缩放，不会出现「框变大了字没变大」。
     */
    private static void testBubbleScaleAndFont() {
        // 1) 泡泡大小：默认 100%、夹取 50–200%
        expect(PetState.clampBubbleScale(100) == 100, "泡泡大小：100% 原样");
        expect(PetState.clampBubbleScale(49) == 50, "泡泡大小：低于下限夹到 50");
        expect(PetState.clampBubbleScale(999) == 200, "泡泡大小：高于上限夹到 200");
        expect(PetState.clampBubbleScale(-10) == 50, "泡泡大小：负数夹到 50");
        PetState s = new PetState();
        expect(s.bubbleScalePercent == 100, "泡泡大小：新状态默认 100%");
        expect(Math.abs(s.bubbleScaleFactor() - 1f) < 1e-6f, "泡泡大小：默认系数 = 1.0");
        s.bubbleScalePercent = 150;
        expect(Math.abs(s.bubbleScaleFactor() - 1.5f) < 1e-6f, "泡泡大小：150% → 系数 1.5");
        s.bubbleScalePercent = 300;
        expect(Math.abs(s.bubbleScaleFactor() - 2.0f) < 1e-6f,
                "泡泡大小：超上限时系数仍是 2.0（不会失控）");
        expect(PetState.bubbleScaleLabel(100).contains("原版"), "泡泡大小：100% 标注「原版大小」");
        expect(PetState.bubbleScaleLabel(150).contains("更大"), "泡泡大小：150% 标注「更大」");
        expect(PetState.bubbleScaleLabel(50).contains("更小"), "泡泡大小：50% 标注「更小」");

        // 2) 字体族（上游同名字段 fontFamily）：默认空 + JSON 往返
        PetBubbleModule m = PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT);
        expect(m.fontFamily != null && m.fontFamily.isEmpty(),
                "字体：默认空 = 系统默认 sans-serif");
        m.fontFamily = "serif";
        m.size = 12;
        m.bold = true;
        List<PetBubbleModule> one = new ArrayList<>();
        one.add(m);
        PetBubbleModule back = PetBubbleModule.listFromJson(PetBubbleModule.listToJson(one)).get(0);
        expect("serif".equals(back.fontFamily), "字体：JSON 往返保留 fontFamily");
        expect(back.size == 12 && back.bold, "字体：字号与加粗同时保留");

        // 3) 字号档公式（滑块用的就是它）：边界
        expect(Math.round(PetBubbleModule.fontU(1)) == 40, "字号档：档 1 → 40u");
        expect(Math.round(PetBubbleModule.fontU(50)) == 240, "字号档：档 50 → 240u");
        expect(PetBubbleModule.fontU(0) == PetBubbleModule.fontU(1), "字号档：越界按档 1 夹取");
        expect(PetBubbleModule.fontU(99) == PetBubbleModule.fontU(50), "字号档：越界按档 50 夹取");
    }

    /**
     * v1.8.0：颜色槽位的「纯色 ↔ 跑马灯 互斥」（用户要求把「文字跑马灯」并进颜色里）。
     *
     * <p>合并的关键前提就是这条互斥规则 —— 否则一个槽位里既可能有纯色又可能有渐变，
     * 渲染端要猜、用户也会困惑。UI 只负责把选项列在一张表里，规则在这里。
     */
    private static void testColorSlots() {
        PetBubbleModule m = PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT);
        expect(m.plainOf(PetBubbleModule.SLOT_TEXT).isEmpty()
                        && m.schemeOf(PetBubbleModule.SLOT_TEXT).isEmpty(),
                "颜色槽：新模块文字槽是空的");

        m.setPlain(PetBubbleModule.SLOT_TEXT, "#E0433F");
        expect("#E0433F".equals(m.plainOf(PetBubbleModule.SLOT_TEXT)), "颜色槽：设纯色生效");
        m.setScheme(PetBubbleModule.SLOT_TEXT, "candy");
        expect("candy".equals(m.schemeOf(PetBubbleModule.SLOT_TEXT)), "颜色槽：设跑马灯生效");
        expect(m.plainOf(PetBubbleModule.SLOT_TEXT).isEmpty(),
                "颜色槽：设跑马灯会清掉同槽位的纯色（互斥）");
        m.setPlain(PetBubbleModule.SLOT_TEXT, "#203170");
        expect("".equals(m.schemeOf(PetBubbleModule.SLOT_TEXT)),
                "颜色槽：设纯色会清掉同槽位的跑马灯（互斥）");

        m.clearSlot(PetBubbleModule.SLOT_TEXT);
        expect(m.plainOf(PetBubbleModule.SLOT_TEXT).isEmpty()
                        && m.schemeOf(PetBubbleModule.SLOT_TEXT).isEmpty(),
                "颜色槽：清除后纯色与跑马灯都没了");

        // 6 个槽位互相独立（不能互相串）
        m.setPlain(PetBubbleModule.SLOT_BG, "#2FA24C");
        m.setScheme(PetBubbleModule.SLOT_PEAK_TEXT, "bamboo");
        m.setPlain(PetBubbleModule.SLOT_OFF_TEXT, "#FFFFFF");
        m.setPlain(PetBubbleModule.SLOT_PEAK_BG, "#123456");
        m.setScheme(PetBubbleModule.SLOT_OFF_BG, "rouge");
        expect(m.plainOf(PetBubbleModule.SLOT_TEXT).isEmpty(), "颜色槽：设其他槽位不影响文字槽");
        expect("#2FA24C".equals(m.plainOf(PetBubbleModule.SLOT_BG)), "颜色槽：底色槽生效");
        expect("bamboo".equals(m.schemeOf(PetBubbleModule.SLOT_PEAK_TEXT)), "颜色槽：峰-文字跑马灯生效");
        expect("#FFFFFF".equals(m.plainOf(PetBubbleModule.SLOT_OFF_TEXT)), "颜色槽：谷-文字生效");
        expect("#123456".equals(m.plainOf(PetBubbleModule.SLOT_PEAK_BG)), "颜色槽：峰-底色生效");
        expect("rouge".equals(m.schemeOf(PetBubbleModule.SLOT_OFF_BG)), "颜色槽：谷-底色跑马灯生效");
        expect(PetBubbleModule.slotLabel(PetBubbleModule.SLOT_PEAK_BG).contains("高峰"),
                "颜色槽：槽位有可读名（UI 行标题/日志用）");

        // 上游 JSON 兼容：直接用老字段写的模块读完仍能取到同一个值
        PetBubbleModule legacy = PetBubbleModule.fromJson(
                PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT).toJson());
        legacy.rgb = "candy";
        PetBubbleModule back = PetBubbleModule.fromJson(legacy.toJson());
        expect("candy".equals(back.schemeOf(PetBubbleModule.SLOT_TEXT)),
                "颜色槽：上游字段 rgb 读回仍是文字跑马灯");

        // 摘要文案
        PetBubbleModule s1 = PetBubbleModule.newOf(PetBubbleModule.TYPE_TEXT);
        expect(s1.slotSummary(PetBubbleModule.SLOT_TEXT).contains("未设"), "颜色槽：空值摘要显示未设");
        s1.setScheme(PetBubbleModule.SLOT_TEXT, "galaxy");
        expect(s1.slotSummary(PetBubbleModule.SLOT_TEXT).contains("galaxy"),
                "颜色槽：摘要显示跑马灯名");
        s1.setPlain(PetBubbleModule.SLOT_TEXT, "#AABBCC");
        expect(s1.slotSummary(PetBubbleModule.SLOT_TEXT).contains("#AABBCC"),
                "颜色槽：摘要显示纯色值");
    }

    /**
     * v1.9.0：① 跑马灯循环的「首尾闭合」；② 点击序列管理（并入编辑器）用到的纯逻辑。
     *
     * <p>跑马灯那条是**真 bug 的回归测试**：用户反馈「播完一轮卡一下再重播」。根因是着色器
     * 用 {@code TileMode.CLAMP}，相位 &gt; 0.5 后元素窗口越过渐变末端被压成纯末色（颜色不动了）。
     * 现在改为 REPEAT 平铺，所以配色必须**首尾闭合**，否则平铺接缝会出现一条硬边。
     */
    private static void testMarqueeAndSeq() {
        // ① 首尾闭合
        int[] closed = {0xFF0000, 0x00FF00, 0xFF0000};
        expect(PetBubbleGradients.loopClosed(closed) == closed,
                "跑马灯：首尾同色时原样返回（不产生新对象）");
        int[] open = {0xFF0000, 0x00FF00};
        int[] fixed = PetBubbleGradients.loopClosed(open);
        expect(fixed.length == 3 && fixed[2] == fixed[0],
                "跑马灯：首尾不同色时补一个首色（REPEAT 平铺才无缝）");
        expect(PetBubbleGradients.loopClosed(null) == null, "跑马灯：null 安全");
        // 内置 17 套配色必须全部首尾同色（否则循环处会有硬边扫过）
        String[] names = PetBubbleGradients.names();
        int bad = 0;
        for (String n : names) if (!PetBubbleGradients.seamless(n)) bad++;
        expect(bad == 0, "跑马灯：全部 " + names.length + " 套配色首尾同色（实际不合规 " + bad + "）");
        expect(names.length >= 16, "跑马灯：配色数量与上游一致（≥16 套）");

        // ② 序列项管理（编辑器里的「点击序列」用它）
        java.util.List<PetBubbleSeq.Item> items = new java.util.ArrayList<>();
        items.add(PetBubbleSeq.newItem(PetBubbleSeq.KIND_NORMAL));
        items.add(PetBubbleSeq.newItem(PetBubbleSeq.KIND_CUSTOM));
        items.add(PetBubbleSeq.newItem(PetBubbleSeq.KIND_CHOICE));
        expect(items.size() == 3, "序列管理：新增三项");
        expect(PetBubbleSeq.moveUp(items, 1), "序列管理：上移成功");
        expect(PetBubbleSeq.KIND_CUSTOM.equals(items.get(0).kind), "序列管理：上移后顺序正确");
        expect(!PetBubbleSeq.moveUp(items, 0), "序列管理：已在最前时上移返回 false");
        expect(PetBubbleSeq.moveDown(items, 0), "序列管理：下移成功");
        expect(PetBubbleSeq.KIND_CUSTOM.equals(items.get(1).kind), "序列管理：下移后顺序正确");
        expect(PetBubbleSeq.removeAt(items, 99) == null, "序列管理：越界删除返回 null");
        expect(items.size() == 3, "序列管理：越界删除没有改动列表");

        // ③ 「并列候选」项不允许被模块编辑器覆盖（编辑器里做的是只读保护，这里守住模型侧约束）
        PetBubbleSeq.Item choice = PetBubbleSeq.newItem(PetBubbleSeq.KIND_CHOICE);
        expect(choice.isChoice() && !choice.options.isEmpty(), "序列管理：并列候选默认带 2 个候选");
        expect(!PetBubbleSeq.KIND_CUSTOM.equals(choice.kind), "序列管理：并列候选不会被当成自定义页");
    }

    /**
     * v1.11.0：随机语句模块（上游 {@code type:"random"} + {@code lines:[{t,w,bold,size}]}）。
     *
     * <p>用户要求「显示文字在源码中也是有个权重的，可以让桌宠更加灵动嘛，你看看源码咋实现的」→
     * 这里把权重抽句、不连续重复、JSON 往返、逐句字号档都钉住。
     */
    private static void testRandomLines() {
        PetBubbleModule m = PetBubbleModule.newOf(PetBubbleModule.TYPE_RANDOM);
        expect(m.lines.size() == 13,
                        "随机语句：从调色板新建时带上游**全部 13 句**语录（实际 " + m.lines.size() + " 句）");
        expect(PetBubbleModule.presetLinesBig().size() == 3
                        && PetBubbleModule.presetLinesSmall().size() == 10,
                "随机语句：上游两组预设（大字组 3 句 / 小字组 10 句，逐条取自源码）");
        expect(PetBubbleModule.presetLinesBig().get(0).weight == 10,
                "随机语句：大字组权重 w10（取自源码）");

        // 抽取：等概率 + 不连续重复（w 字段保留但不参与抽取）
        PetBubbleModule r = new PetBubbleModule();
        r.type = PetBubbleModule.TYPE_RANDOM;
        r.lines.add(new PetBubbleModule.Line("A", 9));
        r.lines.add(new PetBubbleModule.Line("B", 1));
        java.util.Random rnd = new java.util.Random(20261005L);
        int a = 0, b = 0, repeat = 0;
        String last = null;
        for (int i = 0; i < 400; i++) {
            PetBubbleModule.Line ln = r.pickLine(rnd);
            expect(ln != null, "随机语句：抽句不为空");
            if ("A".equals(ln.text)) a++;
            else b++;
            if (ln.text.equals(last)) repeat++;
            last = ln.text;
        }
        // v1.12.0：抽取改为**等概率**（用户要求「不要权重了，概率都定成一样」）
                expect(a > 0 && b > 0, "随机语句：两句都会被抽到（A=" + a + " B=" + b + "）");
                expect(Math.abs(a - b) < 80,
                        "随机语句：**等概率**（w9:w1 也不偏袒 A：" + a + " vs " + b + "）");
                expect(repeat < 40, "随机语句：等概率下仍尽量不连续重复（" + repeat + "/399）");
        PetBubbleModule eq = new PetBubbleModule();
        eq.type = PetBubbleModule.TYPE_RANDOM;
        eq.lines.add(new PetBubbleModule.Line("X", 1));
        eq.lines.add(new PetBubbleModule.Line("Y", 1));
        java.util.Random rnd2 = new java.util.Random(42L);
        int eqRepeat = 0;
        String prev2 = null;
        for (int i = 0; i < 400; i++) {
            PetBubbleModule.Line ln = eq.pickLine(rnd2);
            if (ln.text.equals(prev2)) eqRepeat++;
            prev2 = ln.text;
        }
        expect(eqRepeat < 40, "随机语句：等权重下几乎不连续重复（" + eqRepeat + "/399）");

        // 逐句字号档：抽中带 size 的句子时 fontU() 以它为准
        PetBubbleModule r2 = new PetBubbleModule();
        r2.type = PetBubbleModule.TYPE_RANDOM;
        r2.size = 5;
        r2.lines.add(new PetBubbleModule.Line("大", 1, false, 22));
        r2.pickLine(new java.util.Random(1L));
        expect(Math.round(r2.fontU()) == Math.round(PetBubbleModule.fontU(22)),
                "随机语句：抽中句子的字号档覆盖模块档（上游 lines[].size）");

        // 内容解析：contentOf 返回抽中的那句
        PetBubble bubble = new PetBubble();
        bubble.pickRandom = new java.util.Random(7L);
        String txt = r.contentOf(bubble);
        expect("A".equals(txt) || "B".equals(txt), "随机语句：contentOf 返回候选里的一句（" + txt + "）");

        // JSON 往返（形状与上游一致：t/w/bold/size）
        PetBubbleModule r3 = new PetBubbleModule();
        r3.type = PetBubbleModule.TYPE_RANDOM;
        r3.size = 22;
        r3.lines.add(new PetBubbleModule.Line("好模型...↓", 10, true, 22));
        r3.lines.add(new PetBubbleModule.Line("没吃饱喵", 3, false, 9));
        java.util.List<PetBubbleModule> one = new java.util.ArrayList<>();
        one.add(r3);
        String json = PetBubbleModule.listToJson(one);
        expect(json.contains("\"lines\""), "随机语句：JSON 带 lines 数组");
        expect(json.contains("\"t\":\"好模型...↓\"") && json.contains("\"w\":10"),
                "随机语句：JSON 形状与上游一致（t/w）");
        PetBubbleModule back = PetBubbleModule.listFromJson(json).get(0);
        expect(back.lines.size() == 2, "随机语句：JSON 往返句数一致");
        expect("好模型...↓".equals(back.lines.get(0).text) && back.lines.get(0).weight == 10
                        && back.lines.get(0).bold && back.lines.get(0).size == 22,
                "随机语句：JSON 往返保留文字/权重/加粗/字号档");

        // 默认序列的第 3 项就是随机语句页（用户要求「第三次点击显示文字」）
        PetBubbleSeq seq = PetBubbleSeq.defaultSeq();
        expect(PetBubbleSeq.KIND_CUSTOM.equals(seq.items.get(2).kind)
                        && !seq.items.get(2).modules.isEmpty()
                        && PetBubbleModule.TYPE_RANDOM.equals(seq.items.get(2).modules.get(0).type),
                                        "默认序列：第 3 个（再点泡泡）= 随机语句页");
                                expect(seq.items.get(2).modules.get(0).lines.size() == 13,
                                        "默认序列：第 3 页带上游全部 13 句语录（实际 "
                                                + seq.items.get(2).modules.get(0).lines.size() + " 句）");
    }

    /**
     * v1.12.0：本地记账内核 {@code SpendLedger}。
     *
     * <p>为什么必须自己记：DeepSeek 余额接口（{@code /user/balance}）**没有**任何「今日已用/累计消费」
     * 字段（官方文档 schema 只有 is_available + balance_infos[{currency,total_balance,
     * granted_balance,topped_up_balance}]）→ 只能观测余额下降来记，与上游 accounting.mjs 同口径。
     */
    private static void testSpendLedger() {
        SpendLedger l = new SpendLedger();
        expect(!l.hasData(), "账本：新建时还没有基准");
        expect(l.observe(10000L, "2026-10-05"), "账本：第一次观测建立基准（算有变化、要落盘）");
        expect(l.todayFen == 0 && l.totalFen == 0, "账本：已有余额不计入消费（今日=累计=0）");
        expect("0.00".equals(l.todayYuan()), "账本：今日已用从 0.00 起");
        expect(l.observe(10000L - 1234L, "2026-10-05"), "账本：观测到消费（有变化）");
        expect(l.todayFen == 1234 && l.totalFen == 1234, "账本：降 12.34 → 今日=累计=12.34");
        l.observe(10000L - 1234L - 100L, "2026-10-05");
        expect(l.todayFen == 1334 && l.totalFen == 1334, "账本：再降 1.00 → 累加到 13.34");
        l.observe(10000L, "2026-10-05");
        expect(l.todayFen == 1334 && l.totalFen == 1334 && l.topupFen == 1334,
                "账本：余额上升 = 充值/赠金，不冲抵已有消费（累计仍 13.34，充值 13.34）");
        expect(!l.observe(10000L, "2026-10-05"), "账本：余额没变 → 不算变化（不写盘）");
        l.observe(9000L, "2026-10-06");
        expect(l.todayFen == 1000, "账本：跨日后「今日」从 0 起算（本次下降 10.00）");
        expect(l.totalFen == 2334 && l.topupFen == 1334, "账本：跨日保留累计与充值");
        expect(!l.observe(null, "2026-10-06"), "账本：本次没取到数 → 完全不动账本");
        SpendLedger back = SpendLedger.fromJson(l.toJson());
        expect(back.lastFen != null && l.lastFen.equals(back.lastFen)
                        && "2026-10-06".equals(back.dayKey)
                        && back.todayFen == l.todayFen && back.totalFen == l.totalFen
                        && back.topupFen == l.topupFen,
                "账本：JSON 往返保留基准/日期/今日/累计/充值");
        expect(!SpendLedger.fromJson("").hasData() && !SpendLedger.fromJson("{oops").hasData(),
                "账本：空串与坏 JSON 都退化成空账本（不抛异常）");
        SpendLedger s = new SpendLedger();
        expect(s.seedFromApi(5678L) && s.totalFen == 5678, "账本：接口给了 total_costs 时用它播种累计");
        expect(!s.seedFromApi(9999L) && s.totalFen == 5678, "账本：播种只生效一次");
        expect("12.34".equals(SpendLedger.yuan(1234L)), "账本：分 → 元（两位小数）");
    }
    /**
     * v1.12.1：界面文案的 {@code **强调**} 要渲染成**真加粗**，而不是把星号画在屏幕上。
     */
    private static void testRichText() {
        CharSequence plain = RichText.bold("没有标记的文案");
        expect("没有标记的文案".contentEquals(plain), "富文本：没有标记时原样返回");
        android.text.Spanned sp = (android.text.Spanned) RichText.bold("是**并列候选**：每轮抽一个");
        expect("是并列候选：每轮抽一个".contentEquals(sp), "富文本：** ** 标记被去掉、其它字符一个不少");
        android.text.style.StyleSpan[] spans =
                sp.getSpans(0, sp.length(), android.text.style.StyleSpan.class);
        expect(spans.length == 1 && spans[0].getStyle() == android.graphics.Typeface.BOLD,
                "富文本：** ** 之间的文字真的加粗了（span 数=" + spans.length + "）");
        expect(spans.length == 1 && sp.getSpanStart(spans[0]) == 1 && sp.getSpanEnd(spans[0]) == 5,
                "富文本：加粗范围只覆盖强调文字（1..5）");
        android.text.Spanned two = (android.text.Spanned) RichText.bold("**A**与**B**");
        expect("A与B".contentEquals(two)
                        && two.getSpans(0, two.length(), android.text.style.StyleSpan.class).length == 2,
                "富文本：一行里两段强调都能加粗");
        android.text.Spanned odd = (android.text.Spanned) RichText.bold("落单**的星号");
        expect("落单的星号".contentEquals(odd)
                        && odd.getSpans(0, odd.length(), android.text.style.StyleSpan.class).length == 0,
                "富文本：落单的 ** 只去掉标记、不加粗（不吞字符）");
    }
    /**
     * v1.12.6：泡泡画到桌宠**下方**时，里面的「↓」类箭头要变成「↑」类（方向要指着桌宠）。
     */
    /**
     * v1.12.6 / v1.12.8：泡泡画到桌宠**下方**时，只把**句首 / 句尾**的向下箭头换成向上（方向要指着桌宠）。
     */
    private static void testArrowFlip() {
        // —— 句尾（上游出厂语录就是这个形状）——
        expect("好模型...\u2191".equals(ArrowFlip.up("好模型...\u2193")), "箭头：句尾 ↓ → ↑（「好模型...↓」）");
        expect("好女孩...\u2191".equals(ArrowFlip.up("好女孩...\u2193")), "箭头：另一句预设同样翻转");
        expect("\u2191".equals(ArrowFlip.up("\u2193")), "箭头：整行只有一个箭头（既是句首也是句尾）");
        // —— 句首 ——
        expect("\u2191 余额".equals(ArrowFlip.up("\u2193 余额")), "箭头：句首 → 翻");
        expect("\u2191）。".equals(ArrowFlip.up("\u2193）。")), "箭头：句首 + 后面全是标点 → 翻");
        // —— 句中：不翻（这是 v1.12.8 的重点）——
        expect("看到\u2193了吗".equals(ArrowFlip.up("看到\u2193了吗")), "箭头：句中的不动（「看到↓了吗」）");
        expect("\u2191\u2193\u2191".equals(ArrowFlip.up("\u2191\u2193\u2191")), "箭头：夹在实词中间的不动");
        expect("\u2191\u2191".equals(ArrowFlip.up("\u2191\u2193")), "箭头：↑ 不动，句尾的 ↓ 才翻");
        expect("好模型\u2193呢".equals(ArrowFlip.up("好模型\u2193呢")), "箭头：后面还有字 → 不算句尾 → 不翻");
        // —— 空白与标点算「边缘」——
        expect("好模型...\u2191 ".equals(ArrowFlip.up("好模型...\u2193 ")), "箭头：句尾带空格仍算句尾");
        expect("(\u2191)".equals(ArrowFlip.up("(\u2193)")), "箭头：两侧只有括号 → 算句首");
        // —— 各种向下变体（逐个验）——
        String[] down = {"\u2193", "\u21E9", "\u2B07", "\u25BC", "\u25BE", "\u02C5", "\uFE40", "\u2304"};
        String[] upCh = {"\u2191", "\u21E7", "\u2B06", "\u25B2", "\u25B4", "\u02C4", "\uFE3F", "\u2303"};
        for (int i = 0; i < down.length; i++) {
            expect(upCh[i].equals(ArrowFlip.up(down[i])), "箭头：变体 " + down[i] + " → " + upCh[i]);
        }
        expect("\u2B06\uFE0F".equals(ArrowFlip.up("\u2B07\uFE0F")), "箭头：带变体选择符的 emoji ⬇️ → ⬆️");
        expect("\uD83D\uDD3C".equals(ArrowFlip.up("\uD83D\uDD3D")), "箭头：代理对 emoji 🔽 → 🔼");
        expect("看\uD83D\uDD3D呢".equals(ArrowFlip.up("看\uD83D\uDD3D呢")), "箭头：句中的 🔽 不动");
        // —— 多行：逐行各自判定 ——
        expect("上面\u2191\n下面\u2191".equals(ArrowFlip.up("上面\u2193\n下面\u2193")), "箭头：逐行判定（两行都翻）");
        expect("\u2191到\u2193中间".equals(ArrowFlip.up("\u2193到\u2193中间")), "箭头：句首翻、句中的不翻");
        // —— 边界 ——
        expect(ArrowFlip.up(null) == null && "".equals(ArrowFlip.up("")), "箭头：null/空串安全");
        expect("\u2191".equals(ArrowFlip.up(ArrowFlip.up("\u2193"))), "箭头：幂等（↑ 再翻还是 ↑）");
        expect("没有箭头的一句话".equals(ArrowFlip.up("没有箭头的一句话")), "箭头：没有箭头时一字不改");
        // —— 与 resolve() 的接线 ——
        PetBubble b = new PetBubble();
        b.putToken("t", "1.23");
        expect("\u21931.23".equals(b.resolve("\u2193{t}")), "箭头：默认不动（泡泡在桌宠上方）");
        b.arrowsUp = true;
        expect("\u21911.23".equals(b.resolve("\u2193{t}")), "箭头：画在下方时 resolve() 自动翻转（含占位符）");
        expect("看\u2193吗1.23".equals(b.resolve("看\u2193吗{t}")), "箭头：句中箭头在 resolve() 后也不翻");
        PetBubbleModule rm = PetBubbleModule.newOf(PetBubbleModule.TYPE_RANDOM);
        rm.lines.clear();
        rm.lines.add(new PetBubbleModule.Line("好模型...\u2193", 10));
        PetBubble mb = new PetBubble();
        mb.modules.add(rm);
        mb.arrowsUp = true;
        expect("好模型...\u2191".equals(rm.contentOf(mb)), "箭头：置位后随机语句模块给的就是 ↑");
        mb.arrowsUp = false;
        expect("好模型...\u2193".equals(rm.contentOf(mb)), "箭头：不置位时仍是 ↓（只影响显示，不改文字）");
    }

    /**
     * v1.12.8（开源整理）：**模块类型的「两份清单」必须同步**。
     *
     * <p>背景：{@code PetBubbleModule.TYPE_NAMES}（引擎/自检用）与编辑器里的 {@code PALETTE}（UI 里能加哪些）
     * 曾经是两份手写清单 —— v1.12.0 加「今日已用」时只改了前者，结果 UI 里根本加不出来（用户截图才发现）。
     * 这条断言把这个不变量钉住：只要两边不一致，自检立刻失败。
     */
    private static void testModuleTypeListConsistency() {
        String[][] palette = PetBubbleEditorActivity.PALETTE;
        expect(palette != null && palette.length > 0, "类型清单：编辑器可加清单非空");
        for (String[] entry : palette) {
            String label = entry[0];
            String id = entry[1];
            String base = id.contains("-") ? id.substring(0, id.indexOf('-')) : id;
            expect(PetBubbleModule.isKnownType(base),
                    "类型清单：可加项「" + label + "」（" + id + "）是引擎认识的类型");
        }
        for (String t : PetBubbleModule.TYPE_NAMES) {
            boolean found = false;
            for (String[] entry : palette) {
                String id = entry[1];
                String base = id.contains("-") ? id.substring(0, id.indexOf('-')) : id;
                if (base.equals(t)) {
                    found = true;
                    break;
                }
            }
            expect(found, "类型清单：引擎类型「" + t + "」在编辑器里能加出来（防再漏）");
        }
    }

    /**
         * v1.13.0：角色注册表（内置 + 自定义）。
         *
         * <p>不碰文件系统：自定义角色的“图片不存在则跳过/回退”正好在这里被钉住。
         */
        private static void testCharacterRegistry() {
            PetState st = new PetState();
            expect(PetCharacters.current(st).id.equals("deepseek"), "角色：默认是内置蓝色大肥鱼");
            expect(PetCharacters.current(st).hasTablet(), "角色：内置角色手持平板（显示余额）");
            PetCharacters.Current whale = PetCharacters.builtIn(PetCharacter.WHALE);
            expect(!whale.hasTablet() && whale.tabletCorners() == null,
                    "角色：Whale 小鲸鱼是纯形象（不显示余额、没有平板坐标）");
            expect(PetCharacters.all(st).size() >= 5, "角色：内置清单至少 5 个（含 Whale）");
            // 自定义角色：图片不存在 → 不进列表、也不会被选中
            st.customCharactersJson = "[{\"id\":\"cx\",\"name\":\"我的角色\","
                    + "\"file\":\"/nonexistent/nope.png\"}]";
            expect(PetCharacters.customs(st).isEmpty(), "角色：图片已不在的自定义角色会被跳过");
            PetCharacters.selectCustom(st, "cx");
            expect(PetCharacters.current(st).id.equals("deepseek"),
                    "角色：自定义角色失效时自动回落到内置角色");
            st.customCharactersJson = "[]";
            PetCharacters.selectBuiltIn(st, PetCharacter.WHALE);
            expect(PetCharacters.current(st).id.equals("whale"), "角色：能选中 Whale 小鲸鱼");
            expect(st.customCharacterId.isEmpty(), "角色：选内置角色会清掉自定义选择");
            java.io.File fake = new java.io.File(PetPaths.supportDir(), "characters/zzz-selftest.png");
            if (fake.getParentFile() != null) fake.getParentFile().mkdirs();
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(fake);
                fos.write(new byte[]{1, 2, 3});
                fos.close();
                String id = PetCharacters.addCustom(st, "test-id", "测试角色", fake);
                expect(PetCharacters.customs(st).size() == 1, "角色：能登记自定义角色");
                expect(PetCharacters.renameCustom(st, id, "新名字")
                                && PetCharacters.customs(st).get(0).name.equals("新名字"),
                        "角色：自定义角色能改名");
                expect(PetCharacters.selectCustom(st, id)
                                && PetCharacters.current(st).id.equals(id)
                                && PetCharacters.current(st).isCustom(),
                        "角色：能选中自定义角色（且识别为自定义）");
                expect(!PetCharacters.current(st).hasTablet(),
                        "角色：自定义角色不显示余额（没有平板）");
                expect(PetCharacters.removeCustom(st, id),
                        "角色：能删除自定义角色");
                expect(!fake.exists(), "角色：删除自定义角色会连同图片一起删掉");
                expect(PetCharacters.current(st).id.equals("deepseek"),
                        "角色：删掉正在用的自定义角色 → 回到蓝色大肥鱼");
                st.customCharactersJson = "{不是JSON}";
                expect(PetCharacters.customs(st).isEmpty(), "角色：坏 JSON 退化成空列表（不抛）");
            } catch (Exception e) {
                expect(false, "角色：自定义角色自检抛异常 " + e);
            } finally {
                fake.delete();
            }
        }
    /**
         * v1.14.0：备份与恢复（真打 zip / 真恢复，但不碰真实配置 —— 全在临时目录里）。
         */
        private static void testBackupRoundTrip() {
            java.io.File tmp = new java.io.File(PetPaths.supportDir(), "backup-selftest");
            java.io.File src = new java.io.File(tmp, "src");
            java.io.File dst = new java.io.File(tmp, "dst");
            deleteTree(tmp);
            expect(src.mkdirs() && dst.mkdirs(), "备份：自检用临时目录能建出来");
            try {
                writeText(new java.io.File(src, "state.json"), "{\"character\":\"whale\"}");
                java.io.File chars = new java.io.File(src, "characters");
                expect(chars.mkdirs(), "备份：角色目录能建出来");
                writeBytes(new java.io.File(chars, "c1.png"), new byte[]{9, 9, 9});
                // —— 备份 ——
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                BackupManager.Manifest m = BackupManager.write(buf, new java.io.File(src, "state.json"),
                        new java.io.File(src, "ledger.json"), chars, null, false, "1.14.0-selftest");
                expect(m.schema == BackupManager.SCHEMA, "备份：元信息写了 schema");
                expect(m.characterCount == 1 && !m.hasLedger && !m.hasCredentials,
                        "备份：元信息如实统计（1 个角色 / 无账本 / 无密钥）");
                // —— 验元信息 ——
                BackupManager.Manifest back = BackupManager.inspect(
                        new java.io.ByteArrayInputStream(buf.toByteArray()));
                expect(back.schema == BackupManager.SCHEMA && back.characterCount == 1,
                        "备份：inspect 能读回元信息");
                expect("1.14.0-selftest".equals(back.appVersion), "备份：元信息带上了来源版本");
                // —— 恢复（目标里先放一份旧配置，验证安全副本）——
                writeText(new java.io.File(dst, "state.json"), "{\"character\":\"deepseek\"}");
                BackupManager.RestoreResult r = BackupManager.restore(
                        new java.io.ByteArrayInputStream(buf.toByteArray()),
                        new java.io.File(dst, "state.json"), new java.io.File(dst, "ledger.json"),
                        new java.io.File(dst, "characters"), dst, false);
                expect(readText(new java.io.File(dst, "state.json")).contains("whale"),
                        "备份：恢复后 state.json 被换成备份里的内容");
                expect(new java.io.File(dst, "characters/c1.png").isFile(),
                        "备份：恢复后自定义角色图片回来了");
                expect(r.safetyCopy != null && r.safetyCopy.isFile()
                                && readText(r.safetyCopy).contains("deepseek"),
                        "备份：恢复前把旧配置另存成了安全副本");
                // —— 不该被当成备份的东西必须报错（不能静默把配置洗掉）——
                java.io.ByteArrayOutputStream junk = new java.io.ByteArrayOutputStream();
                java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(junk);
                zip.putNextEntry(new java.util.zip.ZipEntry("readme.txt"));
                zip.write("not a backup".getBytes("UTF-8"));
                zip.closeEntry();
                zip.close();
                boolean rejected = false;
                try {
                    BackupManager.inspect(new java.io.ByteArrayInputStream(junk.toByteArray()));
                } catch (BackupManager.BackupError e) {
                    rejected = true;
                }
                expect(rejected, "备份：缺元信息的 zip 会被拒绝（不会当成空备份）");
                boolean newerRejected = false;
                try {
                    BackupManager.Manifest.fromJson("{\"schema\":" + (BackupManager.SCHEMA + 1) + "}");
                } catch (BackupManager.BackupError e) {
                    newerRejected = true;
                }
                expect(newerRejected, "备份：来自更新版本的备份会被拒绝");
                boolean badJson = false;
                try {
                    BackupManager.Manifest.fromJson("{不是JSON}");
                } catch (BackupManager.BackupError e) {
                    badJson = true;
                }
                expect(badJson, "备份：元信息不是 JSON 会报错（不抛到调用方）");
            } catch (Exception e) {
                expect(false, "备份：自检抛异常 " + e);
            } finally {
                deleteTree(tmp);
            }
        }
        private static void writeText(java.io.File f, String s) throws Exception {
            writeBytes(f, s.getBytes("UTF-8"));
        }
        private static void writeBytes(java.io.File f, byte[] data) throws Exception {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            try { out.write(data); } finally { out.close(); }
        }
        private static String readText(java.io.File f) throws Exception {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[(int) Math.max(1, f.length())];
                int n = in.read(buf);
                return new String(buf, 0, Math.max(0, n), "UTF-8");
            } finally { in.close(); }
        }
        private static void deleteTree(java.io.File f) {
            if (f == null || !f.exists()) return;
            if (f.isDirectory()) {
                java.io.File[] kids = f.listFiles();
                if (kids != null) for (java.io.File k : kids) deleteTree(k);
            }
            f.delete();
        }
    private static void testArtwork(Context context) {
        // 自检会加载全部立绘：先记录现有缓存，结束后只释放自检新增的部分，
        // 避免（a）把正在显示的位图回收掉，(b) 留下 30MB 位图不释放。
        java.util.Set<String> before = PetAssets.cachedKeys();
        try {
            PetCharacter[] characters = PetCharacter.values();
        for (PetCharacter character : characters) {
            PetAssets.Artwork art = PetAssets.get(character.assetName, false);
            expect(art != null, character.displayName + " 立绘可解码");
            if (art == null) continue;
            expect(art.rects.length >= 8, character.displayName + " 穿透矩形数量合理");
            boolean inside = true;
            for (int i = 0; i + 3 < art.rects.length; i += 4) {
                if (art.rects[i] < 0 || art.rects[i + 1] < 0
                        || art.rects[i + 2] > 1.0001f || art.rects[i + 3] > 1.0001f
                        || art.rects[i + 2] <= art.rects[i] || art.rects[i + 3] <= art.rects[i + 1]) {
                    inside = false;
                }
            }
            expect(inside, character.displayName + " 矩形都在 0…1 内且非空");

            // 平板安全区必须落在不透明区域内，否则余额文字会被裁掉
            if (!character.hasTablet()) continue;   // v1.13.0：纯形象角色没有平板，跳过平板安全区检查
                        float[] c = character.tabletCorners();
            float tlx = c[0], tly = c[1], trx = c[2], try_ = c[3], blx = c[4], bly = c[5];
            int samples = 0, hits = 0;
            for (int iu = 1; iu <= 5; iu++) {
                for (int iw = 1; iw <= 5; iw++) {
                    float u = PetLayout.PANEL_W * iu / 6f;
                    float w = PetLayout.PANEL_H * iw / 6f;
                    float ix = tlx + (trx - tlx) / PetLayout.PANEL_W * u
                            - (tlx - blx) / PetLayout.PANEL_H * w;
                    float iy = tly + (try_ - tly) / PetLayout.PANEL_W * u
                            + (bly - tly) / PetLayout.PANEL_H * w;
                    float nx = ix / PetLayout.ART_W;
                    float ny = iy / PetLayout.ART_H;
                    samples++;
                    for (int i = 0; i + 3 < art.rects.length; i += 4) {
                        if (nx >= art.rects[i] && nx <= art.rects[i + 2]
                                && ny >= art.rects[i + 1] && ny <= art.rects[i + 3]) {
                            hits++;
                            break;
                        }
                    }
                }
            }
            expect(hits >= samples - 2, character.displayName + " 平板安全区被穿透矩形覆盖（"
                    + hits + "/" + samples + "）");
        }
        PetAssets.Artwork offline = PetAssets.get(PetCharacter.OFFLINE_ASSET, true);
        expect(offline != null && offline.offlineArt, "抱盆图可解码且标记为离线素材");
        } finally {
            PetAssets.releaseExcept(before);
        }
    }

    /**
     * v1.14.3：检查「关于 · 更新记录」用的素材。
     *
     * <p>CHANGELOG.md 由 build.sh 在构建时复制进 assets/，所以这条断言同时验证了两件事：
     * 素材确实进了 APK，而且它里面有当前版本那一条（防止「App 里的更新记录忘了跟着版本更新」）。
     */
    private static void testChangelogAsset(Context context) {
        String text = readAsset(context, "CHANGELOG.md");
        expect(text != null && text.length() > 500, "更新记录素材存在于 APK 且非空");
        if (text == null) return;
        expect(text.startsWith("# 更新记录"), "更新记录素材以标题开头");
        String ver = MainActivity.VERSION.endsWith("-android")
                ? MainActivity.VERSION.substring(0, MainActivity.VERSION.length() - "-android".length())
                : MainActivity.VERSION;
        expect(text.contains("### v" + ver), "更新记录里有当前版本 v" + ver);
        int entries = 0;
        int idx = text.indexOf("### v");
        while (idx >= 0) {
            entries++;
            idx = text.indexOf("### v", idx + 5);
        }
        expect(entries >= 10, "更新记录条目数合理（实际 " + entries + " 条）");
    }

    /** 读 assets 里的文本文件；读不到返回 null（调用方自己判断，不假装成功）。 */
    private static String readAsset(Context context, String name) {
        java.io.InputStream in = null;
        try {
            in = context.getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
        }
    }
}