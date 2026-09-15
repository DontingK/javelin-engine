package com.javelin.rhi;

import java.util.List;

/**
 * 描述符集布局，定义一组描述符绑定的集合，用于创建描述符集和管线布局。
 */
public class RHIDescriptorSetLayout {

    private final List<RHIBinding> bindings;

    private RHIDescriptorSetLayout(List<RHIBinding> bindings) {
        this.bindings = List.copyOf(bindings);
    }

    /** 根据绑定列表创建描述符集布局。 */
    public static RHIDescriptorSetLayout create(List<RHIBinding> bindings) {
        return new RHIDescriptorSetLayout(bindings);
    }

    /** 返回此布局中的所有绑定定义。 */
    public List<RHIBinding> bindings() {
        return bindings;
    }
}
