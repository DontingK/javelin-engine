package cn.donting.javelin.editor.menu.project;

import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.ProjectChangedEvent;
import cn.donting.javelin.editor.file.SceneFileType;
import cn.donting.javelin.editor.i18n.I18nKey;
import cn.donting.javelin.editor.importer.ImportTask;
import cn.donting.javelin.editor.meta.AssetMeta;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.serializable.TextExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 创建场景资产（project.create.scene，Unity Create → Scene 对齐）：选中目录落
 * {@code scene.scene}（重名去重 scene 1.scene …），内容 = 空 {@link GameScene}
 * 文本序列化；**创建即切进新场景编辑**（Unity 口径：新建场景成为当前编辑对象；
 * 当前场景未保存时走 {@link SceneFileType} 同款脏确认三键弹窗）。落盘即提交导入
 * （submitImport 产 Library 产物），切换经 {@code SceneFileType.openScene} 复用
 * 双击打开的 save→load→swap→事件整链（含 guid 同步/选中收口/清脏/树重拉）。
 *
 * <p>源文本 guid 与伴生 .meta 同源（.scene 保存/CreateMaterial 同口径）：先
 * getOrCreateMeta 拿 guid、setGuid 后一次性落盘。线程口径：EDT 纯 IO +
 * 管线提交，场景切换 submitTask 帧边界（SceneFileType 同款）。</p>
 *
 * @author javelin
 * @create 2026/11/01
 */
public class CreateSceneMenuCommand extends ProjectMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateSceneMenuCommand.class);

    private static final String BASE_NAME = "scene";
    /** 导入同步收口超时（毫秒）——.scene 小体量，超时=放弃等待（导入仍在跑不判失败） */
    private static final long AWAIT_IMPORT_TIMEOUT_MS = 5000;

    private final String id;
    private final String shortcut;
    private final String icon;

    public CreateSceneMenuCommand(String id, String shortcut) {
        this(id, shortcut, null);
    }

    public CreateSceneMenuCommand(String id, String shortcut, String icon) {
        this.id = id;
        this.shortcut = shortcut;
        this.icon = icon;
    }

    @Override
    public String id() {
        return "project.create.scene";
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
            LOGGER.warn("编辑器实例未就绪，跳过创建场景（project.create.scene）");
            return;
        }

        // 落盘前先过脏确认：取消=不创建（新建必然切换走，当前场景编辑无处保留）
        if (!SceneFileType.confirmDirtyOrCancel()) {
            return;
        }

        File file = uniqueTarget(dir);
        AssetMeta meta = editor.getMetaManager().getOrCreateMeta(file);
        GameScene scene = new GameScene(file.getName().substring(0,
                file.getName().length() - ".scene".length()));
        scene.setGuid(meta.getGuid());
        try {
            new TextExporter(file).export(scene);
        } catch (Exception e) {
            LOGGER.error("场景文件写出失败: {}", file, e);
            return;
        }

        // 落盘即导入并同步收口（新建即可双击打开——异步窗口 index 未建修复同材质）；
        // 盘面变更通知面板重拉树
        ImportTask task = editor.getAssetImportPipeline().submitImport(file);
        editor.getAssetImportPipeline().awaitImport(task, AWAIT_IMPORT_TIMEOUT_MS);
        if (task.getStatus() == ImportTask.Status.FAILED) {
            LOGGER.error("新建场景导入失败: {} ({})", file, task.getErrorMessage());
        }
        EditorEventBus.publish(new ProjectChangedEvent());
        LOGGER.info("创建场景资产并切换: {}", file);

        // 创建即打开（Unity 口径）；切换链复用双击打开（含可选保存当前场景）
        SceneFileType.openScene(file);
    }

    /** 目标目录内重名去重：scene.scene / scene 1.scene / …（文件名编号口径） */
    private static File uniqueTarget(File dir) {
        File candidate = new File(dir, BASE_NAME + ".scene");
        int n = 1;
        while (candidate.exists()) {
            candidate = new File(dir, BASE_NAME + " " + n + ".scene");
            n++;
        }
        return candidate;
    }
}
