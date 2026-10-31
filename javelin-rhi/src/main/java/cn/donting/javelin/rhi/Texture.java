package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.TextureDesc;

import java.nio.ByteBuffer;

/**
 * 纹理接口。
 * 对照设计文档第 26.2 节。
 */
public interface Texture extends GPUResource {

    TextureDesc desc();

    /** 运行期更新（staging 池） */
    void update(TextureRegion region, ByteBuffer data);

    /** 回读（P2，HOST_CACHED） */
    ByteBuffer readback();

    /** Vulkan 句柄 VkImage */
    long handle();
}
