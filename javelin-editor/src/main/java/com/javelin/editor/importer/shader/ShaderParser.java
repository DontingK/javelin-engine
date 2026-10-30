package com.javelin.editor.importer.shader;

import com.javelin.core.render.material.MaterialProperty;
import com.javelin.core.render.material.PassState;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.ColorComponentFlags;
import com.javelin.rhi.types.CompareOp;
import com.javelin.rhi.types.CullMode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * .shader 文本解析器（Unity ShaderLab 简化版，RHI 21 节 v0.13）。
 *
 * <p>语法：{@code Shader "name" { Properties {...} Pass 名 { 状态命令 vs{} fs{} } }}。
 * 顶层识别大小写敏感（Shader/Properties/Pass/vs/fs），状态命令取值大小写不敏感
 * （Unity 惯用名与 Vulkan 枚举名都收，如 LEqual / Less / SRC_ALPHA）。
 * 行注释与块注释对解析器透明（注释层跳过）。</p>
 *
 * <p>解析产出 core 数据模型：MaterialProperty 列表 + ParsedPass（名/PassState/vs/fs 原文），
 * SPIR-V 编译与声明注入由 {@link ShaderInjector} / 导入器衔接。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class ShaderParser {

    private final String src;
    private final String sourceName;
    private int pos = 0;
    private int line = 1;

    /** ZTest 取值：Unity 惯用名 → CompareOp（CompareOp 无 ALWAYS，Unity Always 显式报错） */
    private static final Map<String, CompareOp> ZTEST_NAMES = Map.of(
            "lequal", CompareOp.LESS_OR_EQUAL,
            "less_or_equal", CompareOp.LESS_OR_EQUAL,
            "less", CompareOp.LESS,
            "equal", CompareOp.EQUAL,
            "gequal", CompareOp.GREATER_OR_EQUAL,
            "greater_or_equal", CompareOp.GREATER_OR_EQUAL,
            "greater", CompareOp.GREATER,
            "notequal", CompareOp.NOT_EQUAL,
            "not_equal", CompareOp.NOT_EQUAL,
            "never", CompareOp.NEVER);

    /**
     * Blend 因子：key = 小写去下划线形态（与 blendFactor 查询端对齐——
     * "OneMinusSrcAlpha"（Unity）与 "ONE_MINUS_SRC_ALPHA"（Vulkan）统一归一为 oneminussrcalpha）
     */
    private static final Map<String, BlendFactor> BLEND_NAMES = Map.ofEntries(
            Map.entry("one", BlendFactor.ONE),
            Map.entry("zero", BlendFactor.ZERO),
            Map.entry("srccolor", BlendFactor.SRC_COLOR),
            Map.entry("oneminussrccolor", BlendFactor.ONE_MINUS_SRC_COLOR),
            Map.entry("dstcolor", BlendFactor.DST_COLOR),
            Map.entry("oneminusdstcolor", BlendFactor.ONE_MINUS_DST_COLOR),
            Map.entry("srcalpha", BlendFactor.SRC_ALPHA),
            Map.entry("oneminussrcalpha", BlendFactor.ONE_MINUS_SRC_ALPHA),
            Map.entry("dstalpha", BlendFactor.DST_ALPHA),
            Map.entry("oneminusdstalpha", BlendFactor.ONE_MINUS_DST_ALPHA),
            Map.entry("constantcolor", BlendFactor.CONSTANT_COLOR),
            Map.entry("oneminusconstantcolor", BlendFactor.ONE_MINUS_CONSTANT_COLOR),
            Map.entry("constantalpha", BlendFactor.CONSTANT_ALPHA),
            Map.entry("oneminusconstantalpha", BlendFactor.ONE_MINUS_CONSTANT_ALPHA),
            Map.entry("srcalphasaturate", BlendFactor.SRC_ALPHA_SATURATE));

    private ShaderParser(String src, String sourceName) {
        this.src = src;
        this.sourceName = sourceName;
    }

    /**
     * 解析 .shader 源码（v0.13 全语法）。解析失败（语法错/未知命令/取值非法）抛
     * {@link ShaderParseException}（带行号）。
     */
    public static ParsedShader parse(String source, String sourceName) {
        ShaderParser parser = new ShaderParser(source, sourceName);
        ParsedShader shader = parser.parseShader();
        if (shader.passes().isEmpty()) {
            throw new ShaderParseException(EditorLang.key("shader.parse.needPass"), sourceName, parser.line);
        }
        return shader;
    }

    // ═══ 解析主体 ═══

    private ParsedShader parseShader() {
        expectKeyword("Shader");
        String shaderName = readString();
        expect('{');

        ArrayList<MaterialProperty> properties = new ArrayList<>();
        ArrayList<ParsedPass> passes = new ArrayList<>();
        Set<String> passNames = new java.util.HashSet<>();

        while (true) {
            skipTrivia();
            if (peek() == '}') {
                next();
                break;
            }
            if (atEnd()) {
                throw error("shader.parse.shaderUnclosed");
            }
            String keyword = readIdentifier();
            switch (keyword) {
                case "Properties" -> properties.addAll(parseProperties());
                case "Pass" -> {
                    ParsedPass pass = parsePassBody();
                    if (!passNames.add(pass.name())) {
                        throw error("shader.parse.dupPass", pass.name());
                    }
                    passes.add(pass);
                }
                default -> throw error("shader.parse.unknownTopBlock", keyword);
            }
        }
        return new ParsedShader(shaderName, properties, passes);
    }

    private ArrayList<MaterialProperty> parseProperties() {
        expect('{');
        ArrayList<MaterialProperty> properties = new ArrayList<>();
        while (true) {
            skipTrivia();
            if (peek() == '}') {
                next();
                break;
            }
            if (atEnd()) {
                throw error("shader.parse.propertiesUnclosed");
            }
            properties.add(parsePropertyLine());
        }
        return properties;
    }

    /**
     * 属性声明：_Name ("Display", Type) = 默认值 [;]
     * Color/Vector → (r,g,b,a)；Float/Range → 0.5；2D → "white" [{ }]
     */
    private MaterialProperty parsePropertyLine() {
        String propName = readIdentifier();
        expect('(');
        String displayName = readString();
        expect(',');
        skipTrivia();
        String typeKeyword = readTypeKeyword();
        MaterialProperty property = switch (typeKeyword) {
            case "Color", "Vector" -> {
                expect(')');
                expect('=');
                expect('(');
                float r = readNumber();
                expect(',');
                float g = readNumber();
                expect(',');
                float b = readNumber();
                expect(',');
                float a = readNumber();
                expect(')');
                float[] v = {r, g, b, a};
                yield typeKeyword.equals("Color")
                        ? MaterialProperty.color(propName, displayName, v[0], v[1], v[2], v[3])
                        : MaterialProperty.vector(propName, displayName, v[0], v[1], v[2], v[3]);
            }
            case "Float" -> {
                expect(')');
                expect('=');
                yield MaterialProperty.floatProp(propName, displayName, readNumber());
            }
            case "Range" -> {
                expect('(');
                float min = readNumber();
                expect(',');
                float max = readNumber();
                expect(')');
                expect(')');   // 外层声明括号——Range 类型自带 (min, max) 参数括号，须另吞
                expect('=');
                yield MaterialProperty.range(propName, displayName, min, max, readNumber());
            }
            case "2D" -> {
                expect(')');
                expect('=');
                String placeholder = readString();
                skipTrivia();
                if (peek() == '{') {          // Unity 的 "white" {} 带空块声明，吞掉
                    next();
                    skipTrivia();
                    expect('}');
                }
                yield MaterialProperty.texture2D(propName, displayName, placeholder);
            }
            default -> throw error("shader.parse.unknownPropType", typeKeyword);
        };
        skipTrivia();
        if (peek() == ';') {
            next();
        }
        return property;
    }

    private ParsedPass parsePassBody() {
        skipTrivia();
        String passName = readIdentifier();
        expect('{');
        PassState state = new PassState();
        String vsBody = null;
        String fsBody = null;
        int vsLine = 0;
        int fsLine = 0;

        while (true) {
            skipTrivia();
            if (peek() == '}') {
                next();
                break;
            }
            if (atEnd()) {
                throw error("shader.parse.passUnclosed");
            }
            String keyword = readIdentifier();
            switch (keyword) {
                case "vs" -> {
                    if (vsBody != null) {
                        throw error("shader.parse.dupVs");
                    }
                    vsLine = line;
                    vsBody = captureGlslBlock();
                }
                case "fs" -> {
                    if (fsBody != null) {
                        throw error("shader.parse.dupFs");
                    }
                    fsLine = line;
                    fsBody = captureGlslBlock();
                }
                case "Cull" -> state.setCull(readCull());
                case "ZWrite" -> state.setDepthWrite(readOnOff("ZWrite"));
                case "ZTest" -> state.setDepthTest(readZTest());
                case "Blend" -> readBlend(state);
                case "ColorMask" -> state.setColorMask(readColorMask());
                default -> throw error("shader.parse.unknownPassCmd", keyword);
            }
        }

        if (vsBody == null || fsBody == null) {
            throw new ShaderParseException(
                    EditorLang.key("shader.parse.passMissingStage", vsBody == null ? "vs" : "fs"),
                    sourceName, line);
        }
        return new ParsedPass(passName, state, vsBody, fsBody, vsLine, fsLine);
    }

    // ═══ 状态命令取值 ═══

    private CullMode readCull() {
        String value = readIdentifier().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "back" -> CullMode.BACK;
            case "front" -> CullMode.FRONT;
            case "off" -> CullMode.NONE;
            default -> throw error("shader.parse.badCull", value);
        };
    }

    private boolean readOnOff(String command) {
        String value = readIdentifier().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "on" -> true;
            case "off" -> false;
            default -> throw error("shader.parse.badOnOff", command, value);
        };
    }

    private CompareOp readZTest() {
        String value = readIdentifier().toLowerCase(Locale.ROOT);
        CompareOp op = ZTEST_NAMES.get(value);
        if (op == null) {
            throw error("shader.parse.badZTest", value);
        }
        return op;
    }

    /** Blend Off | Blend SrcFactor DstFactor（颜色/alpha 同因子，PassState v0.1 语义） */
    private void readBlend(PassState state) {
        String first = readIdentifier();
        if ("off".equalsIgnoreCase(first)) {
            state.setBlend(false, BlendFactor.ONE, BlendFactor.ZERO);
            return;
        }
        BlendFactor src = blendFactor(first);
        BlendFactor dst = blendFactor(readIdentifier());
        state.setBlend(true, src, dst);
    }

    private BlendFactor blendFactor(String rawName) {
        // 命名容差：OneMinusSrcAlpha（Unity）/ ONE_MINUS_SRC_ALPHA（Vulkan）统一小写去下划线匹配
        String key = rawName.toLowerCase(Locale.ROOT).replace("_", "");
        BlendFactor factor = BLEND_NAMES.get(key);
        if (factor == null) {
            throw error("shader.parse.badBlend", rawName);
        }
        return factor;
    }

    /** ColorMask 掩码字（如 RGBA / RG / A）：读一个标识符逐字符解析，防误吞后续命令首字母 */
    private Set<ColorComponentFlags> readColorMask() {
        String word = readIdentifier().toUpperCase(Locale.ROOT);
        Set<ColorComponentFlags> mask = EnumSet.noneOf(ColorComponentFlags.class);
        for (int i = 0; i < word.length(); i++) {
            switch (word.charAt(i)) {
                case 'R' -> mask.add(ColorComponentFlags.R);
                case 'G' -> mask.add(ColorComponentFlags.G);
                case 'B' -> mask.add(ColorComponentFlags.B);
                case 'A' -> mask.add(ColorComponentFlags.A);
                default -> throw error("shader.parse.badColorMask", word);
            }
        }
        return mask;
    }

    // ═══ 词法 ═══

    /** 跳过空白与行/块注释——GUID 文件头行注释在此透明 */
    private void skipTrivia() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                if (c == '\n') {
                    line++;
                }
                pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                pos += 2;
                while (pos + 1 < src.length()
                        && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                    if (src.charAt(pos) == '\n') {
                        line++;
                    }
                    pos++;
                }
                pos = Math.min(pos + 2, src.length());
            } else {
                break;
            }
        }
    }

    /** 抓取 vs/fs 的原始 GLSL 块（从 '{' 到配对 '}'，跳过注释与字符串字面量防大括号误配） */
    private String captureGlslBlock() {
        skipTrivia();
        expect('{');
        int start = pos;
        int depth = 1;
        while (pos < src.length() && depth > 0) {
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
                pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                pos += 2;
                while (pos + 1 < src.length()
                        && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                    if (src.charAt(pos) == '\n') {
                        line++;
                    }
                    pos++;
                }
                pos = Math.min(pos + 2, src.length());
            } else if (c == '"') {
                pos++;
                while (pos < src.length() && src.charAt(pos) != '"') {
                    pos++;
                }
                pos++;
            } else if (c == '{') {
                depth++;
                pos++;
            } else if (c == '}') {
                depth--;
                pos++;
            } else {
                pos++;
            }
        }
        if (depth > 0) {
            throw error("shader.parse.glslUnclosed");
        }
        // 回退最后的配对 '}'，块内容 = [start, pos-1)
        return src.substring(start, pos - 1);
    }

    private char peek() {
        if (atEnd()) {
            throw error("shader.parse.unexpectedEof");
        }
        return src.charAt(pos);
    }

    private void next() {
        pos++;
    }

    private boolean atEnd() {
        return pos >= src.length();
    }

    private void expect(char expected) {
        skipTrivia();
        if (atEnd() || peek() != expected) {
            throw error("shader.parse.expectChar", String.valueOf(expected),
                    atEnd() ? "<EOF>" : String.valueOf(peek()));
        }
        next();
    }

    private void expectKeyword(String keyword) {
        skipTrivia();
        String ident = readIdentifier();
        if (!keyword.equals(ident)) {
            throw error("shader.parse.expectKeyword", keyword, ident);
        }
    }

    private String readIdentifier() {
        skipTrivia();
        if (atEnd() || !isIdentStart(peek())) {
            throw error("shader.parse.expectIdent", atEnd() ? "<EOF>" : String.valueOf(peek()));
        }
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        return src.substring(start, pos);
    }

    /**
     * 类型关键字读词：允许数字开头——"2D" 合法（readIdentifier 拒收致 2D 属性
     * 恒炸的死分支，2026-10-25 修）。属性名仍走 readIdentifier 不放宽。
     */
    private String readTypeKeyword() {
        skipTrivia();
        if (atEnd() || !isIdentPart(peek())) {
            throw error("shader.parse.expectType", atEnd() ? "<EOF>" : String.valueOf(peek()));
        }
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        return src.substring(start, pos);
    }

    private String readString() {
        skipTrivia();
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != '"') {
            sb.append(src.charAt(pos));
            pos++;
        }
        if (atEnd()) {
            throw error("shader.parse.stringUnclosed");
        }
        next();
        return sb.toString();
    }

    private float readNumber() {
        skipTrivia();
        int start = pos;
        if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
            pos++;
        }
        while (pos < src.length()
                && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos++;
        }
        if (start == pos) {
            throw error("shader.parse.expectNumber", atEnd() ? "<EOF>" : String.valueOf(peek()));
        }
        try {
            return Float.parseFloat(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw error("shader.parse.badNumber", src.substring(start, pos));
        }
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 解析错抛（收 I18nKey，异常自持键、显示点解析） */
    private ShaderParseException error(String key, Object... args) {
        return new ShaderParseException(EditorLang.key(key, args), sourceName, line);
    }

    // ═══ 解析产物 ═══

    /**
     * 解析结果：Shader 名 + 属性声明序 + Pass 列表（SPIR-V 未编译，vs/fs 为原文）。
     */
    public static final class ParsedShader {
        private final String shaderName;
        private final ArrayList<MaterialProperty> properties;
        private final ArrayList<ParsedPass> passes;

        ParsedShader(String shaderName, ArrayList<MaterialProperty> properties,
                     ArrayList<ParsedPass> passes) {
            this.shaderName = shaderName;
            this.properties = properties;
            this.passes = passes;
        }

        public String shaderName() {
            return shaderName;
        }

        public ArrayList<MaterialProperty> properties() {
            return properties;
        }

        public ArrayList<ParsedPass> passes() {
            return passes;
        }
    }

    /**
     * 单个 Pass 的解析结果：块名（渲染阶段名）+ 固定状态 + vs/fs GLSL 原文
     * （起始行号供编译报错定位用）。
     */
    public static final class ParsedPass {
        private final String name;
        private final PassState state;
        private final String vsBody;
        private final String fsBody;
        private final int vsStartLine;
        private final int fsStartLine;

        ParsedPass(String name, PassState state, String vsBody, String fsBody,
                   int vsStartLine, int fsStartLine) {
            this.name = name;
            this.state = state;
            this.vsBody = vsBody;
            this.fsBody = fsBody;
            this.vsStartLine = vsStartLine;
            this.fsStartLine = fsStartLine;
        }

        public String name() {
            return name;
        }

        public PassState state() {
            return state;
        }

        public String vsBody() {
            return vsBody;
        }

        public String fsBody() {
            return fsBody;
        }

        public int vsStartLine() {
            return vsStartLine;
        }

        public int fsStartLine() {
            return fsStartLine;
        }
    }
}
