package com.javelin.editor.asset;

import com.javelin.core.Env;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.importer.AssetImporter;
import com.javelin.editor.importer.ImportSettings;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.meta.MetaManager;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.project.Project;
import com.javelin.editor.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Enumeration;

/**
 * 编辑器内置资源释放器。
 *
 * <p>负责将打包在 classpath 中的内置资源释放到项目目录的 assets 文件夹中，
 * 伴生 {@code .meta} 随包一起打包释放（guid 真值单源=伴生 .meta，2026-10-29 拍板：
 * guid 头只剩 src 下 .java 专属）。</p>
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
        // 释放完内置资源须重写 Library 索引：MetaManager 构造器的 sync/updateIndex
        // 早于本方法执行（.javelin/assets 尚空，内置条目被漏）；而 refresh 对 mtime
        // 已对齐的资产不重导、也不再触发 updateIndex，缺失条目将永久滞留——场景里
        // @guid 引用解析为 null 直至渲染层崩
        metaManager.updateIndex();
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
        throw new IllegalArgumentException(EditorLang.text("builtin.unknownSuffix", path));
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
            throw new RuntimeException(EditorLang.text("builtin.resourceMissing", classpathPath));
        }
        byte[] source;
        try (InputStream inputStream = resource.openStream()) {
            source = inputStream.readAllBytes();
        }
        if (file.exists() && contentEquals(file, source)) {
            // 内容未变：不重拷；把 meta 的 mtime 对齐当前文件——needImport 按
            // file vs meta mtime 判等，对齐即不触发重导（meta 缺失时从包内补释放）
            syncMetaMtime(file, classpathPath, importSettings);
            return;
        }
        FileUtil.copy(new ByteArrayInputStream(source), file);
        releaseMeta(file, classpathPath, importSettings);
        LOGGER.info("已释放内置资源 {}", resource.getPath());
    }

    /**
     * 释放伴生 {@code .meta}（包内 {@code {classpathPath}.meta} 同拷）。
     * meta 已存在时不动——保留旧 mtime 与新文件 mtime 不等，refresh 检出差异
     * 触发重导（内置资源换版本生效路径）；缺失时补拷，mtime 保持 0 与文件 mtime
     * 不等，同样由 refresh 兜底导入（导入完成回写对齐）。
     */
    private void releaseMeta(File file, String classpathPath, ImportSettings importSettings) throws IOException {
        File metaFile = new File(file.getParentFile(), file.getName() + ".meta");
        if (metaFile.exists()) {
            return;
        }
        byte[] metaSource = readResource(classpathPath + ".meta");
        FileUtil.copy(new ByteArrayInputStream(metaSource), metaFile);
        // 包内 meta importSettings 为空：补默认导入设置落盘（mtime 不动=触发重导）
        AssetMeta meta = metaManager.getOrCreateMeta(file);
        meta.setImportSettings(importSettings);
        metaManager.saveMeta(file, meta);
    }

    /** 内容未变时的 meta 对齐：缺 meta 从包内补释放，mtime = 当前文件 mtime（判等即不重导） */
    private void syncMetaMtime(File file, String classpathPath, ImportSettings importSettings) throws IOException {
        File metaFile = new File(file.getParentFile(), file.getName() + ".meta");
        if (!metaFile.exists()) {
            FileUtil.copy(new ByteArrayInputStream(readResource(classpathPath + ".meta")), metaFile);
        }
        AssetMeta meta = metaManager.getOrCreateMeta(file);
        if (meta.getImportSettings() == null) {
            meta.setImportSettings(importSettings);
        }
        meta.setLastModifiedTime(file.lastModified());
        metaManager.saveMeta(file, meta);
    }

    /** 读包内资源字节；缺失直接抛错（缺伴生 .meta 属打包异常，显性化不兜底） */
    private byte[] readResource(String classpathPath) throws IOException {
        URL url = this.getClass().getClassLoader().getResource(classpathPath);
        if (url == null) {
            throw new RuntimeException(EditorLang.text("builtin.jarResourceMissing", classpathPath));
        }
        try (InputStream is = url.openStream()) {
            return is.readAllBytes();
        }
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

    /** 逐字节比对已释放文件与内置源（KB 级文件整读，成本忽略） */
    private static boolean contentEquals(File file, byte[] source) throws IOException {
        return Arrays.equals(Files.readAllBytes(file.toPath()), source);
    }
}
