package com.dsh.balancepet;

/**
 * 四个内置角色。ID 会被持久化，显示名可改而不丢失用户选择。
 * 逐条移植自 dsh-balance-pet-macos/Sources/PetCharacter.swift。
 */
public enum PetCharacter {
    DEEPSEEK("deepseek", "蓝色大肥鱼", "sprite.png", true),
        GPT("gpt", "GPT龙娘", "sprite-gpt.png", true),
        CLAUDE("claude", "大小姐Claude", "sprite-claude.png", true),
        GEMINI("gemini", "北美猫娘Gemini", "sprite-gemini.png", true),
        /**
         * v1.13.0：来自 Whale 插件的角色（`assets/DSniang1.png`，见 PROVENANCE.md）。
         *
         * <p>它是**纯形象**：手里没有平板，因此 {@link #tablet}=false —— 不会画余额数字
         * （余额照样可以在泡泡里用模块显示）。
         */
        WHALE("whale", "Whale小鲸鱼", "sprite-whale.png", false);
        public final String id;
        public final String displayName;
        public final String assetName;
        /** 是否手持平板（= 是否在角色身上显示余额）。 */
        public final boolean tablet;
        PetCharacter(String id, String displayName, String assetName, boolean tablet) {
            this.id = id;
            this.displayName = displayName;
            this.assetName = assetName;
            this.tablet = tablet;
        }
        /** 这个角色是否会在身上显示余额（只有手持平板的角色会）。 */
        public boolean hasTablet() { return tablet; }

    /** 蓝色大肥鱼未连接时使用的抱盆图（其他角色无此状态）。 */
    public static final String OFFLINE_ASSET = "sprite-deepseek-offline.png";

    public static PetCharacter fromId(String id) {
        if (id != null) {
            for (PetCharacter c : values()) if (c.id.equals(id)) return c;
        }
        return DEEPSEEK; // 旧配置或未知 ID 回退
    }

    /**
     * 每张 1536 × 1024 PNG 中测量出的平板安全区三点（图像坐标，y 向下）。
     * 与 Swift 版 PetCharacter.tabletCorners 完全一致。
     */
    public float[] tabletCorners() {
        if (this == GEMINI) {
            // Gemini 右手指更靠近平板中央，单独收窄。
            return new float[]{1065f, 699f, 1400f, 646f, 1095f, 889f};
        }
        return new float[]{1060f, 699f, 1413f, 644f, 1090f, 889f};
    }
}