package com.javelin.editor.menu.project;

import com.javelin.editor.JavelinEditor;
import com.javelin.editor.importer.AssetImportPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 「重新导入」命令（project.reimport，Unity Reimport 口径）：选中文件直接
 * {@link AssetImportPipeline#submitImport(File)} 强制重导（绕过 needImport 的
 * mtime 比对，产物覆盖写）；选中目录递归收集受支持文件逐个提交（异步管线
 * 消费，不阻塞命令线程）。无导入器的文件跳过（扩展名不在注册表 = 管线语义
 * 上的非资产，如 .meta/.gitignore）。
 *
 * @author javelin
 * @create 2026/10/17
 */
public class ReimportMenuCommand extends ProjectMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReimportMenuCommand.class);

    @Override
    public String id() {
        return "project.reimport";
    }

    @Override
    public String label() {
        return "重新导入";
    }

    /** 选中文件有对应导入器（非资产文件不出此项），或选中目录（目录内递归筛）才可用 */
    @Override
    public Boolean enabled() {
        File file = getSelectedFile();
        if (file != null) {
            AssetImportPipeline pipeline = pipeline();
            return pipeline != null && pipeline.getAssetImporter(file) != null;
        }
        return getSelectedDirectory() != null;
    }

    @Override
    public void execute() {
        AssetImportPipeline pipeline = pipeline();
        if (pipeline == null) {
            return;
        }
        File file = getSelectedFile();
        if (file != null) {
            pipeline.submitImport(file);
            return;
        }
        File dir = getSelectedDirectory();
        if (dir == null) {
            return;
        }
        int count = 0;
        // 迭代式目录遍历（目录树浅，无递归必要；Deque 复用引擎既有习惯）
        Deque<File> stack = new ArrayDeque<>();
        stack.push(dir);
        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] children = current.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (pipeline.getAssetImporter(child) != null) {
                    pipeline.submitImport(child);
                    count++;
                }
            }
        }
        LOGGER.info("目录重新导入完成: {} 提交 {} 个导入任务", dir.getName(), count);
    }

    /** 导入管线入口（编辑器实例 null = 未就绪记 warn） */
    private static AssetImportPipeline pipeline() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未就绪，导入管线不可用");
            return null;
        }
        return editor.getAssetImportPipeline();
    }
}
