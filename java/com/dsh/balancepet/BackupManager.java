package com.dsh.balancepet;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 备份与恢复（v1.14.0）。
 *
 * <p>打成一个 **zip**，里面结构固定（这样以后加字段也不会互相不认识）：
 * <pre>
 * dshpet-backup.json     元信息：schema / appVersion / createdAt / 角色数 / 是否含凭证 / 是否含账本
 * state.json             全部设置（泡泡模块、点击序列、角色、颜色、尺寸、帧率……）
 * ledger.json            本地记账（今日/累计/充值）
 * characters/&lt;id&gt;.png    自定义角色的图片
 * credentials/…          **仅当你选了“包含密钥”**：apikey.txt / credentials.yaml
 * </pre>
 *
 * <p>两条纪律：
 * <ul>
 *   <li>恢复前先把现有的 {@code state.json} 另存为 {@code state.json.bak-<时间戳>}，万一恢复错了还能找回来；</li>
 *   <li>不认识的 / 缺元信息的 zip **直接报错**，绝不“当成空备份”把用户配置洗掉。</li>
 * </ul>
 */
public final class BackupManager {
    /** 元信息文件名（恢复时靠它判断“这是不是我们的备份”）。 */
    public static final String MANIFEST = "dshpet-backup.json";
    /** 备份格式版本。解析到更新的版本会拒绝（而不是猜）。 */
    public static final int SCHEMA = 1;
    /** 单个条目大小上限（防止恶意/损坏的 zip 把内存吃满）。 */
    private static final long MAX_ENTRY_BYTES = 24L * 1024 * 1024;
    /** 全部条目总大小上限。 */
    private static final long MAX_TOTAL_BYTES = 96L * 1024 * 1024;

    private BackupManager() {}

    /** 备份失败时带上原因，交给界面如实显示。 */
    public static final class BackupError extends Exception {
        public BackupError(String message) { super(message); }
    }

    /** 备份包里的元信息。 */
    public static final class Manifest {
        public int schema;
        public String appVersion = "";
        public long createdAt;
        public int characterCount;
        public boolean hasLedger;
        public boolean hasCredentials;

        public static Manifest fromJson(String json) throws BackupError {
            if (json == null || json.trim().isEmpty()) throw new BackupError("备份元信息为空");
            Manifest m = new Manifest();
            try {
                JSONObject o = new JSONObject(json);
                m.schema = o.optInt("schema", 0);
                m.appVersion = o.optString("appVersion", "");
                m.createdAt = o.optLong("createdAt", 0L);
                m.characterCount = o.optInt("characterCount", 0);
                m.hasLedger = o.optBoolean("hasLedger", false);
                m.hasCredentials = o.optBoolean("hasCredentials", false);
            } catch (Exception e) {
                throw new BackupError("备份元信息不是合法 JSON：" + e.getMessage());
            }
            if (m.schema <= 0) throw new BackupError("备份元信息里没有 schema");
            if (m.schema > SCHEMA) {
                throw new BackupError("这个备份来自更新的版本（schema=" + m.schema + "），当前只认到 " + SCHEMA);
            }
            return m;
        }

        public String toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("schema", schema);
                o.put("appVersion", appVersion == null ? "" : appVersion);
                o.put("createdAt", createdAt);
                o.put("characterCount", characterCount);
                o.put("hasLedger", hasLedger);
                o.put("hasCredentials", hasCredentials);
            } catch (Exception ignored) { }
            return o.toString();
        }

        /** 给界面/日志看的一行摘要。 */
        public String describe() {
            return "schema=" + schema + "，来源版本 " + (appVersion.isEmpty() ? "未知" : appVersion)
                    + "，自定义角色 " + characterCount + " 个"
                    + (hasLedger ? "，含记账" : "，不含记账")
                    + (hasCredentials ? "，**含密钥**" : "，不含密钥");
        }
    }

    /** 恢复结果（用来告诉用户到底恢复了什么）。 */
    public static final class RestoreResult {
        public Manifest manifest;
        public int restoredCharacters;
        public boolean restoredLedger;
        public boolean restoredCredentials;
        /** 恢复前自动另存的旧配置（可能是 null，例如本来就没有配置）。 */
        public File safetyCopy;

        public String describe() {
            return "已恢复：" + manifest.describe()
                    + "；角色图片 " + restoredCharacters + " 个"
                    + (restoredLedger ? "；记账已恢复" : "")
                    + (restoredCredentials ? "；密钥已恢复" : "")
                    + (safetyCopy == null ? "" : "；旧配置已另存为 " + safetyCopy.getName());
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 写一份备份。
     *
     * @param includeCredentials 是否把 api key / 凭证文件一起打包（默认关：备份文件可能被到处传）
     */
    public static Manifest write(OutputStream out,
                                 File stateFile, File ledgerFile, File charactersDir,
                                 List<File> credentialFiles, boolean includeCredentials,
                                 String appVersion) throws BackupError {
        Manifest m = new Manifest();
        m.schema = SCHEMA;
        m.appVersion = appVersion == null ? "" : appVersion;
        m.createdAt = System.currentTimeMillis();
        m.hasLedger = ledgerFile != null && ledgerFile.isFile();
        m.hasCredentials = includeCredentials && credentialFiles != null && !credentialFiles.isEmpty();
        List<File> characters = listFiles(charactersDir);
        m.characterCount = characters.size();

        ZipOutputStream zip = null;
        try {
            zip = new ZipOutputStream(out);
            putText(zip, MANIFEST, m.toJson());
            if (stateFile != null && stateFile.isFile()) putFile(zip, "state.json", stateFile);
            if (m.hasLedger) putFile(zip, "ledger.json", ledgerFile);
            for (File f : characters) putFile(zip, "characters/" + f.getName(), f);
            if (m.hasCredentials) {
                for (File f : credentialFiles) {
                    if (f != null && f.isFile()) putFile(zip, "credentials/" + f.getName(), f);
                }
            }
            zip.finish();
            Log.write("备份完成：" + m.describe()
                    + "（state=" + (stateFile != null && stateFile.isFile())
                    + "，角色 " + characters.size() + " 个）");
            return m;
        } catch (Exception e) {
            throw new BackupError("写备份失败：" + e);
        } finally {
            if (zip != null) try { zip.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------ 读

    /** 只看元信息（恢复前先验一遍，别拿一个坏 zip 去覆盖用户配置）。 */
    public static Manifest inspect(InputStream in) throws BackupError {
        try {
            ZipInputStream zip = new ZipInputStream(in);
            try {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (MANIFEST.equals(e.getName())) {
                        String json = new String(readEntry(zip, e), java.nio.charset.StandardCharsets.UTF_8);
                        return Manifest.fromJson(json);
                    }
                }
            } finally {
                zip.close();
            }
            throw new BackupError("这个 zip 里没有 " + MANIFEST + " —— 不像是本应用的备份");
        } catch (BackupError e) {
            throw e;
        } catch (Exception e) {
            throw new BackupError("读取备份失败：" + e);
        }
    }

    /**
     * 把备份恢复到给定位置；恢复前会把现有 state.json 另存一份安全副本。
     *
     * <p>调用方在恢复完成后需要：重新 {@code PetState.load()}、通知服务重建、刷新界面。
     */
    public static RestoreResult restore(InputStream in,
                                        File stateFile, File ledgerFile, File charactersDir,
                                        File credentialDir, boolean restoreCredentials)
            throws BackupError {
        RestoreResult r = new RestoreResult();
        try {
            // 先安全副本：万一恢复的内容不对，用户还能找回原来的配置
            if (stateFile != null && stateFile.isFile()) {
                r.safetyCopy = new File(stateFile.getParentFile(),
                        stateFile.getName() + ".bak-" + System.currentTimeMillis());
                copyFile(stateFile, r.safetyCopy);
            }
            ZipInputStream zip = new ZipInputStream(in);
            try {
                ZipEntry e;
                long total = 0;
                boolean sawManifest = false;
                while ((e = zip.getNextEntry()) != null) {
                    String name = e.getName();
                    if (e.isDirectory()) continue;
                    byte[] data = readEntry(zip, e);
                    total += data.length;
                    if (total > MAX_TOTAL_BYTES) throw new BackupError("备份内容过大（超过 96MB）");
                    if (MANIFEST.equals(name)) {
                        sawManifest = true;
                        r.manifest = Manifest.fromJson(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                        continue;
                    }
                    if ("state.json".equals(name)) {
                        writeFile(stateFile, data);
                    } else if ("ledger.json".equals(name)) {
                        writeFile(ledgerFile, data);
                        r.restoredLedger = true;
                    } else if (name.startsWith("characters/") && charactersDir != null) {
                        if (!charactersDir.exists() && !charactersDir.mkdirs()) {
                            throw new BackupError("创建角色目录失败：" + charactersDir);
                        }
                        String base = new File(name).getName();
                        if (base.isEmpty()) continue;
                        writeFile(new File(charactersDir, base), data);
                        r.restoredCharacters++;
                    } else if (name.startsWith("credentials/") && restoreCredentials && credentialDir != null) {
                        String base = new File(name).getName();
                        if (base.isEmpty()) continue;
                        if (!credentialDir.exists() && !credentialDir.mkdirs()) {
                            throw new BackupError("创建凭证目录失败：" + credentialDir);
                        }
                        writeFile(new File(credentialDir, base), data);
                        r.restoredCredentials = true;
                    }
                }
                if (!sawManifest) throw new BackupError("备份里缺少 " + MANIFEST + "，已中止（现有配置未被改动）");
            } finally {
                zip.close();
            }
            if (r.manifest == null) throw new BackupError("没读到备份元信息");
            Log.write("恢复完成：" + r.describe());
            return r;
        } catch (BackupError e) {
            Log.write("恢复失败（现有配置保持原样，已被覆盖的文件请看安全副本）: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            throw new BackupError("恢复失败：" + e);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static List<File> listFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] all = dir == null ? null : dir.listFiles();
        if (all == null) return out;
        for (File f : all) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".png")) out.add(f);
        }
        return out;
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putFile(ZipOutputStream zip, String name, File src) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        try {
            zip.putNextEntry(new ZipEntry(name));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
            zip.closeEntry();
        } finally {
            in.close();
        }
    }

    private static byte[] readEntry(InputStream zip, ZipEntry e) throws IOException, BackupError {
        long declared = e.getSize();
        if (declared > MAX_ENTRY_BYTES) throw new BackupError("条目过大：" + e.getName());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                declared > 0 ? (int) Math.min(declared, 1 << 20) : 8192);
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = zip.read(buf)) > 0) {
            total += n;
            if (total > MAX_ENTRY_BYTES) throw new BackupError("条目过大：" + e.getName());
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void writeFile(File target, byte[] data) throws IOException {
        if (target == null) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("创建目录失败：" + parent);
        }
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}