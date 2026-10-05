# 素材与来源说明（PROVENANCE）

> 这个文件只讲**事实**：哪些文件来自哪里、怎么核对的。**许可证结论待项目作者拍板**（见文末）。

## 一、这个项目是什么

**dshpet** = 把两个**别人的**项目搬到 Android 上重实现：

1. **`VKmich16/VK-1`**（"DSH 余额桌宠"，原版 Windows PowerShell，另有 macOS Swift 版）
   → 本项目的**阶段一**：角色悬浮窗、余额轮询、扣费动画、长按菜单、双渲染模式……
   上游基线：**v1.3.1，commit `9ab36bd2fa9ff13b0b832f98a7c0f240aa0cf36d`**（见 `UpstreamInfo.java`）。
2. **`MeteorNOX/DeepSeek-Balance-Whale-Widget`**（v0.3.17，DeepSeek Harness 的网页挂件）
   → 本项目的**阶段二**：那套「模块化泡泡」的交互与外观体系（点击序列、并列权重、峰谷样式、
   跑马灯、模块化内容、编辑器……）。

**本项目是"移植/重实现"，不是上游官方发布**，与上述两个项目没有隶属关系。

## 二、随包分发的素材（逐字节核对）

`verify.sh` 每轮构建都会把 APK 里的素材与 `assets.sha256` 里的哈希**逐一比对**，不一致即构建失败。
当前基线（与上游文件逐字节一致）：

| 文件 | 用途 | 来自 | SHA-256（前 16 位） |
|---|---|---|---|
| `assets/sprite.png` | 角色立绘（蓝色大肥鱼 / DeepSeek） | VK-1 | `a98329d36dd9169a` |
| `assets/sprite-gpt.png` | 角色立绘（GPT 龙娘） | VK-1 | `41f79346666fbb77` |
| `assets/sprite-claude.png` | 角色立绘（Claude 大小姐） | VK-1 | `81f0e787057dd9d5` |
| `assets/sprite-gemini.png` | 角色立绘（Gemini 猫娘） | VK-1 | `ad5fbeb07c221247` |
| `assets/sprite-deepseek-offline.png` | 「未连接」时的抱盆图 | VK-1 | `fb4c5cb3001ca43d` |
| `res/raw/hit.mp3` | 原版扣费音效 | VK-1 | `43fa877b537d8cbf` |
| `assets/sprite-whale.png` | Whale 小鲸鱼（**纯形象**，不显示余额） | Whale 挂件 `assets/DSniang1.png`（按高度适配合成到 1536×1024 画布） | `67aa8613fe0ce200` |
| `res/raw/ya1.mp3` / `ya2.mp3` | 按压音效（小黄鸭 Ya1 / Ya2） | Whale 挂件 `assets/Ya1.mp3` / `Ya2.mp3`（**逐字节一致**） | `0ad8f934ae5fa3cb` / `1a8998077e5e306c` |
| `res/raw/d1.mp3` / `d2.mp3` | 松开音效 | Whale 挂件 `assets/D1.mp3` / `D2.mp3`（**逐字节一致**） | `626c0fb5dbfb2529` / `8327a07da3ec17f4` |

> **v1.14.2 修正（重要）**：此前 `LICENSE` 把 `ya*.mp3` / `d*.mp3` 也写成「来自 VK-1（MIT）」，那是**不实的**。
> 2026-10-05 用 sha256 逐字节核对两个上游后确认：这 4 个音效来自 **Whale 挂件的 `assets/`**，
> 而该目录**不在其 MIT 范围内** → 已同时改正 `LICENSE` 与 `assets.sha256`。
> 现在上面这 **11 个文件全部**纳入 `verify.sh` 的逐字节哈希校验。

## 三、从上游"读出来"的常数（不是复制文件，是照着写代码）

- **泡泡形状与几何**：`WhaleBubbleSpec` 里的 SVG 路径/几何常数，是从上游挂件
  `assets/whale-widget.js` 里读出来的（对应关系在类注释里写了行号区间）。
- **峰谷时段规则与文案**：`PeakValley`（含「梁文峰谷」「!?强强?!」「简洁」「倒计时」等样式），
  与上游的显示规则**逐点核对过**（历史记录：3003 个时间点的判定结果零差异）。
- **随机语句的出厂语录**：`PetBubbleModule.presetLinesBig()/presetLinesSmall()`（共 13 句），
  逐条抄自上游 `whale-widget.js` 第 7366 行起的默认文案。
- **跑马灯配色**：`PetBubbleGradients` 里的 17 套渐变，对应上游的 `dshwvRainbow` 系列配色。

## 四、许可证：**已定 MIT**（2026-10-05 由项目作者拍板）

最终结论（已落地：`LICENSE` = 纯标准 MIT 正文，好让 GitHub 识别成 MIT 徽标；素材边界写在 `NOTICE`）：

1. **代码**（`java/` 下全部源码、`build.sh`、`verify.sh`、`docs/` 与其它文档）= **MIT**。
2. **素材分两栏写**（当初的顾虑正是「不能笼统地给整个仓库打一个 MIT」，所以边界必须分开写）——现写在 `NOTICE`：
   - **随 MIT 分发**：来自 **VK-1** 的 5 张立绘 + `res/raw/hit.mp3`
     （上游许可明确覆盖角色美术与音效，并说明移植到其它平台注明来源即可）；
   - **不在本项目 MIT 范围内（as-is 原样携带）**：来自 **Whale 挂件** 的 `assets/sprite-whale.png`
     + `res/raw/ya1.mp3`、`ya2.mp3`、`d1.mp3`、`d2.mp3`（其 `assets/**` 不适用 MIT、不授予再许可）。
3. README 顶部已加「非官方移植，仅供学习交流」声明，并保留两个上游的仓库链接与致谢。