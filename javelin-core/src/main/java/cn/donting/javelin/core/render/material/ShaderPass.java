package cn.donting.javelin.core.render.material;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * Shader 的一个 Pass（.shader {@code Pass GBuffer {}} 块编译后的结构，RHI 20.2 / 21 节）。
 *
 * <p>块名即渲染阶段名，是引擎 {@code pipelineFor("GBuffer")} 的查找键；
 * SPIR-V 为离线构建产物（vs/fs 块经 {@code #version} 后注入声明，由 shaderc 编译）。
 * 纯数据、device 无关；PSO 经渲染侧缓存按 Pass 取用（RHI 20.7-4），本类不持有 PSO。</p>
 *
 * <p>序列化：实现 {@link Serializable} 走 Capsule 二进制协议（.shader 资产产物格式，
 * Engine_Material_Design §3）；字段非 final——反序列化需回写（红线 9），只读靠 API 面。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public final class ShaderPass implements Serializable {

    private String name;
    private byte[] vertexSpirv;
    private byte[] fragmentSpirv;
    private PassState state;

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public ShaderPass() {
    }

    public ShaderPass(String name, byte[] vertexSpirv, byte[] fragmentSpirv, PassState state) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Pass 名不能为空（块名即渲染阶段名）");
        }
        this.name = name;
        this.vertexSpirv = vertexSpirv;
        this.fragmentSpirv = fragmentSpirv;
        this.state = state != null ? state : new PassState();
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("name", name);
        output.write("vertexSpirv", vertexSpirv);
        output.write("fragmentSpirv", fragmentSpirv);
        output.write("state", state);
    }

    @Override
    public void read(InputCapsule input) {
        name = input.readString("name");
        vertexSpirv = input.readByteArray("vertexSpirv");
        fragmentSpirv = input.readByteArray("fragmentSpirv");
        state = input.readSerializable("state", PassState.class);
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
