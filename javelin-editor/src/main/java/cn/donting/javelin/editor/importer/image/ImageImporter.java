package cn.donting.javelin.editor.importer.image;

import cn.donting.javelin.core.render.texture.Image;
import cn.donting.javelin.core.render.texture.TextureLoader;
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

/**
 * .png/.jpg/.jpeg 图片导入器（ImageIO 解码 → Image 像素资产 → Library 二进制产物）。
 *
 * <p>导入链（Engine_Texture_Design §2）：{@link TextureLoader#loadImage} 解码为
 * 紧密打包 RGBA8 → guid/lastModified 回写 → {@link BinaryExporter} 写
 * {@code Library/{guid}.asset}。源是二进制、无 guid 文件头——伴生 .meta 即 guid
 * 真值（同 .mat/.scene 口径）。加载侧经
 * {@code AssetLibManager.loadGuid(guid, Image.class)} 取回。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class ImageImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".png", ".jpg", ".jpeg");

    @Override
    public String getName() {
        return "ImageImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new ImageImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();
        String sourceName = sourceFile.getName();

        try {
            Image image = TextureLoader.loadImage(sourceFile.toPath());

            // 二进制源无 guid 头：伴生 meta 即真值（同 .mat/.scene 口径）
            String guid = task.getMeta().getGuid();
            image.setGuid(guid);
            image.setLastModified(sourceFile.lastModified());

            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(image);

            LOGGER.info("贴图导入成功: {} ({}x{}) -> {}",
                    sourceName, image.getWidth(), image.getHeight(), outputFile.getName());
            return true;

        } catch (Exception e) {
            LOGGER.error("贴图导入失败: {}", sourceName, e);
            task.setErrorMessage(EditorLang.text("import.image.importFailed", e.getMessage()));
            return false;
        }
    }
}
