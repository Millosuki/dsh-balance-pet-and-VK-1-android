package com.dsh.balancepet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * 余额查询与严格响应解析。
 * 移植自 macOS 版 BalanceClient.swift（含其代码审查中修掉的所有坑：
 * 非 CNY 不冒充人民币、拒绝重定向、小数定点解析、429 退避时间解析）。
 */
public final class BalanceClient {

    private static final int MAX_BODY = 1024 * 1024;
    private static final Pattern NUMERIC =
            Pattern.compile("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$");

    private BalanceClient() {}

    // ---- 读数 ----

    public static final class BalanceReading {
        private final BigDecimal normal;
        private final BigDecimal bonus;
        private final BigDecimal spent;
        public final String raw;

        BalanceReading(BigDecimal normal, BigDecimal bonus, BigDecimal spent, String raw) {
            this.normal = normal;
            this.bonus = bonus;
            this.spent = spent;
            this.raw = raw;
        }

        public Double normalCny() { return normal == null ? null : normal.doubleValue(); }
        public Double bonusCny() { return bonus == null ? null : bonus.doubleValue(); }
        public Double spentCny() { return spent == null ? null : spent.doubleValue(); }

        private BigDecimal total() {
            if (normal == null || bonus == null) return null;
            return normal.add(bonus);
        }

        /** 十进制合计后再四舍五入到「分」，并以整数分返回；越界返回 null。 */
        public Integer totalCents() {
            BigDecimal amount = total();
            if (amount == null) return null;
            BigDecimal scaled;
            try {
                scaled = amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
            } catch (ArithmeticException e) {
                return null;
            }
            if (scaled.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                    || scaled.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                return null;
            }
            return scaled.intValue();
        }
    }

    // ---- 错误 ----

    public abstract static class FetchError extends Exception {
        FetchError(String message) { super(message); }

        public abstract String describe();

        public static final class Auth extends FetchError {
            public Auth(String detail) { super(detail); }
            @Override public String describe() {
                return "认证失败（" + getMessage() + "）——请检查 API Key / 账号是否过期";
            }
        }

        public static final class RateLimited extends FetchError {
            public final Double retryAfter;
            public RateLimited(Double retryAfter) { super("429"); this.retryAfter = retryAfter; }
            @Override public String describe() {
                String suffix = (retryAfter != null && Double.isFinite(retryAfter) && retryAfter >= 0)
                        ? String.format(Locale.US, "，%.0fs 后重试", retryAfter) : "";
                return "请求过于频繁（429）" + suffix;
            }
        }

        public static final class Http extends FetchError {
            private final int code;
            private final String message;
            public Http(int code, String message) { super(String.valueOf(code)); this.code = code; this.message = message; }
            @Override public String describe() {
                return "HTTP " + code + (message == null || message.isEmpty() ? "" : " · " + message);
            }
        }

        public static final class Transport extends FetchError {
            public Transport(String detail) { super(detail); }
            @Override public String describe() { return "网络错误：" + getMessage(); }
        }

        public static final class Parse extends FetchError {
            public Parse(String detail) { super(detail); }
            @Override public String describe() { return "响应无法解析：" + getMessage(); }
        }
    }

    // ---- 请求 ----

    public static BalanceReading fetch(CredentialStore.Credential cred, int timeoutSeconds)
            throws FetchError {
        if (cred == null) throw new FetchError.Transport("没有可用凭证");
        if (!CredentialStore.isSafeEndpoint(cred.endpoint) || !CredentialStore.isValidToken(cred.token)) {
            throw new FetchError.Auth("凭证或 HTTPS 地址无效");
        }
        int timeout = Math.min(120, Math.max(1, timeoutSeconds));
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(cred.endpoint).openConnection();
            conn.setInstanceFollowRedirects(false);   // 绝不把认证头带去别的地址
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeout * 1000);
            conn.setReadTimeout(timeout * 1000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "DSHBalancePet/1.0 (Android)");
            if (cred.mode == CredentialStore.Mode.API_KEY) {
                conn.setRequestProperty("Authorization", "Bearer " + cred.token);
            } else {
                conn.setRequestProperty("x-dsh-auth-token", cred.token);
            }

            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) {
                throw new FetchError.Transport("服务端要求重定向，已拒绝");
            }
            InputStream in = (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
            String body = readBody(in);
            Map<String, String> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                headers.put(e.getKey().toLowerCase(Locale.US), e.getValue().get(0));
            }
            return parseResponse(status, body, headers, cred.mode);
        } catch (FetchError e) {
            throw e;
        } catch (IOException e) {
            // 不把可能含 URL 的异常原文写进状态：只给一句可读的提示
            throw new FetchError.Transport("请求未完成，请检查网络连接");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readBody(InputStream in) throws FetchError {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_BODY) throw new FetchError.Parse("响应过大");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new FetchError.Transport("读取响应失败");
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    // ---- 解析 ----

    static BalanceReading parseResponse(int status, String body, Map<String, String> headers,
                                        CredentialStore.Mode mode) throws FetchError {
        if (status == 401 || status == 403) throw new FetchError.Auth("HTTP " + status);
        if (status == 429) {
            String header = headers == null ? null : headers.get("retry-after");
            throw new FetchError.RateLimited(retryDelay(header, System.currentTimeMillis()));
        }
        if (status != 200) throw new FetchError.Http(status, "");
        if (body == null || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY) {
            throw new FetchError.Parse("响应过大");
        }
        JSONObject root;
        try {
            root = new JSONObject(body);
        } catch (Exception e) {
            throw new FetchError.Parse("需要有效的 JSON 对象");
        }
        return mode == CredentialStore.Mode.ACCOUNT
                ? parseAccount(root, body) : parseApiKey(root, body);
    }

    /** 解析 Retry-After（秒数或 HTTP 日期），上限 24 小时。 */
    public static Double retryDelay(String header, long nowMillis) {
        if (header == null) return null;
        String raw = header.trim();
        if (raw.isEmpty()) return null;
        boolean digits = true;
        for (int i = 0; i < raw.length(); i++) if (raw.charAt(i) < '0' || raw.charAt(i) > '9') { digits = false; break; }
        if (digits) {
            try {
                double seconds = Double.parseDouble(raw);
                return Math.min(86400, seconds);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        String[] formats = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy"
        };
        for (String format : formats) {
            for (String zone : new String[]{"GMT", "UTC"}) {
                SimpleDateFormat fmt = new SimpleDateFormat(format, Locale.US);
                fmt.setLenient(false);
                fmt.setTimeZone(TimeZone.getTimeZone(zone));
                try {
                    Date date = fmt.parse(raw);
                    if (date != null) {
                        double delta = (date.getTime() - nowMillis) / 1000.0;
                        return Math.min(86400, Math.max(0, delta));
                    }
                } catch (Exception ignored) {
                    // 换下一种格式
                }
            }
        }
        return null;
    }

    private static BalanceReading parseAccount(JSONObject root, String raw) throws FetchError {
        JSONObject payload = root;
        if (root.has("code") || root.has("data")) {
            checkCode(root.opt("code"), "code");
            JSONObject data = root.optJSONObject("data");
            if (data == null) throw new FetchError.Parse("缺少 data 对象");
            payload = data;
        }
        if (payload.has("biz_code") || payload.has("biz_data")) {
            checkCode(payload.opt("biz_code"), "biz_code");
            JSONObject biz = payload.optJSONObject("biz_data");
            if (biz == null) throw new FetchError.Parse("缺少 biz_data 对象");
            payload = biz;
        }
        BigDecimal normal = walletTotal(payload.opt("normal_wallets"), true, "normal_wallets", "balance");
        BigDecimal bonus = walletTotal(payload.opt("bonus_wallets"), false, "bonus_wallets", "balance");
        BigDecimal spent = null;
        if (payload.has("total_costs")) {
            spent = walletTotal(payload.opt("total_costs"), false, "total_costs", "amount");
        }
        return checkedReading(normal, bonus, spent, raw);
    }

    private static void checkCode(Object value, String label) throws FetchError {
        BigDecimal decimal = decimalValue(value);
        if (decimal == null || decimal.stripTrailingZeros().scale() > 0) {
            throw new FetchError.Parse(label + " 无效");
        }
        if (decimal.compareTo(BigDecimal.valueOf(40003)) == 0) {
            throw new FetchError.Auth("平台返回 code 40003");
        }
        if (decimal.compareTo(BigDecimal.ZERO) != 0) {
            throw new FetchError.Parse("平台返回非成功 " + label);
        }
    }

    private static BalanceReading parseApiKey(JSONObject root, String raw) throws FetchError {
        BigDecimal normal = walletTotal(root.opt("balance_infos"), true, "balance_infos", "total_balance");
        return checkedReading(normal, BigDecimal.ZERO, null, raw);
    }

    private static BigDecimal walletTotal(Object value, boolean required, String label, String amountKey)
            throws FetchError {
        if (value == null || value == JSONObject.NULL) {
            if (!required) return BigDecimal.ZERO;
            throw new FetchError.Parse("找不到 " + label);
        }
        if (!(value instanceof JSONArray)) throw new FetchError.Parse(label + " 必须是钱包数组");
        JSONArray wallets = (JSONArray) value;
        BigDecimal total = BigDecimal.ZERO;
        int cnyCount = 0;
        for (int i = 0; i < wallets.length(); i++) {
            Object item = wallets.opt(i);
            if (!(item instanceof JSONObject)) throw new FetchError.Parse(label + " 必须是钱包数组");
            JSONObject wallet = (JSONObject) item;
            Object currencyValue = wallet.opt("currency");
            if (!(currencyValue instanceof String) || ((String) currencyValue).isEmpty()) {
                throw new FetchError.Parse(label + " 缺少货币类型");
            }
            // 这个界面以人民币计价：USD 绝不能被标成 ¥。
            if (!"CNY".equals(currencyValue)) continue;
            BigDecimal amount = decimalValue(wallet.opt(amountKey));
            if (amount == null) throw new FetchError.Parse(label + " 的 CNY 金额无效");
            total = total.add(amount);
            cnyCount++;
        }
        if (cnyCount == 0 && (required || wallets.length() > 0)) {
            throw new FetchError.Parse(label + " 没有 CNY 钱包，暂不支持其他货币");
        }
        return total;
    }

    private static BalanceReading checkedReading(BigDecimal normal, BigDecimal bonus,
                                                 BigDecimal spent, String raw) throws FetchError {
        BalanceReading reading = new BalanceReading(normal, bonus, spent, raw);
        if (reading.totalCents() == null) throw new FetchError.Parse("余额超出可显示范围");
        return reading;
    }

    private static BigDecimal decimalValue(Object value) {
        String string;
        if (value instanceof String) {
            string = ((String) value).trim();
        } else if (value instanceof Number && !(value instanceof Boolean)) {
            string = value.toString();
        } else {
            return null;
        }
        if (!NUMERIC.matcher(string).matches()) return null;
        try {
            return new BigDecimal(string);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}