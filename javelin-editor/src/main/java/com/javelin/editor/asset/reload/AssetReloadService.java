package com.javelin.editor.asset.reload;

import com.javelin.core.assets.Asset;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.material.DefaultShader;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.render.texture.Texture2D;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.asset.ProjectAssetLibManager;
import com.javelin.editor.event.AssetReloadedEvent;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.EditorFocusGainedEvent;
import com.javelin.editor.importer.AssetImportPipeline;
import com.javelin.editor.importer.ImportTask;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.scene.EditorScene;
import com.javelin.editor.window.ImportProgressDialog;
import com.javelin.editor.window.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import javax.swing.SwingUtilities;

/**
 * 资产热重载协调器（Engine_AssetReload_Design.md §5.2）：订阅导入管线完成回调 →
 * reload 得盘上新实例 → 反射 Walker 遍历场景对象图换 Asset 字段指针 →
 * renderingSystem.requestReconcile() 孤儿回收 → 发 AssetReloadedEvent。
 * 触发口 = 手动 project.reimport + 焦点扫描（批次②：主窗口重获焦点扫 Assets/
 * 变更自动导入，有待导入弹模态进度框）。
 *
 * <p>线程契约：onImportComplete 在导入 worker 线程回调，换指针/对账经
 * {@code submitTask} 投引擎帧首执行（改引擎场景走引擎帧队列，EditorEventBus
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
        pipeline.addListener(new AssetImportPipeline.ImportListener() {
            @Override
            public void onImportComplete(ImportTask task) {
                onImported(task);
            }
        });
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

    /** 导入完成回调（worker 线程）：过滤无 guid / .scene / 像素源，其余投引擎帧首 */
    private void onImported(ImportTask task) {
        File source = task.getSourceFile();
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
        AssetMeta meta = task.getMeta();
        String guid = meta != null ? meta.getGuid() : null;
        if (guid == null || guid.isBlank()) {
            log.warn("导入任务无 guid，跳过热重载: {}", source.getName());
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return;
        }
        editor.submitTask(() -> reloadAndRemap(editor, guid, source, type));
    }

    /** 引擎帧首执行（§5.2）：reload 新实例 → Walker 换指针 → 对账 → 发事件 */
    private void reloadAndRemap(JavelinEditor editor, String guid, File source, Class<? extends Asset> type) {
        try {
            ProjectAssetLibManager.ReloadResult<?> result = assetManager.reload(guid, type);
            if (result.getNew() == null) {
                // 产物损坏/加载失败：保持旧引用（残档口径，reload 已 warn），天然回退
                return;
            }
            Asset fresh = result.getNew();
            EditorScene editorScene = editor.getEditorScene();
            int hits = AssetReloadWalker.walk(editorScene.getGameScene(), guid, fresh);
            hits += AssetReloadWalker.walk(editorScene.getEditorScene(), guid, fresh);
            // default.shader 本体被重导换代 → 默认材质单例下帧重建（identity 比对）
            DefaultShader.onShaderReloaded(fresh);
            renderingSystem.requestReconcile();
            EditorEventBus.publish(new AssetReloadedEvent(guid, source));
            log.info("资产热重载: {}（{}）换指针 {} 处", source.getName(), type.getSimpleName(), hits);
        } catch (Exception e) {
            log.warn("资产热重载失败（保持旧引用）: {}", source.getName(), e);
        }
    }

    /** 源文件后缀 → 热重载目标类型（null = 无对应，跳过）；.png 等像素源归批次③ */
    private static Class<? extends Asset> targetClass(File source) {
        String name = source.getName().toLowerCase();
        if (name.endsWith(".mat")) {
            return Material.class;
        }
        if (name.endsWith(".shader")) {
            return Shader.class;
        }
        if (name.endsWith(".texture")) {
            return Texture2D.class;
        }
        if (name.endsWith(".smesh")) {
            return Mesh.class;
        }
        return null;
    }
}
