package com.javelin.editor.importer;


import com.javelin.editor.project.Project;

import java.io.File;
import java.util.List;

/**
 * 资源导入器接口
 *
 * <p>定义资源导入的标准流程，每种资源类型实现此接口</p>
 *
 * @author: javelin
 * @create: 2026/4/8
 * @Version 1.0
 */
public interface AssetImporter {

    public static final String EXTENSION = ".asset";

    /**
     * 获取导入器名称
     *
     * @return 导入器名称
     */
    String getName();

    /**
     * 获取支持的文件扩展名
     *
     * @return 支持的扩展名列表（如 [".sc", ".hlsl"]）
     */
    List<String> getSupportedExtensions();

    /**
     * 检查是否支持导入指定文件
     *
     * @param file 文件
     * @return 如果支持返回 true
     */
    default boolean canImport(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        String name = file.getName().toLowerCase();
        return getSupportedExtensions().stream()
                .anyMatch(name::endsWith);
    }

    /**
     * 创建默认导入设置
     *
     * @param file 源文件
     * @return 默认导入设置
     */
    ImportSettings createDefaultSettings(File file);

    /**
     * 导入资源
     *
     * @param task    导入任务
     * @param project 项目
     * @return 如果导入成功返回 true
     */
    boolean importAsset(ImportTask task, Project project) throws Exception;

    /**
     * 清理导入产生的临时文件
     *
     * @param task    导入任务
     * @param project 项目
     */
    default void cleanup(ImportTask task, Project project) {
        // 默认不清理，具体导入器可覆盖
    }


    default File getOutputFile(ImportTask task, Project project) {
        try {
            File library = project.getLibrary();
            return new File(library, task.getMeta().getGuid() + ".asset");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
