package cn.donting.javelin.core.assets.shadow;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.assets.AbsAsset;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;

/**
 * 原生 GLSL 着色器资产（.vert/.frag 源码编译后的 SPIR-V 产物）。
 *
 * <p>引擎覆盖制 Pass（ShadowPass 等不消费材质 Pass 声明的引擎自有着色器）
 * 的装载形态：源码放 builtin-asset-core，经编辑器导入链（SpvShaderImporter +
 * shaderc）编译为 SPIR-V 落 Library，运行期经 AssetManager 按路径加载。
 * 一个文件一个 stage，扩展名即 stage（.vert → VERTEX / .frag → FRAGMENT）。
 * 纯数据、device 无关；PSO 由渲染侧按需创建，本类不持有。</p>
 *
 * <p>序列化（Capsule 二进制协议）：字段非 final——反序列化需回写（红线 9），
 * 只读靠 API 面保证。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class SpvShader extends AbsAsset {

    // 非 final：序列化反序列化需回写（红线 9）
    private String name;
    private byte[] spvData;

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public SpvShader() {
    }

    /**
     * 构建 SPIR-V 着色器资产（导入器用）。
     *
     * @param name    资产名（通常取自文件名）
     * @param spvData GLSL 编译产物（null = 坏产物 stub，见 {@link #isValid()}）
     */
    public SpvShader(String name, byte[] spvData) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("着色器资产名不能为空");
        }
        this.name = name;
        this.spvData = spvData;
    }

    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("name", name);
        output.write("spvData", spvData);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        name = input.readString("name");
        spvData = input.readByteArray("spvData");
    }

    /** 资产名（通常取自文件名）。 */
    public String getName() {
        return name;
    }

    /** SPIR-V 二进制（编译产物，只读共享，勿修改）。 */
    public byte[] getSpvData() {
        return spvData;
    }

    /**
     * 二进制完整性判定（坏产物 stub 判据，对齐 Shader/ComputeShader.isValid 口径）：
     * spvData 非空——编译失败 stub 产物为 false，消费侧应拒绝并显性报错。
     */
    public boolean isValid() {
        return spvData != null && spvData.length > 0;
    }
}
