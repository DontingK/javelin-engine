package cn.donting.javelin.core.render.texture;

import cn.donting.javelin.core.render.texture.Image;
import cn.donting.javelin.core.render.texture.Texture2D;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.TextureRegion;
import cn.donting.javelin.rhi.desc.TextureDesc;
import cn.donting.javelin.rhi.types.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Texture2D 引用层单测：运行期可变（换图作废旧 GPU 句柄待重传、采样设置直接生效）+
 * Image 像素资产 / Texture2D 引用层序列化往返（三层拍板 2026-10-25）。
 */
public class Texture2DTest {

    @TempDir
    Path tempDir;

    /** 假 GPU 句柄：不碰 Device，仅用于触发"已上传"状态 */
    private static Texture dummyGpu() {
        return new Texture() {
            @Override
            public TextureDesc desc() {
                return null;
            }

            @Override
            public void update(TextureRegion region, ByteBuffer data) {
            }

            @Override
            public ByteBuffer readback() {
                return null;
            }

            @Override
            public long handle() {
                return 0;
            }

            @Override
            public void dispose() {
            }
        };
    }

    @Test
    void modificationBeforeUploadIsAllowed() {
        Texture2D texture = new Texture2D(new Image(2, 2, Format.R8G8B8A8_UNORM, new byte[16]));
        texture.setFilterMode(Texture2D.FilterMode.POINT);
        texture.setWrapMode(Texture2D.WrapMode.CLAMP);
        assertNull(texture.getGpu());
    }

    @Test
    void modificationAfterUploadIsAllowed() {
        Texture2D texture = new Texture2D(new Image(2, 2, Format.R8G8B8A8_UNORM, new byte[16]));
        texture.setGpu(dummyGpu());
        int revision = texture.getRevision();

        // 采样设置只影响 sampler：直接改，revision 自增驱动渲染侧重建描述符集
        texture.setFilterMode(Texture2D.FilterMode.POINT);
        texture.setWrapMode(Texture2D.WrapMode.CLAMP);
        assertEquals(Texture2D.FilterMode.POINT, texture.getFilterMode());
        assertEquals(Texture2D.WrapMode.CLAMP, texture.getWrapMode());
        assertTrue(texture.getRevision() > revision);

        // 换图：旧 GPU 句柄作废（置空），下次绑定时重传
        texture.setImage(new Image(1, 1, Format.R8G8B8A8_UNORM, new byte[4]));
        assertNull(texture.getGpu());
        assertEquals(1, texture.getWidth());
    }

    @Test
    void sameImageReferenceKeepsGpuHandle() {
        Image pixels = new Image(2, 2, Format.R8G8B8A8_UNORM, new byte[16]);
        Texture2D texture = new Texture2D(pixels);
        Texture gpu = dummyGpu();
        texture.setGpu(gpu);

        texture.setImage(pixels);   // 同一引用：不触发重建，句柄保持
        assertSame(gpu, texture.getGpu());
    }

    @Test
    void imageRoundtripRestoresPixelsAndGuid() throws Exception {
        Image source = new Image(2, 1, Format.R8G8B8A8_UNORM,
                new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88});
        source.setGuid("abc123");

        File file = tempDir.resolve("image.asset").toFile();
        new BinaryExporter(file).export(source);
        Image restored = new BinaryImporter(file).importData(Image.class);

        assertEquals("abc123", restored.getGuid());
        assertEquals(2, restored.getWidth());
        assertEquals(1, restored.getHeight());
        assertEquals(Format.R8G8B8A8_UNORM, restored.getFormat());
        assertArrayEquals(source.getPixels(), restored.getPixels());
    }

    @Test
    void textureRoundtripRestoresReferenceAndSettings() throws Exception {
        // image 无 guid → 图内嵌（guid 分流不触发）
        Image pixels = new Image(2, 2, Format.R8G8B8A8_UNORM, new byte[16]);
        Texture2D source = new Texture2D(pixels);
        source.setFilterMode(Texture2D.FilterMode.POINT);
        source.setWrapMode(Texture2D.WrapMode.CLAMP);

        File file = tempDir.resolve("texture.asset").toFile();
        new BinaryExporter(file).export(source);
        Texture2D restored = new BinaryImporter(file).importData(Texture2D.class);

        assertNotNull(restored.getImage());
        assertArrayEquals(pixels.getPixels(), restored.getPixels());
        assertEquals(2, restored.getWidth());
        assertEquals(Texture2D.FilterMode.POINT, restored.getFilterMode());
        assertEquals(Texture2D.WrapMode.CLAMP, restored.getWrapMode());
        assertNull(restored.getGpu());  // gpu 序列化不落地
    }
}
