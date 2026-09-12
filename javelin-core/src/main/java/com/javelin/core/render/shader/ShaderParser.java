package com.javelin.core.render.shader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shader DSL 解析器 —— 将 .shader 文本解析为 {@link ShaderAST}。
 *
 * <p>使用手写 tokenizer + 递归下降解析，支持：</p>
 * <ul>
 *   <li>文件头 {@code // @javelin-guid:} 注释提取</li>
 *   <li>Shader 根块、Properties 属性块、GlobalUniforms 全局 Uniform 块</li>
 *   <li>Pass 块（含渲染状态与 vs/fs 大括号配平的 GLSL 代码块）</li>
 * </ul>
 *
 * <p>解析失败抛出 {@link ShaderParseException}，消息含行列号。
 * 不解析 {@code #pragma} 相关关键字（着色器变体系统本期不引入）。</p>
 */
public class ShaderParser {

    /** GUID 文件头注释前缀 */
    private static final String GUID_PREFIX = "// @javelin-guid:";

    /**
     * 解析 .shader 源文件文本。
     *
     * @param source 源文件文本
     * @return 解析后的 AST 根节点
     * @throws ShaderParseException 解析失败时抛出
     */
    public ShaderAST.ShaderNode parse(String source) {
        // 文件头注释在 tokenizer 中会被跳过，先提取 GUID
        String guid = extractGuid(source);

        Tokenizer tokenizer = new Tokenizer(source);
        List<Token> tokens = tokenizer.tokenize();
        Parser parser = new Parser(tokens, guid);
        return parser.parseShader();
    }

    /**
     * 从源码首个非空行的行注释中提取 GUID。
     *
     * @param source 源文件文本
     * @return GUID 字符串，未找到返回 null
     */
    private String extractGuid(String source) {
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith(GUID_PREFIX)) {
                return trimmed.substring(GUID_PREFIX.length()).trim();
            }
            // 首个非空行不是 GUID 注释则停止扫描
            break;
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Token 定义
    // ═══════════════════════════════════════════════════════════════════════

    private enum TokenType {
        // 关键字
        SHADER, PROPERTIES, GLOBAL_UNIFORMS, PASS,
        RENDER_PATH, RENDER_LAYER, ZWRITE, ZTEST, CULL_MODE, BLEND,
        VS, FS,
        // 类型
        FLOAT, RANGE, INT, BOOL, VECTOR2, VECTOR3, VECTOR4, COLOR, TEXTURE2D,
        // 字面量
        IDENTIFIER, STRING, NUMBER, BOOLEAN,
        // 符号
        LBRACE, RBRACE, LPAREN, RPAREN, LBRACKET, RBRACKET,
        SEMICOLON, COLON, COMMA, EQUALS,
        // 特殊
        VECTOR_LITERAL,   // (1.0, 0.0, 0.0, 1.0)
        PP_DIRECTIVE,     // #version / #include 等预处理指令整行
        EOF
    }

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("Shader", TokenType.SHADER);
        KEYWORDS.put("Properties", TokenType.PROPERTIES);
        KEYWORDS.put("GlobalUniforms", TokenType.GLOBAL_UNIFORMS);
        KEYWORDS.put("Pass", TokenType.PASS);
        KEYWORDS.put("renderPath", TokenType.RENDER_PATH);
        KEYWORDS.put("renderLayer", TokenType.RENDER_LAYER);
        KEYWORDS.put("zWrite", TokenType.ZWRITE);
        KEYWORDS.put("zTest", TokenType.ZTEST);
        KEYWORDS.put("cullMode", TokenType.CULL_MODE);
        KEYWORDS.put("blend", TokenType.BLEND);
        KEYWORDS.put("vs", TokenType.VS);
        KEYWORDS.put("fs", TokenType.FS);
        KEYWORDS.put("Float", TokenType.FLOAT);
        KEYWORDS.put("Range", TokenType.RANGE);
        KEYWORDS.put("Int", TokenType.INT);
        KEYWORDS.put("Bool", TokenType.BOOL);
        KEYWORDS.put("Vector2", TokenType.VECTOR2);
        KEYWORDS.put("Vector3", TokenType.VECTOR3);
        KEYWORDS.put("Vector4", TokenType.VECTOR4);
        KEYWORDS.put("Color", TokenType.COLOR);
        KEYWORDS.put("Texture2D", TokenType.TEXTURE2D);
    }

    private static class Token {
        final TokenType type;
        final String text;
        final int line;
        final int column;

        Token(TokenType type, String text, int line, int column) {
            this.type = type;
            this.text = text;
            this.line = line;
            this.column = column;
        }

        @Override
        public String toString() {
            return String.format("%s('%s')@%d:%d", type, text, line, column);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  词法分析器 (Tokenizer)
    // ═══════════════════════════════════════════════════════════════════════

    private static class Tokenizer {
        private final String source;
        private int pos;
        private int line = 1;
        private int column = 1;
        private final List<Token> tokens = new ArrayList<>();

        Tokenizer(String source) {
            this.source = source;
        }

        List<Token> tokenize() {
            while (pos < source.length()) {
                skipWhitespace();
                if (pos >= source.length()) break;

                char c = source.charAt(pos);

                if (c == '/' && peek(1) == '/') {
                    skipLineComment();
                } else if (c == '/' && peek(1) == '*') {
                    skipBlockComment();
                } else if (c == '"') {
                    readString();
                } else if (c == '#') {
                    // GLSL 预处理指令（#version / #include 等）按整行保留
                    readPreprocessorDirective();
                } else if (Character.isLetter(c) || c == '_') {
                    readIdentifierOrKeyword();
                } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
                    readNumber();
                } else if (c == '(') {
                    // 可能是向量字面量 (1.0, 0.0, ...)
                    if (!tryReadVectorLiteral()) {
                        addToken(TokenType.LPAREN, "(");
                        advance();
                    }
                } else {
                    readSymbol();
                }
            }

            tokens.add(new Token(TokenType.EOF, "", line, column));
            return tokens;
        }

        private void skipWhitespace() {
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r') {
                    advance();
                } else if (c == '\n') {
                    line++;
                    column = 1;
                    advance();
                } else {
                    break;
                }
            }
        }

        private void skipLineComment() {
            while (pos < source.length() && source.charAt(pos) != '\n') {
                advance();
            }
        }

        private void skipBlockComment() {
            advance(); // /
            advance(); // *
            while (pos < source.length() - 1) {
                if (source.charAt(pos) == '*' && source.charAt(pos + 1) == '/') {
                    advance();
                    advance();
                    return;
                }
                if (source.charAt(pos) == '\n') {
                    line++;
                    column = 1;
                }
                advance();
            }
            throw new ShaderParseException("未闭合的块注释", line, column);
        }

        private void readString() {
            int startLine = line;
            int startCol = column;
            advance(); // 跳过开头 "
            StringBuilder sb = new StringBuilder();
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (c == '"') {
                    advance();
                    tokens.add(new Token(TokenType.STRING, sb.toString(), startLine, startCol));
                    return;
                } else if (c == '\\' && pos + 1 < source.length()) {
                    char next = source.charAt(pos + 1);
                    if (next == '"' || next == '\\') {
                        sb.append(next);
                        advance();
                        advance();
                    } else {
                        sb.append(c);
                        advance();
                    }
                } else {
                    sb.append(c);
                    advance();
                }
            }
            throw new ShaderParseException("未闭合的字符串", startLine, startCol);
        }

        /** 读取以 # 开头的预处理指令整行（不含换行符） */
        private void readPreprocessorDirective() {
            int startLine = line;
            int startCol = column;
            StringBuilder sb = new StringBuilder();
            while (pos < source.length() && source.charAt(pos) != '\n') {
                sb.append(source.charAt(pos));
                advance();
            }
            tokens.add(new Token(TokenType.PP_DIRECTIVE, sb.toString().trim(), startLine, startCol));
        }

        private void readIdentifierOrKeyword() {
            int startLine = line;
            int startCol = column;
            StringBuilder sb = new StringBuilder();
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    sb.append(c);
                    advance();
                } else {
                    break;
                }
            }
            String text = sb.toString();
            TokenType type = KEYWORDS.get(text);
            if (type == null) {
                if (text.equals("true") || text.equals("false")) {
                    type = TokenType.BOOLEAN;
                } else {
                    type = TokenType.IDENTIFIER;
                }
            }
            tokens.add(new Token(type, text, startLine, startCol));
        }

        private void readNumber() {
            int startLine = line;
            int startCol = column;
            StringBuilder sb = new StringBuilder();
            boolean hasDot = false;
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (Character.isDigit(c)) {
                    sb.append(c);
                    advance();
                } else if (c == '.' && !hasDot) {
                    hasDot = true;
                    sb.append(c);
                    advance();
                } else {
                    break;
                }
            }
            tokens.add(new Token(TokenType.NUMBER, sb.toString(), startLine, startCol));
        }

        private boolean tryReadVectorLiteral() {
            // 预检查：当前是 '('，内部含逗号且本行闭合，视为向量字面量
            if (source.charAt(pos) != '(') return false;

            int scanPos = pos + 1;
            int parenDepth = 1;
            boolean hasComma = false;
            boolean looksLikeVector = false;

            while (scanPos < source.length() && parenDepth > 0) {
                char c = source.charAt(scanPos);
                if (c == '(') parenDepth++;
                else if (c == ')') {
                    parenDepth--;
                    if (parenDepth == 0) {
                        looksLikeVector = hasComma;
                        break;
                    }
                } else if (c == ',' && parenDepth == 1) {
                    hasComma = true;
                } else if (c == '\n') {
                    break; // 向量字面量不应跨行
                }
                scanPos++;
            }

            if (!looksLikeVector) {
                return false;
            }

            int startLine = line;
            int startCol = column;
            StringBuilder sb = new StringBuilder();
            sb.append('(');
            advance(); // (
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (c == ')') {
                    sb.append(c);
                    advance();
                    break;
                }
                sb.append(c);
                advance();
            }
            tokens.add(new Token(TokenType.VECTOR_LITERAL, sb.toString(), startLine, startCol));
            return true;
        }

        private void readSymbol() {
            char c = source.charAt(pos);
            switch (c) {
                case '{' -> { addToken(TokenType.LBRACE, "{"); advance(); }
                case '}' -> { addToken(TokenType.RBRACE, "}"); advance(); }
                case ')' -> { addToken(TokenType.RPAREN, ")"); advance(); }
                case '[' -> { addToken(TokenType.LBRACKET, "["); advance(); }
                case ']' -> { addToken(TokenType.RBRACKET, "]"); advance(); }
                case ';' -> { addToken(TokenType.SEMICOLON, ";"); advance(); }
                case ':' -> { addToken(TokenType.COLON, ":"); advance(); }
                case ',' -> { addToken(TokenType.COMMA, ","); advance(); }
                case '=' -> { addToken(TokenType.EQUALS, "="); advance(); }
                default -> throw new ShaderParseException("非法字符: '" + c + "'", line, column);
            }
        }

        private void addToken(TokenType type, String text) {
            tokens.add(new Token(type, text, line, column));
        }

        private void advance() {
            if (pos < source.length()) {
                pos++;
                column++;
            }
        }

        private char peek(int offset) {
            int idx = pos + offset;
            return idx < source.length() ? source.charAt(idx) : '\0';
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  语法分析器 (Parser)
    // ═══════════════════════════════════════════════════════════════════════

    private static class Parser {
        private final List<Token> tokens;
        private final String guid;
        private int pos;

        Parser(List<Token> tokens, String guid) {
            this.tokens = tokens;
            this.guid = guid;
        }

        ShaderAST.ShaderNode parseShader() {
            ShaderAST.ShaderNode shader = new ShaderAST.ShaderNode();
            shader.guid = guid;

            consume(TokenType.SHADER, "期望 'Shader' 关键字");
            shader.name = consumeString("期望 Shader 名称字符串");
            consume(TokenType.LBRACE, "期望 '{'");

            while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                if (check(TokenType.PROPERTIES)) {
                    if (shader.properties != null) {
                        throw error("重复的 Properties 块");
                    }
                    shader.properties = parseProperties();
                } else if (check(TokenType.GLOBAL_UNIFORMS)) {
                    if (shader.globalUniforms != null) {
                        throw error("重复的 GlobalUniforms 块");
                    }
                    shader.globalUniforms = parseGlobalUniforms();
                } else if (check(TokenType.PASS)) {
                    shader.passes.add(parsePass());
                } else {
                    throw error("意外的标记: " + peek().text + "，期望 Properties、GlobalUniforms 或 Pass");
                }
            }

            consume(TokenType.RBRACE, "期望 Shader 块的 '}'");
            consume(TokenType.EOF, "期望文件结束");

            validateShader(shader);
            return shader;
        }

        private ShaderAST.PropertiesNode parseProperties() {
            consume(TokenType.PROPERTIES, "");
            consume(TokenType.LBRACE, "期望 Properties 的 '{'");

            ShaderAST.PropertiesNode node = new ShaderAST.PropertiesNode();
            while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                node.properties.add(parseProperty());
            }

            consume(TokenType.RBRACE, "期望 Properties 的 '}'");
            return node;
        }

        private ShaderAST.PropertyNode parseProperty() {
            ShaderAST.PropertyNode prop = new ShaderAST.PropertyNode();

            prop.name = consumeIdentifier("期望属性标识符");
            consume(TokenType.LPAREN, "期望 '('");
            prop.displayName = consumeString("期望属性显示名称");
            consume(TokenType.COMMA, "期望 ','");
            prop.type = parsePropertyType();

            // Range(min, max)：tokenizer 会将 "(0, 1)" 识别为向量字面量，两种形态都要兼容
            if ("Range".equals(prop.type)) {
                float[] range = parseRangeParams();
                prop.rangeMin = range[0];
                prop.rangeMax = range[1];
            } else if (check(TokenType.VECTOR_LITERAL) || check(TokenType.LPAREN)) {
                throw error("非 Range 类型不接受 (min, max) 参数");
            }

            consume(TokenType.RPAREN, "期望属性声明的 ')'");
            consume(TokenType.EQUALS, "期望 '='");
            prop.defaultValue = parseDefaultValue(prop.type);

            return prop;
        }

        /** 解析 Range 的 (min, max) 参数，返回 [min, max] */
        private float[] parseRangeParams() {
            String text;
            if (check(TokenType.VECTOR_LITERAL)) {
                text = advance().text;
            } else {
                consume(TokenType.LPAREN, "期望 Range 参数 '(min, max)'");
                StringBuilder sb = new StringBuilder("(");
                sb.append(consume(TokenType.NUMBER, "Range 参数期望数字").text);
                consume(TokenType.COMMA, "期望 Range 参数的 ','");
                sb.append(", ").append(consume(TokenType.NUMBER, "Range 参数期望数字").text);
                consume(TokenType.RPAREN, "期望 Range 参数的 ')'");
                sb.append(")");
                text = sb.toString();
            }

            String inner = text.substring(1, text.length() - 1);
            String[] parts = inner.split(",");
            if (parts.length != 2) {
                throw error("Range 参数必须恰好为 (min, max) 两个数字");
            }
            return new float[]{
                Float.parseFloat(parts[0].trim()),
                Float.parseFloat(parts[1].trim())
            };
        }

        private String parsePropertyType() {
            Token token = advance();
            switch (token.type) {
                case FLOAT, RANGE, INT, BOOL, VECTOR2, VECTOR3, VECTOR4, COLOR, TEXTURE2D -> {
                    return token.text;
                }
                default -> throw error("未知的属性类型: " + token.text);
            }
        }

        private Object parseDefaultValue(String type) {
            return switch (type) {
                case "Float", "Range" -> Float.parseFloat(consume(TokenType.NUMBER, "期望浮点默认值").text);
                case "Int" -> Integer.parseInt(consume(TokenType.NUMBER, "期望整数默认值").text);
                case "Bool" -> Boolean.parseBoolean(consume(TokenType.BOOLEAN, "期望布尔默认值").text);
                case "Vector2", "Vector3", "Vector4", "Color" -> parseVectorLiteral();
                case "Texture2D" -> consumeString("期望纹理默认值字符串");
                default -> throw error("不支持默认值的类型: " + type);
            };
        }

        private float[] parseVectorLiteral() {
            Token token = consume(TokenType.VECTOR_LITERAL, "期望向量字面量如 (1.0, 0.0, 0.0, 1.0)");
            String text = token.text.substring(1, token.text.length() - 1);
            String[] parts = text.split(",");
            float[] values = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                values[i] = Float.parseFloat(parts[i].trim());
            }
            return values;
        }

        private ShaderAST.GlobalUniformsNode parseGlobalUniforms() {
            consume(TokenType.GLOBAL_UNIFORMS, "");
            consume(TokenType.LBRACE, "期望 GlobalUniforms 的 '{'");

            ShaderAST.GlobalUniformsNode node = new ShaderAST.GlobalUniformsNode();
            while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                ShaderAST.UniformBlockNode block = parseUniformBlock();
                if (block.name.equals("JavelinPerFrame")) {
                    if (node.perFrame != null) {
                        throw error("重复的 JavelinPerFrame 块");
                    }
                    node.perFrame = block;
                } else if (block.name.equals("JavelinPerObject")) {
                    if (node.perObject != null) {
                        throw error("重复的 JavelinPerObject 块");
                    }
                    node.perObject = block;
                } else {
                    throw error("未知的 Uniform 块: " + block.name + "，仅支持 JavelinPerFrame / JavelinPerObject");
                }
            }

            consume(TokenType.RBRACE, "期望 GlobalUniforms 的 '}'");
            return node;
        }

        private ShaderAST.UniformBlockNode parseUniformBlock() {
            ShaderAST.UniformBlockNode block = new ShaderAST.UniformBlockNode();
            block.name = consumeIdentifier("期望 Uniform 块名称");
            consume(TokenType.LBRACE, "期望 Uniform 块的 '{'");

            while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                ShaderAST.UniformFieldNode field = new ShaderAST.UniformFieldNode();
                field.type = consumeIdentifier("期望 GLSL 类型");
                field.name = consumeIdentifier("期望字段名");
                block.fields.add(field);
                match(TokenType.SEMICOLON); // 字段分隔分号可省略
            }

            consume(TokenType.RBRACE, "期望 Uniform 块的 '}'");
            return block;
        }

        private ShaderAST.PassNode parsePass() {
            consume(TokenType.PASS, "");
            ShaderAST.PassNode pass = new ShaderAST.PassNode();
            pass.name = consumeIdentifier("期望 Pass 名称");
            consume(TokenType.LBRACE, "期望 Pass 的 '{'");

            while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
                if (check(TokenType.RENDER_PATH)) {
                    pass.renderPath = parseRenderPath();
                } else if (check(TokenType.RENDER_LAYER)) {
                    pass.renderLayer = parseRenderLayer();
                } else if (check(TokenType.ZWRITE)) {
                    pass.zWrite = parseZWrite();
                } else if (check(TokenType.ZTEST)) {
                    pass.zTest = parseZTest();
                } else if (check(TokenType.CULL_MODE)) {
                    pass.cullMode = parseCullMode();
                } else if (check(TokenType.BLEND)) {
                    pass.blend = parseBlend();
                    pass.blendDeclared = true;
                } else if (check(TokenType.VS)) {
                    if (pass.vertexShader != null) {
                        throw error("重复的 vs 代码块");
                    }
                    pass.vertexShader = parseShaderStage(ShaderAST.StageType.VERTEX);
                } else if (check(TokenType.FS)) {
                    if (pass.fragmentShader != null) {
                        throw error("重复的 fs 代码块");
                    }
                    pass.fragmentShader = parseShaderStage(ShaderAST.StageType.FRAGMENT);
                } else {
                    throw error("Pass 中意外的标记: " + peek().text);
                }
            }

            consume(TokenType.RBRACE, "期望 Pass 的 '}'");
            return pass;
        }

        private ShaderAST.RenderPath parseRenderPath() {
            consume(TokenType.RENDER_PATH, "");
            String value = consumeIdentifier("期望渲染路径值 (Forward/Deferred/Shadow)");
            try {
                return ShaderAST.RenderPath.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw error("未知的渲染路径: " + value);
            }
        }

        private ShaderAST.RenderLayer parseRenderLayer() {
            consume(TokenType.RENDER_LAYER, "");
            String value = consumeIdentifier("期望渲染层值 (Opaque/Transparent/Overlay)");
            try {
                return ShaderAST.RenderLayer.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw error("未知的渲染层: " + value);
            }
        }

        private boolean parseZWrite() {
            consume(TokenType.ZWRITE, "");
            String value = consumeIdentifier("期望 zWrite 值 (On/Off)");
            if (!value.equals("On") && !value.equals("Off")) {
                throw error("未知的 zWrite 值: " + value);
            }
            return value.equals("On");
        }

        private ShaderAST.CompareFunction parseZTest() {
            consume(TokenType.ZTEST, "");
            String value = consumeIdentifier("期望 zTest 值 (Off/Never/Less/LessEqual/Equal/Greater/GreaterEqual/Always)");
            try {
                return ShaderAST.CompareFunction.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw error("未知的 zTest 值: " + value);
            }
        }

        private ShaderAST.CullMode parseCullMode() {
            consume(TokenType.CULL_MODE, "");
            String value = consumeIdentifier("期望 cullMode 值 (Back/Front/None)");
            try {
                return ShaderAST.CullMode.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw error("未知的 cullMode 值: " + value);
            }
        }

        private ShaderAST.BlendStateNode parseBlend() {
            consume(TokenType.BLEND, "");
            ShaderAST.BlendStateNode blend = new ShaderAST.BlendStateNode();

            String value = consumeIdentifier("期望 blend 值 (Off 或 <srcFactor> <dstFactor>)");
            if (value.equals("Off")) {
                blend.enabled = false;
                blend.srcFactor = ShaderAST.BlendFactor.One;
                blend.dstFactor = ShaderAST.BlendFactor.Zero;
            } else {
                blend.enabled = true;
                blend.srcFactor = parseBlendFactor(value);
                blend.dstFactor = parseBlendFactor(consumeIdentifier("期望目标混合因子"));
            }

            return blend;
        }

        private ShaderAST.BlendFactor parseBlendFactor(String value) {
            try {
                return ShaderAST.BlendFactor.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw error("未知的混合因子: " + value);
            }
        }

        private ShaderAST.ShaderStageNode parseShaderStage(ShaderAST.StageType type) {
            consume(type == ShaderAST.StageType.VERTEX ? TokenType.VS : TokenType.FS, "");
            consume(TokenType.LBRACE, "期望着色器代码块的 '{'");

            ShaderAST.ShaderStageNode stage = new ShaderAST.ShaderStageNode(type);
            stage.source = extractCodeBlock();
            return stage;
        }

        /** 大括号配平提取 GLSL 代码块（GLSL 内部允许任意嵌套大括号） */
        private String extractCodeBlock() {
            StringBuilder sb = new StringBuilder();
            int braceDepth = 1;

            while (pos < tokens.size()) {
                Token token = tokens.get(pos);
                if (token.type == TokenType.LBRACE) {
                    braceDepth++;
                    sb.append(token.text);
                    pos++;
                } else if (token.type == TokenType.RBRACE) {
                    braceDepth--;
                    if (braceDepth == 0) {
                        pos++; // 消费闭合的 }
                        break;
                    }
                    sb.append(token.text);
                    pos++;
                } else if (token.type == TokenType.EOF) {
                    throw error("着色器代码块未闭合");
                } else if (token.type == TokenType.PP_DIRECTIVE) {
                    sb.append('\n').append(token.text).append('\n');
                    pos++;
                } else {
                    if (sb.length() > 0 && needsSpace(sb, token)) {
                        sb.append(' ');
                    }
                    sb.append(token.text);
                    pos++;
                }
            }

            return sb.toString().trim();
        }

        private boolean needsSpace(StringBuilder sb, Token token) {
            char last = sb.charAt(sb.length() - 1);
            char first = token.text.charAt(0);
            // 标识符/数字后面跟标识符/数字需要空格
            return (Character.isLetterOrDigit(last) || last == '_') &&
                   (Character.isLetterOrDigit(first) || first == '_');
        }

        // ═════════════════════════════════════════════════════════════════
        //  语义验证
        // ═════════════════════════════════════════════════════════════════

        private void validateShader(ShaderAST.ShaderNode shader) {
            // 至少一个 Pass
            if (shader.passes.isEmpty()) {
                throw new ShaderParseException("Shader '" + shader.name + "' 缺少 Pass 块", 0, 0);
            }

            // Pass 名称唯一
            Set<String> passNames = new HashSet<>();
            for (ShaderAST.PassNode pass : shader.passes) {
                if (!passNames.add(pass.name)) {
                    throw new ShaderParseException("重复的 Pass 名称: " + pass.name, 0, 0);
                }
            }

            // 属性名称唯一
            if (shader.properties != null) {
                Set<String> propNames = new HashSet<>();
                for (ShaderAST.PropertyNode prop : shader.properties.properties) {
                    if (!propNames.add(prop.name)) {
                        throw new ShaderParseException("重复的属性名称: " + prop.name, 0, 0);
                    }
                }
            }

            // 每个 Pass 至少一个着色器阶段
            for (ShaderAST.PassNode pass : shader.passes) {
                if (pass.vertexShader == null && pass.fragmentShader == null) {
                    throw new ShaderParseException("Pass '" + pass.name + "' 缺少着色器代码", 0, 0);
                }
            }
        }

        // ═════════════════════════════════════════════════════════════════
        //  辅助方法
        // ═════════════════════════════════════════════════════════════════

        private Token peek() {
            return tokens.get(pos);
        }

        private Token advance() {
            return tokens.get(pos++);
        }

        private boolean check(TokenType type) {
            return peek().type == type;
        }

        /** 匹配则消费并返回 true，否则不动（可选语法用） */
        private boolean match(TokenType type) {
            if (check(type)) {
                advance();
                return true;
            }
            return false;
        }

        private Token consume(TokenType type, String message) {
            if (!check(type)) {
                throw error(message + "，实际得到: " + peek().text);
            }
            return advance();
        }

        private String consumeString(String message) {
            return consume(TokenType.STRING, message).text;
        }

        private String consumeIdentifier(String message) {
            return consume(TokenType.IDENTIFIER, message).text;
        }

        private ShaderParseException error(String message) {
            Token token = peek();
            return new ShaderParseException(message, token.line, token.column);
        }
    }
}
