package com.javelin.editor.menu.project;

import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.Shader;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.ProjectChangedEvent;
import com.javelin.editor.i18n.I18nKey;
import com.javelin.editor.importer.ImportTask;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.serializable.TextExporter;
import com.javelin.editor.window.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import javax.swing.JOptionPane;

/**
 * 创建材质资产（project.create.material，Unity Create → Material 对齐）：选中目录
 * 落 {@code material.mat}（重名去重 material 1.mat …），内容 = 内置 default.shader
 * 的初始 {@link Material} 文本序列化（「.mat 就是 Material 的序列化」2026-10-19
 * 拍板；new Material(shader) 运行期实例同 CreatePlaneMenuCommand 先例）。**源文本
 * guid 与伴生 .meta 同源**（.scene 保存同口径）：先 getOrCreateMeta 建 .meta 拿
 * guid、setGuid 后一次性落盘——源文本/伴生 .meta/Library 产物/index 四处一致
 * （导入器仍以 meta 为真值源覆盖自述，防手改脱节）。落盘即提交导入：submitImport
 * 产 Library/{guid}.asset 二进制、更新 index（异步管线，命令不等待）。盘面变更发
 * {@code project.changed}，项目面板重拉树快照（右键目录已落 EditorSelection，重拉
 * 后回原目录锚点即见新文件）。
 *
 * <p>线程口径：命令在 EDT 执行，纯 IO + 管线提交（ReimportMenuCommand 同款，
 * 不涉引擎状态不经 submitTask 帧队列）；.mat 文本与二进制产物均小体量无卡顿
 * 顾虑。当前选中目录不在管线 watch 域（如 src 外散目录）时文件照建、导入不
 * 触发（管线语义）。</p>
 *
 * @author javelin
 * @create 2026/10/19
 */
public class CreateMaterialMenuCommand extends ProjectMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateMaterialMenuCommand.class);

    private static final String BASE_NAME = "material";
    /** 导入同步收口超时（毫秒）——.mat 毫秒级，超时=放弃等待（导入仍在跑不判失败） */
    private static final long AWAIT_IMPORT_TIMEOUT_MS = 5000;
    private final String id;
    private final String shortcut;
    private final String icon;
    /** 内置默认着色器 loadAsset 路径（初始材质模板，CreatePlaneMenuCommand 同源） */
    private static final String DEFAULT_SHADER_PATH = "core/shader/default.shader";
    public CreateMaterialMenuCommand(String id, String shortcut) {
        this(id, shortcut, null);
    }

    public CreateMaterialMenuCommand(String id, String shortcut, String icon) {
        this.id = id;
        this.shortcut = shortcut;
        this.icon = icon;
    }

    @Override
    public String id() {
        return "project.create.material";
    }

    @Override
    public I18nKey label() {
        return EditorLang.key(id());

    }

    /** 选中目录语境存在才可用（右键空白=当前目录语境，右区条目选中=其所在目录） */
    @Override
    public Boolean enabled() {
        return getSelectedDirectory() != null;
    }

    @Override
    public void execute() {
        File dir = getSelectedDirectory();
        if (dir == null) {
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未就绪，跳过创建材质（project.create.material）");
            return;
        }

        Shader shader = editor.getAssetManager().loadAsset(DEFAULT_SHADER_PATH, Shader.class);
        if (shader == null) {
            LOGGER.error("默认着色器加载失败 path={}（Library 缺产物或索引未建），跳过创建", DEFAULT_SHADER_PATH);
            return;
        }

        File file = uniqueTarget(dir);
        // 源文本 guid 与 meta 同源（.scene 保存同口径）：先建伴生 .meta 拿 guid、
        // setGuid 后一次性落盘——源文本/伴生 .meta/Library 产物/index 四处一致
        AssetMeta meta = editor.getMetaManager().getOrCreateMeta(file);
        Material material = new Material(shader);
        material.setGuid(meta.getGuid());
        try {
            new TextExporter(file).export(material);
        } catch (Exception e) {
            LOGGER.error("材质文件写出失败: {}", file, e);
            return;
        }

        // 落盘即导入并同步收口（新建即可拖引用——异步窗口「GUID 未找到」修复）；
        // 导入失败弹错（红线 15）；盘面变更通知面板重拉树
        ImportTask task = editor.getAssetImportPipeline().submitImport(file);
        editor.getAssetImportPipeline().awaitImport(task, AWAIT_IMPORT_TIMEOUT_MS);
        if (task.getStatus() == ImportTask.Status.FAILED) {
            JOptionPane.showMessageDialog(MainFrame.getInstance(),
                    EditorLang.text("create.material.importFailed", task.getErrorMessage()),
                    EditorLang.text("create.material.dialogTitle"), JOptionPane.ERROR_MESSAGE);
        }
        EditorEventBus.publish(new ProjectChangedEvent());
        LOGGER.info("创建材质资产: {}", file);
    }

    /** 目标目录内重名去重：material.mat / material 1.mat / …（文件名编号口径） */
    private static File uniqueTarget(File dir) {
        File candidate = new File(dir, BASE_NAME + ".mat");
        int n = 1;
        while (candidate.exists()) {
            candidate = new File(dir, BASE_NAME + " " + n + ".mat");
            n++;
        }
        return candidate;
    }
}
