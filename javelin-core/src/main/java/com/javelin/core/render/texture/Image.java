package com.javelin.core.render.texture;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.rhi.types.Format;

/**
 * 像素资产（图片导入产物）：width/height/原始像素的纯数据持有者——磁盘一份全项目
 * 共享，采样与派生设置（filter/wrap/mip）归 {@link Texture2D} 引用层（三层拍板
 * 2026-10-25，Engine_Texture_Design.md §1）。字段非 final（红线 9）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Image extends AbsAsset {

    private int width;
    private int height;
    private Format format;
    /** 紧密打包像素（行主序、顶行在前，format 决定每像素字节数）；null = 未填充 */
    private byte[] pixels;

    /**
     * 反序列化用空构造（ReflectiveFactory 创建后 read 回写）。
     */
    public Image() {
    }

    /**
     * 创建并填充像素（引用专用数组，勿再改写原数组）。
     */
    public Image(int width, int height, Format format, byte[] pixels) {
        this.width = width;
        this.height = height;
        this.format = format;
        this.pixels = pixels;
    }

    /**
     * 1×1 纯色像素（占位贴图工厂，device 无关）。
     */
    public static Image solid(int r, int g, int b, int a) {
        return new Image(1, 1, Format.R8G8B8A8_UNORM,
                new byte[]{(byte) r, (byte) g, (byte) b, (byte) a});
    }

    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("width", width);
        output.write("height", height);
        output.write("format", format);
        output.write("pixels", pixels);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        width = input.readInt("width");
        height = input.readInt("height");
        Format f = input.readEnum("format", Format.class);
        if (f != null) {
            format = f;
        }
        pixels = input.readByteArray("pixels");
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Format getFormat() {
        return format;
    }

    /**
     * 像素本体（紧密打包；上传前必须非空）。
     */
    public byte[] getPixels() {
        return pixels;
    }
}
