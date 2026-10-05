package com.dsh.balancepet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/**
 * 设置界面。布局风格刻意贴近原生系统设置：白/浅灰底、圆角卡片、
 * 分组标题、细描边、克制的蓝灰强调色；暗色模式自动切换。
 *
 * 这里是「桌面菜单栏 ¥」在 Android 上的落点：状态、凭证、角色、尺寸、
 * 刷新间隔、音效、离线模式、开机自启、日志与离线自检都在这一页。
 */
public final class MainActivity extends Activity {

    public static final String EXTRA_SECTION = "section";
    /** 转发给 PetService 的 action（便于 adb / Shizuku 脚本化控制） */
    public static final String EXTRA_FORWARD = "forward";
    /** 转发后不显示界面，直接退出 */
    public static final String EXTRA_QUIET = "quiet";
    /** 远程控制令牌（见 PetPaths.controlToken 的说明）；转发与 section 都需要它 */
    public static final String EXTRA_TOKEN = "token";
    /** 选择自定义音效文件的请求码 */
    private static final int REQUEST_PICK_SOUND = 1001;
        /** v1.13.0：选一张图当自定义角色。 */
        private static final int REQUEST_PICK_CHARACTER = 1002;
            /** v1.14.0：备份写到哪里 / 从哪个 zip 恢复。 */
            private static final int REQUEST_CREATE_BACKUP = 1003;
            private static final int REQUEST_OPEN_BACKUP = 1004;
            /** 这一次备份是否要把 API Key / 凭证也打进去（界面上问过用户）。 */
            private boolean backupIncludeCredentials;
    /** 每次行为变更都更新这个版本号，避免版本身份不清。 */
    public static final String VERSION = "1.14.3-android";

    private PetState state;
    private boolean night;
    private LinearLayout content;
    private TextView statusLine;

    // ---- 顶部标签页（设置太多，分三块：通用 / VK-1 / Whale挂件） ----
    private static final int TAB_GENERAL = 0;
    private static final int TAB_VK = 1;
    private static final int TAB_WHALE = 2;
    private int currentTab = TAB_GENERAL;
    private final TextView[] tabViews = new TextView[3];
    private EditText keyField;
    private EditText intervalField;
    private EditText sizeField;
    private TextView perfLine;

    private int bg() { return night ? Color.rgb(18, 19, 21) : Color.rgb(247, 248, 250); }
    private int cardBg() { return night ? Color.rgb(30, 32, 35) : Color.WHITE; }
    private int textColor() { return night ? Color.rgb(230, 232, 234) : Color.rgb(26, 28, 30); }
    private int subColor() { return night ? Color.rgb(150, 155, 160) : Color.rgb(122, 130, 138); }
    private int accent() { return night ? Color.rgb(150, 180, 210) : Color.rgb(74, 107, 138); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PetPaths.init(this);
        // 命令行转发：本应用不需要把 PetService 导出给外部
        // 注意：launchMode=singleTop 时后续命令走 onNewIntent，两处都要处理
        if (handleForward(getIntent())) {
            finish();
            return;
        }
        night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        // 普通启动时也把令牌写进日志，方便脚本首次取用（设置界面同样可以查看）
        Log.write("控制令牌：token=" + PetPaths.controlToken());
        state = PetState.load();
        if (state.migrationApplied) {
            // 一次性迁移改过配置：写盘落地（幂等，重复执行结果一样）
            state.save();
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg());
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, dp(20), pad, dp(28));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg());
        root.addView(buildTabBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        buildUi();

        String section = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SECTION);
        if (!authorized(getIntent())) section = null;   // 安全：未带令牌者不能借 section 弹日志/凭证信息
        if ("log".equals(section)) showLog();
        if ("key".equals(section) && keyField != null) {
            keyField.requestFocus();
        }
        if ("size".equals(section) && sizeField != null) sizeField.requestFocus();
        if ("interval".equals(section) && intervalField != null) intervalField.requestFocus();
        if ("editor".equals(section)) launchEditor(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        if (handleForward(intent)) {
            finish();
            return;
        }
        String section = intent.getStringExtra(EXTRA_SECTION);
        if (!authorized(intent)) section = null;   // 安全：未带令牌者不能借 section 弹日志/凭证信息
        if ("log".equals(section)) showLog();
        if ("key".equals(section) && keyField != null) keyField.requestFocus();
        if ("size".equals(section) && sizeField != null) sizeField.requestFocus();
        if ("interval".equals(section) && intervalField != null) intervalField.requestFocus();
        if ("editor".equals(section)) launchEditor(intent);
    }

    /**
     * 打开模块化编辑器（v1.6.0 的脚本入口，与 section=log/key/size 同一套令牌校验）。
     *
     * <pre>
     * --es section editor           打开全局模块编辑器
     * --ez smoke true               建完界面 + 跑一遍自检日志，然后自动关闭（不写盘）
     * --ez export true              smoke 时把预览渲染成 PNG 写到 Download/dshpet_editor_preview.png
     * --es target seq --ei index 1  改成编辑「点击序列第 2 项」的内容
     * </pre>
     */
    private void launchEditor(Intent src) {
        boolean smoke = src != null && src.getBooleanExtra("smoke", false);
        Intent ed = new Intent(this, PetBubbleEditorActivity.class);
        String target = src == null ? null : src.getStringExtra("target");
        String effective = (target == null || target.trim().isEmpty())
                ? PetBubbleEditorActivity.TARGET_MODULES : target;
        int index = src == null ? -1 : src.getIntExtra("index", -1);
        ed.putExtra(PetBubbleEditorActivity.EXTRA_TARGET, effective);
        ed.putExtra(PetBubbleEditorActivity.EXTRA_INDEX, index);
        ed.putExtra(PetBubbleEditorActivity.EXTRA_SMOKE, smoke);
        ed.putExtra(PetBubbleEditorActivity.EXTRA_EXPORT,
                src != null && src.getBooleanExtra("export", false));
        ed.putExtra(PetBubbleEditorActivity.EXTRA_SMOKE_SAVE,
                src != null && src.getBooleanExtra("smokeSave", false));
        // 脚本验证用：临时覆盖「文字颜色」工作副本（不写盘）
        if (src != null && src.hasExtra("editorTextColor")) {
            ed.putExtra("textColor", src.getIntExtra("editorTextColor", 0));
        }
        if (src != null && src.hasExtra("editorHintColor")) {
            ed.putExtra("hintColor", src.getIntExtra("editorHintColor", 0));
        }
        String focusStr = src == null ? null : src.getStringExtra("focus");
        if (focusStr != null && !focusStr.trim().isEmpty()) {
            ed.putExtra(PetBubbleEditorActivity.EXTRA_FOCUS, focusStr.trim());
        }
        Log.write("设置页：打开模块化编辑器（target=" + effective
                + (index >= 0 ? "＋第 " + (index + 1) + " 项" : "") + "，smoke=" + smoke
                + (src != null && src.getBooleanExtra("smokeSave", false) ? "，smokeSave=true" : "") + "）");
        startActivity(ed);
    }

    /**
     * 把 forward 附加项转成 PetService 的 action。
     * 返回 true 表示这是一次「只转发、不显示界面」的调用。
     */
    private boolean handleForward(Intent incoming) {
        if (incoming == null) return false;
        String forward = incoming.getStringExtra(EXTRA_FORWARD);
        boolean quiet = incoming.getBooleanExtra(EXTRA_QUIET, false);
        if (forward == null) return false;
        // 安全：转发命令必须带正确的控制令牌（令牌在应用私有目录，第三方无法读取/伪造），
        // 否则任意应用都能用 forward= 停掉或刷新别人的桌宠。
        if (!PetPaths.matchesControlToken(incoming.getStringExtra(EXTRA_TOKEN))) {
            Log.write("拒绝未授权转发命令（缺少或错误的控制令牌）：" + forward);
            return quiet;
        }
        Log.write("MainActivity 转发命令 → " + forward + (quiet ? "（静默）" : ""));
        Intent svc = new Intent(this, PetService.class).setAction(forward);
        // 关键：把附加项一并带过去（fen / mode 等），否则脚本化的压测参数会被丢掉
        if (incoming.getExtras() != null) svc.putExtras(incoming.getExtras());
        try {
            if (PetService.ACTION_START.equals(forward)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.write("转发命令失败: " + e);
        }
        return quiet;
    }

    private boolean authorized(Intent intent) {
        return intent != null && PetPaths.matchesControlToken(intent.getStringExtra(EXTRA_TOKEN));
    }

    @Override protected void onResume() {
        super.onResume();
        // 模块化编辑器保存过 → 必须重新读盘：本页的 state 是打开时的那份，
        // 若继续用它写盘会把编辑器刚保存的内容覆盖回旧值（真会丢用户改动）。
        if (PetBubbleEditorActivity.consumeSavedFlag()) {
            state = PetState.load();
            buildUi();
            Log.write("设置页：检测到编辑器已保存 → 重新读取 state.json 并刷新界面");
        }
        refreshStatus();
        startPerfMonitor();
    }

    @Override protected void onPause() {
        super.onPause();
        stopPerfMonitor();
    }

    /** 每秒从 status.json 取一次实测帧率（数据来自服务端的真实渲染计数）。 */
    private void startPerfMonitor() {
        stopPerfMonitor();
        if (perfLine == null) return;
        perfTick.run();
    }

    private void stopPerfMonitor() {
        uiHandler.removeCallbacks(perfTick);
    }

    private final android.os.Handler uiHandler = new android.os.Handler();
    private final Runnable perfTick = new Runnable() {
        @Override public void run() {
            if (perfLine != null) perfLine.setText(PetService.describePerformance());
            uiHandler.postDelayed(this, 1000);
        }
    };

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refreshStatus() {
        if (statusLine != null) {
            statusLine.setText("当前状态：" + PetService.describeState());
        }
    }

    // ------------------------------------------------------------------ UI 构建

    private void buildUi() {
        content.removeAllViews();
        styleTabs();

        TextView head = new TextView(this);
        head.setText("DeepSeek 余额桌宠 · Android 移植版 " + VERSION
                + "  ｜  上游基线 " + UpstreamInfo.UPSTREAM_VERSION);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.setTextColor(subColor());
        head.setPadding(0, 0, 0, dp(10));
        // v1.14.3：彩蛋 —— 点这行版本号 = 打开「关于 · 更新记录」
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAbout(); }
        });
        content.addView(head);

        statusLine = new TextView(this);
        statusLine.setTextColor(textColor());
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusLine.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusLine.setBackground(card());
        content.addView(statusLine);
        refreshStatus();

        if (currentTab == TAB_GENERAL) {
        // ---- 权限 ----
        section("权限");
        LinearLayout perm = cardContainer();
        perm.addView(actionRow("悬浮窗权限（必须）",
                Settings.canDrawOverlays(this) ? "已授予" : "未授予 · 点击前往授权", new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }
                }));
        perm.addView(divider());
        perm.addView(actionRow("电池优化白名单",
                "避免后台被冻结（可选，建议开启）", new Runnable() {
                    @Override public void run() {
                        try {
                            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            startActivity(intent);
                        } catch (Exception e) {
                            toast("无法打开电池优化设置");
                        }
                    }
                }));
        perm.addView(divider());
        perm.addView(actionRow("全部文件访问",
                "用于直接读取共享存储上的 .dsh/.credentials.yaml（可选）", new Runnable() {
                    @Override public void run() {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Exception e) {
                                toast("无法打开该设置页");
                            }
                        } else {
                            toast("当前系统无需该权限");
                        }
                    }
                }));
        content.addView(perm);

        }
        if (currentTab == TAB_GENERAL) {
        // ---- 运行 ----
        // v1.13.0：角色（内置四角色 + Whale 小鲸鱼 + 用户自定义）——从 VK-1 页挪到通用页
                section("角色");
                LinearLayout charCard = cardContainer();
                PetCharacters.Current nowChar = PetCharacters.current(state);
                for (final PetCharacters.Current c : PetCharacters.all(state)) {
                    String kind = c.isCustom() ? "自定义·不显示余额"
                            : (c.hasTablet() ? "手持平板·显示余额" : "纯形象·不显示余额");
                    charCard.addView(actionRow(c.displayName,
                            (c.id.equals(nowChar.id) ? "✓ 当前使用 · " : "") + kind, new Runnable() {
                                @Override public void run() {
                                    if (c.isCustom()) PetCharacters.selectCustom(state, c.id);
                                    else PetCharacters.selectBuiltIn(state, c.builtIn);
                                    state.save();
                                    applyToService();
                                    buildUi();
                                }
                            }));
                }
                charCard.addView(divider());
                charCard.addView(actionRow("➕ 导入自定义角色…",
                        "选一张图（建议透明背景 PNG）→ 起个名字 → 立刻切换过去", new Runnable() {
                            @Override public void run() { pickCharacterImage(); }
                        }));
                charCard.addView(actionRow("管理自定义角色…",
                        "改名 / 删除（删除会连同图片一起删掉）", new Runnable() {
                            @Override public void run() { manageCustomCharacters(); }
                        }));
                content.addView(charCard);
        // v1.14.1：角色尺寸（所有角色共用）——从 VK-1 页「外观」挪到通用页
                section("角色尺寸");
                LinearLayout sizeCard = cardContainer();
                TextView sizeHint = new TextView(this);
                sizeHint.setText("尺寸（身体高度）");
                sizeHint.setTextColor(subColor());
                sizeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                sizeHint.setPadding(dp(14), dp(12), dp(14), dp(2));
                sizeCard.addView(sizeHint);
                for (int i = 0; i < PetLayout.SIZE_PRESETS_DP.length; i++) {
                    final int index = i;
                    sizeCard.addView(actionRow(PetLayout.SIZE_PRESET_NAMES[i]
                                    + "　" + (int) PetLayout.SIZE_PRESETS_DP[i] + "dp",
                            (!state.useCustomSize() && i == state.sizeIndex) ? "✓ 当前使用" : null, new Runnable() {
                                @Override public void run() {
                                    state.sizeIndex = index;
                                    state.customSideDp = 0f;   // 选预设即退出自定义
                                    state.save();
                                    applyToService();
                                    buildUi();
                                }
                            }));
                }
                sizeCard.addView(divider());
                sizeField = new EditText(this);
                sizeField.setInputType(InputType.TYPE_CLASS_NUMBER);
                sizeField.setHint("自定义尺寸（dp，身体高度）");
                sizeField.setText(state.useCustomSize() ? String.valueOf((int) state.customSideDp) : "");
                sizeField.setTextColor(textColor());
                sizeField.setHintTextColor(subColor());
                sizeField.setPadding(dp(14), dp(12), dp(14), dp(12));
                sizeCard.addView(sizeField);
                final float maxSide = PetLayout.clampSideDp(9999f,
                        getResources().getDisplayMetrics().widthPixels,
                        getResources().getDisplayMetrics().density);
                sizeCard.addView(actionRow("应用自定义尺寸",
                        "范围 " + (int) PetLayout.MIN_SIDE_DP + "–" + (int) maxSide + "dp（上限受屏宽限制）"
                                + (state.useCustomSize() ? "\n✓ 当前使用 " + (int) state.customSideDp + "dp" : ""),
                        new Runnable() {
                            @Override public void run() {
                                String text = sizeField.getText().toString().trim();
                                if (text.isEmpty()) {
                                    toast("请输入数字");
                                    return;
                                }
                                float dpValue;
                                try {
                                    dpValue = Float.parseFloat(text);
                                } catch (NumberFormatException e) {
                                    toast("请输入数字");
                                    return;
                                }
                                float clamped = PetLayout.clampSideDp(dpValue,
                                        getResources().getDisplayMetrics().widthPixels,
                                        getResources().getDisplayMetrics().density);
                                if (Math.abs(clamped - dpValue) > 0.5f) {
                                    toast("已自动调到合法范围：" + (int) clamped + "dp");
                                }
                                state.customSideDp = clamped;
                                state.save();
                                applyToService();
                                buildUi();
                            }
                        }));
                content.addView(sizeCard);
                        // v1.14.0：备份与恢复（设置 / 泡泡模块 / 角色 / 自定义角色图 / 记账）
                        section("备份与恢复");
                        LinearLayout backupCard = cardContainer();
                        backupCard.addView(actionRow("备份到文件…",
                                "设置 / 泡泡模块 / 角色 / 自定义角色图 / 记账，打成一个 zip（可选含 API Key）",
                                new Runnable() {
                                    @Override public void run() { askBackupOptions(); }
                                }));
                        backupCard.addView(divider());
                        backupCard.addView(actionRow("从备份恢复…",
                                "选一个 zip；会先读取元信息让你确认，并把当前配置另存一份",
                                new Runnable() {
                                    @Override public void run() { pickBackupFile(); }
                                }));
                        content.addView(backupCard);
                section("运行");
        LinearLayout run = cardContainer();
        run.addView(actionRow("启动 / 显示桌宠", "在桌面上出现角色", new Runnable() {
            @Override public void run() {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    toast("请先授予悬浮窗权限");
                    return;
                }
                startService(new Intent(MainActivity.this, PetService.class)
                        .setAction(PetService.ACTION_START));
                toast("桌宠已启动");
            }
        }));
        run.addView(divider());
        run.addView(actionRow("停止桌宠", "移除所有悬浮窗", new Runnable() {
            @Override public void run() {
                startService(new Intent(MainActivity.this, PetService.class)
                        .setAction(PetService.ACTION_STOP));
                toast("桌宠已停止");
            }
        }));
        run.addView(divider());
        run.addView(actionRow("立即刷新余额", "直接对齐最新余额，不补播动画", new Runnable() {
            @Override public void run() {
                startService(new Intent(MainActivity.this, PetService.class)
                        .setAction(PetService.ACTION_REFRESH));
            }
        }));
        run.addView(divider());
        run.addView(actionRow("测试一次扣费", "纯演示，不影响真实余额", new Runnable() {
            @Override public void run() {
                startService(new Intent(MainActivity.this, PetService.class)
                        .setAction(PetService.ACTION_ONE_HIT));
            }
        }));
        run.addView(divider());
        run.addView(actionRow("重新读取凭证", "换过 Key 或凭证文件后使用", new Runnable() {
            @Override public void run() {
                startService(new Intent(MainActivity.this, PetService.class)
                        .setAction(PetService.ACTION_RELOAD));
            }
        }));
        content.addView(run);

        // ---- 交互（通用） ----
        section("交互");
        LinearLayout inputCard = cardContainer();
        inputCard.addView(actionRow("长按桌宠",
                (state.longPressAction == PetState.LONG_PRESS_OFF
                        ? "已关闭（不弹出菜单）"
                        : "打开设置菜单（原版右键菜单的等价物）") + "（点击切换）", new Runnable() {
                    @Override public void run() {
                        final String[] names = {"打开设置菜单", "关闭（不响应长按）"};
                        final int[] values = {PetState.LONG_PRESS_MENU, PetState.LONG_PRESS_OFF};
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("长按桌宠")
                                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        state.longPressAction = values[which];
                                        state.save();
                                        applyToService();
                                        buildUi();
                                    }
                                })
                                .show();
                    }
                }));
        content.addView(inputCard);

        }
        if (currentTab == TAB_VK) {
                // ---- 外观与行为（尺寸已挪到通用页）----
                section("外观与行为");
                LinearLayout look = cardContainer();
        TextView offlineHint = new TextView(this);
        offlineHint.setText("未连接时的显示（仅蓝色大肥鱼）");
        offlineHint.setTextColor(subColor());
        offlineHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        offlineHint.setPadding(dp(14), dp(12), dp(14), dp(2));
        look.addView(offlineHint);
        look.addView(actionRow("抱盆图（上游 v1.3.1 行为）",
                "未配置凭证 / 连接中 / 连接失败时显示抱盆图，隐藏余额文字与状态点"
                        + (state.offlineArtOnDisconnect ? "\n✓ 当前使用" : ""),
                new Runnable() {
                    @Override public void run() {
                        state.offlineArtOnDisconnect = true;
                        state.save();
                        applyToService();
                        buildUi();
                    }
                }));
        look.addView(actionRow("平板图 + “--”（Windows 原版）",
                "无论有没有连上都举着平板；未连接时数字显示 --、状态点变红/黄"
                        + (!state.offlineArtOnDisconnect ? "\n✓ 当前使用" : ""),
                new Runnable() {
                    @Override public void run() {
                        state.offlineArtOnDisconnect = false;
                        state.save();
                        applyToService();
                        buildUi();
                    }
                }));
        look.addView(divider());
        TextView renderHint = new TextView(this);
        renderHint.setText("渲染模式");
        renderHint.setTextColor(subColor());
        renderHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        renderHint.setPadding(dp(14), dp(12), dp(14), dp(2));
        look.addView(renderHint);
        final int[] windowModes = {PetState.MODE_SINGLE, PetState.MODE_OUTLINE};
        final String[] windowNames = {"单窗口（流畅）", "轮廓穿透（精确）"};
        final String[] windowNotes = {
                "只用 1 个悬浮窗，最省资源；代价是透明区域也会吃掉点击",
                "8~12 个窗口精确贴合轮廓，透明处可穿透点击；更吃性能"
        };
        for (int i = 0; i < windowModes.length; i++) {
            final int mode = windowModes[i];
            look.addView(actionRow(windowNames[i],
                    windowNotes[i] + (state.windowMode == mode ? "\n✓ 当前使用" : ""),
                    new Runnable() {
                        @Override public void run() {
                            state.windowMode = mode;
                            state.save();
                            applyToService();
                            buildUi();
                        }
                    }));
        }
        look.addView(divider());
        TextView dragHint = new TextView(this);
        dragHint.setText("拖动行为");
        dragHint.setTextColor(subColor());
        dragHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dragHint.setPadding(dp(14), dp(12), dp(14), dp(2));
        look.addView(dragHint);
        final int[] dragModes = {PetState.DRAG_FREE, PetState.DRAG_EDGE, PetState.DRAG_CORNER};
        final String[] dragNames = {"自由拖动（停在原位）", "吸附最近边缘", "吸附左下角（原版）"};
        for (int i = 0; i < dragModes.length; i++) {
            final int mode = dragModes[i];
            look.addView(actionRow(dragNames[i],
                    state.dragMode == mode ? "✓ 当前使用" : null, new Runnable() {
                        @Override public void run() {
                            state.dragMode = mode;
                            state.snapOnRelease = mode == PetState.DRAG_CORNER;
                            state.save();
                            applyToService();
                            buildUi();
                        }
                    }));
        }
        look.addView(divider());
        look.addView(actionRow("把桌宠拉回左下角", "自由拖动下丢失位置时用", new Runnable() {
            @Override public void run() {
                startService(new Intent(MainActivity.this, PetService.class)
                        .setAction(PetService.ACTION_SNAP));
            }
        }));
        look.addView(divider());
        look.addView(actionRow("音效（原版 hit.mp3）",
                state.soundOn ? "✓ 已开启" : "已关闭", new Runnable() {
                    @Override public void run() {
                        state.soundOn = !state.soundOn;
                        state.save();
                        applyToService();
                        buildUi();
                    }
                }));
        look.addView(divider());
        look.addView(actionRow("当前音效：" + describeSound(),
                "支持 mp3 / wav / ogg。也可以直接把文件放到 Download/DSHPet/hit.mp3 覆盖内置音效",
                new Runnable() {
                    @Override public void run() {
                        startService(new Intent(MainActivity.this, PetService.class)
                                .setAction(PetService.ACTION_TEST_SOUND));
                    }
                }));
        look.addView(actionRow("选择音效文件…", "从系统文件选择器里挑一个音频文件（会复制到应用目录）",
                new Runnable() {
                    @Override public void run() {
                        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        pick.addCategory(Intent.CATEGORY_OPENABLE);
                        pick.setType("audio/*");
                        try {
                            startActivityForResult(pick, REQUEST_PICK_SOUND);
                        } catch (Exception e) {
                            toast("无法打开文件选择器：" + e.getMessage());
                        }
                    }
                }));
        look.addView(actionRow("恢复内置音效", "删除自定义音效，回到原版 hit.mp3",
                new Runnable() {
                    @Override public void run() {
                        PetPaths.customSoundFile().delete();
                        state.soundPath = "";
                        state.save();
                        startService(new Intent(MainActivity.this, PetService.class)
                                .setAction(PetService.ACTION_RELOAD_SOUND));
                        buildUi();
                    }
                }));
        look.addView(divider());
        TextView volumeHint = new TextView(this);
        volumeHint.setText("音量（" + Math.round(state.volume * 100) + "%）");
        volumeHint.setTextColor(subColor());
        volumeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        volumeHint.setPadding(dp(14), dp(12), dp(14), dp(2));
        look.addView(volumeHint);
        final SeekBar volumeBar = new SeekBar(this);
        volumeBar.setMax(100);
        volumeBar.setProgress((int) Math.round(state.volume * 100));
        volumeBar.setPadding(dp(14), 0, dp(14), dp(6));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volumeHint.setText("音量（" + progress + "%）");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                state.volume = Math.min(1, Math.max(0, bar.getProgress() / 100.0));
                state.save();
                applyToService();
            }
        });
        look.addView(volumeBar);
        look.addView(divider());
        TextView throttleHint = new TextView(this);
        throttleHint.setText("连击音效节流（余额连续下降时）");
        throttleHint.setTextColor(subColor());
        throttleHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        throttleHint.setPadding(dp(14), dp(12), dp(14), dp(2));
        look.addView(throttleHint);
        final int[] throttles = {0, 300, 1000};
        final String[] throttleNames = {
                "关（忠于原版：每次扣费都响）",
                "每 300 毫秒最多响一次",
                "每 1 秒最多响一次"
        };
        for (int i = 0; i < throttles.length; i++) {
            final int value = throttles[i];
            look.addView(actionRow(throttleNames[i],
                    state.soundThrottleMs == value ? "✓ 当前使用" : null, new Runnable() {
                        @Override public void run() {
                            state.soundThrottleMs = value;
                            state.save();
                            applyToService();
                            buildUi();
                        }
                    }));
        }
        look.addView(divider());
        look.addView(actionRow("开机自动启动",
                state.autoStart ? "✓ 已开启" : "已关闭", new Runnable() {
                    @Override public void run() {
                        state.autoStart = !state.autoStart;
                        state.save();
                        buildUi();
                    }
                }));
        content.addView(look);

        }
        if (currentTab == TAB_WHALE) {
        // ---- Whale 挂件：泡泡外观（第 1 步先做颜色/透明度自定义） ----
        section("Whale挂件 · 泡泡外观");
        LinearLayout bubbleCard = cardContainer();
        TextView bubbleHint = new TextView(this);
        bubbleHint.setText("默认值取自 WhaleWidget 源码（白底 #FFFFFF、描边 #203170、正文 #536BA9）。"
                + "点一下桌宠即可看到效果；底色偏暗时会自动把文字换成浅色（手动改过文字色则以你为准）。");
        bubbleHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bubbleHint.setTextColor(subColor());
        bubbleHint.setPadding(dp(14), dp(12), dp(14), dp(6));
        bubbleCard.addView(bubbleHint);
bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("泡泡大小",
                PetState.bubbleScaleLabel(state.bubbleScalePercent) + "（点击选择）", new Runnable() {
                    @Override public void run() {
                        final String[] names = {
                                "50%（很小）", "60%", "75%", "80%", "90%",
                                "100%（原版大小，默认）", "110%", "125%", "150%", "200%（很大）",
                                "自定义…",
                        };
                        final int[] values = {50, 60, 75, 80, 90, 100, 110, 125, 150, 200, -1};
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("泡泡大小（当前 " + state.bubbleScalePercent + "%）")
                                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        if (values[which] < 0) {
                                            promptText("自定义泡泡大小（%）",
                                                    String.valueOf(state.bubbleScalePercent),
                                                    PetState.BUBBLE_SCALE_MIN + "–" + PetState.BUBBLE_SCALE_MAX,
                                                    new java.util.function.Consumer<String>() {
                                                        @Override public void accept(String v) {
                                                            double pct = parseDoubleSafe(v);
                                                            if (pct < 0) {
                                                                toast("请输入数字");
                                                                return;
                                                            }
                                                            state.bubbleScalePercent = PetState.clampBubbleScale(
                                                                    (int) Math.round(pct));
                                                            state.save();
                                                            applyToService();
                                                            buildUi();
                                                        }
                                                    });
                                            return;
                                        }
                                        state.bubbleScalePercent = values[which];
                                        state.save();
                                        applyToService();
                                        buildUi();
                                    }
                                })
                                .show();
                    }
                }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("泡泡位置",
                "贴近屏幕顶部时：" + PetState.bubbleTopModeLabel(state.bubbleTopMode)
                        + "（点击切换；共三种可选）", new Runnable() {
                    @Override public void run() {
                        final String[] names = {
                                "等比缩小泡泡（默认，会缩到 55% 下限）",
                                "不显示泡泡（顶部空间不足就不弹）",
                                "显示在桌宠下方（形状翻转，尾巴朝上）",
                        };
                        final int[] values = {PetState.BUBBLE_TOP_SHRINK, PetState.BUBBLE_TOP_HIDE,
                                PetState.BUBBLE_TOP_BELOW};
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("泡泡位置")
                                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        state.bubbleTopMode = values[which];
                                        state.save();
                                        applyToService();
                                        buildUi();
                                    }
                                })
                                .show();
                    }
                }));
        bubbleCard.addView(actionRow("泡泡留存时间",
                PetState.bubbleTtlLabel(state.bubbleTtlMs) + "（点击选择；0 = 常驻）", new Runnable() {
                    @Override public void run() {
                        final String[] names = {
                                "3 秒", "5 秒（原插件值，默认）", "6 秒（本工程旧值）",
                                "8 秒", "10 秒", "15 秒",
                                "不自动收起（常驻，点一下才关）", "自定义…",
                        };
                        final int[] values = {3000, 5000, 6000, 8000, 10000, 15000, 0, -1};
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("泡泡留存时间")
                                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        if (values[which] < 0) {
                                            promptText("自定义留存时间（秒）",
                                                    String.valueOf(state.bubbleTtlMs / 1000.0),
                                                    "0 = 不自动收起（常驻）",
                                                    new java.util.function.Consumer<String>() {
                                                        @Override public void accept(String v) {
                                                            double sec = parseDoubleSafe(v);
                                                            if (sec < 0) {
                                                                toast("请输入数字（秒），0 表示常驻");
                                                                return;
                                                            }
                                                            state.bubbleTtlMs = PetState.clampBubbleTtl(
                                                                    (int) Math.round(sec * 1000));
                                                            state.save();
                                                            applyToService();
                                                            buildUi();
                                                        }
                                                    });
                                            return;
                                        }
                                        state.bubbleTtlMs = PetState.clampBubbleTtl(values[which]);
                                        state.save();
                                        applyToService();
                                        buildUi();
                                    }
                                })
                                .show();
                    }
                }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("泡泡背景色",
                String.format(Locale.US, "#%06X", state.bubbleFillColor & 0xFFFFFF) + "（点击选择）",
                new Runnable() {
                    @Override public void run() {
                        pickColor(state.bubbleFillColor & 0xFFFFFF, "泡泡背景色", new java.util.function.IntConsumer() {
                            @Override public void accept(int value) {
                                state.bubbleFillColor = value;
                                state.save();
                                applyToService();    // 通知运行中的服务重新读盘，否则改了看不出效果
                                buildUi();
                            }
                        });
                    }
                }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("背景不透明度", alphaLabel(state.bubbleFillAlpha), new Runnable() {
            @Override public void run() {
                pickAlpha(state.bubbleFillAlpha, "背景不透明度", new java.util.function.IntConsumer() {
                    @Override public void accept(int value) {
                        state.bubbleFillAlpha = value;
                        state.save();
                        applyToService();    // 通知运行中的服务重新读盘，否则改了看不出效果
                        buildUi();
                    }
                });
            }
        }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("泡泡描边色",
                String.format(Locale.US, "#%06X", state.bubbleStrokeColor & 0xFFFFFF) + "（点击选择）",
                new Runnable() {
                    @Override public void run() {
                        pickColor(state.bubbleStrokeColor & 0xFFFFFF, "泡泡描边色", new java.util.function.IntConsumer() {
                            @Override public void accept(int value) {
                                state.bubbleStrokeColor = value;
                                state.save();
                                applyToService();    // 通知运行中的服务重新读盘，否则改了看不出效果
                                buildUi();
                            }
                        });
                    }
                }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("描边不透明度", alphaLabel(state.bubbleStrokeAlpha), new Runnable() {
            @Override public void run() {
                pickAlpha(state.bubbleStrokeAlpha, "描边不透明度", new java.util.function.IntConsumer() {
                    @Override public void accept(int value) {
                        state.bubbleStrokeAlpha = value;
                        state.save();
                        applyToService();    // 通知运行中的服务重新读盘，否则改了看不出效果
                        buildUi();
                    }
                });
            }
        }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("按压形变",
                state.pressSquashOn
                        ? "✓ 已开启（按下 scaleY0.88/scaleX1.05，220ms 回弹，与原版一致）"
                        : "已关闭", new Runnable() {
                    @Override public void run() {
                        state.pressSquashOn = !state.pressSquashOn;
                        state.save();
                        applyToService();
                        buildUi();
                    }
                }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("双击桌宠",
                (state.doubleTapAction == PetState.DOUBLE_TAP_SQUASH
                        ? "挤压效果（按住 0.4s 再回弹，不扣费）"
                        : "扣费效果（原版：手动触发一次扣费动画）") + "（点击切换）", new Runnable() {
                    @Override public void run() {
                        final String[] names = {"扣费效果（原版语义）", "挤压效果（不扣费）"};
                        final int[] values = {PetState.DOUBLE_TAP_HIT, PetState.DOUBLE_TAP_SQUASH};
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("双击桌宠")
                                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        state.doubleTapAction = values[which];
                                        state.save();
                                        applyToService();
                                        buildUi();
                                    }
                                })
                                .show();
                    }
                }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("按压音效",
                pressSetLabel() + "（点击切换）", new Runnable() {
                    @Override public void run() {
                        final String[] names = {"小黄鸭（原版默认 Ya1/Ya2）", "音效1（D1/D2）", "关闭"};
                        final String[] values = {"duck", "fx1", "off"};
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("按压音效组")
                                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int which) {
                                        state.pressSoundSet = values[which];
                                        state.save();
                                        applyToService();
                                        buildUi();
                                    }
                                })
                                .show();
                    }
                }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("按压音量",
                Math.round(state.pressVolume * 100) + "%", new Runnable() {
                    @Override public void run() {
                        pickAlpha((int) Math.round(state.pressVolume * 255), "按压音量",
                                new java.util.function.IntConsumer() {
                                    @Override public void accept(int value) {
                                        state.pressVolume = value / 255.0;
                                        state.save();
                                        applyToService();
                                        buildUi();
                                    }
                                });
                    }
                }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("试听按压音", "按一下→松开 的完整音频", new Runnable() {
            @Override public void run() {
                startService(new Intent(MainActivity.this, PetService.class)
                        .setAction(PetService.ACTION_TEST_PRESS));
            }
        }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("恢复默认外观", "回到 WhaleWidget 原版配色", new Runnable() {
            @Override public void run() {
                state.bubbleFillColor = 0xFFFFFF;
                state.bubbleFillAlpha = 255;
                state.bubbleStrokeColor = 0x203170;
                state.bubbleStrokeAlpha = 255;
                state.bubbleTextColor = 0x536BA9;
                state.bubbleHintColor = 0x9FB0D9;
                state.save();
                applyToService();    // 通知运行中的服务重新读盘，否则改了看不出效果
                buildUi();
            }
        }));
        bubbleCard.addView(divider());
        bubbleCard.addView(actionRow("预览泡泡", "立刻弹一个泡泡看看效果", new Runnable() {
            @Override public void run() {
                startService(new Intent(MainActivity.this, PetService.class)
                        .setAction(PetService.ACTION_TEST_BUBBLE));
            }
        }));
        content.addView(bubbleCard);

        // ---- Whale 挂件：泡泡编辑器（v1.9.0：内容 / 峰谷 / 文字颜色 / 点击序列 全部收进一个页面） ----
        section("Whale挂件 · 泡泡编辑器");
        LinearLayout editorCard = cardContainer();
        TextView editorHint = new TextView(this);
        editorHint.setText(RichText.bold("**只留这一个内容入口**：默认泡泡、第 1/2/3…次点击的内容、峰谷（全局）、"
                + "泡泡文字颜色、点击序列结构，全在编辑器里改 —— 顶部切换目标、底部一次保存。"
                + "（历史上分开的那几个入口已合并，避免「改了一个、在另一个里看到老内容」。）"));
        editorHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        editorHint.setTextColor(subColor());
        editorHint.setPadding(dp(14), dp(12), dp(14), dp(6));
        editorCard.addView(editorHint);

        editorCard.addView(actionRow("打开泡泡编辑器 ★",
                editorSummaryText(), new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(MainActivity.this, PetBubbleEditorActivity.class));
                    }
                }));
        editorCard.addView(divider());
        editorCard.addView(actionRow("点按角色推进队列（tapAdvance）",
                PetBubbleSeq.tapAdvanceOf(state.bubbleSeqJson)
                        ? "✓ 已开启（点桌宠 = 推进到下一次点击的内容）"
                        : "已关闭（默认：点桌宠只续时 / 回到第 1 次点击）",
                new Runnable() {
                    @Override public void run() {
                        boolean now = PetBubbleSeq.tapAdvanceOf(state.bubbleSeqJson);
                        state.bubbleSeqJson = PetBubbleSeq.withTapAdvance(state.bubbleSeqJson, !now);
                        state.save();
                        applyToService();
                        buildUi();
                    }
                }));
        content.addView(editorCard);

        }
        if (currentTab == TAB_GENERAL) {
        // ---- 帧率（Android 独有：上游是固定 30fps 定时器） ----
        section("动画帧率");
        LinearLayout fpsCard = cardContainer();
        TextView fpsHint = new TextView(this);
        fpsHint.setText("上游是固定 30fps；本移植版可以吃满屏幕刷新率。"
                + "档位越高越顺滑，代价是耗电与发热。");
        fpsHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        fpsHint.setTextColor(subColor());
        fpsHint.setPadding(dp(14), dp(12), dp(14), dp(6));
        fpsCard.addView(fpsHint);
        final int[] fpsOptions = {PetTuning.TARGET_FPS_FOLLOW, 120, 90, 60, 30};
        final String[] fpsNames = {"跟随屏幕刷新率（推荐）", "120 fps（超屏幕时自动取屏幕上限）",
                "90 fps（本机屏幕上限）", "60 fps", "30 fps（与原版一致）"};
        for (int i = 0; i < fpsOptions.length; i++) {
            final int value = fpsOptions[i];
            if (i > 0) fpsCard.addView(divider());
            fpsCard.addView(actionRow(fpsNames[i],
                    state.targetFps == value ? "✓ 当前使用" : null, new Runnable() {
                        @Override public void run() {
                            state.targetFps = value;
                            state.save();
                            applyToService();
                            buildUi();
                        }
                    }));
        }
        fpsCard.addView(divider());
        perfLine = new TextView(this);
        perfLine.setTextColor(textColor());
        perfLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        perfLine.setPadding(dp(14), dp(12), dp(14), dp(12));
        fpsCard.addView(perfLine);
        content.addView(fpsCard);

        }
        if (currentTab == TAB_VK) {
        // ---- 刷新间隔 ----
        section("刷新间隔");
        LinearLayout interval = cardContainer();
        final double[] secondsList = {1, 5, 10, 30, 60, 300, 600};
        for (int i = 0; i < secondsList.length; i++) {
            final double seconds = secondsList[i];
            if (i > 0) interval.addView(divider());
            interval.addView(actionRow(
                    seconds < 60 ? ((int) seconds) + " 秒" : ((int) (seconds / 60)) + " 分钟",
                    Math.abs(seconds - state.pollSeconds) < 0.5 ? "✓ 当前使用" : null, new Runnable() {
                        @Override public void run() {
                            state.pollSeconds = seconds;
                            state.save();
                            applyToService();
                            buildUi();
                        }
                    }));
        }
        interval.addView(divider());
        intervalField = new EditText(this);
        intervalField.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalField.setHint("自定义间隔（秒，1–600）");
        intervalField.setText(String.valueOf((int) state.pollSeconds));
        intervalField.setTextColor(textColor());
        intervalField.setHintTextColor(subColor());
        intervalField.setPadding(dp(14), dp(12), dp(14), dp(12));
        interval.addView(intervalField);
        interval.addView(actionRow("应用自定义间隔",
                "范围 1 秒 – 600 秒（10 分钟）。间隔过密时 429 退避会自动接管",
                new Runnable() {
                    @Override public void run() {
                        String text = intervalField.getText().toString().trim();
                        if (text.isEmpty()) {
                            toast("请输入秒数");
                            return;
                        }
                        double seconds;
                        try {
                            seconds = Double.parseDouble(text);
                        } catch (NumberFormatException e) {
                            toast("请输入数字");
                            return;
                        }
                        double clamped = Math.min(600, Math.max(1, seconds));
                        if (Math.abs(clamped - seconds) > 0.001) {
                            toast("已自动调到 1–600 秒：" + (int) clamped + " 秒");
                        }
                        state.pollSeconds = clamped;
                        state.save();
                        applyToService();
                        buildUi();
                    }
                }));
        content.addView(interval);

        }
        if (currentTab == TAB_VK) {
        // ---- 凭证 ----
        section("凭证与余额");
        LinearLayout cred = cardContainer();
        TextView credInfo = new TextView(this);
        String src = "（未找到凭证）";
        if (state.offline) {
            src = "离线模式已开启，不会读取凭证或联网";
        } else {
            CredentialStore.Credential c = CredentialStore.resolve(false, state.apiPath);
            if (c != null) src = c.shortDescription() + "\n接口：" + c.endpoint;
        }
        credInfo.setText("当前来源：" + src
                + "\n应用内 apikey.txt：" + describeStoredKey()
                + "\n查找顺序：Download/DSHPet/apikey.txt → 应用内 apikey.txt → 导入的 credentials.yaml → 共享存储 .dsh/.credentials.yaml"
                + (PetPaths.sidecarKeyFile().exists()
                ? "\n⚠ Download/DSHPet/apikey.txt 存在，它的优先级高于这里保存的 Key" : ""));
        credInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        credInfo.setTextColor(subColor());
        credInfo.setPadding(dp(14), dp(12), dp(14), dp(12));
        cred.addView(credInfo);
        cred.addView(divider());

        keyField = new EditText(this);
        keyField.setHint("sk-...（留空不修改）");
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setTextColor(textColor());
        keyField.setHintTextColor(subColor());
        keyField.setPadding(dp(14), dp(12), dp(14), dp(12));
        cred.addView(keyField);
        cred.addView(actionRow("保存 API Key 并立即验证",
                "写入应用配置目录的 apikey.txt，然后向 DeepSeek 实测一次余额",
                new Runnable() {
                    @Override public void run() {
                        String value = keyField.getText().toString().trim();
                        if (value.isEmpty()) {
                            toast("留空不修改");
                            return;
                        }
                        CredentialStore.Credential candidate =
                                CredentialStore.apiKeyCredential(value, "刚保存的 Key");
                        if (candidate == null) {
                            dialog("API Key 无法保存", "Key 必须是单行可打印字符（不含空格/换行）。");
                            return;
                        }
                        try {
                            CredentialStore.saveAPIKey(value, PetPaths.userKeyFile());
                            keyField.setText("");
                            toast("已保存，开始验证…");
                            startService(new Intent(MainActivity.this, PetService.class)
                                    .setAction(PetService.ACTION_RELOAD));
                            verifyCredential(candidate);
                            buildUi();
                        } catch (Exception e) {
                            dialog("API Key 未保存", String.valueOf(e.getMessage()));
                        }
                    }
                }));
        cred.addView(divider());
        cred.addView(actionRow("验证当前凭证",
                "按当前生效的凭证向 DeepSeek 查一次余额（不修改任何配置）",
                new Runnable() {
                    @Override public void run() {
                        CredentialStore.Credential current =
                                CredentialStore.resolve(state.offline, state.apiPath);
                        if (current == null) {
                            dialog("没有可用凭证",
                                    "请先设置 API Key，或把 Key 放到 Download/DSHPet/apikey.txt。"
                                            + (state.offline ? "\n\n注意：当前开启了离线模式。" : ""));
                            return;
                        }
                        verifyCredential(current);
                    }
                }));
        cred.addView(divider());
        cred.addView(actionRow("离线模式（不联网）",
                state.offline ? "✓ 已开启" : "已关闭", new Runnable() {
                    @Override public void run() {
                        state.offline = !state.offline;
                        state.save();
                        applyToService();
                        buildUi();
                    }
                }));
        cred.addView(divider());
        final EditText pathField = new EditText(this);
        pathField.setHint("账号接口路径，默认 /api/v0/users/get_user_summary");
        pathField.setText(state.apiPath);
        pathField.setTextColor(textColor());
        pathField.setHintTextColor(subColor());
        pathField.setPadding(dp(14), dp(12), dp(14), dp(12));
        cred.addView(pathField);
        cred.addView(actionRow("保存接口路径", "对应原版 DSHPET_API_PATH", new Runnable() {
            @Override public void run() {
                state.apiPath = pathField.getText().toString().trim();
                state.save();
                applyToService();
                toast("已保存");
            }
        }));
        content.addView(cred);

        }
        if (currentTab == TAB_GENERAL) {
        // ---- 诊断 ----
        section("诊断");
        LinearLayout diag = cardContainer();
        diag.addView(actionRow("运行离线自检", "账本 / 调度 / 响应解析 / 凭证 / 素材", new Runnable() {
            @Override public void run() { showSelfTest(); }
        }));
        diag.addView(divider());
        diag.addView(actionRow("查看日志", "pet.log 的结尾部分", new Runnable() {
            @Override public void run() { showLog(); }
        }));
        diag.addView(divider());
        diag.addView(actionRow("查看状态快照", "status.json（每秒更新）", new Runnable() {
            @Override public void run() {
                String text = CredentialStore.readText(PetPaths.statusFile());
                dialog("状态快照 status.json", text == null ? "(还没有快照)" : text);
            }
        }));
        diag.addView(divider());
        diag.addView(actionRow("上游版本与素材基线", "本项目对照的上游 commit 与素材哈希",
                new Runnable() {
                    @Override public void run() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("上游仓库：").append(UpstreamInfo.REPO).append("\n")
                                .append("上游版本：").append(UpstreamInfo.UPSTREAM_VERSION).append("\n")
                                .append("对照 commit：").append(UpstreamInfo.UPSTREAM_COMMIT).append("\n")
                                .append("commit 日期：").append(UpstreamInfo.UPSTREAM_COMMIT_DATE).append("\n")
                                .append("本移植版：").append(UpstreamInfo.PORT_VERSION).append("\n\n")
                                .append("文件对应关系：\n");
                        for (String line : UpstreamInfo.FILE_MAP) sb.append("· ").append(line).append("\n");
                        sb.append("\n素材基线哈希：\n");
                        for (String line : UpstreamInfo.ASSET_HASHES) sb.append("· ").append(line.trim()).append("\n");
                        sb.append("\n升级步骤见项目里的 UPGRADE.md。");
                        dialog("上游版本基线", sb.toString());
                    }
                }));
        diag.addView(divider());
        diag.addView(actionRow("校验素材与上游是否一致", "对打包素材做 SHA-256 比对", new Runnable() {
            @Override public void run() { dialog("素材校验", AssetVerifier.verify(MainActivity.this)); }
        }));
        diag.addView(divider());
        diag.addView(actionRow("控制令牌（adb / Shizuku 脚本用）",
                "转发命令必须携带它，否则被拒绝", new Runnable() {
                    @Override public void run() {
                        dialog("控制令牌",
                                "把它作为附加项传给本应用即可远程控制桌宠（仅 adb/Shizuku 脚本需要）：\n\n"
                                        + "token = " + PetPaths.controlToken() + "\n\n"
                                        + "示例：\n"
                                        + "am start -n com.dsh.balancepet/.MainActivity \\\n"
                                        + "  --es forward com.dsh.balancepet.START \\\n"
                                        + "  --es token " + PetPaths.controlToken() + " \\\n"
                                        + "  --ez quiet true\n\n"
                                        + "令牌保存在应用私有目录，第三方应用读不到，因此无法伪造。");
                    }
                }));
        diag.addView(divider());
        diag.addView(actionRow("清空日志", null, new Runnable() {
            @Override public void run() {
                Log.clear();
                toast("日志已清空");
            }
        }));
        content.addView(diag);
        // v1.14.3：关于 / 更新记录（正文来自仓库根目录的 CHANGELOG.md，build.sh 构建时打进 assets/）
        section("关于");
        LinearLayout aboutCard = cardContainer();
        aboutCard.addView(actionRow("关于 · 更新记录…",
                "版本 / 许可 / 上游与致谢 / 完整更新记录（也可以点最上面那行版本号）",
                new Runnable() {
                    @Override public void run() { showAbout(); }
                }));
        content.addView(aboutCard);

        }

        TextView footer = new TextView(this);
        footer.setText("说明：本应用不修改 DSH 任何文件，只读取凭证并访问 DeepSeek 官方余额接口；"
                + "不联网上传任何数据。悬浮窗采用轮廓多窗口实现「透明处穿透点击」。");
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setTextColor(subColor());
        footer.setPadding(0, dp(18), 0, 0);
        content.addView(footer);
    }

    private void applyToService() {
        startService(new Intent(this, PetService.class).setAction(PetService.ACTION_APPLY));
        refreshStatus();
    }

    // ------------------------------------------------------------------ 小工具

    // ------------------------------------------------------------------ 顶部标签页

    /** 顶部三个分类：通用 / VK-1 / Whale挂件。点一下重建内容（buildUi 自身会 removeAllViews）。 */
    private LinearLayout buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(6));
        bar.setBackgroundColor(bg());
        String[] names = {"通用", "VK-1", "Whale挂件"};
        for (int i = 0; i < names.length; i++) {
            final int tab = i;
            TextView tv = new TextView(this);
            tv.setText(names[i]);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setPadding(dp(10), dp(9), dp(10), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(8));
            tv.setLayoutParams(lp);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (currentTab == tab) return;
                    currentTab = tab;
                    styleTabs();
                    buildUi();
                }
            });
            tabViews[i] = tv;
            bar.addView(tv);
        }
        styleTabs();
        return bar;
    }

    /** 选中态：强调色实心底 + 白字；未选中：浅灰底 + 正文色。 */
    private void styleTabs() {
        for (int i = 0; i < tabViews.length; i++) {
            TextView tv = tabViews[i];
            if (tv == null) continue;
            boolean on = i == currentTab;
            tv.setTextColor(on ? Color.WHITE : textColor());
            tv.setBackground(pill(on ? accent()
                    : (night ? Color.rgb(42, 45, 49) : Color.rgb(236, 239, 243))));
        }
    }

    private android.graphics.drawable.GradientDrawable pill(int color) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        return g;
    }

    private void section(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(accent());
        tv.setPadding(dp(4), dp(22), dp(4), dp(8));
        content.addView(tv);
    }

    private LinearLayout cardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(card());
        return card;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(night ? Color.rgb(52, 55, 59) : Color.rgb(233, 235, 238));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f)));
        lp.setMargins(dp(14), 0, dp(14), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private View actionRow(String title, String subtitle, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(textColor());
        row.addView(tv);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTextColor(subColor());
            sub.setPadding(0, dp(3), 0, 0);
            row.addView(sub);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return row;
    }

    private GradientDrawable card() {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(12));
        d.setColor(cardBg());
        return d;
    }


    public void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void dialog(String title, String body) {
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(textColor());
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showLog() {
        dialog("pet.log（结尾）", Log.tail(12000));
    }

    private void showSelfTest() {
        dialog("离线自检结果", SelfTests.run(this));
    }

    /**
     * v1.14.3：关于 · 更新记录。
     *
     * <p>更新记录的正文来自仓库根目录的 {@code CHANGELOG.md}：{@code build.sh} 会在构建时把它
     * 复制成 {@code assets/CHANGELOG.md}（构建产物、不入库），所以这里显示的就是仓库里那一份，
     * 不会出现「App 里写的更新记录和仓库对不上」。
     */
    private void showAbout() {
        String ver = VERSION.endsWith("-android")
                ? VERSION.substring(0, VERSION.length() - "-android".length()) : VERSION;
        StringBuilder sb = new StringBuilder();
        sb.append("DeepSeek 余额桌宠 · Android 移植版\n")
                .append("版本：").append(VERSION).append("\n")
                .append("许可：代码 MIT；素材分两栏（详见 LICENSE 与 NOTICE）\n\n")
                .append("上游基线：").append(UpstreamInfo.UPSTREAM_VERSION)
                .append(" @ ").append(UpstreamInfo.UPSTREAM_COMMIT).append("\n")
                .append("（").append(UpstreamInfo.UPSTREAM_COMMIT_DATE).append("）\n\n")
                .append("本项目 = 移植（在 Android 上重新实现）+ 融合（两套体系合为一套），")
                .append("两个上游都要感谢：\n")
                .append("· VKmich16/VK-1 —— 原版桌面宠物（Windows PowerShell + macOS Swift）\n")
                .append("   ").append(UpstreamInfo.REPO).append("\n")
                .append("· MeteorNOX/DeepSeek-Balance-Whale-Widget —— 网页挂件（泡泡体系）\n")
                .append("   ").append(UpstreamInfo.REPO_WHALE).append("\n\n")
                .append("本仓库：").append(UpstreamInfo.REPO_SELF).append("\n\n")
                .append("作者：纯 AI 结对编程（webcoding）产物\n")
                .append("　第一作者 ").append(UpstreamInfo.AUTHOR_AI_PRIMARY).append("\n")
                .append("　第二作者 ").append(UpstreamInfo.AUTHOR_AI_SECOND).append("\n")
                .append("　通讯作者 ").append(UpstreamInfo.AUTHOR_CONTACT).append("\n")
                .append("所使用的 AI Agent 应用：").append(UpstreamInfo.AGENT_APP)
                .append("（").append(UpstreamInfo.AGENT_APP_REPO).append("）\n\n")
                .append("素材边界：assets/sprite-whale.png 与 res/raw/ya1|ya2|d1|d2.mp3 ")
                .append("不在本项目 MIT 范围内（原样携带，as-is）\n")
                .append("侵权处理与联系方式：见仓库 README\n\n")
                .append("————————————————————————————\n\n")
                .append(readChangelogAsset());
        dialog("关于 · 更新记录 " + ver, sb.toString());
    }

    /** 读构建时打进 assets 的 CHANGELOG.md；读不到就如实说明，不假装成功。 */
    private String readChangelogAsset() {
        java.io.InputStream in = null;
        try {
            in = getAssets().open("CHANGELOG.md");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String text = new String(bos.toByteArray(), "UTF-8");
            // 让纯文本视图里更好读：把 markdown 的标题记号换成符号
            return text.replace("### ", "■ ").replace("## ", "◆ ");
        } catch (Exception e) {
            return "（更新记录不可用：读不到 assets/CHANGELOG.md —— " + e + "）";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
        }
    }


    private String alphaLabel(int alpha) {
        return Math.round(alpha / 255f * 100) + "%（" + alpha + "/255）";
    }

    private String pressSetLabel() {
        if ("fx1".equals(state.pressSoundSet)) return "音效1（D1/D2）";
        if ("off".equals(state.pressSoundSet)) return "已关闭";
        return "小黄鸭（Ya1/Ya2）";
    }

    /** 颜色选择：预设色板 + 自定义 #RRGGBB。 */
    /**
     * 带输入框的小对话框（v1.5.0：峰谷两态自定义文字用）。
     * 留空 = 清空该项（回到上游默认文案）。
     */
public void promptText(String title, String initial, String hint,
                                     final java.util.function.Consumer<String> consumer) {
        final EditText field = new EditText(this);
        field.setText(initial == null ? "" : initial);
        field.setHint(hint == null ? "" : hint);
        field.setHintTextColor(subColor());
        field.setSingleLine(true);
        LinearLayout wrap = new LinearLayout(this);
        int pad = dp(18);
        wrap.setPadding(pad, dp(4), pad, 0);
        wrap.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        consumer.accept(field.getText().toString().trim());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 「泡泡编辑器」入口行的摘要（v1.9.0：设置页只留一个内容入口，这里显示它现在装着什么）。
     */
    private String editorSummaryText() {
        java.util.List<PetBubbleModule> defs = PetBubbleModule.listFromJson(state.bubbleModulesJson);
        PetBubbleSeq seq = PetBubbleSeq.fromJson(state.bubbleSeqJson);
        int seqCount = seq.items.isEmpty() ? PetBubbleSeq.defaultSeq().items.size() : seq.items.size();
        return "默认泡泡 " + (defs.isEmpty() ? "经典四行" : defs.size() + " 个模块")
                + " · 点击序列 " + seqCount + " 项（点桌宠/点泡泡各显一个）"
                + " · 峰谷 " + (state.peakShow ? "开" : "关")
                + "（" + PeakValley.styleDisplayName(state.peakStyle) + "）"
                + " · 文字色 #" + String.format(Locale.US, "%06X", state.bubbleTextColor & 0xFFFFFF)
                + "/#" + String.format(Locale.US, "%06X", state.bubbleHintColor & 0xFFFFFF)
                + "\n点它打开编辑器（顶部切换「默认泡泡 / 第 1..N 个」，底部一次保存）";
    }

    /** 「峰谷显示」当前值摘要（v1.7.0：设置页只读显示，改值请到模块化编辑器 → ⚙ 峰谷（全局设置））。 */
    private String peakSummaryText() {
        int style = PeakValley.clampStyle(state.peakStyle);
        boolean count = PeakValley.isCountStyle(style);
        return (state.peakShow ? "峰谷行开" : "峰谷行关")
                + " · " + PeakValley.styleDisplayName(style)
                + (count ? "" : "（「" + (state.peakTextCustom.isEmpty() ? "高峰时段" : state.peakTextCustom)
                + "」/「" + (state.valleyTextCustom.isEmpty() ? "空闲时段" : state.valleyTextCustom) + "」）")
                + " · 峰 #" + String.format(Locale.US, "%06X", state.peakColor & 0xFFFFFF)
                + " / 谷 #" + String.format(Locale.US, "%06X", state.valleyColor & 0xFFFFFF)
                + " · 倒计时 " + PeakValley.countdownFormatName(state.countdownFormat)
                + "\n点这里打开编辑器改（改完按「保存」才生效）";
    }

    /** 宽松解析小数（失败返回 -1）。 */
    private static double parseDoubleSafe(String s) {
        if (s == null) return -1;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 多行输入框对话框（模块 JSON 用）。 */

    public void pickColor(final int current, String title, final java.util.function.IntConsumer consumer) {
        final String[] names = {
                "白 #FFFFFF", "黑 #000000", "藏青 #203170", "深灰 #2E3440",
                "天蓝 #8ECAE6", "薄荷 #A8E6CF", "樱粉 #FFC2D1", "暖黄 #FFE08A", "浅灰 #F2F3F5"
        };
        final int[] values = {
                0xFFFFFF, 0x000000, 0x203170, 0x2E3440, 0x8ECAE6, 0xA8E6CF, 0xFFC2D1, 0xFFE08A, 0xF2F3F5
        };
        String currentHex = String.format(Locale.US, "#%06X", current & 0xFFFFFF);
        String[] items = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            String hex = String.format(Locale.US, "#%06X", values[i]);
            items[i] = names[i] + (hex.equalsIgnoreCase(currentHex) ? "　✓ 当前" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(title + "（当前 " + currentHex + "）")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        consumer.accept(values[which]);
                    }
                })
                .setNeutralButton("自定义…", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        askHexColor(current, consumer);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askHexColor(final int current, final java.util.function.IntConsumer consumer) {
        final EditText field = new EditText(this);
        field.setHint("#RRGGBB");
        field.setText(String.format(Locale.US, "%06X", current & 0xFFFFFF));
        field.setTextColor(textColor());
        field.setHintTextColor(subColor());
        field.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("自定义颜色（十六进制）")
                .setView(field)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String text = field.getText().toString().trim().replace("#", "").replace("0x", "");
                        try {
                            int value = (int) Long.parseLong(text, 16);
                            consumer.accept(value & 0xFFFFFF);
                        } catch (NumberFormatException e) {
                            toast("格式不对，示例：8ECAE6");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 不透明度选择：0–100% 滑杆。 */
    private void pickAlpha(int current, String title, final java.util.function.IntConsumer consumer) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, dp(8));
        final TextView label = new TextView(this);
        label.setTextColor(textColor());
        label.setText(alphaLabel(current));
        final SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.round(current / 255f * 100));
        box.addView(label);
        box.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(alphaLabel(Math.round(progress / 100f * 255)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        consumer.accept(Math.round(bar.getProgress() / 100f * 255));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 应用内 apikey.txt 的现状（掩码展示，不打印 Key 本身）。 */
    private String describeStoredKey() {
        String raw = CredentialStore.readText(PetPaths.userKeyFile());
        if (raw == null || raw.trim().isEmpty()) return "（未保存）";
        return CredentialStore.maskKey(raw.trim());
    }

    /** 当前实际会用到的音效（自定义文件 / 内置）。 */
    private String describeSound() {
        File file = PetPaths.resolveSoundFile(state.soundPath);
        if (file == null) return "内置 hit.mp3（原版素材）";
        String name = file.getName();
        if (file.equals(PetPaths.customSoundFile())) {
            name = name + "（导入，" + (file.length() / 1024) + " KB）";
        }
        return name + " @ " + file.getParent();
    }

    // ---------------------------------------------------------------- 备份与恢复（v1.14.0）
        /** 先问清楚：备份里要不要包含 API Key / 凭证。 */
        private void askBackupOptions() {
            new AlertDialog.Builder(this)
                    .setTitle("备份内容")
                    .setItems(new String[]{
                            "不含 API Key（推荐）",
                            "包含 API Key / 凭证（方便换机，但文件要保管好）",
                            "取消"
                    }, new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            if (which == 2) return;
                            startBackup(which == 1);
                        }
                    })
                    .show();
        }
        /** 用系统“新建文档”选择器挑个位置写备份（不需要任何存储权限）。 */
        private void startBackup(boolean includeCredentials) {
            backupIncludeCredentials = includeCredentials;
            String name = "dshpet-backup-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                            .format(new java.util.Date()) + ".zip";
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE);
            create.setType("application/zip");
            create.putExtra(Intent.EXTRA_TITLE, name);
            try {
                startActivityForResult(create, REQUEST_CREATE_BACKUP);
            } catch (Exception e) {
                toast("无法打开保存对话框：" + e.getMessage());
            }
        }
        /** 真正写备份。 */
        private void writeBackupTo(android.net.Uri uri) {
            java.io.OutputStream out = null;
            try {
                out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new java.io.IOException("无法写入选中的位置");
                java.util.List<File> creds = new java.util.ArrayList<>();
                creds.add(PetPaths.userKeyFile());
                creds.add(PetPaths.importedCredentials());
                BackupManager.Manifest m = BackupManager.write(out, PetPaths.stateFile(),
                        PetPaths.ledgerFile(), PetPaths.characterDir(), creds,
                        backupIncludeCredentials, VERSION);
                showDialog("备份完成", "已写入备份文件。\n" + m.describe()
                        + (backupIncludeCredentials ? "\n\n⚠️ 这个文件里有你的 API Key，请妥善保管。" : ""));
            } catch (Exception e) {
                Log.write("备份失败: " + e);
                showDialog("备份失败", String.valueOf(e.getMessage()));
            } finally {
                if (out != null) try { out.close(); } catch (Exception ignored) { }
            }
        }
        /** 挑一个备份 zip 来恢复。 */
        private void pickBackupFile() {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "*/*"});
            try {
                startActivityForResult(pick, REQUEST_OPEN_BACKUP);
            } catch (Exception e) {
                toast("无法打开文件选择器：" + e.getMessage());
            }
        }
        /** 先验元信息 → 让用户确认 → 再恢复（两遍读，第一遍不会改动任何东西）。 */
        private void readBackupFrom(final android.net.Uri uri) {
            final BackupManager.Manifest manifest;
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("无法读取选中的文件");
                try {
                    manifest = BackupManager.inspect(in);
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                Log.write("读取备份失败: " + e);
                showDialog("这不是可用的备份", String.valueOf(e.getMessage()));
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("恢复这个备份？")
                    .setMessage("备份内容：\n" + manifest.describe()
                            + "\n\n恢复会覆盖当前的设置（角色/泡泡模块等），并自动把当前配置另存一份备份副本。"
                            + (manifest.hasCredentials && !backupIncludeCredentials
                            ? "\n\n（该备份里含密钥，将一并恢复）" : ""))
                    .setPositiveButton("恢复", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            doRestore(uri, manifest);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
        /** 真正恢复；完了重载配置 + 通知服务 + 刷新界面。 */
        private void doRestore(android.net.Uri uri, BackupManager.Manifest manifest) {
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("无法读取选中的文件");
                BackupManager.RestoreResult r;
                try {
                    r = BackupManager.restore(in, PetPaths.stateFile(), PetPaths.ledgerFile(),
                            PetPaths.characterDir(), PetPaths.supportDir(), manifest.hasCredentials);
                } finally {
                    in.close();
                }
                state = PetState.load();          // 重新读盘（否则界面上还是恢复前的内存副本）
                applyToService();                 // 让服务重建桌宠/泡泡
                buildUi();
                showDialog("恢复完成", r.describe());
            } catch (Exception e) {
                Log.write("恢复失败: " + e);
                showDialog("恢复失败", String.valueOf(e.getMessage()));
            }
        }
        /** 一个老老实实的提示框（备份/恢复的成败都用它，别用一闪而过的 toast）。 */
        private void showDialog(String title, String message) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message == null ? "" : message)
                    .setPositiveButton("好", null)
                    .show();
        }
        // ---------------------------------------------------------------- 自定义角色（v1.13.0）
        /** 让用户挑一张图片当自定义角色。 */
        private void pickCharacterImage() {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            try {
                startActivityForResult(pick, REQUEST_PICK_CHARACTER);
            } catch (Exception e) {
                toast("无法打开文件选择器：" + e.getMessage());
            }
        }
        /** 把选中的图片复制进应用目录，然后问名字 —— 名字定了才算“有角色”。 */
        private void importCharacterFrom(android.net.Uri uri) {
            String id = "c" + System.currentTimeMillis();
            File target = PetPaths.characterFile(id);
            try {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("无法读取所选图片");
                java.io.FileOutputStream out = new java.io.FileOutputStream(target);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 16 * 1024 * 1024) throw new java.io.IOException("图片超过 16MB");
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                    in.close();
                }
            } catch (Exception e) {
                Log.write("导入自定义角色失败: " + e);
                toast("导入失败：" + e.getMessage());
                target.delete();
                return;
            }
            askCharacterName("我的角色", new Runnable() {
                @Override public void run() { }
            }, id, target);
        }
        /** 起名字的小弹框；确认后登记 + 立刻使用。 */
        private void askCharacterName(String initial, Runnable unused, final String id, final File file) {
            final EditText input = new EditText(this);
            input.setText(initial == null ? "" : initial);
            input.setSelection(input.getText().length());
            new AlertDialog.Builder(this)
                    .setTitle("给这个角色起个名字")
                    .setView(input)
                    .setPositiveButton("就用它", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            PetCharacters.addCustom(state, id, input.getText().toString(), file);
                            PetCharacters.selectCustom(state, id);
                            state.save();
                            applyToService();
                            buildUi();
                            toast("已添加自定义角色");
                        }
                    })
                    .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            file.delete();
                            toast("已取消导入");
                        }
                    })
                    .show();
        }
        /** 管理自定义角色：改名 / 删除。 */
        private void manageCustomCharacters() {
            final java.util.List<PetCharacters.Custom> list = PetCharacters.customs(state);
            if (list.isEmpty()) {
                toast("还没有自定义角色（用上面那一行导入）");
                return;
            }
            final String[] names = new String[list.size()];
            for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name;
            new AlertDialog.Builder(this)
                    .setTitle("管理自定义角色")
                    .setItems(names, new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            final PetCharacters.Custom c = list.get(which);
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(c.name)
                                    .setItems(new String[]{"✎ 改名字", "🗑 删除"},
                                            new android.content.DialogInterface.OnClickListener() {
                                        @Override public void onClick(android.content.DialogInterface d2, int w2) {
                                            if (w2 == 0) {
                                                final EditText input = new EditText(MainActivity.this);
                                                input.setText(c.name);
                                                input.setSelection(input.getText().length());
                                                new AlertDialog.Builder(MainActivity.this)
                                                        .setTitle("改名字")
                                                        .setView(input)
                                                        .setPositiveButton("好", new android.content.DialogInterface.OnClickListener() {
                                                            @Override public void onClick(android.content.DialogInterface d3, int w3) {
                                                                PetCharacters.renameCustom(state, c.id, input.getText().toString());
                                                                state.save();
                                                                buildUi();
                                                            }
                                                        })
                                                        .setNegativeButton("取消", null)
                                                        .show();
                                            } else {
                                                PetCharacters.removeCustom(state, c.id);
                                                state.save();
                                                applyToService();
                                                buildUi();
                                                toast("已删除");
                                            }
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        }
        /** 把选中的音频复制进应用目录并生效（不走持久化 URI 权限，最稳）。 */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CREATE_BACKUP) {
                    if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                        writeBackupTo(data.getData());
                    } else {
                        toast("已取消备份");
                    }
                    return;
                }
                if (requestCode == REQUEST_OPEN_BACKUP) {
                    if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                        readBackupFrom(data.getData());
                    }
                    return;
                }
                if (requestCode == REQUEST_PICK_CHARACTER) {
                    if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                        importCharacterFrom(data.getData());
                    }
                    return;
                }
                if (requestCode != REQUEST_PICK_SOUND || resultCode != RESULT_OK || data == null
                        || data.getData() == null) {
                    return;
                }
        try {
            InputStream in = getContentResolver().openInputStream(data.getData());
            if (in == null) throw new java.io.IOException("无法读取所选文件");
            File target = PetPaths.customSoundFile();
            java.io.FileOutputStream out = new java.io.FileOutputStream(target);
            try {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 8 * 1024 * 1024) throw new java.io.IOException("文件超过 8MB");
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
                in.close();
            }
            state.soundPath = target.getAbsolutePath();
            state.save();
            startService(new Intent(this, PetService.class)
                    .setAction(PetService.ACTION_RELOAD_SOUND));
            toast("音效已替换，点「当前音效」可试听");
            Log.write("自定义音效已导入：" + target.length() + " 字节");
            buildUi();
        } catch (Exception e) {
            dialog("音效导入失败", String.valueOf(e.getMessage()));
        }
    }

    /**
     * 真实请求一次余额来验证凭证：成功显示余额，失败显示原始错误分类。
     * 这是「配置完看不出有没有配好」问题的解法——不靠猜，直接实测。
     */
    private void verifyCredential(final CredentialStore.Credential cred) {
        if (cred == null) {
            dialog("验证失败", "没有可用的凭证对象");
            return;
        }
        final android.app.ProgressDialog progress = new android.app.ProgressDialog(this);
        progress.setMessage("正在向 DeepSeek 验证…");
        progress.setCancelable(false);
        try {
            progress.show();
        } catch (Exception ignored) {
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    BalanceClient.BalanceReading reading = BalanceClient.fetch(cred, 15);
                    Integer cents = reading.totalCents();
                    result = "✅ 验证成功\n\n"
                            + "余额：" + (cents == null ? "?" : PetModel.fenString(cents)) + " 元\n"
                            + "凭证来源：" + cred.shortDescription() + "\n"
                            + "请求地址：" + cred.endpoint
                            + (reading.spentCny() != null
                            ? "\n累计消费：" + String.format(Locale.US, "%.2f", reading.spentCny()) + " 元" : "");
                } catch (BalanceClient.FetchError e) {
                    result = "❌ 验证失败\n\n" + e.describe() + "\n\n凭证来源：" + cred.shortDescription();
                    Log.write("凭证验证失败：" + e.describe());
                } catch (Throwable t) {
                    result = "❌ 验证失败\n\n" + t;
                }
                final String text = result;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { progress.dismiss(); } catch (Exception ignored) { }
                        dialog("API Key 验证", text);
                        refreshStatus();
                    }
                });
            }
        }, "dshpet-verify").start();
    }
}