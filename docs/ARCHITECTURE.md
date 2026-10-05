# 代码结构速览（dshpet）

> 面向要读代码 / 提 PR 的人。**没有 Gradle、没有 AndroidX**：纯 `aapt2 + javac + d8 + apksigner`
> （见 `docs/BUILD.md`）。所有代码在 `java/com/dsh/balancepet/`，单模块、单个包。

## 一句话架构

一个**前台 Service** 用「悬浮窗」把角色画在桌面上，用**轮询**拿 DeepSeek 余额，
用一套自研的「泡泡（气泡）」系统显示文字 —— 泡泡的内容是**模块化**的（上游 Whale 挂件的模型），
编辑器让你把模块拖成行、配颜色/字体/跑马灯。

```
MainActivity（设置页）
   └─ PetBubbleEditorActivity（泡泡内容编辑器：目标芯片 / 拖拽 / 预览 / 一次保存）
              │  写 state.json + 广播 APPLY
              ▼
        PetService（前台服务，进程里唯一持有悬浮窗的人）
              ├─ PetModel        余额模型 + 扣费/受击/飘字动画
              ├─ BalanceClient   余额接口解析（严格：币种/精度/错误码）
              ├─ CredentialStore 凭证解析（多来源）
              ├─ SpendLedger     本地记账（观测余额下降 → 今日/累计/充值）
              ├─ PollSchedule    轮询与 429 退避
              ├─ PetRenderer / PetSceneView / PetBandView / PetOverlayView
              │      把立绘画成 1 个窗口（单窗口模式）或 N 个分带窗口（轮廓模式）
              ├─ PetBubbleView + PetBubble + PetBubbleModule
              │      泡泡：SVG 路径几何 + 行/模块布局 + 颜色/跑马灯 + 静态/翻转
              └─ PetMenuView     长按菜单
```

## 关键数据流

1. **余额**：`PetService.poll()`（后台线程）→ `BalanceClient.fetch()` → `PetModel.apply()`；
   同时 `SpendLedger.observe()` 把「余额下降」记成今日/累计消费（接口**没有**消费字段，只能自己记）。
2. **泡泡内容**：`PetState.bubbleModulesJson` / `bubbleSeqJson`（用户配置）
   → `PetBubbleModule.listFromJson()` → `PetBubble.modules` → `PetBubbleView` 逐行逐模块绘制。
3. **点击序列**：第一次点桌宠 → 第 1 项；之后每次点泡泡推进；「并列」项按权重抽一个候选
   （`PetBubbleSeq.Option`）。默认泡泡与各次点击的内容都存在同一份配置里。
4. **编辑器**：编辑的是**工作副本**（`PetBubbleEditModel.rows` + `PetBubbleSeq` 对象），
   点「保存」才：重新读盘 → 只改自己那一项 → 写盘 → 广播 `ACTION_APPLY`（服务热重载）。

## 文件分组（42 个类，约 1.75 万行）

| 分组 | 文件 |
|---|---|
| **入口 / 平台** | `MainActivity`、`PetService`、`PetControlReceiver`（脚本化控制）、`BootReceiver`、`PetPaths` |
| **余额 / 凭证 / 记账** | `BalanceClient`、`CredentialStore`、`YamlMini`、`SpendLedger`、`PollSchedule`、`PetModel` |
| **渲染** | `PetRenderer`、`PetSceneView`、`PetBandView`、`PetOverlayView`、`PetCharacter`、`PetAssets`、`SvgPath`、`PetLayout`、`PetTuning` |
| **泡泡** | `PetBubbleView`、`PetBubble`、`PetBubbleModule`、`PetBubbleSpec`→`WhaleBubbleSpec`、`PetBubbleStyle`、`PetBubbleGradients`、`FlowLayout`、`PetBubbleSeq`、`PetBubbleEditModel`、`PetDragListLayout`、`PetBubbleEditorActivity`、`PeakValley` |
| **文本工具** | `RichText`（`**强调**` → 真加粗）、`ArrowFlip`（泡泡在桌宠下方时把句首/句尾的 ↓ 换成 ↑） |
| **角色 / 备份** | `PetCharacters`（内置 / Whale / 自定义角色注册表）、`PetCharacter`、`BackupManager`（zip 备份与恢复，不依赖 Activity 可单测） |
| **自检 / 校验** | `SelfTests`（904 项离线断言）、`AssetVerifier`、`UpstreamInfo`、`Log` |

## 设计约束（有意为之，别"顺手"改掉）

- **不引 AndroidX / 不用 Gradle**：目标是一台 Android + proot Ubuntu 上能离线跑通构建。
  代价是布局全部手写（`FlowLayout` 就是为此存在）。
- **不读别人 App 的私有数据**：凭证只从「本应用配置目录 / Download/DSHPet/apikey.txt / 环境」等用户显式提供的位置读。
- **界面文案里的 `**强调**` 由 `RichText` 渲染**：直接 `setText` 会把星号画出来（踩过）。
- **泡泡里所有文字都经过 `PetBubble.resolve()`**：占位符替换、箭头翻转都收口在这里，别在别处拼字符串。
- **模块类型的唯一权威清单是 `PetBubbleModule.PALETTE`**：`TYPE_NAMES` 由它派生，编辑器直接用别名，
  防止「加了新类型、UI 里加不出来」。
