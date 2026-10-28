package com.javelin.editor.menu.project;

import com.javelin.editor.JavelinEditor;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.ProjectChangedEvent;
import com.javelin.editor.i18n.I18nKey;
import com.javelin.editor.importer.ImportTask;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.window.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.swing.JOptionPane;

/**
 * 创建着色器资产（project.create.shader，Unity Create → Shader 对齐）：选中目录落
 * {@code NewShader.shader}（重名去重 NewShader 1.shader …），内容 = 极简前向着色器
 * 模板。用户文件不写 guid 头（内置资源专属）——guid 真值归伴生 .meta 随机分配，
 * 导入器走头缺失回退 meta 的路径。落盘即 submitImport，盘面变更发
 * {@code project.changed}；线程口径同 CreateMaterialMenuCommand（EDT 纯 IO）。
 *
 * @author javelin
 * @create 2026/10/25
 */
public class CreateShaderMenuCommand extends ProjectMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateShaderMenuCommand.class);

    private static final String BASE_NAME = "NewShader";
    /** 导入同步收口超时（毫秒）——.shader 含 shaderc 编译秒级，超时=放弃等待 */
    private static final long AWAIT_IMPORT_TIMEOUT_MS = 5000;
    private final String id;
    private final String shortcut;
    private final String icon;

    /** 创建模板：颜色属性走 Properties → MaterialBlock 通路，前向不透底稿 */
    private static final String TEMPLATE = """
            Shader "NewShader"
            {
                Properties
                {
                    _Color ("Main Color", Color) = (1, 1, 1, 1)
                }

                Pass Forward
                {
                    Cull Back
                    ZWrite On
                    ZTest LEqual
                    Blend Off

                    vs {
                        #version 450
                        layout(location = 0) in vec3 aPos;
                        void main() {
                            gl_Position = cam.viewProj * pc.model * vec4(aPos, 1.0);
                        }
                    }
                    fs {
                        #version 450
                        layout(location = 0) out vec4 fragColor;
                        void main() {
                            fragColor = material._Color;
                        }
                    }
                }
            }
            """;

    public CreateShaderMenuCommand(String id, String shortcut) {
        this(id, shortcut, null);
    }

    public CreateShaderMenuCommand(String id, String shortcut, String icon) {
        this.id = id;
        this.shortcut = shortcut;
        this.icon = icon;
    }

    @Override
    public String id() {
        return "project.create.shader";
    }

    @Override
    public I18nKey label() {
        return EditorLang.key(id());
    }

    @Override
    public String icon() {
        return "shader";
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
            LOGGER.warn("编辑器实例未就绪，跳过着色器创建（project.create.shader）");
            return;
        }

        File file = uniqueTarget(dir);
        try {
            Files.writeString(file.toPath(), TEMPLATE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("着色器文件写出失败: {}", file, e);
            return;
        }

        // 落盘即导入并同步收口（新建即可拖引用——异步窗口「GUID 未找到」修复）；
        // 导入失败弹错（红线 15）；盘面变更通知面板重拉树
        ImportTask task = editor.getAssetImportPipeline().submitImport(file);
        editor.getAssetImportPipeline().awaitImport(task, AWAIT_IMPORT_TIMEOUT_MS);
        // 编译失败落 stub 产物（任务 SUCCESS 但带 errorMessage）——错误显性化（红线 15）
        if (task.getStatus() == ImportTask.Status.FAILED || task.getErrorMessage() != null) {
            JOptionPane.showMessageDialog(MainFrame.getInstance(),
                    EditorLang.text("create.shader.import.failed", task.getErrorMessage()),
                    EditorLang.text("create.shader.dialog.title"), JOptionPane.ERROR_MESSAGE);
        }
        EditorEventBus.publish(new ProjectChangedEvent());
        LOGGER.info("创建着色器资产: {}", file);
    }

    /** 目标目录内重名去重：NewShader.shader / NewShader 1.shader / …（文件名编号口径） */
    private static File uniqueTarget(File dir) {
        File candidate = new File(dir, BASE_NAME + ".shader");
        int n = 1;
        while (candidate.exists()) {
            candidate = new File(dir, BASE_NAME + " " + n + ".shader");
            n++;
        }
        return candidate;
    }
}
