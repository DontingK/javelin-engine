package com.javelin.rhi;

/**
 * GPU 着色器模块抽象基类，表示已编译的 SPIR-V 着色器代码。
 */
public abstract class RHIShaderModule extends RHIResource {

    protected RHIShaderModule(RHI rhi, long handle) {
        super(rhi, handle);
    }
}
