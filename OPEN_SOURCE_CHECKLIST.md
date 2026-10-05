# 开源准备清单（OPEN SOURCE CHECKLIST）

> 状态：**进行中**。✅ = 已完成；⬜ = 待做；❓ = **需要项目作者拍板**（不是我该替他决定的）。
>
> 当前工程：**v1.14.1-android（versionCode 11401）**，已装机、自检 **904 项全过**；手机与工程版本一致。

## 一、仓库卫生 ✅

- ✅ `.gitignore`：`build/`、`out/`、`*.apk`、`*.idsig`、`*.jks`/`*.keystore`/`key.properties`、`evidence/`、IDE 产物
- ✅ 工程里**没有**硬编码的密钥/keystore（构建脚本用的 keystore 路径在仓库之外，可用环境变量覆盖）
- ✅ 无第三方二进制依赖（不引 AndroidX/Gradle；`aapt2`/`d8`/`android.jar` 是构建环境的事）

## 二、文档 ✅

- ✅ `README.md`：项目定位 + 目录结构 + 构建 + 脚本化控制 + 完整「更新记录」+ 验证边界
- ✅ `docs/ARCHITECTURE.md`：架构、数据流、文件分组、**别顺手改掉的设计约束**
- ✅ `docs/BUILD.md`：无 Gradle 构建、环境变量、安装、自检、**踩过的坑**
- ✅ `CONTRIBUTING.md`：硬性规则（自检必须过 / 版本两处 bump / 补丁脚本改大文件 / 不许引 AndroidX …）
- ✅ `PROVENANCE.md`：素材来源、逐字节核对方式、许可证现状
- ✅ `README.md` 的「更新记录」已补到 **v1.14.1**（v1.14.0「备份与恢复」此前漏记，已事后补写）

## 三、代码整理

- ✅ **模块类型清单同源**：`PetBubbleModule.PALETTE` 是唯一权威，`TYPE_NAMES` 由它派生，
  编辑器只留别名；并加了一条自检断言防止再漏（历史 bug：v1.12.0 加了「今日已用」但 UI 里加不出来）
- ✅ 死代码扫描：写脚本按「声明后从未被调用」+「类名只在自己文件里出现」扫了一遍，
  **没有发现真正的死代码**（命中的都是私有构造器、框架回调、manifest 里引用的 Receiver）
- ⬜ 「文本工具」小类可以考虑合并（`RichText` / `ArrowFlip`）—— 现在拆开更清晰，**倾向保留**
- ⬜ 部分注释里的历史版本号（v1.5.x、v1.6.0…）可以直接保留：它们解释了"为什么长这样"，是资产

## 四、需要作者拍板的事项（**已全部拍板 ✅**）

1. ✅ **代码许可证 = MIT**（2026-10-05 作者定）：跟两个上游一致（VK-1 是 MIT 且明确允许移植使用其素材、注明来源即可；
   Whale 插件的代码也是 MIT）。已落地 `LICENSE`。
2. ✅ **素材标注**：`LICENSE` 里分两栏写明 —— VK-1 的立绘/音效随 MIT 分发并保留来源说明；
   `assets/sprite-whale.png` 原样携带、**不在本项目 MIT 范围内**（按其上游 PROVENANCE 的 as-is 条款）。
3. ✅ **仓库名与作者署名**（2026-10-05 作者定）：仓库名 **`dsh-balance-pet-android`**；
   署名 = **纯 AI 结对编程（webcoding）产物：AI（Operit）主写，Millosuki 提需求与验收**。
   已落地 README 顶部 + `UpstreamInfo.AUTHOR_AI / AUTHOR_HUMAN`。
4. ✅ **开发过程的 memory / 归档不进仓库**（2026-10-05 作者定）：仓库只放代码与面向使用者的文档；
   架构说明以 README「架构（一句话看明白）」+ `docs/ARCHITECTURE.md` 为准。
5. ✅ **已换成正式 release 签名**（2026-10-05 作者定）：PKCS12 / RSA-4096 / 有效期 10000 天；
   keystore 与密码都在**仓库之外**（`/root/work/android/keys/`），证书指纹写进 `docs/BUILD.md`。
   ⚠️ 换签名必须卸载重装（私有目录清空）→ 作者已先「备份到文件」，装完再恢复。

## 五、发布前最后一遍

- ✅ README 顶部加「非官方移植，仅供学习交流」声明（2026-10-05 落地）
- ✅ `LICENSE`(MIT) + 素材分栏说明落地
- ✅ README 顶部补**作者署名**与**上游与致谢**（两个上游仓库链接 + 感谢）
- ✅ 换成**正式 release 签名**（keystore / 密码在仓库外；指纹写进 `docs/BUILD.md`）
- ✅ `docs/ARCHITECTURE.md` 刷新（42 个类 / 约 1.75 万行 / 自检 904 项 + 角色·备份分组）
- ⬜ `git init` + 首次提交（`main` 分支）+ tag `v1.14.2`
- ⬜ 发布 APK 到 Releases（或明确"只发源码、不发 APK"）——**由作者决定**
- ⬜ 在 README 里写清**隐私**：应用只做两件事 —— 读你给的 API Key 查余额、把配置写在自己私有目录；
  没有统计、没有回传（`app/build` 里也没有任何网络上报代码）

## 六、GitHub 建仓时填什么

- **仓库名**：`dsh-balance-pet-android`
- **Description（About 栏，简洁版）**：
  `DSH 余额桌宠的 Android 移植版（含 Whale 挂件泡泡体系）｜AI 结对编程产物｜MIT`
- **Topics**：`android`、`deepseek`、`desktop-pet`、`floating-widget`、`port`、`mit-license`
- **不要勾** Add README / .gitignore / license（仓库里已经都有了，勾了会冲突）