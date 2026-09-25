package com.javelin.core.render.material;

/**
 * Shader 的一个 Pass（.shader {@code Pass GBuffer {}} 块编译后的结构，RHI 20.2 / 21 节）。
 *
 * <p>块名即渲染阶段名，是引擎 {@code pipelineFor("GBuffer")} 的查找键；
 * SPIR-V 为离线构建产物（vs/fs 块经 {@code #version} 后注入声明，由 shaderc 编译）。
 * 纯数据、device 无关；PSO 经渲染侧缓存按 Pass 取用（RHI 20.7-4），本类不持有 PSO。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class ShaderPass {

    private final String name;
    private final byte[] vertexSpirv;
    private final byte[] fragmentSpirv;
    private final PassState state;

    public ShaderPass(String name, byte[] vertexSpirv, byte[] fragmentSpirv, PassState state) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Pass 名不能为空（块名即渲染阶段名）");
        }
        this.name = name;
        this.vertexSpirv = vertexSpirv;
        this.fragmentSpirv = fragmentSpirv;
        this.state = state != null ? state : new PassState();
    }

    /**
     * Pass 名（渲染阶段名，如 GBuffer / ShadowCaster / Transparent）。
     */
    public String getName() {
        return name;
    }

    /**
     * 顶点着色器 SPIR-V（离线编译产物，只读共享，勿修改）。
     */
    public byte[] getVertexSpirv() {
        return vertexSpirv;
    }

    /**
     * 片元着色器 SPIR-V（离线编译产物，只读共享，勿修改）。
     */
    public byte[] getFragmentSpirv() {
        return fragmentSpirv;
    }

    public PassState getState() {
        return state;
    }
}
