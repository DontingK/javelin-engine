package com.javelin.rhi.desc;

import com.javelin.rhi.types.DescriptorType;

/**
 * 描述符池创建描述符。
 */
public record DescriptorPoolDesc(
    int maxSets,
    PoolSize[] poolSizes,
    boolean freeIndividualSets
) {
    public DescriptorPoolDesc {
        if (poolSizes == null) poolSizes = new PoolSize[0];
    }

    public DescriptorPoolDesc(int maxSets, PoolSize[] poolSizes) {
        this(maxSets, poolSizes, false);
    }

    // ========== 内部类型 ==========

    public record PoolSize(DescriptorType type, int count) {}
}
