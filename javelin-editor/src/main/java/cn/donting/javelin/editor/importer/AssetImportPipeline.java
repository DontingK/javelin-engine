package cn.donting.javelin.editor.importer;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.editor.event.AssetImportEvent;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.meta.AssetMeta;
import cn.donting.javelin.editor.meta.GuidHeaderManager;
import cn.donting.javelin.editor.meta.MetaManager;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.project.Project;
import cn.donting.javelin.editor.util.ClassUtil;
import cn.donting.javelin.editor.util.FileUtil;
import cn.donting.javelin.editor.util.ThreadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 资源导入管线管理器
 *
 * <p>管理资源导入任务队列，调度导入器执行导入操作。导入开始/完成/失败经
 * {@link EditorEventBus} 发布 {@link AssetImportEvent}（消费方统一订总线）。</p>
 *
 * @author: javelin
 * @create: 2026/4/8
 * @Version 1.0
 */
public class AssetImportPipeline {

    private static final Logger logger = LoggerFactory.getLogger(AssetImportPipeline.class);

    private final Project project;
    private final MetaManager metaManager;
    private final Map<String, AssetImporter> importers = new ConcurrentHashMap<>();
    private final BlockingQueue<ImportTask> taskQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executorService;
    private final GuidHeaderManager guidHeaderManager;
    private final AssetManager assetManager;

    private final AtomicInteger importCount = new AtomicInteger();
    private volatile boolean running = false;

    /**
     * 等待所有导入任务完成
     */
    public void waitImportFinished() {
        while (importCount.get()>0) {
            logger.info("等待所有导入任务完成，正在执行中: {},", importCount.get());
            ThreadUtil.sleep(500);
        }
        logger.info("所有导入任务已完成");
    }


    /**
     * 创建导入管线
     *
     * @param project      项目
     * @param metaManager  元数据管理器
     * @param assetManager
     */
    public AssetImportPipeline(Project project, MetaManager metaManager, AssetManager assetManager) {
        this.project = project;
        this.metaManager = metaManager;
        this.guidHeaderManager = new GuidHeaderManager();
        this.assetManager = assetManager;

        this.executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                r -> {
                    Thread t = new Thread(r, "AssetImport-Worker");
                    t.setDaemon(true);
                    return t;
                }
        );
        List<Class<?>> classes = ClassUtil.scanClasses("", AssetImporter.class);
        for (Class<?> clazz : classes) {
            try {
                AssetImporter importer = (AssetImporter) clazz.newInstance();
                registerImporter(importer);
            } catch (Exception e) {
                logger.error("创建导入器失败: {}", clazz.getName(), e);
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 注册导入器
     *
     * @param importer 导入器
     */
    public void registerImporter(AssetImporter importer) {
        for (String ext : importer.getSupportedExtensions()) {
            importers.put(ext.toLowerCase(), importer);
        }
        logger.info("注册导入器: {} 支持扩展名: {}", importer.getName(), importer.getSupportedExtensions());
    }

    /**
     * 启动导入管线
     * <p>启动后会自动扫描并导入所有未导入或已变更的资源。</p>
     */
    public void startAndWait() {
        if (running) {
            return;
        }
        running = true;
        logger.info("资源导入管线已启动");

        // 启动工作线程
        int workerCount = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < workerCount; i++) {
            executorService.submit(this::processTasks);
        }

        // 扫描并导入未导入的资源
        refresh();
        waitImportFinished();
    }

    /**
     * 停止导入管线
     */
    public void stop() {
        running = false;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("资源导入管线已停止");
    }

    /**
     * 处理导入任务队列
     */
    private void processTasks() {
        while (running) {
            try {
                ImportTask task = taskQueue.poll(1, TimeUnit.SECONDS);
                if (task != null) {
                    executeImport(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 执行单个导入任务
     *
     * @param task 导入任务
     */
    private void executeImport(ImportTask task) {
        task.markStart();
        publishImportEvent(AssetImportEvent.Phase.START, task);

        try {
            AssetImporter importer = findImporter(task.getSourceFile());
            if (importer == null) {
                task.setErrorMessage(EditorLang.text("import.noImporter"));
                logger.error("导入失败: 找不到导入器 for {}", task.getSourceFile());
                return;
            }

            logger.info("开始导入: {} 使用 {}", task.getSourceFile().getName(), importer.getName());
            boolean success = importer.importAsset(task, project);

            task.markEnd(success);

            if (success) {
                if (!isScriptFile(task.getSourceFile())) {
                    // 更新伴生 meta（含 .shader/.smesh——2026-10-25 统一：mtime 对齐
                    // 源文件，needImport 即据此判等）
                    AssetMeta meta = task.getMeta();
                    meta.setImportSettings(task.getSettings());
                    meta.setLastModifiedTime(task.getSourceFile().lastModified());
                    metaManager.saveMeta(task.getSourceFile(), meta);
                }

                // 更新 Library 索引
                metaManager.updateIndex();

                logger.info("导入成功: {} 耗时 {}ms", task.getSourceFile().getName(), task.getDuration());
                publishImportEvent(AssetImportEvent.Phase.COMPLETE, task);
            } else {
                logger.error("导入失败: {} - {}", task.getSourceFile().getName(), task.getErrorMessage());
                publishImportEvent(AssetImportEvent.Phase.FAILED, task);
            }
        } catch (Exception e) {
            task.setErrorMessage(e.getMessage());
            task.markEnd(false);
            logger.error("导入异常: {}", task.getSourceFile().getName(), e);
            publishImportEvent(AssetImportEvent.Phase.FAILED, task);
        }finally {
            importCount.decrementAndGet();
        }
    }

    /**
     * 查找适合的导入器
     *
     * @param file 文件
     * @return 导入器，找不到返回 null
     */
    private AssetImporter findImporter(File file) {
        String name = file.getName().toLowerCase();
        for (Map.Entry<String, AssetImporter> entry : importers.entrySet()) {
            if (name.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 提交导入任务
     *
     * @param file 源文件
     * @return 导入任务
     */
    public ImportTask submitImport(File file) {
        importCount.incrementAndGet();
        AssetMeta meta;
        ImportSettings settings;

        if (isScriptFile(file)) {
            // 脚本：guid 从文件头读（缺失注入），不建 .meta——内存 meta 即可
            String guid = guidHeaderManager.extractOrInjectGuid(file, project.getRelativePath(file));
            meta = new AssetMeta(guid, detectAssetType(file));
            settings = getOrCreateSettings(file, meta);
        } else {
            meta = metaManager.getOrCreateMeta(file);
            settings = getOrCreateSettings(file, meta);
        }

        ImportTask task = new ImportTask(file, meta, settings,assetManager);
        taskQueue.offer(task);
        logger.debug("提交导入任务: {}", file.getName());
        return task;
    }

    /**
     * 等待单个导入任务终态（创建命令同步收口用——新建资产立即拖引用不再撞
     * 异步导入窗口「GUID 未找到」）。EDT 轮询小睡、超时放弃返回 false 不长阻塞
     * UI（.mat/.texture 毫秒级、.shader 含 shaderc 编译秒内）。
     *
     * @param task      submitImport 返回的任务
     * @param timeoutMs 超时毫秒（超时≠失败——任务仍在跑，只是不等了）
     * @return true = 导入成功终态
     */
    public boolean awaitImport(ImportTask task, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ImportTask.Status status = task.getStatus();
            if (status == ImportTask.Status.SUCCESS || status == ImportTask.Status.FAILED
                    || status == ImportTask.Status.CANCELLED) {
                return status == ImportTask.Status.SUCCESS;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 获取或创建设置
     *
     * @param file 文件
     * @param meta 元数据
     * @return 导入设置
     */
    private ImportSettings getOrCreateSettings(File file, AssetMeta meta) {
        ImportSettings settings = (ImportSettings) meta.getImportSettings();
        if (settings != null) {
            return settings;
        }

        AssetImporter importer = findImporter(file);
        if (importer != null) {
            settings = importer.createDefaultSettings(file);
            meta.setImportSettings(settings);
            metaManager.saveMeta(file, meta);
        }
        return settings;
    }

    /**
     * 检查文件是否需要导入
     *
     * @param file 文件
     * @return 如果需要导入返回 true
     */
    private boolean needImport(File file) {
        if (isScriptFile(file)) {
            // 脚本无 meta：产物存在即可（guid 头真值稳定，无 mtime 判定载体）
            return !isImportedInLibrary(file);
        }
        // 统一 meta mtime 判定：源 mtime ≠ meta mtime 即重导（内置资产重释放/
        // 用户手改源均经此检出）
        AssetMeta meta = metaManager.getMeta(file);
        if (meta == null) {
            return true;
        }
        return file.lastModified() != meta.getLastModifiedTime();
    }

    /**
     * 发布导入时机事件（快照 sourceFile + guid，不携带可变 ImportTask——总线不可变契约）。
     */
    private static void publishImportEvent(AssetImportEvent.Phase phase, ImportTask task) {
        AssetMeta meta = task.getMeta();
        String guid = meta != null ? meta.getGuid() : null;
        EditorEventBus.publish(new AssetImportEvent(phase, task.getSourceFile(), guid));
    }

    /**
     * 获取导入队列大小
     *
     * @return 待处理任务数
     */
    public int getPendingCount() {
        return taskQueue.size();
    }

    /**
     * 获取在途导入数（已提交未完成——导入进度框轮询源，比队列尺寸更稳定）
     */
    public int getImportingCount() {
        return importCount.get();
    }

    /**
     * 清空导入队列
     */
    public void clearQueue() {
        taskQueue.clear();
    }

    /**
     * 扫描并导入所有未导入或已变更的资源
     * <p>遍历 {@code project.getWatchAssetDir()}，对需要导入的文件自动提交导入任务。</p>
     */
    public void refresh() {
        logger.info("开始扫描未导入资源...");
        int count = 0;
        for (File dir : project.getWatchAssetDir()) {
            if (!dir.exists()) {
                continue;
            }
            List<File> files = collectFiles(dir);
            for (File file : files) {
                if (findImporter(file) == null) {
                    continue;
                }
                if (needImport(file) || !isImportedInLibrary(file)) {
                    submitImport(file);
                    count++;
                }
            }
        }
        logger.info("扫描完成，提交 {} 个待导入任务", count);
    }

    /**
     * 全量重新导入所有资源
     * <p>清空 Library 目录后，强制重新导入所有受支持的资源文件。</p>
     */
    public void reimportAll() {
        logger.info("开始全量重新导入...");
        clearQueue();

        // 清空 Library 目录
        File libraryDir = project.getLibrary();
        if (libraryDir.exists()) {
            deleteDirectory(libraryDir);
            libraryDir.mkdirs();
            logger.info("已清空 Library 目录");
        }

        int count = 0;
        for (File dir : project.getWatchAssetDir()) {
            if (!dir.exists()) {
                continue;
            }
            List<File> files = collectFiles(dir);
            for (File file : files) {
                if (findImporter(file) != null) {
                    submitImport(file);
                    count++;
                }
            }
        }
        logger.info("全量重新导入完成，提交 {} 个导入任务", count);
    }

    /**
     * 检查资源是否已在 Library 中存在导入产物
     *
     * @param file 源文件
     * @return 如果存在返回 true
     */
    private boolean isImportedInLibrary(File file) {
        File product = libraryFileFor(file);
        return product != null && product.exists();
    }

    /**
     * 解析源文件对应的 Library 产物文件（guid + ".asset"）。
     * .java 按文件头 guid（代码树无伴生 meta），其余一律按伴生 meta 的 guid
     * （guid 真值单源=伴生 .meta，2026-10-29 拍板——guid 头只剩 src 下 .java 专属）。
     *
     * @param file 源文件
     * @return 产物文件（可能不存在），guid 不可得时 null
     */
    private File libraryFileFor(File file) {
        String guid;
        if (isScriptFile(file)) {
            guid = guidHeaderManager.extractGuid(file);
        } else {
            AssetMeta meta = metaManager.getMeta(file);
            if (meta == null) {
                return null;
            }
            guid = meta.getGuid();
        }
        if (guid == null || guid.isEmpty()) {
            return null;
        }
        return new File(project.getLibrary(), guid + ".asset");
    }

    /**
     * 判断是否为脚本文件（src/main/java 下的 .java）
     *
     * <p>guid 真值在文件头且不落伴生 .meta（代码树不产 meta）——2026-10-25 文本
     * 资产 meta 统一后唯一无 meta 例外。</p>
     *
     * @param file 文件
     * @return 如果是脚本文件返回 true
     */
    private boolean isScriptFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".java") && FileUtil.isSubFile(project.getSrcMainJava(), file);
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
        } else if (name.endsWith(".compute")) {
            return "ComputeShader";
        } else if (name.endsWith(".wav") || name.endsWith(".mp3") || name.endsWith(".ogg")) {
            return "Audio";
        } else if (name.endsWith(".scene")) {
            return "Scene";
        } else if (name.endsWith(".mat")) {
            return "Material";
        } else if (name.endsWith(".java")) {
            return "Script";
        } if (name.endsWith(".smesh")) {
            return "Mesh";
        } else if (name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".xml")) {
            return "Data";
        }
        return "Unknown";
    }

    /**
     * 递归收集目录下所有文件
     *
     * @param dir 目录
     * @return 文件列表
     */
    private List<File> collectFiles(File dir) {
        List<File> files = new ArrayList<>();
        collectFilesRecursive(dir, files);
        return files;
    }

    private void collectFilesRecursive(File dir, List<File> result) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectFilesRecursive(entry, result);
            } else {
                result.add(entry);
            }
        }
    }

    /**
     * 递归删除目录及其内容
     *
     * @param dir 要删除的目录
     */
    private void deleteDirectory(File dir) {
        File[] entries = dir.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.isDirectory()) {
                    deleteDirectory(entry);
                } else {
                    entry.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * 获取 Library 目录
     *
     * @return Library 目录
     */
    public File getLibraryDir() {
        File libraryDir = new File(project.getEditorDir().getParentFile(), "Library");
        if (!libraryDir.exists()) {
            libraryDir.mkdirs();
        }
        return libraryDir;
    }

    public AssetImporter getAssetImporter(File file){
        return findImporter(file);
    }
}
