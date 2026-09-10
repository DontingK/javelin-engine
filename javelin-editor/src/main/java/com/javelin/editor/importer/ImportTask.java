package com.javelin.editor.importer;


import com.javelin.core.assets.AssetManager;
import com.javelin.editor.meta.AssetMeta;

import java.io.File;

/**
 * 资源导入任务
 *
 * <p>封装单个资源的导入操作</p>
 *
 * @author: javelin
 * @create: 2026/4/8
 * @Version 1.0
 */
public class ImportTask {

    /**
     * 导入状态
     */
    public enum Status {
        PENDING,      // 等待导入
        IMPORTING,    // 导入中
        SUCCESS,      // 导入成功
        FAILED,       // 导入失败
        CANCELLED     // 已取消
    }

    private final File sourceFile;
    private final AssetMeta meta;
    private final ImportSettings settings;
    private Status status = Status.PENDING;
    private String errorMessage;
    private long startTime;
    private long endTime;
    private final AssetManager assetManager;


    public ImportTask(File sourceFile, AssetMeta meta, ImportSettings settings, AssetManager assetManager) {
        this.sourceFile = sourceFile;
        this.meta = meta;
        this.settings = settings;
        this.assetManager = assetManager;
    }

    public AssetManager getAssetManager() {
        return assetManager;
    }

    /**
     * 获取源文件
     *
     * @return 源文件
     */
    public File getSourceFile() {
        return sourceFile;
    }

    /**
     * 获取资源元数据
     *
     * @return 元数据
     */
    public AssetMeta getMeta() {
        return meta;
    }

    /**
     * 获取导入设置
     *
     * @return 导入设置
     */
    public ImportSettings getSettings() {
        return settings;
    }

    /**
     * 获取导入状态
     *
     * @return 状态
     */
    public Status getStatus() {
        return status;
    }

    /**
     * 设置导入状态
     *
     * @param status 状态
     */
    public void setStatus(Status status) {
        this.status = status;
    }

    /**
     * 获取错误信息
     *
     * @return 错误信息
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 设置错误信息
     *
     * @param errorMessage 错误信息
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        this.status = Status.FAILED;
    }

    /**
     * 标记导入开始
     */
    public void markStart() {
        this.startTime = System.currentTimeMillis();
        this.status = Status.IMPORTING;
    }

    /**
     * 标记导入完成
     *
     * @param success 是否成功
     */
    public void markEnd(boolean success) {
        this.endTime = System.currentTimeMillis();
        this.status = success ? Status.SUCCESS : Status.FAILED;
    }

    /**
     * 获取导入耗时（毫秒）
     *
     * @return 耗时，如果未开始返回 -1
     */
    public long getDuration() {
        if (startTime == 0) {
            return -1;
        }
        if (endTime == 0) {
            return System.currentTimeMillis() - startTime;
        }
        return endTime - startTime;
    }

    @Override
    public String toString() {
        return "ImportTask{" +
                "file=" + sourceFile.getName() +
                ", status=" + status +
                ", duration=" + getDuration() + "ms" +
                '}';
    }
}
