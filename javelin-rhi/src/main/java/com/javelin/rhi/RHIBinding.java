package com.javelin.rhi;

/**
 * 描述符绑定定义，描述一个描述符在着色器中的布局信息。
 *
 * @param set        描述符集索引
 * @param binding    绑定槽位
 * @param type       描述符类型
 * @param count      数组元素个数（非数组为 1）
 * @param stageFlags 适用的着色器阶段（位掩码）
 */
public record RHIBinding(
    int set,
    int binding,
    RHIDescriptorType type,
    int count,
    int stageFlags
) {
}
