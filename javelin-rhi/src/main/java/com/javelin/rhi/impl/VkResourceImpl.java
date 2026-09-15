package com.javelin.rhi.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vulkan 资源表实现，管理所有 GPU 资源的句柄分配和查找。
 * <p>
 * 为每个原生 Vulkan 对象分配唯一的句柄，并提供线程安全的存取接口。
 * 是 RHI 层与底层 Vulkan 对象之间的映射层，支持缓冲区、纹理、管线等各类资源。
 */
public class VkResourceImpl {

    /** 下一个可用的句柄值，使用原子自增保证线程安全 */
    private final AtomicLong nextHandle = new AtomicLong(1);
    /** 句柄到原生资源的映射表 */
    private final ConcurrentHashMap<Long, Object> resources = new ConcurrentHashMap<>();

    /**
     * 为原生资源分配一个唯一句柄，并将其注册到资源表中。
     *
     * @param nativeResource 原生 Vulkan 资源对象
     * @return 分配的唯一句柄
     */
    public long allocateHandle(Object nativeResource) {
        long handle = nextHandle.getAndIncrement();
        resources.put(handle, nativeResource);
        return handle;
    }

    /**
     * 根据句柄获取对应的资源对象。
     *
     * @param <T>    资源类型
     * @param handle 资源句柄
     * @return 对应的资源对象，若不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(long handle) {
        return (T) resources.get(handle);
    }

    /**
     * 从资源表中移除指定句柄对应的资源。
     *
     * @param handle 资源句柄
     * @return 被移除的资源对象，若不存在则返回 null
     */
    public Object remove(long handle) {
        return resources.remove(handle);
    }

    /**
     * 获取当前存活资源的数量。
     *
     * @return 存活资源数
     */
    public int liveCount() {
        return resources.size();
    }
}
