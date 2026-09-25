package com.javelin.core.render.texture;

import com.javelin.rhi.Texture;
import com.javelin.rhi.desc.TextureDesc;
import com.javelin.rhi.types.Format;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Texture2D 冻结语义单元测试：上传（句柄回填）后资产不可变，与 Mesh 口径一致。
 */
public class Texture2DTest {

    /** 假 GPU 句柄：不碰 Device，仅用于触发"已上传"状态 */
    private static Texture dummyGpu() {
        return new Texture() {
            @Override
            public TextureDesc desc() {
                return null;
            }

            @Override
            public void update(com.javelin.rhi.TextureRegion region, ByteBuffer data) {
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
        Texture2D texture = new Texture2D(2, 2, Format.R8G8B8A8_UNORM, 1);
        texture.setPixels(ByteBuffer.allocateDirect(16));
        texture.setFilterMode(Texture2D.FilterMode.POINT);
        texture.setWrapMode(Texture2D.WrapMode.CLAMP);
        assertNull(texture.getGpu());
    }

    @Test
    void modificationAfterUploadThrows() {
        Texture2D texture = new Texture2D(2, 2, Format.R8G8B8A8_UNORM, 1,
                ByteBuffer.allocateDirect(16));
        texture.setGpu(dummyGpu());

        assertThrows(IllegalStateException.class, () -> texture.setPixels(ByteBuffer.allocateDirect(16)));
        assertThrows(IllegalStateException.class, () -> texture.setFilterMode(Texture2D.FilterMode.POINT));
        assertThrows(IllegalStateException.class, () -> texture.setWrapMode(Texture2D.WrapMode.CLAMP));
    }
}
