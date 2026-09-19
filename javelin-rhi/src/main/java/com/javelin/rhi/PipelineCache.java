package com.javelin.rhi;

import java.nio.file.Path;

/**
 * 管线缓存接口。
 * 对照设计文档第 16.4 节 / 第 26.3 节。
 */
public interface PipelineCache extends GPUResource {

    /** 磁盘持久化 */
    void saveToFile(Path path);

    /** 从磁盘加载 */
    void loadFromFile(Path path);

    /** Vulkan 句柄 VkPipelineCache */
    long handle();
}
