package cn.donting.javelin.core.render.texture;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.assets.AbsAsset;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.rhi.types.Format;

/**
 * cubemap 像素资产（equirect 全景图导入产物）：六面等尺寸正方形像素的纯数据
 * 持有者——{@link Image} 的 cubemap 同族（Engine_Skybox_Design §5 步骤三）。
 * 消费方 SkyboxPass 懒上传桥；设置层（filter/wrap）后置，本类不持采样参数。
 * 字段非 final（红线 9）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class CubemapImage extends AbsAsset {

    /** 六面 layer 序：+X/-X/+Y/-Y/+Z/-Z（Vulkan cubemap 标准，CubemapTest 同款） */
    public static final int FACE_COUNT = 6;

    private int faceSize;
    private Format format;
    /** 紧密打包像素（6 面×faceSize² 按面序连续、面内行主序顶行在前）；null = 未填充 */
    private byte[] pixels;

    /**
     * 反序列化用空构造（ReflectiveFactory 创建后 read 回写）。
     */
    public CubemapImage() {
    }

    /**
     * 创建并填充六面像素（引用专用数组，勿再改写原数组）。
     */
    public CubemapImage(int faceSize, Format format, byte[] pixels) {
        this.faceSize = faceSize;
        this.format = format;
        this.pixels = pixels;
    }

    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("faceSize", faceSize);
        output.write("format", format);
        output.write("pixels", pixels);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        faceSize = input.readInt("faceSize");
        Format f = input.readEnum("format", Format.class);
        if (f != null) {
            format = f;
        }
        pixels = input.readByteArray("pixels");
    }

    public int getFaceSize() {
        return faceSize;
    }

    public Format getFormat() {
        return format;
    }

    /**
     * 六面像素本体（紧密打包；上传前必须非空）。
     */
    public byte[] getPixels() {
        return pixels;
    }
}
