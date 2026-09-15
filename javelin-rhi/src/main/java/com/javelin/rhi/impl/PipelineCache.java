package com.javelin.rhi.impl;

import com.javelin.rhi.RHIPipeline;
import com.javelin.rhi.RHIPipelineDesc;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 管线缓存，用于复用已创建的 Vulkan 图形/计算管线对象。
 * <p>
 * 通过描述符（{@link RHIPipelineDesc}）的哈希值作为键，避免重复创建相同的管线，
 * 从而提升渲染性能。同时支持将缓存持久化到磁盘，加速下次启动时的管线预热。
 */
public class PipelineCache {

    /** 内部缓存，键为管线描述符的哈希值，值为对应的管线对象 */
    private final Map<Integer, RHIPipeline> cache = new ConcurrentHashMap<>();

    /**
     * 根据描述符获取或创建管线。
     * 如果缓存中已存在对应描述符的管线，则直接返回；否则通过工厂方法创建并缓存。
     *
     * @param desc    管线描述符
     * @param factory 管线创建工厂
     * @return 对应的管线对象
     */
    public RHIPipeline getOrCreate(RHIPipelineDesc desc, Supplier<RHIPipeline> factory) {
        int key = computeKey(desc);
        return cache.computeIfAbsent(key, k -> factory.get());
    }

    /**
     * 将管线缓存序列化并保存到磁盘。
     *
     * @param path 目标文件路径
     */
    public void saveToDisk(java.nio.file.Path path) {
        // TODO: Serialize VkPipelineCache binary
    }

    /**
     * 从磁盘加载已持久化的管线缓存数据。
     *
     * @param path 缓存文件路径
     */
    public void loadFromDisk(java.nio.file.Path path) {
        // TODO: Deserialize VkPipelineCache binary
    }

    /**
     * 预热管线缓存，异步预编译指定描述符列表对应的管线。
     *
     * @param descs 需要预热的管线描述符列表
     */
    public void prewarm(java.util.List<RHIPipelineDesc> descs) {
        // TODO: Async pipeline compilation
    }

    /**
     * 计算管线描述符的哈希值，用于作为缓存键。
     *
     * @param desc 管线描述符
     * @return 描述符的哈希值
     */
    private int computeKey(RHIPipelineDesc desc) {
        // TODO: Deterministic hash of desc fields
        return desc.hashCode();
    }
}
