package com.dsh.balancepet;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 YAML 读取器，只覆盖 DSH 实际写出的凭证结构。
 * 支持块式映射、引号与注释；不支持锚点、别名或多行标量。
 *
 * 逐条移植自 macOS 版 AppConfig.swift 的 CredentialStore 内部实现。
 */
public final class YamlMini {

    public static final class MappingLine {
        public final int indent;
        public final String key;
        public final String value;
        public final int offset;   // 在原文件中的行号

        MappingLine(int offset, int indent, String key, String value) {
            this.offset = offset;
            this.indent = indent;
            this.key = key;
            this.value = value;
        }
    }

    private final List<String> lines = new ArrayList<>();
    private final List<MappingLine> mappings = new ArrayList<>();

    public YamlMini(String text) {
        for (String line : text.split("\n", -1)) lines.add(line);
        for (int i = 0; i < lines.size(); i++) {
            MappingLine m = mappingLine(i, lines.get(i));
            if (m != null) mappings.add(m);
        }
    }

    /** 唯一的一条 DEEPSEEK_API_KEY 映射（重复或非法则拒绝）。 */
    public String apiKey() {
        MappingLine found = null;
        for (MappingLine m : mappings) {
            if (!"DEEPSEEK_API_KEY".equals(m.key)) continue;
            if (found != null) return null;   // 重复字段不可信
            found = m;
        }
        if (found == null) return null;
        String value = scalar(found.value);
        return CredentialStore.isValidToken(value) ? value : null;
    }

    /**
     * 读取 DSH 的账号记录：kind + payload { version, token, issuer }；
     * 兼容旧版把 token / issuer 直接写在记录下的结构。
     * 只有在同一层容器内的兄弟字段才能组成凭证。
     */
    public String[] accountGrant() {
        MappingLine grant = null;
        for (MappingLine m : mappings) {
            if (!"deepseek-account-platform/default".equals(m.key)) continue;
            if (grant != null) return null;
            grant = m;
        }
        if (grant == null || !grant.value.isEmpty()) return null;

        List<MappingLine> direct = childrenAfter(grant.offset, grant.indent);
        if (direct == null) return null;

        List<MappingLine> payloads = new ArrayList<>();
        List<MappingLine> legacy = new ArrayList<>();
        for (MappingLine m : direct) {
            if ("payload".equals(m.key)) payloads.add(m);
            else if ("token".equals(m.key) || "issuer".equals(m.key)) legacy.add(m);
        }

        List<MappingLine> fields;
        if (!payloads.isEmpty()) {
            if (payloads.size() != 1 || !legacy.isEmpty()) return null;
            MappingLine payload = payloads.get(0);
            if (!payload.value.isEmpty()) return null;
            List<MappingLine> nested = childrenAfter(payload.offset, payload.indent);
            if (nested == null) return null;
            fields = nested;
        } else {
            fields = legacy;
        }

        String token = null, issuer = null;
        for (MappingLine field : fields) {
            if (!"token".equals(field.key) && !"issuer".equals(field.key)) continue;
            String value = scalar(field.value);
            if (value == null) return null;
            if ("token".equals(field.key)) {
                if (token != null) return null;
                token = value;
            } else {
                if (issuer != null) return null;
                issuer = value;
            }
        }
        if (!CredentialStore.isValidToken(token) || issuer == null || issuer.isEmpty()) return null;
        return new String[]{token, issuer};
    }

    /** 返回 offset 之后、缩进大于 parentIndent、且处于同一子级缩进的行。 */
    private List<MappingLine> childrenAfter(int offset, int parentIndent) {
        List<MappingLine> result = new ArrayList<>();
        Integer childIndent = null;
        for (int i = offset + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            if (indent <= parentIndent) break;
            if (childIndent == null) childIndent = indent;
            if (indent != childIndent) continue;
            MappingLine item = mappingLine(i, line);
            if (item == null) return null;   // 子级里出现非映射行 → 结构不可信
            result.add(item);
        }
        return result;
    }

    static MappingLine mappingLine(int offset, String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        String text = line.substring(indent);
        if (text.isEmpty() || text.startsWith("#") || text.startsWith("\t")) return null;

        Character quote = null;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\' && quote != null && quote == '"') { escaped = true; continue; }
            if (ch == '"' || ch == '\'') {
                if (quote != null && quote == ch) quote = null;
                else if (quote == null) quote = ch;
                continue;
            }
            if (ch == ':' && quote == null) {
                boolean boundary = (i + 1 == text.length()) || Character.isWhitespace(text.charAt(i + 1));
                if (!boundary) continue;
                String key = scalar(text.substring(0, i));
                if (key == null) return null;
                String value = stripComment(text.substring(i + 1)).trim();
                return new MappingLine(offset, indent, key, value);
            }
        }
        return null;
    }

    static String stripComment(String text) {
        Character quote = null;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\' && quote != null && quote == '"') { escaped = true; continue; }
            if (ch == '"' || ch == '\'') {
                if (quote != null && quote == ch) quote = null;
                else if (quote == null) quote = ch;
            } else if (ch == '#' && quote == null
                    && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    static String scalar(String raw) {
        String text = stripComment(raw).trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("\"")) {
            try {
                return new org.json.JSONArray("[" + text + "]").getString(0);
            } catch (Exception e) {
                return null;
            }
        }
        if (text.startsWith("'")) {
            if (text.length() < 2 || !text.endsWith("'")) return null;
            String body = text.substring(1, text.length() - 1);
            String value = body.replace("''", "'");
            if (body.replace("''", "").indexOf('\'') >= 0) return null;
            return value;
        }
        if ("null".equals(text) || "Null".equals(text) || "NULL".equals(text) || "~".equals(text)) return null;
        if ("!&*[{|>".indexOf(text.charAt(0)) >= 0) return null;
        return text;
    }
}