package com.dsh.balancepet;

/**
 * 上游版本基线。升级上游时只需要改这一个文件 + 重新核对 {@link PetTuning}。
 *
 * 记录方式刻意用 commit sha 而不是「v1.3.x」这种会漂移的标签。
 */
public final class UpstreamInfo {

    /** 上游仓库（README 里自称的链接被截断成 VKmich16/V，实际仓库名是 VK-1）。 */
    public static final String REPO = "https://github.com/VKmich16/VK-1";
    /** 第二个上游：Whale 挂件的交互与「高度自定义泡泡」体系（代码 MIT；其 assets/** 不在 MIT 内）。 */
    public static final String REPO_WHALE = "https://github.com/MeteorNOX/DeepSeek-Balance-Whale-Widget";
    /** 作者署名：本项目是纯 AI 结对编程（webcoding）产物 —— AI 主写，Millosuki 提需求与验收。 */
    public static final String AUTHOR_AI = "Operit (AI)";
    public static final String AUTHOR_HUMAN = "Millosuki";

    /** 上游 README 标注的版本。 */
    public static final String UPSTREAM_VERSION = "v1.3.1";

    /** 我们对照的上游 commit。 */
    public static final String UPSTREAM_COMMIT = "9ab36bd2fa9ff13b0b832f98a7c0f240aa0cf36d";

    /** 上游 commit 日期。 */
    public static final String UPSTREAM_COMMIT_DATE = "2026-10-03";

    /** 本移植版版本（每次行为变更都要 bump）。 */
    public static final String PORT_VERSION = MainActivity.VERSION;

    /** 上游文件 → 本移植版文件 的对应关系（升级时按这张表逐个核对）。 */
    public static final String[] FILE_MAP = {
            "dsh-balance-pet-macos/Sources/PetModel.swift        → PetModel.java（账本 + 动画）",
            "dsh-balance-pet-macos/Sources/PetView.swift         → PetRenderer.java + PetBandView/PetSceneLayout",
            "dsh-balance-pet-macos/Sources/PetController.swift   → PetService.java",
            "dsh-balance-pet-macos/Sources/PetLayout.swift       → PetLayout.java",
            "dsh-balance-pet-macos/Sources/PetCharacter.swift    → PetCharacter.java",
            "dsh-balance-pet-macos/Sources/PetAssets.swift       → PetAssets.java（+ 轮廓矩形分解）",
            "dsh-balance-pet-macos/Sources/BalanceClient.swift   → BalanceClient.java",
            "dsh-balance-pet-macos/Sources/AppConfig.swift       → CredentialStore.java + YamlMini.java + PetPaths/Log/PetState",
            "dsh-balance-pet-macos/Sources/PollSchedule.swift    → PollSchedule.java",
            "dsh-balance-pet-macos/Sources/*SelfTests.swift      → SelfTests.java",
            "原版（Windows版）/DSH余额桌宠/dsh_pet.ps1             → 行为基准（0.2s 节奏 / 0.01 步长 / 3 层受伤叠加）"
    };

    /** 素材基线哈希（与上游 Resources 逐字节一致；升级时用 verify.sh 校验）。 */
    public static final String[] ASSET_HASHES = {
            "sprite.png                     a98329d36dd9169a1856f3a396bc9e602ed1a739bd3097eead1b744c6bb3dd71",
            "sprite-gpt.png                 41f79346666fbb776ee2baef2a0cf044850a50e30c177b3adf9bb14e84296467",
            "sprite-claude.png              81f0e787057dd9d5e400e5f43a44a1b55da4adfa7329aa712d986531cbd5d90d",
            "sprite-gemini.png              ad5fbeb07c2212476d4670ec56b8e7202e21f05b42ab0461cf3150a43f91a2ef",
            "sprite-deepseek-offline.png    fb4c5cb3001ca43d268d2e3bdf39b9d984e28d592e3b73e46e6fd44ccebb4555",
            "hit.mp3                        43fa877b537d8cbfbd676d76109b9a960551bfeae06c62e2d1a7d64d3994cb29"
    };

    private UpstreamInfo() {}
}