package cn.donting.javelin.core.render.compute;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.assets.AbsAsset;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;

/**
 * 计算着色器资产（.compute 源码文件编译后的结构）。
 *
 * <p>对齐 Unity 心智：扩展名 .compute 即独立资产类型（与 .shader 区分），
 * 一个文件一个 kernel（GLSL compute 入口恒为 main，多 kernel = 多文件）。
 * 纯数据、device 无关——源码经编辑器导入链 shaderc 编译为 SPIR-V；
 * ComputePipeline 由渲染侧按需创建，本类不持有。</p>
 *
 * <p>序列化（Capsule 二进制协议）：字段非 final——反序列化需回写（红线 9），
 * 只读靠 API 面保证。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class ComputeShader extends AbsAsset {

    // 非 final：序列化反序列化需回写（红线 9）
    private String name;
    private byte[] spirv;

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public ComputeShader() {
    }

    /**
     * 构建计算着色器资产（导入器用）。
     *
     * @param name  资产名（通常取自文件名）
     * @param spirv GLSL compute 编译产物（null = 坏产物 stub，见 {@link #isValid()}）
     */
    public ComputeShader(String name, byte[] spirv) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("计算着色器名不能为空");
        }
        this.name = name;
        this.spirv = spirv;
    }

    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("name", name);
        output.write("spirv", spirv);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        name = input.readString("name");
        spirv = input.readByteArray("spirv");
    }

    /** 资产名（通常取自文件名）。 */
    public String getName() {
        return name;
    }

    /** compute SPIR-V（编译产物，只读共享，勿修改）。 */
    public byte[] getSpirv() {
        return spirv;
    }

    /**
     * 二进制完整性判定（坏产物 stub 判据，对齐 Shader.isValid 口径）：
     * spirv 非空——编译失败 stub 产物为 false，消费侧应拒绝并显性报错。
     */
    public boolean isValid() {
        return spirv != null && spirv.length > 0;
    }
}
