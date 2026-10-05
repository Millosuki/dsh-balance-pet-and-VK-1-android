package com.dsh.balancepet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 脚本化控制入口（广播），等同于 MainActivity 的 {@code forward} 通道，
 * 但**不显示界面**——这是关键：经 Activity 转发时，启动页会把整个屏幕（连悬浮窗）盖住约 1 秒，
 * 既打扰正在用手机的人，也让「截图取证」拿到的全是启动页。
 *
 * 用法（Shizuku / adb shell）：
 * <pre>
 * am broadcast -a com.dsh.balancepet.CONTROL \
 *     --es token &lt;control.token&gt; --es action com.dsh.balancepet.SET_POS --ei x 300 --ei y 1200
 * </pre>
 *
 * 安全模型与 MainActivity 的 forward 完全一致（见 PetPaths.controlToken 的说明）：
 * 1) 必须带正确的控制令牌——令牌存放在应用私有目录，第三方应用与普通用户都读不到；
 * 2) 只接受白名单动作，不能用来执行任意 Intent；
 * 3) 需要令牌时**失败即拒绝**（fail-closed），并写日志。
 */
public final class PetControlReceiver extends BroadcastReceiver {

    public static final String ACTION_CONTROL = "com.dsh.balancepet.CONTROL";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        PetPaths.init(context);

        String action = intent.getStringExtra("action");
        if (!allowed(action)) {
            Log.write("拒绝控制广播：动作不在白名单（" + action + "）");
            return;
        }
        if (!PetPaths.matchesControlToken(intent.getStringExtra("token"))) {
            Log.write("拒绝控制广播：控制令牌缺失或错误（" + action + "）");
            return;
        }

        Intent svc = new Intent(context, PetService.class).setAction(action);
        if (intent.getExtras() != null) svc.putExtras(intent.getExtras());
        try {
            if (PetService.ACTION_START.equals(action)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            Log.write("控制广播 → " + action);
        } catch (Exception e) {
            // 前台服务启动限制等；如实记录，不假装成功
            Log.write("控制广播转发失败（" + action + "）: " + e);
        }
    }

    /** 白名单：只允许转发本应用自己的服务动作。 */
    private static boolean allowed(String action) {
        if (action == null) return false;
        return PetService.ACTION_START.equals(action)
                || PetService.ACTION_STOP.equals(action)
                || PetService.ACTION_REFRESH.equals(action)
                || PetService.ACTION_ONE_HIT.equals(action)
                || PetService.ACTION_RELOAD.equals(action)
                || PetService.ACTION_APPLY.equals(action)
                || PetService.ACTION_SELFTEST.equals(action)
                || PetService.ACTION_SNAP.equals(action)
                || PetService.ACTION_DEMO.equals(action)
                || PetService.ACTION_RELOAD_SOUND.equals(action)
                || PetService.ACTION_TEST_SOUND.equals(action)
                || PetService.ACTION_SET_FPS.equals(action)
                || PetService.ACTION_SET_OFFLINE_ART.equals(action)
                || PetService.ACTION_SET_OFFLINE.equals(action)
                || PetService.ACTION_TEST_BUBBLE.equals(action)
                || PetService.ACTION_SET_POS.equals(action)
                || PetService.ACTION_SET_BUBBLE_STYLE.equals(action)
                || PetService.ACTION_TEST_PRESS.equals(action)
                || PetService.ACTION_TEST_SQUEEZE.equals(action)
                || PetService.ACTION_TEST_TAP.equals(action)
                || PetService.ACTION_TEST_BUBBLE_CLICK.equals(action)
                || PetService.ACTION_TEST_EDIT.equals(action)
                || PetService.ACTION_MODE.equals(action)
                                || PetService.ACTION_EDITOR_MODE.equals(action)
                                                || PetService.ACTION_SET_CHARACTER.equals(action);
    }
}