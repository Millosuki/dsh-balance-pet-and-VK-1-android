package com.dsh.balancepet;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 本地文件布局。对应 macOS 版的 PetPaths（~/Library/Application Support/DSHBalancePet/）。
 *
 * Android 映射：
 *   filesDir/state.json     状态（角色、尺寸、音效、间隔、窗口位置）
 *   filesDir/status.json    每秒原子更新的存活/几何快照
 *   filesDir/pet.log(.1)    诊断日志，约 1 MiB 轮转
 *   filesDir/apikey.txt     设置窗口保存的 API Key
 *   filesDir/credentials.yaml  用户用「导入凭证文件」复制进来的 DSH 凭证
 *   Download/DSHPet/apikey.txt  旁车 Key（等价于原版「应用目录 apikey.txt」）
 */
public final class PetPaths {
    private static Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static Context context() { return appContext; }

    public static File supportDir() { return appContext.getFilesDir(); }

    public static File logFile() { return new File(supportDir(), "pet.log"); }
    public static File logFileRotated() { return new File(supportDir(), "pet.log.1"); }
    public static File stateFile() { return new File(supportDir(), "state.json"); }
    public static File statusFile() { return new File(supportDir(), "status.json"); }
    /**
     * 真实数据快照（v1.6.0）：服务在每次取数后写入余额 / 累计消费 / 状态文字 / 峰谷状态，
     * **编辑器预览**读它来显示真数据（读不到就显示 {@code --} 并标注「未连接」，不假装有数据）。
     */
    public static File runtimeFile() { return new File(supportDir(), "runtime.json"); }

    /**
     * 本地账本（v1.12.0）：服务观测余额下降累加出的「今日已用 / 累计 / 充值」。
     * 接口没有这类字段（已核实），所以只能自己记 —— 与上游 accounting.mjs 同口径。
     */
    public static File ledgerFile() { return new File(supportDir(), "ledger.json"); }
    public static File userKeyFile() { return new File(supportDir(), "apikey.txt"); }
    /** 自定义角色图片目录（v1.13.0）。 */
        public static File characterDir() {
            File d = new File(supportDir(), "characters");
            if (!d.exists() && !d.mkdirs()) Log.write("创建自定义角色目录失败：" + d);
            return d;
        }
        /** 自定义角色图片：{@code files/characters/<id>.png}。 */
        public static File characterFile(String id) { return new File(characterDir(), id + ".png"); }
    public static File importedCredentials() { return new File(supportDir(), "credentials.yaml"); }

    /** 旁车目录：用户用文件管理器就能放 Key。 */
    public static File sidecarDir() {
        File dir = new File(android.os.Environment.getExternalStorageDirectory(), "Download/DSHPet");
        return dir;
    }

    public static File sidecarKeyFile() { return new File(sidecarDir(), "apikey.txt"); }

    /** DSH 在共享存储上的凭证副本（Android 无法读别的 App 的 HOME）。 */
    public static File sharedDshCredentials() {
        return new File(android.os.Environment.getExternalStorageDirectory(), ".dsh/.credentials.yaml");
    }

    /**
     * 远程控制令牌。
     *
     * 为什么需要：Android 的 Activity 拿不到「调用方 uid」（getLaunchedFromUid /
     * getLaunchedFromPackage 都是隐藏 API），所以「导出的 MainActivity + 转发命令」
     * 必须自带凭据，否则任意第三方应用都能用 forward= 启停/刷新别人的桌宠，
     * 或用 section=log 弹出含余额的日志。
     *
     * 令牌保存在应用私有目录，只有本应用（和 root）能读，第三方应用无法伪造。
     * 生成后写入日志，便于 adb / Shizuku 脚本取用（普通应用在 Android 4.1+ 读不到别人的日志）。
     */
    public static synchronized String controlToken() {
        File file = new File(supportDir(), "control.token");
        String existing = CredentialStore.readText(file);
        if (existing != null && existing.trim().length() >= 16) return existing.trim();
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 32; i++) sb.append(Character.forDigit(random.nextInt(16), 16));
        String token = sb.toString();
        try {
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Exception e) {
            // 写不进去就退化成「本次进程内有效」，不阻塞功能
        }
        return token;
    }

    public static boolean matchesControlToken(String provided) {
        return provided != null && provided.equals(controlToken());
    }

    /** 用户通过「选择音效文件」导入后保存的位置（保留扩展名）。 */
    public static File customSoundFile() {
        return new File(supportDir(), "custom_sound");
    }

    /** 自定义音效的完整查找顺序（第一个存在的胜出），最后回退内置 hit.mp3。 */
    public static File[] soundCandidates() {
        return new File[]{
                customSoundFile(),                            // 设置界面导入
                new File(sidecarDir(), "hit.mp3"),            // 等价原版：直接覆盖 hit.mp3
                new File(sidecarDir(), "hit.wav"),
                new File(sidecarDir(), "custom_sound.mp3"),   // 或叫 custom_sound.*
                new File(sidecarDir(), "custom_sound.wav")
        };
    }

    /** 当前实际会用到的音效文件；null 表示回退到内置资源。 */
    public static File resolveSoundFile(String statePath) {
        if (statePath != null && !statePath.isEmpty()) {
            File f = new File(statePath);
            if (f.exists() && f.canRead()) return f;
        }
        for (File f : soundCandidates()) {
            if (f.exists() && f.canRead()) return f;
        }
        return null;
    }
}