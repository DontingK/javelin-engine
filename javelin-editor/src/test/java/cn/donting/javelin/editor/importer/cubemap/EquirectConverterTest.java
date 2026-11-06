package cn.donting.javelin.editor.importer.cubemap;

import cn.donting.javelin.core.render.texture.CubemapImage;
import cn.donting.javelin.core.render.texture.Image;
import cn.donting.javelin.rhi.types.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EquirectConverter 单测：轴向着色比对（faceSize=1 单像素面精确采样，方向约定
 * 整体验证）+ 面中心/非中心方向（归一化回归）+ 非中心 texel 经纬回归 + RGBE 解码
 * （RLE/flat 手工构造流）+ 2:1 校验。纯 CPU 直调静态方法（ComputeShaderImporterTest
 * 先例），不涉 RHI/导入管线。
 */
public class EquirectConverterTest {

    @TempDir
    Path tempDir;

    /**
     * 轴向渐变着色：R=u 经度斜坡、G=v 纬度斜坡的 8x4 源 → faceSize=1（单像素面
     * = 面心方向精确回采）六面比对。期望值由 three.js EquirectUV 约定手算：
     * +X→u=0.5、-X→u=1.0（seam clamp 列末）、+Z→u=0.75、-Z→u=0.25、±Y→u=0.5；
     * v=0 顶行=天空/dir.y=+1。线性斜坡对双线性精确（round 误差 ±1 内）。
     */
    @Test
    public void axisRampOnFaceCenters() throws IOException {
        CubemapImage out = EquirectConverter.convert(rampSource(8, 4), 1);
        // (R,G) 期望六面：+X(128,128) -X(238,128) +Y(128,32) -Y(128,223) +Z(191,128) -Z(64,128)
        assertChannel(out, 0, 128, 128, "+X 面心 u=0.5 v=0.5");
        assertChannel(out, 1, 238, 128, "-X 面心 u=1.0 seam clamp 列末 v=0.5");
        assertChannel(out, 2, 128, 32, "+Y 面心 u=0.5 v=0 顶行");
        assertChannel(out, 3, 128, 223, "-Y 面心 u=0.5 v=1 底行");
        assertChannel(out, 4, 191, 128, "+Z 面心 u=0.75 v=0.5");
        assertChannel(out, 5, 64, 128, "-Z 面心 u=0.25 v=0.5");
    }

    /**
     * 非中心 texel 经纬回归（2026-11-07 排障修）：faceSize=4 + 8x4 斜坡源。
     * +X 面左上角 texel（s=t=-0.75）方向 (1,0.75,0.75)/√2.125 → lon=36.87°
     * lat=30.95° → (u,v)=(0.6024,0.3281) → (R,G)=(153,84)；修复前纬度吃未归一化的
     * dir.y=0.75 → lat=48.59° → v=0.2301 → G≈59。经度 atan2 尺度不变，R 修复前后
     * 都应是 153（本用例同时护住「只改纵向映射」）。+Y 面极冠 texel 方向
     * (-0.75,1,-0.75)/√2.125 → lat=43.30° → v=0.2595 → G≈66；修复前该面 v≡0
     * （整面退化成源图顶行）→ G≈32。
     */
    @Test
    public void offCenterTexelLatitude() throws IOException {
        CubemapImage out = EquirectConverter.convert(rampSource(8, 4), 4);
        assertTexel(out, 0, 0, 0, 153, 84, 2, "+X 面左上角 texel");
        assertTexel(out, 2, 0, 0, 32, 66, 2, "+Y 面极冠 texel");
    }

    /** R=u / G=v 轴向斜坡源（线性斜坡对双线性精确，round 误差 ±1 内） */
    private static Image rampSource(int srcW, int srcH) {
        byte[] px = new byte[srcW * srcH * 4];
        for (int y = 0; y < srcH; y++) {
            for (int x = 0; x < srcW; x++) {
                int i = (y * srcW + x) * 4;
                px[i] = (byte) Math.round((x + 0.5f) / srcW * 255);
                px[i + 1] = (byte) Math.round((y + 0.5f) / srcH * 255);
                px[i + 2] = 0;
                px[i + 3] = (byte) 0xFF;
            }
        }
        return new Image(srcW, srcH, Format.R8G8B8A8_UNORM, px);
    }

    /** 面心单像素 R/G 通道值比对（容差 ±1：斜坡像素 round 误差） */
    private static void assertChannel(CubemapImage img, int face, int expectR, int expectG, String what) {
        assertTexel(img, face, img.getFaceSize() / 2, img.getFaceSize() / 2, expectR, expectG, 1, what);
    }

    /** 面内指定 texel (x,y) 的 R/G 通道值比对 */
    private static void assertTexel(CubemapImage img, int face, int x, int y,
                                    int expectR, int expectG, int tolerance, String what) {
        int fs = img.getFaceSize();
        int i = (face * fs * fs + y * fs + x) * 4;
        byte[] p = img.getPixels();
        int r = p[i] & 0xFF;
        int g = p[i + 1] & 0xFF;
        assertTrue(Math.abs(r - expectR) <= tolerance, what + "：R=" + r + " 期望≈" + expectR);
        assertTrue(Math.abs(g - expectG) <= tolerance, what + "：G=" + g + " 期望≈" + expectG);
    }

    /**
     * faceCoordToDir 面中心（s=t=0）→ 面主轴方向（GL cubemap 标准语义）。
     */
    @Test
    public void faceCenterDirections() {
        float[] d = new float[3];
        EquirectConverter.faceCoordToDir(0, 0, 0, d);
        assertArrayEquals(new float[]{1, 0, 0}, d, 1e-6f);
        EquirectConverter.faceCoordToDir(1, 0, 0, d);
        assertArrayEquals(new float[]{-1, 0, 0}, d, 1e-6f);
        EquirectConverter.faceCoordToDir(2, 0, 0, d);
        assertArrayEquals(new float[]{0, 1, 0}, d, 1e-6f);
        EquirectConverter.faceCoordToDir(3, 0, 0, d);
        assertArrayEquals(new float[]{0, -1, 0}, d, 1e-6f);
        EquirectConverter.faceCoordToDir(4, 0, 0, d);
        assertArrayEquals(new float[]{0, 0, 1}, d, 1e-6f);
        EquirectConverter.faceCoordToDir(5, 0, 0, d);
        assertArrayEquals(new float[]{0, 0, -1}, d, 1e-6f);
    }

    /**
     * 面角方向必须归一化（2026-11-07 排障回归：faceCoordToDir 未归一化时
     * asin(dir.y) 吃到 |dir|=√2 的非单位向量被 clamp 钳到 1，角附近纬度全堆
     * ±90°——顶/底盖退化成极行、角部错边；faceSize=1 面心测试 |dir| 恰好=1
     * 踩不到，此用例堵漏）。判别量=纬度（asin 尺度敏感）；经度 atan2 对整体
     * 缩放不变（Pitfalls #32 口径），不能当归一化的判别量。
     */
    @Test
    public void faceCornerDirectionsNormalized() {
        float[] d = new float[3];
        // +X 面角 (s,t)=(1,1)：未归一 (1,-1,-1)，归一后 y=-1/√3——纬度应 ≈-35.26°
        //（未归一时 dir.y=-1 被钳、asin(-1)=-90°）
        EquirectConverter.faceCoordToDir(0, 1, 1, d);
        assertEquals(1.0f, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-6f);
        float lat = (float) Math.asin(d[1]);
        assertEquals(-0.61548f, lat, 1e-4f);   // -35.264° = asin(-1/√3)
        // +Y 面角 (s,t)=(1,1)：未归一 (1,1,1)，归一后 y=1/√3——纬度应 ≈35.26°
        //（未归一时 dir.y=1 恒 1，asin(1)=90° 整面塌成极点——#32 症状本体）
        EquirectConverter.faceCoordToDir(2, 1, 1, d);
        assertEquals(1.0f, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-6f);
        float capLat = (float) Math.asin(d[1]);
        assertEquals(0.61548f, capLat, 1e-4f); // +35.264° = asin(1/√3)；未归一 = π/2 整面错
    }

    /**
     * faceCoordToDir 非中心点必须返回**单位**方向向量（2026-11-07 排障回归）：
     * 纬度 asin(dir.y) 吃单位向量，未归一化时只有面心（|dir|=1）正确——极盖面退化成
     * 源图极行、侧面纵向从覆盖 ±45° 变成覆盖整球（视口症状：上下盖一圈 = 立方体
     * 的棱与角处明显分界）。
     */
    @Test
    public void faceCoordToDirNormalizedOffCenter() {
        float[] d = new float[3];
        float ir2 = (float) (1 / Math.sqrt(2));
        float ir3 = (float) (1 / Math.sqrt(3));
        // +X 面顶边中列（s=0, t=-1）：方向 (1,1,0)/√2——45° 仰角面边；未归一化时 dir.y=1（正北极）
        EquirectConverter.faceCoordToDir(0, 0, -1, d);
        assertArrayEquals(new float[]{ir2, ir2, 0}, d, 1e-6f);
        // +X 面左上角（s=-1, t=-1）：方向 (1,1,1)/√3——立方体一角 35.26° 仰角
        EquirectConverter.faceCoordToDir(0, -1, -1, d);
        assertArrayEquals(new float[]{ir3, ir3, ir3}, d, 1e-6f);
        // +Y 面角（s=1, t=-1）：方向 (1,1,-1)/√3——极盖面同样必须随面内坐标变化
        EquirectConverter.faceCoordToDir(2, 1, -1, d);
        assertArrayEquals(new float[]{ir3, ir3, -ir3}, d, 1e-6f);
        // 面内任意点模长恒 1
        EquirectConverter.faceCoordToDir(4, 0.75f, -0.25f, d);
        assertEquals(1f, (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-6f);
    }

    /**
     * 2:1 源校验：非 2:1 显性报错。
     */
    @Test
    public void nonTwoToOneRejected() {
        Image bad = new Image(3, 2, Format.R8G8B8A8_UNORM, new byte[3 * 2 * 4]);
        IOException e = assertThrows(IOException.class,
                () -> EquirectConverter.convert(bad, 8));
        assertEquals("equirect 源图必须 2:1（当前 3x2）", e.getMessage());
    }

    /**
     * RGBE flat 解码：全像素 4 字节直读 + 无偏移换算 v·2^(e-136) + sRGB 编码量化
     * （线性 0.5→188、0.25→137——标准 sRGB 传递函数）。
     */
    @Test
    public void decodeHdrFlat() throws IOException {
        // e=129 → scale=2^-7：(128,64,32,129)→线性(1,0.5,0.25)→sRGB(255,188,137)；
        // e=0 → 全黑分支
        byte[] hdr = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 2\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = {(byte) 128, 64, 32, (byte) 129, 0, 0, 0, 0};
        Path file = tempDir.resolve("flat.hdr");
        Files.write(file, concat(hdr, body));

        Image img = EquirectConverter.decodeHdr(file);
        assertEquals(2, img.getWidth());
        assertEquals(1, img.getHeight());
        assertArrayEquals(new byte[]{(byte) 255, (byte) 188, (byte) 137, (byte) 255,
                0, 0, 0, (byte) 255}, img.getPixels());
    }

    /**
     * RGBE RLE 解码：02 02 宽度标记 + 每通道独立 RLE（重复段 130=n+128 / 字面段）。
     */
    @Test
    public void decodeHdrRle() throws IOException {
        // 2x1 图 e=129（scale=2^-7）：R=run(64×2)→线性 0.5→sRGB 188、G=literal(32,16)
        // →线性 0.25/0.125→sRGB 137/99、B=run(0×2)→0、E=run(129×2)
        byte[] hdr = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 2\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] marker = {2, 2, 0, 2}; // 宽度 2 大端
        byte[] rle = {(byte) 130, 64, 2, 32, 16, (byte) 130, 0, (byte) 130, (byte) 129};
        Path file = tempDir.resolve("rle.hdr");
        Files.write(file, concat(hdr, marker, rle));

        Image img = EquirectConverter.decodeHdr(file);
        assertEquals(2, img.getWidth());
        assertEquals(1, img.getHeight());
        assertArrayEquals(new byte[]{
                (byte) 188, (byte) 137, 0, (byte) 255,
                (byte) 188, (byte) 99, 0, (byte) 255}, img.getPixels());
    }

    /**
     * RGBE 头校验：缺 #? 识别头 / 非 32-bit_rle_rgbe 显性报错。
     */
    @Test
    public void decodeHdrBadHeader() throws IOException {
        Path noMagic = tempDir.resolve("nomagic.hdr");
        Files.write(noMagic, "FORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 2\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        assertThrows(IOException.class, () -> EquirectConverter.decodeHdr(noMagic));

        Path badFormat = tempDir.resolve("badformat.hdr");
        Files.write(badFormat, "#?RADIANCE\nFORMAT=flat\n\n-Y 1 +X 2\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        assertThrows(IOException.class, () -> EquirectConverter.decodeHdr(badFormat));
    }

    private static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) {
            len += a.length;
        }
        byte[] out = new byte[len];
        int ptr = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, ptr, a.length);
            ptr += a.length;
        }
        return out;
    }
}
