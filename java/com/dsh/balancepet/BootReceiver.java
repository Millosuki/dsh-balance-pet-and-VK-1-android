package com.dsh.balancepet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启（对应原版把 vbs 快捷方式丢进 shell:startup）。
 * 只有用户在设置里显式打开「开机自动启动」才会拉起桌宠。
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        PetPaths.init(context);
        PetState state = PetState.load();
        if (!state.autoStart) return;
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.write("开机自启跳过：还没有悬浮窗权限");
            return;
        }
        try {
            Intent start = new Intent(context, PetService.class).setAction(PetService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(start);
            } else {
                context.startService(start);
            }
            Log.write("开机自启：已拉起桌宠");
        } catch (Exception e) {
            Log.write("开机自启失败: " + e);
        }
    }
}