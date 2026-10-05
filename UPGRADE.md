# 上游升级指南（Upgrade）

上游更新后按这个流程走，目标是**每个差异都能被指出来**，而不是"凭感觉同步"。

## 0. 前提

- 当前基线：`UpstreamInfo.UPSTREAM_COMMIT`
- 素材哈希：`UpstreamInfo.ASSET_HASHES`（用 `AssetVerifier` / `verify.sh` 校验）
- 参数集中地：`PetTuning.java`

## 1. 取上游新版本

```sh
cd /root/work
curl -sSL -o vk1.tar.gz https://codeload.github.com/VKmich16/VK-1/tar.gz/refs/heads/main
tar xzf vk1.tar.gz && mv VK-1-main VK-1-new
curl -sS https://api.github.com/repos/VKmich16/VK-1/commits/main | python3 -c \
  "import sys,json;d=json.load(sys.stdin);print(d['sha'], d['commit']['committer']['date'])"
```

## 2. 看上游改了什么

```sh
diff -ru VK-1-old/dsh-balance-pet-macos/Sources VK-1-new/dsh-balance-pet-macos/Sources
diff -ru VK-1-old/'原版（Windows版）' VK-1-new/'原版（Windows版）' | head -100
```

按 `UpstreamInfo.FILE_MAP` 挨个对照到本项目的文件：

| 上游文件 | 本移植版 | 需要重点看 |
| --- | --- | --- |
| `PetModel.swift` | `PetModel.java` | 账本语义、扣费节奏、受伤叠加层数 |
| `PetView.swift` | `PetRenderer.java` + `PetBandView/PetSceneLayout` | 绘制顺序、叠色、飘字曲线、抖动公式 |
| `PetController.swift` | `PetService.java` | 窗口、菜单项、轮询、音效策略 |
| `PetLayout.swift` | `PetLayout.java` | 窗口/立绘几何、平板仿射 |
| `PetCharacter.swift` | `PetCharacter.java` | 角色列表与平板安全区三点 |
| `PetAssets.swift` | `PetAssets.java` | 命中判定阈值（本移植版额外做轮廓分解） |
| `BalanceClient.swift` | `BalanceClient.java` | 响应字段、错误码、退避 |
| `AppConfig.swift` | `CredentialStore/YamlMini/PetPaths/PetState/Log` | 凭证来源顺序、YAML 结构、状态字段 |
| `PollSchedule.swift` | `PollSchedule.java` | 调度与代次 |
| `*SelfTests.swift` | `SelfTests.java` | 把新增断言一并搬过来 |

## 3. 改代码的顺序（建议）

1. **`PetTuning.java`**：先同步所有数字类变更（节奏、时长、阈值、上限）。
2. **`UpstreamInfo.java`**：更新 commit / 日期 / 文件对应表 / 素材哈希。
3. 再改逻辑文件；每改一处就在 `SelfTests.java` 补一条断言（上游的 SelfTests 是现成的清单）。
4. 行为差异若必须保留（见 README 的差异表），在 `PetTuning` 注释里写明"上游是 X，本移植版是 Y，原因"。

## 4. 素材更新

```sh
cp VK-1-new/dsh-balance-pet-macos/Resources/*.png assets/
cp VK-1-new/dsh-balance-pet-macos/Resources/hit.mp3 res/raw/
sha256sum assets/*.png res/raw/hit.mp3   # 写回 UpstreamInfo.ASSET_HASHES
```

立绘尺寸或平板位置变化时，必须重跑自检里的"平板安全区被穿透矩形覆盖"断言
（它会用 `tabletCorners` + 轮廓分解结果做交叉验证）。

## 5. 验证清单

```sh
bash build.sh . out/dshpet.apk && bash verify.sh
```

- [ ] `verify.sh`：签名、素材哈希、dex 标记全部通过
- [ ] 应用内「运行离线自检」= 全部通过
- [ ] 「校验素材与上游是否一致」= 全部一致
- [ ] 单窗口与轮廓模式各跑一次 60 次扣费压测（`ACTION_DEMO` + `--ez mute true`）：
      掉帧率 < 2%、Missed Vsync ≤ 数帧
- [ ] 换角色、换尺寸、切拖动行为、切音效文件各一次，确认持久化正确

## 6. 版本与文档

- bump `MainActivity.VERSION`
- 在本文件末尾追加"本次升级记录"（原 commit → 新 commit、改了什么、验证结果、未验证的部分）

## 升级记录

### 2026-10-04 · 基线建立（→ `9ab36bd`）

首次把 Android 移植版对齐到上游 v1.3.1；引入 `PetTuning` 与 `UpstreamInfo`，
素材哈希与上游逐字节一致（5 张 PNG + hit.mp3）。
