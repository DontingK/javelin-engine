package cn.donting.javelin.editor.asset.reload;

import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.material.DefaultMaterial;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.asset.ProjectAssetLibManager;
import cn.donting.javelin.editor.event.AssetImportEvent;
import cn.donting.javelin.editor.event.AssetReloadedEvent;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.EditorFocusGainedEvent;
import cn.donting.javelin.editor.importer.AssetImportPipeline;
import cn.donting.javelin.editor.swing.dialog.ImportProgressDialog;
import cn.donting.javelin.editor.window.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import javax.swing.SwingUtilities;

/**
 * 资产热重载协调器（Engine_AssetReload_Design.md §5.2）：订阅导入总线事件
 * （AssetImportEvent，AssetImportPipeline 直接发布）→ reload 得盘上新实例 →
 * 反射 Walker 扫对象注册表全量换 Asset 字段指针 → renderingSystem.requestReconcile() 孤儿回收 →
 * 发 AssetReloadedEvent。触发口 = 手动 project.reimport + 焦点扫描（批次②：主窗口重获焦点扫
 * Assets/ 变更自动导入，有待导入弹模态进度框）。
 *
 * <p><b>热重载范围（拍板 2026-10-29 收窄）：仅 shader</b>——文件是真值源且渲染侧
 * 按 shader 身份烤死（set2 布局/UBO 尺寸/PSO 缓存键），内存引用必须换代。.mat
 * （编辑=内存直改即时生效，文件是镜像）、.texture/.smesh（GPU 侧上传烤死，外部改
 * 须重上传）均摘出 reload 链待后续加回；像素源 .png 同归后续批次。</p>
 *
 * <p>线程契约：AssetImportEvent 在总线泵线程回调（导入方在 worker 线程 publish），
 * 换指针/对账经 {@code submitTask} 投引擎帧首执行（改引擎场景走引擎帧队列，EditorEventBus
 * 同口径）。全程不抛——重载失败保持旧引用 + warn（残档口径，设计 §6）。</p>
 */
public class AssetReloadService {

    private static final Logger log = LoggerFactory.getLogger(AssetReloadService.class);

    private final AssetImportPipeline pipeline;
    private final ProjectAssetLibManager assetManager;
    private final RenderingSystem renderingSystem;
    /** 导入进度模态框单例守卫（EDT 持有，isVisible 判重弹） */
    private ImportProgressDialog progressDialog;

    public AssetReloadService(AssetImportPipeline pipeline, ProjectAssetLibManager assetManager,
                              RenderingSystem renderingSystem) {
        if (pipeline == null || assetManager == null || renderingSystem == null) {
            throw new IllegalArgumentException("pipeline / assetManager / renderingSystem must not be null");
        }
        this.pipeline = pipeline;
        this.assetManager = assetManager;
        this.renderingSystem = renderingSystem;
        // 导入完成/失败经总线到达（导入管线直接发布），消费侧口径统一
        EditorEventBus.subscribe(AssetImportEvent.class, this::onImportEvent);
        // 焦点扫描触发（Unity Auto Refresh 口径，批次②）：主窗口重获焦点 → 扫描
        // Assets/ 变更自动导入（事件解耦窗口与扫描逻辑；扫描 IO 走后台线程）
        EditorEventBus.subscribe(EditorFocusGainedEvent.class, e -> onFocusGained());
    }

    /** 主窗口重获焦点（总线泵线程）：导入进行中跳过，否则后台线程扫描提交 */
    private void onFocusGained() {
        if (pipeline.getImportingCount() > 0) {
            // 导入中不重扫（自身保存导入 mtime 幂等，重复提交无益）
            return;
        }
        Thread scanner = new Thread(this::scanAndShow, "focus-scan");
        scanner.setDaemon(true);
        scanner.start();
    }

    /** 后台扫描（refresh 同步 IO）→ 有待导入弹模态进度框（EDT；小批次可能已导完不弹） */
    private void scanAndShow() {
        try {
            pipeline.refresh();
        } catch (Exception e) {
            log.warn("焦点扫描资产失败", e);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (pipeline.getImportingCount() > 0
                    && (progressDialog == null || !progressDialog.isVisible())) {
                progressDialog = new ImportProgressDialog(MainFrame.getInstance(), pipeline);
                progressDialog.setVisible(true);
            }
        });
    }

    /** 导入事件（总线泵线程）：START 无消费，完成/失败转既有重映射路径（原直连监听器口径）。 */
    private void onImportEvent(AssetImportEvent event) {
        if (event.getPhase() == AssetImportEvent.Phase.START) {
            return;
        }
        onImported(event);
    }

    /** 导入完成/失败（总线泵线程）：过滤无 guid / .scene / 像素源，其余投引擎帧首 */
    private void onImported(AssetImportEvent event) {
        File source = event.getSourceFile();
        if (source == null) {
            return;
        }
        if (source.getName().endsWith(".scene")) {
            // .scene 重导 = 整场景重载豁免路径（§4.5，批次②）——本期日志提示跳过，不炸
            log.info("场景重导跳过字段级重映射（批次②豁免路径）: {}", source.getName());
            return;
        }
        Class<? extends Asset> type = targetClass(source);
        if (type == null) {
            // .png/.jpg/.jpeg 等像素源 → Image 产物换装要连 Texture2D/GPU 重传，批次③
            log.info("无热重载目标类型，跳过: {}", source.getName());
            return;
        }
        String guid = event.getGuid();
        if (guid == null || guid.isBlank()) {
            log.warn("导入任务无 guid，跳过热重载: {}", source.getName());
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return;
        }
        editor.submitTask(() -> reloadAndRemap(guid, source, type));
    }

    /** 引擎帧首执行（§5.2）：reload 新实例 → Walker 换指针 → 对账 → 发事件 */
    private void reloadAndRemap(String guid, File source, Class<? extends Asset> type) {
        try {
            ProjectAssetLibManager.ReloadResult<?> result = assetManager.reload(guid, type);
            if (result.getNew() == null) {
                // 产物损坏/加载失败：保持旧引用（残档口径，reload 已 warn），天然回退
                return;
            }
            Asset fresh = result.getNew();
            int hits = AssetReloadWalker.walk(EditorObjectRegistry.snapshot(), guid, fresh);
            // default.shader 本体被重导换代 → 默认材质单例下帧重建（identity 比对）
            DefaultMaterial.onShaderReloaded(fresh);
            renderingSystem.requestReconcile();
            EditorEventBus.publish(new AssetReloadedEvent(guid, source));
            log.info("资产热重载: {}（{}）换指针 {} 处", source.getName(), type.getSimpleName(), hits);
        } catch (Exception e) {
            log.warn("资产热重载失败（保持旧引用）: {}", source.getName(), e);
        }
    }

    /** 源文件后缀 → 热重载目标类型（null = 无对应，跳过）——拍板 2026-10-29 收窄仅 .shader，
     *  .mat/.texture/.smesh/像素源摘出待后续加回（AssetReload_Design §5.2） */
    private static Class<? extends Asset> targetClass(File source) {
        String name = source.getName().toLowerCase();
        if (name.endsWith(".shader")) {
            return Shader.class;
        }
        return null;
    }
}
