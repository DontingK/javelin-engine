package cn.donting.javelin.editor.menu.project;

import cn.donting.javelin.core.render.texture.Texture2D;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.ProjectChangedEvent;
import cn.donting.javelin.editor.i18n.I18nKey;
import cn.donting.javelin.editor.importer.ImportTask;
import cn.donting.javelin.editor.meta.AssetMeta;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.serializable.TextExporter;
import cn.donting.javelin.editor.window.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import javax.swing.JOptionPane;

/**
 * 创建纹理资产（project.create.texture）：选中目录落 {@code texture.texture}
 * （重名去重 texture 1.texture …），内容 = 空 Image 引用 + 默认采样设置的
 * {@link Texture2D} 文本序列化（「.texture 就是 Texture2D 的序列化」，
 * Engine_Texture_Design §2 同 .mat 口径）。**源文本 guid 与伴生 .meta 同源**
 * （CreateMaterialMenuCommand 同款）：先 getOrCreateMeta 建 .meta 拿 guid、
 * setGuid 后一次性落盘。落盘即提交导入：TextureImporter 产
 * Library/{guid}.asset 二进制、更新 index。盘面变更发 {@code project.changed}
 * 面板重拉。选中后检查器 .texture 面板可换 Image/改采样设置。
 *
 * <p>线程口径：命令在 EDT 执行，纯 IO + 管线提交（ReimportMenuCommand 同款）；
 * .texture 文本体量极小无卡顿顾虑。</p>
 *
 * @author javelin
 * @create 2026/10/25
 */
public class CreateTextureMenuCommand extends ProjectMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateTextureMenuCommand.class);

    private static final String BASE_NAME = "texture";
    /** 导入同步收口超时（毫秒）——.texture 毫秒级，超时=放弃等待 */
    private static final long AWAIT_IMPORT_TIMEOUT_MS = 5000;
    private final String id;
    private final String shortcut;

    public CreateTextureMenuCommand(String id, String shortcut) {
        this.id = id;
        this.shortcut = shortcut;
    }

    @Override
    public String id() {
        return "project.create.texture";
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
            LOGGER.warn("编辑器实例未就绪，跳过创建纹理（project.create.texture）");
            return;
        }

        File file = uniqueTarget(dir);
        // 源文本 guid 与 meta 同源（CreateMaterialMenuCommand 同款四处一致口径）
        AssetMeta meta = editor.getMetaManager().getOrCreateMeta(file);
        Texture2D texture = new Texture2D();
        texture.setGuid(meta.getGuid());
        try {
            new TextExporter(file).export(texture);
        } catch (Exception e) {
            LOGGER.error("纹理文件写出失败: {}", file, e);
            return;
        }

        // 落盘即导入并同步收口（新建即可拖引用——异步窗口「GUID 未找到」修复）；
        // 导入失败弹错（红线 15）；盘面变更通知面板重拉树
        ImportTask task = editor.getAssetImportPipeline().submitImport(file);
        editor.getAssetImportPipeline().awaitImport(task, AWAIT_IMPORT_TIMEOUT_MS);
        if (task.getStatus() == ImportTask.Status.FAILED) {
            JOptionPane.showMessageDialog(MainFrame.getInstance(),
                    EditorLang.text("create.texture.importFailed", task.getErrorMessage()),
                    EditorLang.text("create.texture.dialogTitle"), JOptionPane.ERROR_MESSAGE);
        }
        EditorEventBus.publish(new ProjectChangedEvent());
        LOGGER.info("创建纹理资产: {}", file);
    }

    /** 目标目录内重名去重：texture.texture / texture 1.texture / …（文件名编号口径） */
    private static File uniqueTarget(File dir) {
        File candidate = new File(dir, BASE_NAME + ".texture");
        int n = 1;
        while (candidate.exists()) {
            candidate = new File(dir, BASE_NAME + " " + n + ".texture");
            n++;
        }
        return candidate;
    }
}
