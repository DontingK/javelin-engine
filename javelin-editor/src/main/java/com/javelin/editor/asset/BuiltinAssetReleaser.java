package com.javelin.editor.asset;

import com.javelin.core.Env;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.importer.AssetImporter;
import com.javelin.editor.importer.ImportSettings;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.meta.GuidHeaderManager;
import com.javelin.editor.meta.MetaManager;
import com.javelin.editor.project.Project;
import com.javelin.editor.util.FileUtil;
import com.javelin.editor.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * 编辑器内置资源释放器。
 *
 * <p>负责将打包在 classpath 中的内置资源释放到项目目录的 assets 文件夹中，
 * 并生成对应的 meta 文件。</p>
 *
 * <p>扫描机制：运行时通过 {@link ClassLoader#getResources(String)} 获取所有
 * {@code builtin-asset.index} 索引文件，从每条路径的 {@code builtin-asset-{name}/} 前缀
 * 解析资源名，资源释放到 {@code .javelin/assets/{name}/} 目录下。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class BuiltinAssetReleaser {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuiltinAssetReleaser.class);

    /**
     * 索引文件名。
     */
    private static final String INDEX_NAME = "builtin-asset.index";

    /**
     * 资源目录前缀。
     */
    private static final String ASSET_DIR_PREFIX = "builtin-asset-";

    /**
     * 项目实例。
     */
    private final Project project;

    /**
     * Meta 文件管理器。
     */
    private final MetaManager metaManager;
    private final AssetImportPipeline assetImportPipeline;

    /**
     * @param project             项目实例
     * @param metaManager         Meta 文件管理器
     * @param assetImportPipeline assetImportPipeline
     */
    public BuiltinAssetReleaser(Project project, MetaManager metaManager,
                                AssetImportPipeline assetImportPipeline) {
        this.project = project;
        this.metaManager = metaManager;
        this.assetImportPipeline = assetImportPipeline;
    }

    /**
     * 释放所有内置资源。
     *
     * @throws IOException IO 异常
     */
    public void releaseAll() throws IOException {
        ClassLoader classLoader = this.getClass().getClassLoader();
        Enumeration<URL> urls = classLoader.getResources(INDEX_NAME);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            try (InputStream is = url.openStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String assetName = extractAssetName(line);
                    if (assetName == null) {
                        LOGGER.warn("无法从路径解析资源名: {}", line);
                        continue;
                    }
                    ImportSettings importSettings = createImportSettings(line);
                    release(assetName, line, importSettings);
                }
            }
        }
    }

    /**
     * 从完整 classpath 路径中提取资源名。
     *
     * <p>路径形如 {@code builtin-asset-core/mesh/plane.smesh}，提取得到 {@code core}。</p>
     *
     * @param classpathPath 完整 classpath 路径
     * @return 资源名，解析失败返回 {@code null}
     */
    private String extractAssetName(String classpathPath) {
        if (!classpathPath.startsWith(ASSET_DIR_PREFIX)) {
            return null;
        }
        int slash = classpathPath.indexOf('/', ASSET_DIR_PREFIX.length());
        if (slash < 0) {
            return null;
        }
        return classpathPath.substring(ASSET_DIR_PREFIX.length(), slash);
    }

    /**
     * 根据资源路径后缀创建对应的导入设置。
     *
     * @param path 资源路径
     * @return 导入设置实例
     */
    private ImportSettings createImportSettings(String path) {
        AssetImporter assetImporter = assetImportPipeline.getAssetImporter(new File(path));
        if (assetImporter != null) {
            return assetImporter.createDefaultSettings(new File(path));
        }
        throw new IllegalArgumentException("未知的资源后缀: " + path);
    }

    /**
     * 释放单个内置资源。
     *
     * @param assetName      资源名（决定释放到 {@code .javelin/assets/{assetName}/}）
     * @param classpathPath  完整 classpath 路径（如 {@code builtin-asset-core/mesh/plane.smesh}）
     * @param importSettings 导入设置
     * @throws IOException IO 异常
     */
    private void release(String assetName, String classpathPath, ImportSettings importSettings) throws IOException {
        String relPath = extractRelativePath(assetName, classpathPath);
        File rootDir = project.getRootDir();
        File file = new File(rootDir, ".javelin/assets/" + assetName + "/" + relPath);
        if (Env.isRelease() && file.exists()) {
            return;
        }
        ClassLoader classLoader = this.getClass().getClassLoader();
        URL resource = classLoader.getResource(classpathPath);
        if (resource == null) {
            throw new RuntimeException("资源不存在：" + classpathPath);
        }
        try (InputStream inputStream = resource.openStream()) {
            FileUtil.copy(inputStream, file);
        }
        GuidHeaderManager guidHeaderManager = new GuidHeaderManager();
        String guid = guidHeaderManager.extractGuid(file);
        if (StringUtil.isEmpty(guid)) {
            throw new RuntimeException("资源guid为空,异常数据：" + classpathPath);
        }

        // 生成 meta 文件
        AssetMeta assetMeta = new AssetMeta(guid, importSettings.getType());
        assetMeta.setLastModifiedTime(0);
        assetMeta.setImportSettings(importSettings);
        metaManager.saveMeta(file, assetMeta);
        LOGGER.info("已释放内置资源 {}", resource.getPath());
    }

    /**
     * 从完整 classpath 路径中提取相对于 builtin-asset-{name} 的相对路径。
     *
     * @param assetName     资源名
     * @param classpathPath 完整 classpath 路径
     * @return 相对路径
     */
    private String extractRelativePath(String assetName, String classpathPath) {
        String prefix = ASSET_DIR_PREFIX + assetName + "/";
        if (classpathPath.startsWith(prefix)) {
            return classpathPath.substring(prefix.length());
        }
        return classpathPath;
    }
}
