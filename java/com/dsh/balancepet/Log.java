package com.dsh.balancepet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地诊断日志，约 1 MiB 轮转一次。
 * 移植自 macOS 版 AppConfig.swift 的 Log。
 */
public final class Log {
    private static final long MAX_BYTES = 1024 * 1024L;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public static void write(String message) {
        // 同时进 logcat：便于在没有 root 的情况下诊断（adb logcat -s DSHPet）
        try {
            android.util.Log.i("DSHPet", message);
        } catch (Throwable ignored) {
        }
        synchronized (LOCK) {
            try {
                File file = PetPaths.logFile();
                if (file.length() >= MAX_BYTES) {
                    File rotated = PetPaths.logFileRotated();
                    if (rotated.exists() && !rotated.delete()) {
                        // 删不掉就继续追加，不阻塞运行
                    }
                    file.renameTo(rotated);
                }
                String line = FMT.format(new Date()) + " " + message + "\n";
                FileOutputStream out = new FileOutputStream(file, true);
                try {
                    out.write(line.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            } catch (IOException e) {
                // 日志失败绝不能影响桌宠本体
            }
        }
    }

    public static String tail(int maxChars) {
        File file = PetPaths.logFile();
        if (!file.exists()) return "(暂无日志)";
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                long len = raf.length();
                int take = (int) Math.min(len, maxChars);
                raf.seek(len - take);
                byte[] buf = new byte[take];
                raf.readFully(buf);
                return new String(buf, "UTF-8");
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            return "(读取日志失败: " + e + ")";
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            PetPaths.logFile().delete();
            PetPaths.logFileRotated().delete();
        }
    }
}