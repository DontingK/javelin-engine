package com.javelin.editor.meta;

import com.javelin.editor.project.Project;
import com.javelin.editor.serializable.TextExporter;
import com.javelin.editor.serializable.TextImporter;
import com.javelin.editor.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 元数据管理器
 *
 * <p>管理资源元数据的加载、保存和查询。</p>
 * <p>采用伴生模式：每个资源文件同级目录生成 {@code .meta} 文件（2026-10-25 统一：
 * .shader/.smesh 也落伴生 .meta，guid 为文件头镜像——文件头仍是真值，保证重释放
 * guid 稳定；guid 头为内置资源专属，用户文件缺头走随机 meta guid）。</p>
 * <p>唯一例外：{@code .java}（{@code src/} 下）guid 在文件头且不生成 {@code .meta}。</p>
 *
 * @author: javelin
 * @create: 2026/4/7
 * @Version 1.0
 */
public class MetaManager {

    private static final Logger logger = LoggerFactory.getLogger(MetaManager.class);

    private static final String META_EXTENSION = ".meta";

    private final Project project;

    private final GuidHeaderManager guidHeaderManager;
    private final Map<String, AssetMeta> guidToMetaCache;
    private final Map<String, String> pathToGuidCache;
    private final Map<String, String> guidToPathCache;

    /**
     * 创建元数据管理器
     *
     * <p>创建时自动同步资源与元数据。</p>
     *
     * @param project 项目实例
     */
    public MetaManager(Project project) {
        this.project = project;

        this.guidHeaderManager = new GuidHeaderManager();
        this.guidToMetaCache = new ConcurrentHashMap<>();
        this.pathToGuidCache = new ConcurrentHashMap<>();
        this.guidToPathCache = new ConcurrentHashMap<>();

        // 自动同步资源与元数据
        sync();
    }

    /**
     * 同步资源与元数据
     *
     * <p>遍历所有监听目录，为新增资源创建 {@code .meta}，删除孤儿 {@code .meta}，
     * 并更新内部缓存。</p>
     */
    public void sync() {
        logger.info("开始同步资源元数据...");
        Set<File> resourceFiles = new HashSet<>();
        Set<File> metaFiles = new HashSet<>();

        for (File dir : project.getWatchAssetDir()) {
            if (!dir.exists()) {
                continue;
            }
            collectFiles(dir, resourceFiles, metaFiles);
        }

        // 删除孤儿的 .meta 文件
        int orphanCount = 0;
        for (File metaFile : metaFiles) {
            File resourceFile = getResourceFileFromMeta(metaFile);
            if (!resourceFile.exists()) {
                deleteMetaFile(metaFile);
                orphanCount++;
            }
        }
        if (orphanCount > 0) {
            logger.info("清理 {} 个孤儿 .meta 文件", orphanCount);
        }

        // 为没有 .meta 的资源文件创建元数据（.shader/.smesh 也走 .meta：guid 从
        // 文件头取真值，createNewMeta 内处理；仅 .java 保持纯文件头无 meta）
        int createdCount = 0;
        for (File file : resourceFiles) {
            if (isScriptFile(file)) {
                String guid = guidHeaderManager.extractOrInjectGuid(file, project.getRelativePath(file));
                String relativePath = project.getRelativePath(file);
                AssetMeta meta = new AssetMeta(guid, detectAssetType(file));
                meta.setLastModifiedTime(file.lastModified());
                cacheMeta(relativePath, meta);
            } else {
                File metaFile = getMetaFile(file);
                if (!metaFile.exists()) {
                    AssetMeta meta = createNewMeta(file);
                    saveMeta(file, meta);
                    createdCount++;
                } else {
                    AssetMeta meta = loadMetaFromFile(metaFile);
                    if (meta != null) {
                        cacheMeta(project.getRelativePath(file), meta);
                    }
                }
            }
        }
        updateIndex();
        logger.info("元数据同步完成：创建 {} 个 .meta，清理 {} 个孤儿 .meta", createdCount, orphanCount);
    }

    /**
     * 获取或创建资源的元数据
     *
     * <p>加载同目录下的 {@code .meta} 文件，不存在则自动创建（.shader/.smesh 的
     * guid 从文件头取）。仅 {@code .java}（src/ 下）例外：从文件头提取 GUID 返回
     * 元数据、不创建 .meta。</p>
     *
     * @param file 资源文件
     * @return 资源元数据
     */
    public AssetMeta getOrCreateMeta(File file) {
        String relativePath = project.getRelativePath(file);

        if (isScriptFile(file)) {
            String guid = guidHeaderManager.extractOrInjectGuid(file, project.getRelativePath(file));
            AssetMeta meta = guidToMetaCache.get(guid);
            if (meta == null) {
                meta = new AssetMeta(guid, detectAssetType(file));
                cacheMeta(relativePath, meta);
            }
            return meta;
        }

        String guid = pathToGuidCache.get(relativePath);
        if (guid != null) {
            AssetMeta meta = guidToMetaCache.get(guid);
            if (meta != null) {
                return meta;
            }
        }

        File metaFile = getMetaFile(file);
        if (metaFile.exists()) {
            AssetMeta meta = loadMetaFromFile(metaFile);
            if (meta != null) {
                cacheMeta(relativePath, meta);
                return meta;
            }
        }

        AssetMeta meta = createNewMeta(file);
        saveMeta(file, meta);
        cacheMeta(relativePath, meta);
        return meta;
    }

    /**
     * 获取资源的元数据
     *
     * <p>如果不存在则返回 {@code null}。</p>
     *
     * @param file 资源文件
     * @return 元数据，不存在返回 null
     */
    public AssetMeta getMeta(File file) {
        String relativePath = project.getRelativePath(file);

        if (isScriptFile(file)) {
            String guid = guidHeaderManager.extractGuid(file);
            if (guid != null) {
                return guidToMetaCache.get(guid);
            }
            return null;
        }

        String guid = pathToGuidCache.get(relativePath);
        if (guid != null) {
            AssetMeta cached = guidToMetaCache.get(guid);
            if (cached != null) {
                return cached;
            }
        }

        File metaFile = getMetaFile(file);
        if (metaFile.exists()) {
            AssetMeta meta = loadMetaFromFile(metaFile);
            if (meta != null) {
                cacheMeta(relativePath, meta);
                return meta;
            }
        }
        return null;
    }

    /**
     * 根据 GUID 获取元数据
     *
     * @param guid 资源 GUID
     * @return 元数据，不存在返回 null
     */
    public AssetMeta getMetaByGuid(String guid) {
        return guidToMetaCache.get(guid);
    }

    /**
     * 根据相对路径获取 GUID
     *
     * @param relativePath 相对于项目根目录的路径
     * @return GUID，不存在返回 null
     */
    public String getGuidByPath(String relativePath) {
        return pathToGuidCache.get(relativePath);
    }

    /**
     * 根据 GUID 获取相对路径
     *
     * @param guid 资源 GUID
     * @return 相对于项目根目录的路径，不存在返回 null
     */
    public String getPathByGuid(String guid) {
        return guidToPathCache.get(guid);
    }

    /**
     * 保存资源元数据到伴生 {@code .meta} 文件
     *
     * <p>.shader/.smesh 也落盘（2026-10-25 统一，meta 内 guid 为文件头镜像）；
     * 仅 {@code .java}（src/ 下）只进内存缓存不落盘。</p>
     *
     * @param file 资源文件
     * @param meta 元数据
     */
    public void saveMeta(File file, AssetMeta meta) {
        if (isScriptFile(file)) {
            cacheMeta(project.getRelativePath(file), meta);
            return;
        }

        File metaFile = getMetaFile(file);
        try {
            new TextExporter(metaFile).export(meta);
            cacheMeta(project.getRelativePath(file), meta);
            logger.debug("保存 .meta: {}", metaFile.getName());
        } catch (IOException e) {
            logger.error("保存 .meta 文件失败: {}", metaFile, e);
        }
    }

    /**
     * 删除资源的元数据
     *
     * <p>删除伴生 {@code .meta} 文件并从缓存中移除。</p>
     *
     * @param file 资源文件
     */
    public void deleteMeta(File file) {
        String relativePath = project.getRelativePath(file);

        if (isScriptFile(file)) {
            String guid = guidHeaderManager.extractGuid(file);
            if (guid != null) {
                guidToMetaCache.remove(guid);
                pathToGuidCache.remove(relativePath);
                guidToPathCache.remove(guid);
            }
            return;
        }

        File metaFile = getMetaFile(file);
        if (metaFile.exists()) {
            if (!metaFile.delete()) {
                logger.error("删除 .meta 文件失败: {}", metaFile);
            }
        }

        String guid = pathToGuidCache.get(relativePath);
        if (guid != null) {
            guidToMetaCache.remove(guid);
            pathToGuidCache.remove(relativePath);
            guidToPathCache.remove(guid);
        }
    }

    /**
     * 移动资源的元数据
     *
     * <p>资源重命名或移动时，同步移动伴生 {@code .meta} 文件。</p>
     *
     * @param source 原资源文件
     * @param dest   目标资源文件
     */
    public void moveMeta(File source, File dest) {
        String sourcePath = project.getRelativePath(source);
        String destPath = project.getRelativePath(dest);

        if (isScriptFile(source)) {
            String guid = guidHeaderManager.extractGuid(source);
            if (guid != null) {
                pathToGuidCache.remove(sourcePath);
                guidToPathCache.remove(guid);
                pathToGuidCache.put(destPath, guid);
                guidToPathCache.put(guid, destPath);
            }
            return;
        }

        File sourceMeta = getMetaFile(source);
        File destMeta = getMetaFile(dest);

        if (sourceMeta.exists()) {
            try {
                Files.move(sourceMeta.toPath(), destMeta.toPath());
                String guid = pathToGuidCache.remove(sourcePath);
                if (guid != null) {
                    pathToGuidCache.put(destPath, guid);
                    guidToPathCache.put(guid, destPath);
                }
                logger.info("移动 .meta: {} -> {}", sourceMeta.getName(), destMeta.getName());
            } catch (IOException e) {
                logger.error("移动 .meta 文件失败: {} -> {}", sourceMeta, destMeta, e);
            }
        }
    }

    /**
     * 判断是否为脚本文件（src/main/java 下的 .java）
     *
     * <p>guid 真值在文件头且<b>不落伴生 .meta</b>（代码树不产 meta 文件）——
     * 2026-10-25 文本资产 meta 统一后唯一无 meta 例外。</p>
     *
     * @param file 资源文件
     * @return 如果是脚本文件返回 true
     */
    private boolean isScriptFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".java") && FileUtil.isSubFile(project.getSrcMainJava(), file);
    }

    /**
     * 从 .meta 文件加载元数据
     *
     * @param metaFile .meta 文件
     * @return 元数据，加载失败返回 null
     */
    private AssetMeta loadMetaFromFile(File metaFile) {
        try {
            return new TextImporter(metaFile).importData(AssetMeta.class);
        } catch (Exception e) {
            logger.error("加载 .meta 文件失败: {}", metaFile, e);
            return null;
        }
    }

    /**
     * 删除 .meta 文件并清理缓存
     *
     * @param metaFile .meta 文件
     */
    private void deleteMetaFile(File metaFile) {
        File resourceFile = getResourceFileFromMeta(metaFile);
        String relativePath = project.getRelativePath(resourceFile);
        String guid = pathToGuidCache.get(relativePath);

        if (!metaFile.delete()) {
            logger.error("删除孤儿 .meta 文件失败: {}", metaFile);
        }

        if (guid != null) {
            guidToMetaCache.remove(guid);
            pathToGuidCache.remove(relativePath);
            guidToPathCache.remove(guid);
        }
    }

    /**
     * 获取资源文件对应的 .meta 文件
     *
     * @param file 资源文件
     * @return .meta 文件
     */
    private File getMetaFile(File file) {
        return new File(file.getParentFile(), file.getName() + META_EXTENSION);
    }

    /**
     * 从 .meta 文件获取对应的资源文件
     *
     * @param metaFile .meta 文件
     * @return 资源文件
     */
    private File getResourceFileFromMeta(File metaFile) {
        String name = metaFile.getName();
        String resourceName = name.substring(0, name.length() - META_EXTENSION.length());
        return new File(metaFile.getParentFile(), resourceName);
    }

    /**
     * 创建新的元数据
     *
     * @param file 资源文件
     * @return 新元数据
     */
    private AssetMeta createNewMeta(File file) {
        // guid 真值在文件头（.java/.shader/.smesh）从文件头取——重释放 guid 稳定的
        // 根基。缺头处理分层：.java 无 meta、头是唯一真值，注入路径种子 guid；
        // .shader/.smesh 缺头=用户文件（guid 头是内置资源专属，不注入），走
        // 随机 guid——meta 即真值，导入器头缺失回退 meta 的路径接住
        String guid;
        if (isScriptFile(file)) {
            guid = guidHeaderManager.extractOrInjectGuid(file, project.getRelativePath(file));
        } else {
            String headerGuid = guidHeaderManager.extractGuid(file);
            guid = (headerGuid == null || headerGuid.isEmpty())
                    ? UUID.randomUUID().toString().replace("-", "")
                    : headerGuid;
        }
        AssetMeta meta = new AssetMeta(guid, detectAssetType(file));
        meta.setLastModifiedTime(file.lastModified());
        return meta;
    }

    /**
     * 将元数据加入缓存
     *
     * @param relativePath 相对路径
     * @param meta         元数据
     */
    private void cacheMeta(String relativePath, AssetMeta meta) {
        if (meta.getGuid() != null) {
            if (guidToPathCache.containsKey(meta.getGuid())) {
                if (!guidToPathCache.get(meta.getGuid()).equals(relativePath)) {
                    logger.error("GUID 重复: {}->{}", meta.getGuid(), relativePath);
                    throw new RuntimeException("GUID 重复: " + meta.getGuid() + " -> " + relativePath);
                }
            }
            logger.info("缓存元数据: {} -> {}", meta.getGuid(), relativePath);
            guidToMetaCache.put(meta.getGuid(), meta);
            pathToGuidCache.put(relativePath, meta.getGuid());
            guidToPathCache.put(meta.getGuid(), relativePath);
        }
    }

    /**
     * 更新 Library 索引文件
     *
     * <p>扫描所有 {@code .meta} 文件与 {@code src/main/java} 下的 {@code .java}
     * （.java 无伴生 .meta，guid 从文件头提取；.shader/.smesh 由 .meta 分支收录，
     * 文件头分支保留为 meta 缺失时的兜底），生成
     * {@code Library/index.asset}，存储 guid → Library 产物路径的映射。</p>
     */
    public synchronized void updateIndex() {
        File indexFile = new File(project.getLibrary(), "index.asset");
        Map<String, String> index = new LinkedHashMap<>();

        // 扫描所有监听目录下的 .meta 文件和 .java 文件
        for (File dir : project.getWatchAssetDir()) {
            if (!dir.exists()) {
                continue;
            }
            collectIndexEntries(dir, dir, index);
        }

        // 写入 index.asset
        File parent = indexFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        Properties props = new Properties();
        props.putAll(index);
        try (FileWriter writer = new FileWriter(indexFile)) {
            props.store(writer, "Javelin Library Index");
            logger.info("更新 Library 索引完成: {} 条记录", index.size());
        } catch (IOException e) {
            logger.error("写入 index.asset 失败", e);
        }
    }

    /**
     * 递归收集索引条目
     *
     * @param dir     当前扫描目录
     * @param rootDir 监听根目录（用于计算相对路径）
     * @param index   索引映射
     */
    private void collectIndexEntries(File dir, File rootDir, Map<String, String> index) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectIndexEntries(file, rootDir, index);
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(META_EXTENSION)) {
                    AssetMeta meta = loadMetaFromFile(file);
                    if (meta != null && meta.getGuid() != null) {
                        File resourceFile = getResourceFileFromMeta(file);
                        String sourcePath = getRelativePath(rootDir, resourceFile);
                        index.put(meta.getGuid(), sourcePath);
                    }
                } else if (name.endsWith(".java") && FileUtil.isSubFile(project.getSrcMainJava(), file)) {
                    String guid = guidHeaderManager.extractGuid(file);
                    if (guid != null) {
                        String sourcePath = getRelativePath(rootDir, file);
                        index.put(guid, sourcePath);
                    }
                } else if (name.endsWith(".shader") || name.endsWith(".smesh")) {
                    // 兜底：meta 缺失时 guid 仍从文件头可解（正常路径走上方 .meta 分支）
                    String guid = guidHeaderManager.extractGuid(file);
                    if (guid != null) {
                        String sourcePath = getRelativePath(rootDir, file);
                        index.put(guid, sourcePath);
                    }
                }
            }
        }
    }

    /**
     * 计算文件相对于基准目录的路径
     *
     * @param baseDir 基准目录
     * @param file    文件
     * @return 相对路径（使用 / 分隔）
     */
    private String getRelativePath(File baseDir, File file) {
        String basePath = baseDir.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.startsWith(basePath)) {
            String relative = filePath.substring(basePath.length());
            if (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }
            return relative.replace("\\", "/");
        }
        return file.getName();
    }

    /**
     * 递归收集文件
     *
     * @param dir           目录
     * @param resourceFiles 资源文件集合
     * @param metaFiles     .meta 文件集合
     */
    private void collectFiles(File dir, Set<File> resourceFiles, Set<File> metaFiles) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectFiles(file, resourceFiles, metaFiles);
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(META_EXTENSION)) {
                    metaFiles.add(file);
                } else {
                    resourceFiles.add(file);
                }
            }
        }
    }

    /**
     * 检测资源类型
     *
     * @param file 资源文件
     * @return 资源类型
     */
    private String detectAssetType(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".bmp")) {
            return "Texture";
        } else if (name.endsWith(".texture")) {
            return "Texture";
        } else if (name.endsWith(".shader")) {
            return "Shader";
        } else if (name.endsWith(".wav") || name.endsWith(".mp3") || name.endsWith(".ogg")) {
            return "Audio";
        } else if (name.endsWith(".scene")) {
            return "Scene";
        } else if (name.endsWith(".mat")) {
            return "Material";
        } else if (name.endsWith(".java")) {
            return "Script";
        } else if (name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".xml")) {
            return "Data";
        }
        return "Unknown";
    }
}
