package cn.donting.javelin.editor.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 源码机检（Editor_Plugin_Design §4.5-3）：javelin-editor 主源码的字符串
 * 字面量不得含 CJK 字符——漏国际化从人眼 CR 变测试红线。扫描面=
 * src/main/java 全量 .java；词法状态机剥离注释（注释中文合法——项目注释
 * 语言即中文）、跨行携带字符串/字符态（text block 内容按字面量检）后收集
 * 字符串内容再检。log 调用行豁免（诊断面拍板：Unity 也不 i18n 日志）；
 * 白名单=引导期文件（早于语言包注册，EditorLang 不可用）与语言自书数据/
 * 开发者面工具类（§4.5-3 白名单口径，见 WHITELIST）。
 */
public class CjkStringLiteralCheckTest {

    /** 白名单（/ 分隔的路径后缀）：引导期（EditorLang 未注入即可能被调）+ 开发者面 */
    private static final Set<String> WHITELIST = Set.of(
            // i18n 基建自举（异常消息若走 I18n 解析=引导死循环）
            "i18n/I18n.java",
            "i18n/I18nKey.java",
            "i18n/I18nScope.java",
            "plugin/EditorLang.java",
            // 插件装载链：装载期可能早于 editor.core setup（EditorLang 未注入）
            "plugin/PluginManager.java",
            "plugin/PluginInstance.java",
            "plugin/PluginClassLoader.java",
            "plugin/PluginContext.java",
            // 启动器窗口：先于插件装载显示（语言包未注册）
            "window/ProjectFrame.java",
            "window/ProjectLoadingFrame.java",
            // 语言自书显示名（registerLanguage("zh_CN", "中文")——语言数据非界面文案）
            "plugin/EditorCorePlugin.java",
            // 开发者面工具类（路径/类名拼错误消息，不进用户 UI）
            "util/FileUtil.java",
            "util/ClassUtil.java",
            "util/StringUtil.java");

    @Test
    public void noCjkStringLiteralsInMainSources() throws IOException {
        Path mainJava = Paths.get("src", "main", "java");
        if (!Files.isDirectory(mainJava)) {
            // IDE/构建工作目录不定，退回仓库根再找
            mainJava = Paths.get("javelin-editor", "src", "main", "java");
        }
        assertTrue(Files.isDirectory(mainJava),
                "找不到 src/main/java（工作目录=" + System.getProperty("user.dir") + "）");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainJava)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String unified = p.toString().replace('\\', '/');
                        return WHITELIST.stream().noneMatch(unified::endsWith);
                    })
                    .forEach(p -> scan(p, violations));
        }
        if (!violations.isEmpty()) {
            fail("发现未国际化中文字面量（应迁 EditorLang + 语言包；日志/白名单口径见 CjkStringLiteralCheckTest）：\n"
                    + String.join("\n", violations));
        }
    }

    /** 逐文件扫描：词法状态跨行携带；log 调用行的字面量豁免 */
    private static void scan(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            violations.add(file + "：读取失败 " + e.getMessage());
            return;
        }
        LexState state = new LexState();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean logCall = line.matches(".*\\b(log|LOGGER|logger|out)\\s*\\.\\s*\\w+\\s*\\(.*");
            LexState after = lexLine(line, state);
            if (!logCall && after.literals.stream().anyMatch(CjkStringLiteralCheckTest::containsCjk)) {
                violations.add(file + ":" + (i + 1) + ": " + line.trim());
            }
            state = after;
        }
    }

    /** 词法状态（跨行携带：块注释 / 字符串 / 字符 / 未闭合字面量缓冲） */
    private static final class LexState {
        final boolean inBlockComment;
        final boolean inString;
        final boolean inChar;
        final StringBuilder pending;
        final List<String> literals;

        LexState() {
            this(false, false, false, null, new ArrayList<>());
        }

        LexState(boolean inBlockComment, boolean inString, boolean inChar,
                 StringBuilder pending, List<String> literals) {
            this.inBlockComment = inBlockComment;
            this.inString = inString;
            this.inChar = inChar;
            this.pending = pending;
            this.literals = literals;
        }
    }

    /** 扫一行：进入态 → 收集本行完成的字面量 + 行末态 */
    private static LexState lexLine(String line, LexState start) {
        boolean inBlockComment = start.inBlockComment;
        boolean inString = start.inString;
        boolean inChar = start.inChar;
        StringBuilder pending = start.pending;
        List<String> literals = new ArrayList<>();
        for (int j = 0; j < line.length(); j++) {
            char c = line.charAt(j);
            char next = j + 1 < line.length() ? line.charAt(j + 1) : '\0';
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    j++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    if (next != '\0') {
                        pending.append(next);
                    }
                    j++;
                    continue;
                }
                if (c == '"') {
                    literals.add(pending.toString());
                    pending = null;
                    inString = false;
                    continue;
                }
                pending.append(c);
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    j++;
                    continue;
                }
                if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                break;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                j++;
                continue;
            }
            if (c == '"') {
                inString = true;
                pending = new StringBuilder();
                continue;
            }
            if (c == '\'') {
                inChar = true;
            }
        }
        return new LexState(inBlockComment, inString, inChar, pending, literals);
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }
}
