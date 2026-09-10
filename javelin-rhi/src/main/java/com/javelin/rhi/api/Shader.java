package com.javelin.rhi.api;

import com.javelin.rhi.types.ShaderStage;

/**
 * 着色器模块（SPIR-V）。
 */
public abstract class Shader extends GPUResource {

    public abstract ShaderStage stage();
}
