# DSH 余额桌宠 · Android 移植版

**第一作者**：DeepSeek-V4.1-Flash
**第二作者**：DeepSeek-V4-Pro-0813
**通讯作者**：Millosuki

> ⚠️ **非官方移植 · 仅供学习交流**：本项目**不是** DeepSeek 官方产品，与 DeepSeek 无隶属或背书关系。
> 代码按 **MIT** 发布。素材分两栏 —— 来自上游 **VK-1** 的 5 张立绘与 `res/raw/hit.mp3` 随 MIT 分发（保留来源说明）；
> `assets/sprite-whale.png` 与 `res/raw/ya1.mp3`、`ya2.mp3`、`d1.mp3`、`d2.mp3` **不在本项目 MIT 范围内**（原样携带）。
> 详见 `LICENSE`（代码）、`NOTICE`（素材）、`PROVENANCE.md`（来源与逐字节核对）。

## 这是什么

一个给 **DeepSeek Harness** 用的余额桌宠（Android）。本项目把**两个上游项目合并移植**并融合成同一个 App：

- **[VKmich16/VK-1](https://github.com/VKmich16/VK-1)** —— DSH 余额桌宠的 Windows PowerShell + macOS Swift **原版**：提供角色、余额轮询与交互；
- **[MeteorNOX/DeepSeek-Balance-Whale-Widget](https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget)** —— DeepSeek Harness 的**网页挂件**：提供那套「高度自定义泡泡」的内容与样式体系。

两者在同一个 App 里**融为一体**：VK-1 的角色站在桌面上，用 Whale 的泡泡系统显示文字；
余额 / 峰谷 / 今日已用等数据都能作为泡泡模块呈现，角色也能在内置角色 / Whale / 自己导入的图片之间随时切换。

## 作者 / Authors

本项目是**纯 AI 结对编程（webcoding）产物**：

- **代码与自检**：由 **AI（DeepSeek）** 编写，并由它自己完成离线自检与真机取证；
- **所使用的 AI Agent 应用**：[Operit](https://github.com/AAswordman/Operit)（Android 平台上的 AI Agent，https://operit.app）；
- **需求、验收与日常使用反馈**：**Millosuki**；
- *开发过程消耗的 API 费用：¥22.15（由 Millosuki 承担）*。

### 注意

**本项目 = 移植（在 Android 上重新实现）+ 融合（两套体系合为一套）。**
行为基准与部分素材来自 **VK-1**（MIT）；泡泡体系与部分素材来自 **Whale 挂件**（代码 MIT，但其 `assets/**` 不在 MIT 范围内，按 as-is 原样携带）。
素材边界与逐字节核对见 `LICENSE`、`PROVENANCE.md`。**非官方移植，与两个上游均无隶属关系。**

## 上游与致谢

这个 App 站在两个开源项目的肩膀上，**感谢两位上游作者**：

| 上游仓库 | 许可 | 本项目用到它的什么 |
|---|---|---|
| [**VKmich16 / VK-1**](https://github.com/VKmich16/VK-1) | MIT | DSH 余额桌宠的**原版**（Windows PowerShell + macOS Swift）—— 本移植的**行为基准**（0.2s 节奏 / 0.01 步长 / 3 层受伤叠加），以及立绘与音效素材 |
| [**MeteorNOX / DeepSeek-Balance-Whale-Widget**](https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget) | 代码 MIT（其 `assets/**` 不在 MIT 内） | **Whale 挂件**的交互与「高度自定义泡泡」体系（模块化内容 / 点击序列 / 峰谷 / 跑马灯 …），以及 `sprite-whale.png` 素材 |

再次感谢上游作者把作品开源出来；本项目只是把它们搬到 Android 上并做了整合。

## 还原度说明（作者主观估计）

由于**技术原因**（实话：token 不够了），对 **VK-1** 原版桌面宠物的还原度约 **90%**，
对 **DeepSeek-Balance-Whale-Widget** 网页挂件的还原度约 **75%** —— 在此向两位上游作者致歉。

## 架构

一个**前台 Service** 用悬浮窗把角色画在桌面上，**轮询** DeepSeek 余额接口，
用一套自研的**模块化泡泡**显示文字；设置页与泡泡编辑器只做「改配置 + 广播热重载」。

```
MainActivity（设置页：通用 / VK-1 / Whale挂件）
   └─ PetBubbleEditorActivity（泡泡编辑器：目标芯片 → 拖拽并排 → 实时预览 → 一次保存）
              │  写 state.json / ledger.json + 广播 APPLY
              ▼
        PetService（前台服务，进程里唯一持有悬浮窗的人）
              ├─ PetModel / BalanceClient / CredentialStore / PollSchedule / SpendLedger（余额·凭证·轮询·本地记账）
              ├─ PetRenderer / PetSceneView / PetBandView（单窗口 或 轮廓分带窗口）
              ├─ PetBubbleView / PetBubble / PetBubbleModule（泡泡几何 + 模块布局 + 颜色/跑马灯）
              ├─ PetCharacters / PetCharacter / PetAssets（内置角色 · Whale · 用户自定义角色）
              ├─ BackupManager（备份 / 恢复：zip）
              └─ PetMenuView（长按菜单）
```

细节（数据流、文件分组、**别顺手改掉的设计约束**）见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

- 对照上游：**v1.3.1 @ `9ab36bd2fa9ff13b0b832f98a7c0f240aa0cf36d`**（2026-10-03）
- 本移植版：见 `MainActivity.VERSION`
- 角色：蓝色大肥鱼 / GPT龙娘 / 大小姐Claude / 北美猫娘Gemini（未连接时大肥鱼显示抱盆图）
- 余额每下降 1 分：红闪 + 抖动 + 原版音效 + `-0.01` 飘字，间隔 0.2 秒，串成一条

## 目录结构
```
docs/ARCHITECTURE.md      代码结构 / 数据流 / 别顺手改掉的设计约束
docs/BUILD.md             无 Gradle 构建、环境变量、安装、自检、踩过的坑
CONTRIBUTING.md           硬性开发规则（自检必须过 / 版本两处 bump / 用补丁脚本改大文件…）
LICENSE                   代码许可：标准 MIT 正文
NOTICE                    素材许可边界（哪一栏随 MIT、哪一栏不在 MIT 内）
PROVENANCE.md             素材来源、逐字节核对方式、许可边界
CHANGELOG.md              完整更新记录（App 内「关于 · 更新记录」读的就是它的副本）
OPEN_SOURCE_CHECKLIST.md  开源准备进度
AndroidManifest.xml
res/                      图标、字符串、音效（hit.mp3 原版扣费音 + ya1/ya2/d1/d2 按压松开音）
assets/                   五张立绘 + 抱盆图 + Whale 小鲸鱼（与上游逐字节一致，边界见 NOTICE）
java/com/dsh/balancepet/
  PetModel.java           账本（真实/屏显余额）+ 动画（3 层受伤叠加）
  PetRenderer.java        渲染器：预缩放立绘、预渲染文字层、飘字位图缓存
  PetSceneLayout/View     单窗口渲染与轮廓分带窗口
  PetService.java         前景服务：窗口、菜单、拖动、轮询、音效、通知、状态快照
  MainActivity.java       设置界面（权限/角色/尺寸/帧率/音效/凭证/诊断/关于）
  PetControlReceiver.java ★ 脚本化控制广播（需控制令牌，见下）
  PetTuning.java          ★ 与上游对齐的全部魔法数字（升级时只改这里）
  UpstreamInfo.java       ★ 上游版本基线：commit、文件对应表、素材哈希
  AssetVerifier.java      素材 SHA-256 与上游比对
  BalanceClient.java      严格响应解析（币种/精度/错误码）
  SpendLedger.java        本地记账内核（v1.12.0：观测余额下降 → 今日已用 / 累计 / 充值）
  CredentialStore.java    凭证解析（YAML 迷你读取器）
  YamlMini.java, PetPaths.java, Log.java, PetState.java, PollSchedule.java
  SelfTests.java          离线自检（908 项）
build.sh                  无 Gradle 构建脚本（aapt2 + javac + d8 + apksigner）
verify.sh                 重建 + 签名校验 + 素材哈希 + dex 标记校验
```

## 构建

```sh
# 依赖：aapt2 / apksigner / zipalign / JDK17 / d8(r8.jar) / android.jar
bash build.sh . out/dshpet.apk
bash verify.sh
```

在 Operit 的 Ubuntu 环境里已就绪（工具与路径见 `build.sh` 顶部）。目标 API 32
（Debian 版 aapt2 读不了 API 33+ 的 `resources.arsc`，详见脚本注释）。

## 操作

| 操作 | 效果 |
| --- | --- |
| 长按桌宠 | 打开菜单（原版右键菜单的等价物，含二级菜单） |
| 拖动 | 拖动行为可选：自由停放 / 吸附左下角（原版）/ 吸附最近边缘 |
| 点一下 | 按下即形变（底部中心为轴压扁），松开回弹；280ms 后弹出/收起泡泡 |
| 双击 | 可配置：**扣费效果**（默认，原版语义，手动触发一次扣费动画）/ **挤压效果**（按住 0.4s 再回弹，不扣费） |
| 通知栏 | 余额状态 + 刷新 / 测试扣费 / 设置 / 退出 |
| 设置界面 | 权限、角色、尺寸（含自定义）、渲染模式、动画帧率（含实测监控）、音效（含自定义文件与音量）、拖动行为、刷新间隔（1s–10min 含自定义）、凭证（含实测验证）、诊断（自检/日志/状态/素材校验/控制令牌） |

## 脚本化控制（Shizuku / adb shell）

**不要**用 `am start ... --es forward=...` 驱动（`MainActivity` 的转发通道）来配合截图取证：
它会把启动页闪到前台约 1 秒，把这期间的整屏（含悬浮窗）都盖住。请改用广播通道——不显示界面：

```sh
# 令牌：设置 → 诊断 → 控制令牌（也会写进 logcat 与 pet.log）
T=<control-token>
am broadcast -n com.dsh.balancepet/.PetControlReceiver -a com.dsh.balancepet.CONTROL \
  --es token $T --es action com.dsh.balancepet.SET_POS --ei x 200 --ei y 1000
```

- **必须用显式组件 `-n`**：本 ROM 下隐式广播（仅给 `-a`）对后台应用的接收者解析结果为 0 个。
- 可用动作＝`PetService` 的动作白名单（`PetControlReceiver.allowed()`），例如
  `SET_POS(--ei x/y)`、`TEST_BUBBLE`、`TEST_PRESS(--ei hold <ms>)`、`TEST_SQUEEZE`、
  `SET_BUBBLE_STYLE(--ei fill/stroke/alpha)`、`MODE(--ei mode 0/1)`、`START/STOP/REFRESH/APPLY/SELFTEST/DEMO(--ei fen/--ez mute)`。
- 冷启动（服务没在跑时广播 `START`）会被系统的前台服务启动限制/厂商后台策略挡住；此时先用界面或通知启动一次。
- **全新安装后**应用处于 stopped 状态：这时广播会被系统**排队**（返回成功但没有任何日志输出），
  要先把界面拉到前台、再发广播才生效。
- 安全：需要控制令牌（存于应用私有目录，第三方读不到）＋ 动作白名单；令牌缺失或错误一律拒绝并记日志。


## 更新记录

完整历史更新记录（v1.2 → v1.14.3）见 [`CHANGELOG.md`](CHANGELOG.md)。
App 里也能看：**设置 → 通用 → 「关于 · 更新记录」**，或者**点设置页最上面那行版本号**。

## 与上游的差异（有意为之）

| 项 | 上游 | 本移植版 | 原因 |
| --- | --- | --- | --- |
| 透明处穿透 | 逐像素 `window.ignoresMouseEvents` | 单窗口（不吃穿透）或轮廓分带多窗口（≤12 窗口，透明处真穿透） | Android 无法按像素设置窗口输入区域；实测可触摸窗口的透明像素也会吞点击 |
| 帧率 | 30fps 定时器 | 跟随屏幕刷新率（可选 30/60/90/120），每 vsync 渲染 | 90Hz 屏上 30fps 明显发顿 |
| 尺寸 | 110/150/210/280 pt | 80/110/150/200 dp + 自定义（受屏宽限制） | 手机屏窄，窗口宽 = 1.5×side |
| 轮询间隔 | 10–300 秒 | 1–600 秒（可自定义） | 用户要求更灵活；429 退避自动接管 |
| 未连接时的大肥鱼 | 抱盆图（v1.3.1 源码 `PetView.showsOfflineArtwork` 实现，含 `ArtworkSelfTests` 断言） | 默认同上游，但**可关**：关掉即 Windows 原版（平板图 + `--` + 状态点） | 两种表现都有人喜欢；用户要求做成开关 |
| 音效 | 固定 hit.mp3 | 可替换（Download/DSHPet/hit.mp3 或设置界面导入）+ 音量 + 可选节流 | 用户要求 |
| 菜单栏 ¥ | 系统菜单栏 | 常驻通知 | Android 无菜单栏 |
| 右键 | 右键 | 长按 | 触摸屏 |

## 验证边界（不夸大）

- 帧率数据来自 `dumpsys gfxinfo` 与自家每秒统计；**悬浮窗无法把面板拉到 90Hz**，
  ColorOS 对 overlay 固定 `frameRateOverride=60`（实测），面板真在 90Hz 时会自动跟随。
- 外观观感未由程序断言（视觉 API 不可用），只用像素统计检查过完整性（角色像素占比、接缝行=0）。
- 真实账号接口只在本机验证过；不同厂商 ROM 的悬浮窗/窗口数量策略未穷举。
- **桌宠贴到屏幕最顶**（窗口 y=0，立绘顶边 y=191）时，立绘头顶以上只剩 ≈84px：泡泡会缩到 55% 下限、
  顶部钳在刘海安全区（107px），但尾巴仍有 ≈45px 压进宠物头部。可选后续方案：
  ①保持现状 ②空间不足时整体翻转到桌宠下方（需要镜像形状**与文字**，当前 `PetBubbleView.setFlipped`
  只镜像形状、不能用）③点击时把桌宠临时下移。
- **设置页的三标签结构**已用 `am start` + `uiautomator dump` 复核过（实测 `am start` 能把设置页顶到前台）：
  通用页顺序 = 权限 → 角色 → **角色尺寸** → 备份与恢复 → 运行 → 交互 → 帧率 → 诊断；VK-1 页起手是 **「外观与行为」**。
  **编辑器**界面观感仍以 smoke 自截图为准（`View.draw()` 软渲染，不含系统状态栏）。
  
## 侵权处理与联系方式

如果此项目涉及到**侵权内容**请联系我，我将**第一时间**响应以及**道歉**并**发表声明**
**我的个人邮箱**
Millosuki@outlook.com
Millosuki@proton.me
