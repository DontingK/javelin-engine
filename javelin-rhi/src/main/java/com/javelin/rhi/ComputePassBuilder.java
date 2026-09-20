package com.javelin.rhi;

/**
 * 计算 Pass 在图中的句柄。
 * 对照设计文档第 26.5 节。
 */
public interface ComputePassBuilder {

    /** Pass 名（图内唯一） */
    String name();
}
