# 参与开发（CONTRIBUTING）

## 硬性规则（这个工程的历史教训换来的）

1. **改完必须自检全过**：`bash verify.sh`（构建 + 5 项校验）**且**装机后跑
   `SELFTEST`（当前 873 项）。自检里挂了任何一条，都不算完成。
2. **版本号每次都要 bump，两处一起改**：`MainActivity.VERSION` 与 `AndroidManifest.xml`
   的 `versionCode` / `versionName`。versionCode 规则：`major*10000 + minor*100 + patch`。
3. **失败/不确定必须如实报告**，不要用"应该可以"糊过去。技术结论要给出证据
   （`logcat`、`dumpsys`、源码行号、哈希、像素统计…）。
4. **改大文件用补丁脚本**（`python3` 脚本 + **唯一匹配断言** + `dry-run` + 任何一处不匹配就整批放弃）。
   直接把一大段代码粘进文件，是本工程**返工最多**的失败模式。
5. **不许引 AndroidX / Gradle**：目标是"没有 Android Studio 也能构建"（见 `docs/BUILD.md`）。
   需要新控件就手写（参考 `FlowLayout`）。
6. **不伪造可用性**：实现不了就在注释/界面里如实写"未支持"，**不要**放一个点了没反应的按钮。

## 代码约定

- **一个包一个模块**：`java/com/dsh/balancepet/`，类名 = 文件名，不放子包（构建脚本按目录找源码）。
- **注释写"为什么"**，不写"做了什么"；涉及上游行为的，写清对应文件与行号区间。
- **界面文案里的 `**强调**`** 必须经过 `RichText.bold()` 渲染（否则星号会直接画在屏幕上）。
- **泡泡文字** 一律经过 `PetBubble.resolve()`（占位符替换、箭头翻转都收口在那里）。
- **模块类型** 只改 `PetBubbleModule.PALETTE`（`TYPE_NAMES` 由它派生，编辑器用别名）。
- **纯逻辑与 Android 依赖分离**：能被自检断言的东西（如 `SpendLedger`、`ArrowFlip`、`RichText`、
  `PetBubbleEditModel`）不要碰 `Context`。
- 新增功能尽量**顺手加自检**：`SelfTests` 里的每一条断言都是回归网。

## 提 PR 前自查

- [ ] `bash verify.sh` 全绿
- [ ] 装机后 `SELFTEST` 全过（项数变化要在 PR 里说明）
- [ ] 两处版本号已 bump，README 的「更新记录」加了一条
- [ ] 新行为有证据（日志/坐标/像素/断言），PR 描述里贴出来
- [ ] 没有把密钥、keystore、抓的截图提交进来（见 `.gitignore`）

## 环境提示（在 Android 手机上用 proot Ubuntu 构建时）

- 用 `setsid … &` 跑长构建，然后轮询日志；**不要**在前台硬等（容易被打断）。
- `/sdcard` 通常是 `noexec`：装 APK 要先 `cp` 到 `/data/local/tmp`。
- 别用 heredoc 写多行脚本；`pkill -f` 的 pattern 写成 `[p]attern`。
- 详见 `docs/BUILD.md` 结尾的「踩过的坑」。