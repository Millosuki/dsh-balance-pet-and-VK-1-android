package com.dsh.balancepet;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 素材基线与上游的一致性校验（升级保障）：
 * 对 APK 内打包的立绘与音效做 SHA-256，与 {@link UpstreamInfo#ASSET_HASHES} 比对。
 *
 * 这样升级上游后可以立刻看出「素材到底换没换、换的是哪张」，
 * 而不是靠肉眼比对或「感觉一样」。
 */
public final class AssetVerifier {

    private AssetVerifier() {}

    public static String verify(Context context) {
        StringBuilder sb = new StringBuilder();
        int ok = 0, bad = 0;
        for (String line : UpstreamInfo.ASSET_HASHES) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            String name = parts[0];
            String expected = parts[1];
            String actual = sha256OfAsset(context, name);
            boolean match = expected.equalsIgnoreCase(actual);
            if (match) ok++;
            else bad++;
            sb.append(match ? "✅ " : "❌ ").append(name).append("\n");
            if (!match) {
                sb.append("   期望 ").append(expected).append("\n")
                        .append("   实际 ").append(actual == null ? "(读取失败)" : actual).append("\n");
            }
        }
        sb.append("\n合计：一致 ").append(ok).append(" 项");
        if (bad > 0) sb.append("，**不一致 ").append(bad).append(" 项**");
        sb.append("\n（音频的哈希对的是内置资源 res/raw/hit.mp3）");
        String report = sb.toString();
        Log.write("素材校验：" + report.replace("\n", " | "));
        return report;
    }

    private static String sha256OfAsset(Context context, String name) {
        try {
            InputStream in;
            if (name.endsWith(".mp3")) {
                in = context.getResources().openRawResource(R.raw.hit);
            } else {
                in = context.getAssets().open(name);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            in.close();
            byte[] out = digest.digest();
            StringBuilder hex = new StringBuilder();
            for (byte b : out) hex.append(String.format(Locale.US, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            Log.write("素材校验读取 " + name + " 失败: " + e);
            return null;
        }
    }

    /** 兜底：素材文件是否存在（不哈希）。 */
    public static boolean assetExists(Context context, String name) {
        try {
            InputStream in = context.getAssets().open(name);
            in.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 供设置界面显示自定义音效文件大小。 */
    public static long fileSize(File file) {
        return file == null || !file.exists() ? 0 : file.length();
    }
}