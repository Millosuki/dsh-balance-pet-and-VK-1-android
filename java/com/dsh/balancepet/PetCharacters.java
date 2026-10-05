package com.dsh.balancepet;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 角色注册表（v1.13.0）：内置角色 + **用户自定义角色**。
 *
 * <p>为什么要这一层：内置四个角色是「手持平板、身上显示余额」的（平板安全区坐标是对着图量过的），
 * 而 v1.13.0 起多了两类**不显示余额**的角色 —— Whale 小鲸鱼、以及用户自己导入的图片。
 * 所以“当前用哪个角色”不能再是一个枚举，而是「一个描述」：
 *
 * <ul>
 *   <li>内置：{@link PetCharacter}（其中 {@code tablet=false} 的不画平板）；</li>
 *   <li>自定义：图片存在应用私有目录 {@code files/characters/<id>.png}，名字由用户起，
 *       索引写在 {@link PetState#customCharactersJson}。</li>
 * </ul>
 *
 * <p>余额显示策略：只有内置且 {@code tablet=true} 的角色才在**身上**画余额数字；
 * 其它角色照样可以在**泡泡**里用「余额数值 / 今日已用」等模块看余额 —— 两者互不影响。
 */
public final class PetCharacters {

    /** 一个「当前角色」的解析结果。 */
    public static final class Current {
        public final String id;
        public final String displayName;
        /** 内置角色：APK assets 里的文件名；自定义角色为 null。 */
        public final String assetName;
        /** 自定义角色：用户导入并复制过来的图片；内置角色为 null。 */
        public final java.io.File file;
        /** 内置角色本体；自定义角色为 null。 */
        public final PetCharacter builtIn;

        Current(String id, String displayName, String assetName, java.io.File file, PetCharacter builtIn) {
            this.id = id;
            this.displayName = displayName;
            this.assetName = assetName;
            this.file = file;
            this.builtIn = builtIn;
        }

        public boolean isCustom() { return file != null; }

        /** 是否在**角色身上**显示余额（只有内置且手持平板的角色会）。 */
        public boolean hasTablet() { return builtIn != null && builtIn.hasTablet(); }

        /** 平板安全区三点；不显示余额的角色返回 null。 */
        public float[] tabletCorners() { return hasTablet() ? builtIn.tabletCorners() : null; }

        /**
         * 位图缓存键。自定义角色带上文件修改时间 —— 否则用户换了同一路径的图片会命中旧位图。
         */
        public String cacheKey() {
            if (file != null) return "file:" + file.getAbsolutePath() + "@" + file.lastModified();
            return assetName;
        }
    }

    /** 自定义角色的元数据（对应 {@link PetState#customCharactersJson} 里的一项）。 */
    public static final class Custom {
        public final String id;
        public final String name;
        public final java.io.File file;

        Custom(String id, String name, java.io.File file) {
            this.id = id;
            this.name = name;
            this.file = file;
        }
    }

    private PetCharacters() {}

    // ------------------------------------------------------------------ 读

    /** 解析自定义角色列表（JSON 坏掉就当空列表，不抛）。 */
    public static java.util.List<Custom> customs(PetState state) {
        java.util.List<Custom> out = new java.util.ArrayList<>();
        if (state == null || state.customCharactersJson == null || state.customCharactersJson.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(state.customCharactersJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                String name = o.optString("name", "");
                String path = o.optString("file", "");
                if (id.isEmpty() || path.isEmpty()) continue;
                java.io.File f = new java.io.File(path);
                if (!f.exists()) {
                    Log.write("自定义角色「" + name + "」的图片已不在（" + path + "），已跳过");
                    continue;
                }
                out.add(new Custom(id, name.isEmpty() ? "自定义角色" : name, f));
            }
        } catch (Exception e) {
            Log.write("自定义角色列表解析失败（按空处理）: " + e);
        }
        return out;
    }

    /** 当前真正生效的角色：优先自定义（且图片还在），否则内置。 */
    public static Current current(PetState state) {
        if (state == null) return builtIn(PetCharacter.DEEPSEEK);
        String want = state.customCharacterId == null ? "" : state.customCharacterId;
        if (!want.isEmpty()) {
            for (Custom c : customs(state)) {
                if (c.id.equals(want)) return new Current(c.id, c.name, null, c.file, null);
            }
            Log.write("自定义角色 " + want + " 已不存在 → 回落到内置角色");
        }
        return builtIn(state.character);
    }

    /** 内置角色 → Current。 */
    public static Current builtIn(PetCharacter character) {
        PetCharacter c = character == null ? PetCharacter.DEEPSEEK : character;
        return new Current(c.id, c.displayName, c.assetName, null, c);
    }

    /** 界面上要展示的全部角色（内置在前、自定义在后）。 */
    public static java.util.List<Current> all(PetState state) {
        java.util.List<Current> out = new java.util.ArrayList<>();
        for (PetCharacter c : PetCharacter.values()) out.add(builtIn(c));
        for (Custom c : customs(state)) out.add(new Current(c.id, c.name, null, c.file, null));
        return out;
    }

    // ------------------------------------------------------------------ 写

    /** 选内置角色。 */
    public static void selectBuiltIn(PetState state, PetCharacter character) {
        state.character = character;
        state.customCharacterId = "";
    }

    /** 选自定义角色（id 不存在则什么也不做）。 */
    public static boolean selectCustom(PetState state, String id) {
        for (Custom c : customs(state)) {
            if (c.id.equals(id)) {
                state.customCharacterId = id;
                return true;
            }
        }
        return false;
    }

    /**
         * 登记一个自定义角色（图片已由调用方复制到 {@link PetPaths#characterFile(String)}）。
         * id 由调用方生成，这样“文件路径”和“索引里的 id”从一开始就一致。
         */
        public static String addCustom(PetState state, String id, String name, java.io.File file) {
        try {
            JSONArray arr = new JSONArray(
                    state.customCharactersJson == null || state.customCharactersJson.trim().isEmpty()
                            ? "[]" : state.customCharactersJson);
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", (name == null || name.trim().isEmpty()) ? "自定义角色" : name.trim());
            o.put("file", file.getAbsolutePath());
            arr.put(o);
            state.customCharactersJson = arr.toString();
            Log.write("已登记自定义角色「" + o.optString("name") + "」id=" + id + " 文件=" + file);
        } catch (Exception e) {
            Log.write("登记自定义角色失败: " + e);
        }
        return id;
    }

    /** 改名字。 */
    public static boolean renameCustom(PetState state, String id, String newName) {
        try {
            JSONArray arr = new JSONArray(
                    state.customCharactersJson == null || state.customCharactersJson.trim().isEmpty()
                            ? "[]" : state.customCharactersJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !id.equals(o.optString("id"))) continue;
                o.put("name", (newName == null || newName.trim().isEmpty()) ? "自定义角色" : newName.trim());
                state.customCharactersJson = arr.toString();
                Log.write("自定义角色改名 → " + o.optString("name"));
                return true;
            }
        } catch (Exception e) {
            Log.write("自定义角色改名失败: " + e);
        }
        return false;
    }

    /** 删除自定义角色（同时删图片；如果正在用它，就切回内置的蓝色大肥鱼）。 */
    public static boolean removeCustom(PetState state, String id) {
        try {
            JSONArray arr = new JSONArray(
                    state.customCharactersJson == null || state.customCharactersJson.trim().isEmpty()
                            ? "[]" : state.customCharactersJson);
            JSONArray keep = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (id.equals(o.optString("id"))) {
                    removed = true;
                    java.io.File f = new java.io.File(o.optString("file", ""));
                    if (f.isFile() && !f.delete()) Log.write("自定义角色图片删除失败：" + f);
                    continue;
                }
                keep.put(o);
            }
            if (!removed) return false;
            state.customCharactersJson = keep.toString();
            if (id.equals(state.customCharacterId)) {
                state.customCharacterId = "";
                state.character = PetCharacter.DEEPSEEK;
                Log.write("正在使用的自定义角色被删除 → 切回蓝色大肥鱼");
            }
            Log.write("已删除自定义角色 id=" + id);
            return true;
        } catch (Exception e) {
            Log.write("删除自定义角色失败: " + e);
            return false;
        }
    }
}