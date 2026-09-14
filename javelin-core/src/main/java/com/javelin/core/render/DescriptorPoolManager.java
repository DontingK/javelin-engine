package com.javelin.core.render;

import com.javelin.rhi.api.*;
import com.javelin.rhi.desc.*;
import com.javelin.rhi.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局描述符池管理器 —— 集中管理 DescriptorSet 分配。
 *
 * <p>按帧分配和回收，避免每帧创建/销毁 DescriptorPool。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class DescriptorPoolManager {

    private static final Logger log = LoggerFactory.getLogger(DescriptorPoolManager.class);

    private final Device device;

    /** 全局描述符池 */
    private DescriptorPool descriptorPool;

    /** 配置常量 */
    private static final int MAX_SETS = 1024;
    private static final int MAX_UNIFORM_BUFFERS = 1024;
    private static final int MAX_COMBINED_IMAGE_SAMPLERS = 512;

    // ═══════════════════════════════════════════════════════════════════════
    //  构造
    // ═══════════════════════════════════════════════════════════════════════

    public DescriptorPoolManager(Device device) {
        this.device = device;
        createPool();
    }

    private void createPool() {
        DescriptorPoolDesc.PoolSize[] poolSizes = new DescriptorPoolDesc.PoolSize[]{
            new DescriptorPoolDesc.PoolSize(DescriptorType.UNIFORM_BUFFER, MAX_UNIFORM_BUFFERS),
            new DescriptorPoolDesc.PoolSize(DescriptorType.COMBINED_IMAGE_SAMPLER, MAX_COMBINED_IMAGE_SAMPLERS)
        };

        DescriptorPoolDesc desc = new DescriptorPoolDesc(MAX_SETS, poolSizes, true);
        descriptorPool = device.createDescriptorPool(desc);
        log.info("DescriptorPoolManager created (maxSets={})", MAX_SETS);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  公共方法
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取底层描述符池。
     */
    public DescriptorPool getDescriptorPool() {
        return descriptorPool;
    }

    /**
     * 分配描述符集。
     */
    public DescriptorSet allocateSet(DescriptorSetLayout layout) {
        return descriptorPool.allocateSet(layout);
    }

    /**
     * 重置池（每帧调用，用于 TRANSIENT 模式）。
     */
    public void reset() {
        if (descriptorPool != null) {
            descriptorPool.reset();
        }
    }

    /**
     * 释放资源。
     */
    public void dispose() {
        if (descriptorPool != null) {
            descriptorPool.dispose();
            descriptorPool = null;
        }
        log.info("DescriptorPoolManager disposed");
    }
}
