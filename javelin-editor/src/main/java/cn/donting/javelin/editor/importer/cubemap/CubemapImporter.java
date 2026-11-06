package cn.donting.javelin.editor.importer.cubemap;

import cn.donting.javelin.core.render.texture.CubemapImage;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.editor.importer.AssetImporter;
import cn.donting.javelin.editor.importer.ImportSettings;
import cn.donting.javelin.editor.importer.ImportTask;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * cubemap 天空盒导入器：.hdr（Radiance RGBE）/ .pano（png/jpg 全景图）equirect
 * 源 → 六面 cubemap 像素资产（Engine_Skybox_Design §5 步骤三）。转换归
 * {@link EquirectConverter}；产物 Library 二进制（Image 同款，伴生 .meta 即
 * guid 真值）。源图须 2:1，坏源显性报错（红线 15）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class CubemapImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubemapImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".hdr", ".pano");

    @Override
    public String getName() {
        return "CubemapImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new CubemapImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();
        String sourceName = sourceFile.getName();

        String guid = task.getMeta().getGuid();
        if (guid == null || guid.isEmpty()) {
            task.setErrorMessage(EditorLang.text("import.metaMissingGuid"));
            return false;
        }

        try {
            String lower = sourceName.toLowerCase(Locale.ROOT);
            CubemapImage cubemap = lower.endsWith(".hdr")
                    ? EquirectConverter.fromHdr(sourceFile.toPath())
                    : EquirectConverter.fromPano(sourceFile.toPath());

            // 二进制源无 guid 头：伴生 meta 即真值（Image 同口径）
            cubemap.setGuid(guid);
            cubemap.setLastModified(sourceFile.lastModified());

            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(cubemap);

            LOGGER.info("cubemap 导入成功: {} ({}x{}x6) -> {}",
                    sourceName, cubemap.getFaceSize(), cubemap.getFaceSize(), outputFile.getName());
            return true;

        } catch (Exception e) {
            LOGGER.error("cubemap 导入失败: {}", sourceName, e);
            task.setErrorMessage(EditorLang.text("import.cubemap.importFailed", e.getMessage()));
            return false;
        }
    }
}
