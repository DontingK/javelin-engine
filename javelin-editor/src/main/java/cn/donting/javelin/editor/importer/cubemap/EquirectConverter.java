package cn.donting.javelin.editor.importer.cubemap;

import cn.donting.javelin.core.render.texture.CubemapImage;
import cn.donting.javelin.core.render.texture.Image;
import cn.donting.javelin.core.render.texture.TextureLoader;
import cn.donting.javelin.rhi.types.Format;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * equirect 全景图 → 六面 cubemap 转换（纯逻辑静态方法，供导入器与单测共用，
 * ComputeShaderImporter.buildComputeShader 先例同构）。
 *
 * <p>方向约定：面序 +X/-X/+Y/-Y/+Z/-Z、面内坐标 GL 立方图标准（s、t、m 按
 * face 选取），采样侧由 samplerCube 硬件选面（skybox.frag normalize(vDir)
 * 同一语义）——Engine_Skybox_Design §5。转换 = 每面每像素：面内坐标 → 方向
 * 向量 → 经纬 uv → 源图双线性（四邻加权、边缘 clamp）。源图宽高须 2:1。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class EquirectConverter {

    /** 导入产物固定面尺寸（拍板 2026-11-03：少一个设置面板，可调后置） */
    public static final int FACE_SIZE = 512;

    private EquirectConverter() {
    }

    /**
     * .pano（png/jpg 内容的 equirect 全景图）→ 六面 cubemap。
     */
    public static CubemapImage fromPano(Path source) throws IOException {
        return convert(TextureLoader.loadImage(source), FACE_SIZE);
    }

    /**
     * .hdr（Radiance RGBE）→ 六面 cubemap。
     *
     * @throws IOException 非法 HDR 文件（头/格式不符）
     */
    public static CubemapImage fromHdr(Path source) throws IOException {
        return convert(decodeHdr(source), FACE_SIZE);
    }

    /**
     * equirect 像素（紧密 RGBA8，行主序顶行在前）→ 六面 cubemap 像素。
     *
     * @param source 源全景图（宽高须 2:1，否则显性报错）
     * @param faceSize 目标面尺寸
     */
    public static CubemapImage convert(Image source, int faceSize) throws IOException {
        if (source.getWidth() != 2 * source.getHeight()) {
            throw new IOException("equirect 源图必须 2:1（当前 " + source.getWidth()
                    + "x" + source.getHeight() + "）");
        }
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        byte[] src = source.getPixels();

        byte[] out = new byte[CubemapImage.FACE_COUNT * faceSize * faceSize * 4];
        float[] dir = new float[3];
        for (int face = 0; face < CubemapImage.FACE_COUNT; face++) {
            for (int y = 0; y < faceSize; y++) {
                for (int x = 0; x < faceSize; x++) {
                    // 面内像素中心 → 单位方向向量（GL 立方图标准）
                    faceCoordToDir(face,
                            (x + 0.5f) * 2f / faceSize - 1f,
                            (y + 0.5f) * 2f / faceSize - 1f, dir);
                    // 方向 → 经纬 uv（u=atan2(z,x) 业界约定：赤道中心 u=0.5 ↔ +X，
                    // three.js EquirectUV 原文同式、Poly Haven 预览同源）；像素行
                    // 序换算（v：顶行=+Y 天空）；采样取 texel 中心所以 -0.5 偏移
                    float lon = (float) Math.atan2(dir[2], dir[0]);
                    float lat = (float) Math.asin(clamp(dir[1], -1f, 1f));   // dir 已单位化，clamp 数值兜底
                    float u = (float) (lon / (2 * Math.PI) + 0.5);
                    float v = 1f - (float) (lat / Math.PI + 0.5);
                    int dst = (face * faceSize * faceSize + y * faceSize + x) * 4;
                    sampleBilinear(src, srcW, srcH, u, v, out, dst);
                }
            }
        }
        return new CubemapImage(faceSize, Format.R8G8B8A8_UNORM, out);
    }

    /**
     * 面内坐标（[-1,1]²，x 右 y 下）→ **单位**方向向量（GL cubemap 标准）。
     * 必须归一化：纬度 asin(dir.y) 吃单位向量，未归一时只有面心（|dir|=1）正确
     * ——2026-11-07 排障修（顶/底盖整面退化成极行、侧面纵向覆盖整球）。
     */
    static void faceCoordToDir(int face, float s, float t, float[] out) {
        switch (face) {
            case 0 -> { out[0] = 1; out[1] = -t; out[2] = -s; }   // +X
            case 1 -> { out[0] = -1; out[1] = -t; out[2] = s; }  // -X
            case 2 -> { out[0] = s; out[1] = 1; out[2] = t; }    // +Y
            case 3 -> { out[0] = s; out[1] = -1; out[2] = -t; }  // -Y
            case 4 -> { out[0] = s; out[1] = -t; out[2] = 1; }   // +Z
            case 5 -> { out[0] = -s; out[1] = -t; out[2] = -1; } // -Z
            default -> throw new IllegalArgumentException("face: " + face);
        }
        float invLen = 1f / (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);
        out[0] *= invLen;
        out[1] *= invLen;
        out[2] *= invLen;
    }

    /**
     * 双线性采样（uv ∈ [0,1]，u 右 v 下；边缘 clamp；结果写 out[dst..dst+3]）。
     */
    static void sampleBilinear(byte[] src, int w, int h, float u, float v, byte[] out, int dst) {
        float px = u * w - 0.5f;
        float py = v * h - 0.5f;
        int x0 = (int) Math.floor(px);
        int y0 = (int) Math.floor(py);
        float fx = px - x0;
        float fy = py - y0;
        int x1 = clamp(x0 + 1, 0, w - 1);
        int y1 = clamp(y0 + 1, 0, h - 1);
        x0 = clamp(x0, 0, w - 1);
        y0 = clamp(y0, 0, h - 1);
        for (int c = 0; c < 4; c++) {
            float val = (src[(y0 * w + x0) * 4 + c] & 0xFF) * (1 - fx) * (1 - fy)
                    + (src[(y0 * w + x1) * 4 + c] & 0xFF) * fx * (1 - fy)
                    + (src[(y1 * w + x0) * 4 + c] & 0xFF) * (1 - fx) * fy
                    + (src[(y1 * w + x1) * 4 + c] & 0xFF) * fx * fy;
            out[dst + c] = (byte) (val + 0.5f);
        }
    }

    /**
     * Radiance RGBE 解码（.hdr）：float32 线性光 → RGBA8（v0.x 量化拍板，float
     * 产物归四期环境光批）。支持标准 per-channel RLE scanline（0x02 0x02 宽度
     * 大端标记，rgbe.c 同款——Poly Haven 产物形态）与 flat 未压缩；旧版
     * (1,1,1,n) 逐像素 RLE 不识别（rgbe.c 同款行为）。换算用无偏移公式
     * v·2^(e-136)（[0,1] 往返精确，rgbe.c 口径；Ward 官方 +0.5 分叉不取）。
     */
    static Image decodeHdr(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("#?")) {
                throw new IOException("非法 HDR 文件（缺 #? 识别头）");
            }
            // 头字段直到空行；只认 RADIANCE（RGBE）、GAMMA 缺省、EXPOSURE 恒 1 才无感
            String formatType = null;
            boolean gammaDefault = true;
            boolean exposureDefault = true;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    break;
                }
                if (line.startsWith("FORMAT=")) {
                    formatType = line.substring(7).trim();
                } else if (line.startsWith("GAMMA=")) {
                    gammaDefault = line.substring(6).trim().equals("1.0");
                } else if (line.startsWith("EXPOSURE=")) {
                    exposureDefault = line.substring(9).trim().equals("1.0");
                }
            }
            if (!"32-bit_rle_rgbe".equalsIgnoreCase(formatType)) {
                throw new IOException("不支持的 HDR 格式（仅 32-bit_rle_rgbe，当前 "
                        + formatType + "）");
            }
            if (!gammaDefault || !exposureDefault) {
                throw new IOException("不支持的 HDR 头（GAMMA/EXPOSURE 非 1.0 暂不支持）");
            }

            // 分辨率行：-Y height +X width = 行主序顶到底、行内左到右（标准轴序，
            // 其余 7 种轴序组合罕见不认）
            line = reader.readLine();
            if (line == null) {
                throw new IOException("非法 HDR 文件（缺分辨率行）");
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 4 || !parts[0].equals("-Y") || !parts[2].equals("+X")) {
                throw new IOException("不支持的 HDR 轴序（仅 -Y/+X，当前 " + line + "）");
            }
            int height = Integer.parseInt(parts[1]);
            int width = Integer.parseInt(parts[3]);
            if (width <= 0 || height <= 0) {
                throw new IOException("非法 HDR 分辨率（" + width + "x" + height + "）");
            }

            byte[] pixels = new byte[width * height * 4];
            byte[] rgbe = new byte[4];
            int ptr = 0;
            for (int y = 0; y < height; y++) {
                readFully(reader, rgbe);
                if ((rgbe[0] & 0xFF) == 2 && (rgbe[1] & 0xFF) == 2 && (rgbe[2] & 0x80) == 0
                        && (((rgbe[2] & 0xFF) << 8) | (rgbe[3] & 0xFF)) == width) {
                    // RLE scanline：4 通道（R,G,B,E）各自独立压缩，先读完整通道再下一个
                    byte[] scanline = new byte[width * 4];
                    for (int ch = 0; ch < 4; ch++) {
                        readRleChannel(reader, width, scanline, ch);
                    }
                    for (int x = 0; x < width; x++) {
                        decodeRgbePixel(scanline, x * 4, pixels, ptr);
                        ptr += 4;
                    }
                } else {
                    // flat：首 4 字节即首像素，其余直读
                    decodeRgbePixel(rgbe, 0, pixels, ptr);
                    ptr += 4;
                    for (int x = 1; x < width; x++) {
                        readFully(reader, rgbe);
                        decodeRgbePixel(rgbe, 0, pixels, ptr);
                        ptr += 4;
                    }
                }
            }
            return new Image(width, height, Format.R8G8B8A8_UNORM, pixels);
        }
    }

    /**
     * 读单通道 RLE 流填 scanline[ch]（count>128=data 重复 count-128 次；count≤128
     * =data + 后随 count-1 字面字节，count==0 非法——rgbe.c 读侧同规则）。
     */
    private static void readRleChannel(BufferedReader reader, int width, byte[] scanline, int ch) throws IOException {
        int written = 0;
        while (written < width) {
            int count = readByte(reader);
            int data = readByte(reader);
            if (count == 0) {
                throw new IOException("RLE 数据非法（count==0）");
            }
            if (count > 128) {
                count -= 128;
                if (written + count > width) {
                    throw new IOException("RLE 重复段越界");
                }
                for (int i = 0; i < count; i++) {
                    scanline[written * 4 + ch] = (byte) data;
                    written++;
                }
            } else {
                if (written + count > width) {
                    throw new IOException("RLE 字面段越界");
                }
                scanline[written * 4 + ch] = (byte) data;
                written++;
                for (int i = 1; i < count; i++) {
                    scanline[written * 4 + ch] = (byte) readByte(reader);
                    written++;
                }
            }
        }
    }

    private static void readFully(BufferedReader reader, byte[] buf) throws IOException {
        for (int i = 0; i < buf.length; i++) {
            int c = reader.read();
            if (c < 0) {
                throw new IOException("HDR 数据意外结束");
            }
            buf[i] = (byte) c;
        }
    }

    private static int readByte(BufferedReader reader) throws IOException {
        int c = reader.read();
        if (c < 0) {
            throw new IOException("HDR 数据意外结束");
        }
        return c;
    }

    /**
     * RGBE 像素（src[srcOff..]）→ 线性 float → RGBA8 量化（线性→sRGB 编码后 0..255
     * 截断；>1 高亮截白，负值截 0；换算 v·2^(e-136)、e==0 全黑——rgbe.c 无偏移公式）。
     * sRGB 编码（2026-11-06 偏暗修复）：.hdr 解出线性光，而引擎显示链是伽马字节直通
     * （swapchain 非 sRGB 格式、png/jpg 像素本就伽马域存储）——线性直存 0.5→128
     * 显示比应有效果暗一大截，编码后 0.5→≈188 与 png 口径一致。
     */
    private static void decodeRgbePixel(byte[] src, int srcOff, byte[] out, int ptr) {
        int e = src[srcOff + 3] & 0xFF;
        if (e == 0) {
            out[ptr] = 0;
            out[ptr + 1] = 0;
            out[ptr + 2] = 0;
        } else {
            float scale = (float) Math.pow(2, e - 136);
            out[ptr] = quantize((src[srcOff] & 0xFF) * scale);
            out[ptr + 1] = quantize((src[srcOff + 1] & 0xFF) * scale);
            out[ptr + 2] = quantize((src[srcOff + 2] & 0xFF) * scale);
        }
        out[ptr + 3] = (byte) 0xFF;
    }

    /**
     * 线性值 → sRGB 编码 → 255 量化（0..1 截断；亮度>1 截白。标准 sRGB 分段传递函数，
     * 与 png/jpg 伽马域字节同口径）。
     */
    private static byte quantize(float linear) {
        if (linear <= 0) {
            return 0;
        }
        float srgb = linear <= 0.0031308f
                ? 12.92f * linear
                : 1.055f * (float) Math.pow(linear, 1f / 2.4f) - 0.055f;
        int v = Math.round(srgb * 255f);
        if (v > 255) {
            v = 255;
        }
        return (byte) v;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
