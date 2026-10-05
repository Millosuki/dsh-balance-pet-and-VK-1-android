package com.dsh.balancepet;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 凭证解析与保存。
 * 移植自 macOS 版 AppConfig.swift 的 Credential / CredentialStore。
 *
 * 来源顺序（第一个可用者胜出，与原件一致，仅把「应用目录」映射到 Android 的共享目录）：
 *   1. Download/DSHPet/apikey.txt        （旁车文件，等价原版「应用目录 apikey.txt」）
 *   2. filesDir/apikey.txt               （设置窗口写入）
 *   3. filesDir/credentials.yaml         （用户导入的 DSH 凭证副本）
 *   4. 共享存储 .dsh/.credentials.yaml    （DEEPSEEK_API_KEY）
 *   5. 同一 YAML 的账号记录
 */
public final class CredentialStore {

    public static final String API_KEY_ENDPOINT = "https://api.deepseek.com/user/balance";
    private static final String DEFAULT_ACCOUNT_PATH = "/api/v0/users/get_user_summary";

    public enum Mode { API_KEY, ACCOUNT }

    public static final class Credential {
        public final Mode mode;
        public final String token;
        public final String endpoint;
        public final String source;

        Credential(Mode mode, String token, String endpoint, String source) {
            this.mode = mode;
            this.token = token;
            this.endpoint = endpoint;
            this.source = source;
        }

        public String shortDescription() {
            if (mode == Mode.API_KEY) return "API Key · " + source;
            String host;
            try {
                host = new java.net.URL(endpoint).getHost();
            } catch (Exception e) {
                host = "?";
            }
            return "DSH 账号 · " + source + (host == null ? "" : "（" + host + "）");
        }
    }

    private CredentialStore() {}

    /** 离线模式（对应原版 DSHPET_OFFLINE=1）：完全跳过凭证读取与联网。 */
    public static Credential resolve(boolean offline, String apiPathOverride) {
        if (offline) return null;
        String key = readKeyFile(PetPaths.sidecarKeyFile());
        if (key != null) return apiKey(key, "apikey.txt（Download/DSHPet）");
        key = readKeyFile(PetPaths.userKeyFile());
        if (key != null) return apiKey(key, "apikey.txt（应用配置目录）");

        String yaml = readText(PetPaths.importedCredentials());
        if (yaml != null) {
            Credential c = fromYaml(yaml, "导入的 credentials.yaml", apiPathOverride);
            if (c != null) return c;
        }
        yaml = readText(PetPaths.sharedDshCredentials());
        if (yaml != null) {
            Credential c = fromYaml(yaml, "~/.dsh/.credentials.yaml", apiPathOverride);
            if (c != null) return c;
        }
        return null;
    }

    private static Credential fromYaml(String yaml, String source, String apiPathOverride) {
        YamlMini mini = new YamlMini(yaml);
        String key = mini.apiKey();
        if (key != null) return apiKey(key, source);
        String[] grant = mini.accountGrant();
        if (grant == null) return null;
        String path = (apiPathOverride == null || apiPathOverride.isEmpty())
                ? DEFAULT_ACCOUNT_PATH : apiPathOverride;
        String endpoint = accountEndpoint(grant[1], path);
        if (endpoint == null) return null;
        return new Credential(Mode.ACCOUNT, grant[0], endpoint, source);
    }

    public static boolean isValidToken(String value) {
        if (value == null || value.isEmpty()) return false;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16384) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 33 || c > 126) return false;   // 必须是单行 HTTP 头值
        }
        return true;
    }

    /** 只接受无用户名/密码/查询/片段的 HTTPS 地址。 */
    public static boolean isSafeEndpoint(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return false;
            String host = u.getHost();
            if (host == null || host.isEmpty()) return false;
            if (u.getUserInfo() != null) return false;
            if (u.getRef() != null) return false;
            if (u.getQuery() != null) return false;
            for (int i = 0; i < host.length(); i++) if (Character.isWhitespace(host.charAt(i))) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** issuer + 受控路径拼出账号接口地址；非法组合返回 null。 */
    public static String accountEndpoint(String issuer, String path) {
        if (issuer == null || issuer.isEmpty()) return null;
        for (int i = 0; i < issuer.length(); i++) {
            char c = issuer.charAt(i);
            if (Character.isWhitespace(c) || c == '\\') return null;
        }
        if (!isSafeEndpoint(issuer)) return null;
        if (path == null || !path.startsWith("/") || path.startsWith("//")) return null;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isWhitespace(c) || c == '\\' || c == '?' || c == '#' || c == '%') return null;
        }
        for (String part : path.split("/")) {
            if (".".equals(part) || "..".equals(part)) return null;
        }
        String base;
        try {
            java.net.URL u = new java.net.URL(issuer);
            String p = u.getPath() == null ? "" : u.getPath();
            while (p.startsWith("/")) p = p.substring(1);
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            base = u.getProtocol() + "://" + u.getAuthority() + (p.isEmpty() ? "" : "/" + p);
        } catch (Exception e) {
            return null;
        }
        String url = base + path;
        return isSafeEndpoint(url) ? url : null;
    }

    private static Credential apiKey(String raw, String source) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!isValidToken(value)) return null;
        if (!isSafeEndpoint(API_KEY_ENDPOINT)) return null;
        return new Credential(Mode.API_KEY, value, API_KEY_ENDPOINT, source);
    }

    /** 用指定 Key 构造凭证（用于「保存前先验证」这类场景）。 */
    public static Credential apiKeyCredential(String raw, String source) {
        return apiKey(raw, source);
    }

    /** 展示用的掩码：只露前 6 个字符 + 长度，避免把 Key 打到界面上。 */
    public static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "（空）";
        String head = key.length() <= 6 ? key.substring(0, 1) : key.substring(0, 6);
        return head + "…（共 " + key.length() + " 字符）";
    }

    private static String readKeyFile(File file) {
        String raw = readText(file);
        if (raw == null) return null;
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }

    public static String readText(File file) {
        if (file == null || !file.exists() || !file.canRead()) return null;
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buf = new byte[(int) Math.min(file.length(), 1024 * 1024)];
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n <= 0) break;
                    read += n;
                }
                return new String(buf, 0, read, StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 原子写入：先写临时文件再 rename，失败时保留旧 Key 并把错误抛给调用方。
     * 对应原版 0600 临时文件 + rename 的做法。
     */
    public static void saveAPIKey(String raw, File destination) throws IOException {
        String value = raw == null ? "" : raw.trim();
        if (!isValidToken(value)) throw new IOException("API Key 必须是有效的单行凭证");
        File dir = destination.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录 " + dir);
        File temp = new File(dir, ".apikey-" + System.nanoTime());
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!temp.renameTo(destination)) {
            temp.delete();
            throw new IOException("写入 " + destination + " 失败");
        }
    }
}