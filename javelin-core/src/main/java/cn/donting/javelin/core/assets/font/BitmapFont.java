package cn.donting.javelin.core.assets.font;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.assets.AbsAsset;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;

/**
 * 点阵字库资产（{@code .chars} 字符集源码的烘焙产物）。
 *
 * <p>源码形态 = {@code .chars} 纯文本（列出要烘焙进字库的字符），经编辑器导入器
 * {@code BitmapFontImporter} 用打包字体光栅化为 16x16 1bpp 位图后落 Library；
 * 运行期经 {@link cn.donting.javelin.core.assets.AssetManager} 按路径装载，
 * 交 {@link cn.donting.javelin.core.debug.DebugFont16#parse(byte[])} 解析使用。
 * 二进制布局（"JDTF"）契约见
 * {@code cn.donting.javelin.tool.debugtext.DebugTextFontRasterizer} javadoc。</p>
 *
 * <p>序列化（Capsule 二进制协议）：字段非 final——反序列化需回写（红线 9），
 * 只读靠 API 面保证。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class BitmapFont extends AbsAsset {

    // 非 final：序列化反序列化需回写（红线 9）
    private String name;
    private byte[] data;

    /** 反序列化用无参构造（ReflectiveFactory 重建）。 */
    public BitmapFont() {
    }

    /**
     * 构建点阵字库资产（导入器用）。
     *
     * @param name 资产名（通常取自文件名去扩展名）
     * @param data JDTF 字库二进制（null = 坏产物 stub，见 {@link #isValid()}）
     */
    public BitmapFont(String name, byte[] data) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("字库资产名不能为空");
        }
        this.name = name;
        this.data = data;
    }

    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("name", name);
        output.write("data", data);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        name = input.readString("name");
        data = input.readByteArray("data");
    }

    /** 资产名（通常取自文件名）。 */
    public String getName() {
        return name;
    }

    /** JDTF 字库二进制（烘焙产物，只读共享，勿修改）。 */
    public byte[] getData() {
        return data;
    }

    /**
     * 产物完整性判定（坏产物 stub 判据，对齐 SpvShader.isValid 口径）：
     * data 非空——烘焙失败 stub 产物为 false，消费侧应拒绝并显性报错。
     */
    public boolean isValid() {
        return data != null && data.length > 0;
    }
}
