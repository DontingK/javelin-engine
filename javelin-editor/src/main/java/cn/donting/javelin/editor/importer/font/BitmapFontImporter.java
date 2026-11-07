package cn.donting.javelin.editor.importer.font;

import cn.donting.javelin.core.assets.font.BitmapFont;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.editor.importer.AssetImporter;
import cn.donting.javelin.editor.importer.ImportSettings;
import cn.donting.javelin.editor.importer.ImportTask;
import cn.donting.javelin.editor.meta.AssetMeta;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.project.Project;
import cn.donting.javelin.tool.debugtext.DebugTextFontRasterizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.SortedSet;

/**
 * 点阵字库导入器（{@code .chars} 字符集文本 → Library 二进制 BitmapFont）。
 *
 * <p>DebugText 字库的源形态（2026-11-07 拍板）：{@code .chars} 是纯文本，内容即
 * "要烘焙进字库的字符"；导入器用打包字体（思源黑体 Bold）光栅化为 16x16 1bpp
 * 位图，落 {@link BitmapFont} 产物供运行时经 AssetManager 装载。取代旧的
 * "tool CLI 生成 debug_font16.bin 提交进 core resources" 路线（该 CLI 同批清退）。
 * 排版字符（CR/LF/TAB）不计入字形；□ 回退符（U+25A1）无条件补入。烘焙失败 →
 * 落 stub 产物（空 BitmapFont + guid，isValid=false）→ 消费侧显性报错。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class BitmapFontImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BitmapFontImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".chars");

    @Override
    public String getName() {
        return "BitmapFontImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new BitmapFontImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();
        String sourceName = sourceFile.getName();

        try {
            String text = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);
            BitmapFont font = buildBitmapFont(text, sourceName);

            String guid = task.getMeta().getGuid();
            font.setGuid(guid);
            font.setLastModified(sourceFile.lastModified());

            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(font);

            LOGGER.info("点阵字库导入成功: {} -> {}（{} 字节）",
                    sourceName, outputFile.getName(), font.getData().length);
            return true;

        } catch (Exception e) {
            LOGGER.error("点阵字库导入失败: {}", sourceName, e);
            task.setErrorMessage(EditorLang.text("import.font.importFailed", e.getMessage()));
            return exportStub(sourceFile, task, project);
        }
    }

    /**
     * 烘焙失败 stub 产物（对齐 SpvShaderImporter/ComputeShaderImporter 口径）：
     * 空字段 BitmapFont + guid 替换旧产物；消费侧按 isValid() 判坏显性报错。
     * 返回 true = 产物已落，false = meta 缺 guid 无处落 stub。
     */
    private boolean exportStub(File sourceFile, ImportTask task, Project project) {
        AssetMeta meta = task.getMeta();
        String guid = meta != null ? meta.getGuid() : null;
        if (guid == null || guid.isEmpty()) {
            return false;
        }
        try {
            BitmapFont stub = new BitmapFont();
            stub.setGuid(guid);
            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(stub);
            LOGGER.error("点阵字库烘焙失败，落 stub 产物: {} -> {}",
                    sourceFile.getName(), outputFile.getName());
            return true;
        } catch (Exception exportError) {
            LOGGER.error("stub 产物落盘失败: {}", sourceFile.getName(), exportError);
            return false;
        }
    }

    /**
     * {@code .chars} 文本 → BitmapFont 实例（解析字符集 → 打包字体光栅化 → 装配）。
     * 资产名 = 文件名去扩展名。不含 guid 回写与产物导出——管线与单测共用。
     *
     * @param text       字符集文本
     * @param sourceName 源文件名（取资产名用）
     * @return 点阵字库资产
     * @throws IOException 打包字体缺失或光栅化失败
     */
    public static BitmapFont buildBitmapFont(String text, String sourceName) throws IOException {
        SortedSet<Integer> codepoints = DebugTextFontRasterizer.parseCharset(text);
        byte[] data = DebugTextFontRasterizer.rasterizeToBytes(
                DebugTextFontRasterizer.bundledFontPath(), codepoints);
        String assetName = sourceName.contains(".")
                ? sourceName.substring(0, sourceName.lastIndexOf('.'))
                : sourceName;
        return new BitmapFont(assetName, data);
    }
}
