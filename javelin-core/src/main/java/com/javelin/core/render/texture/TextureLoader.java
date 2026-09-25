package com.javelin.core.render.texture;

import com.javelin.rhi.Device;
import com.javelin.rhi.Texture;
import com.javelin.rhi.UploadData;
import com.javelin.rhi.desc.TextureDesc;
import com.javelin.rhi.exception.RHIException;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.TextureUsage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 纹理加载工具（引擎侧共用）：ImageIO 解码 → 紧密打包 RGBA → R8G8B8A8_UNORM。
 *
 * <p>两条出路：{@link #loadImage(Path)} 出引擎纹理资产（CPU 侧、device 无关，
 * GPU 上传推迟到渲染侧首绑——Unity Texture2D 心智）；
 * {@link #loadRgba8(Device, Path)} / {@link #createSolid(Device, int, int, int, int)}
 * 出 RHI 纹理句柄（直注册模式过渡期 RenderingSystem 用，材质桥接落地后收编）。
 */
public final class TextureLoader {

    private TextureLoader() {
    }

    /**
     * 解码图片文件为引擎纹理资产（Texture2D：CPU 像素 + 元数据，无 GPU 句柄）。
     * 占位常量（WHITE / BLACK / NORMAL）见 {@link Texture2D}。
     */
    public static Texture2D loadImage(Path path) {
        DecodedImage decoded = decode(path);
        return new Texture2D(decoded.width, decoded.height,
                Format.R8G8B8A8_UNORM, 1, decoded.pixels);
    }

    /** 解码图片文件为 RGBA8 采样纹理（device 内部 staging 上传，fence 等待后可用） */
    public static Texture loadRgba8(Device device, Path path) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        DecodedImage decoded = decode(path);
        TextureDesc desc = TextureDesc.texture2D(Format.R8G8B8A8_UNORM,
                decoded.width, decoded.height,
                Set.of(TextureUsage.TRANSFER_DST, TextureUsage.SAMPLED));
        return device.createTexture(desc, UploadData.of(decoded.pixels));
    }

    /** 创建 1×1 纯色占位纹理（RHI 16.3 占位贴图思路；无贴图物体的着色兜底） */
    public static Texture createSolid(Device device, int r, int g, int b, int a) {
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        TextureDesc desc = TextureDesc.texture2D(Format.R8G8B8A8_UNORM, 1, 1,
                Set.of(TextureUsage.TRANSFER_DST, TextureUsage.SAMPLED));
        return device.createTexture(desc, UploadData.of(pixel));
    }

    /** -Dtexture.path 优先；否则从工作目录逐级向上找 resources/texture.jpeg */
    public static Path resolveDefaultTexturePath() {
        String override = System.getProperty("texture.path");
        if (override != null && !override.isEmpty()) {
            return Path.of(override);
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("resources/texture.jpeg");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new RHIException("未找到 resources/texture.jpeg（可用 -Dtexture.path= 指定绝对路径）");
    }

    private static DecodedImage decode(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(path.toFile());
        } catch (IOException e) {
            throw new RHIException("读取图片失败: " + path, e);
        }
        if (image == null) {
            throw new RHIException("无法解码图片（ImageIO 无匹配解码器）: " + path);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                pixels.put((byte) (argb >> 16));
                pixels.put((byte) (argb >> 8));
                pixels.put((byte) argb);
                pixels.put((byte) 0xFF);
            }
        }
        pixels.flip();
        return new DecodedImage(width, height, pixels);
    }

    private static final class DecodedImage {
        final int width;
        final int height;
        final ByteBuffer pixels;

        DecodedImage(int width, int height, ByteBuffer pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }
}
