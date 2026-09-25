package com.javelin.core.render.texture;

import com.javelin.rhi.Device;
import com.javelin.rhi.Texture;
import com.javelin.rhi.UploadData;
import com.javelin.rhi.desc.TextureDesc;
import com.javelin.rhi.types.TextureUsage;

import java.nio.ByteBuffer;
import java.util.Set;

/**
 * 纹理上传桥接（渲染侧）：Texture2D 的 CPU 像素 → GPU 句柄。
 *
 * <p>引擎材质设计 §4 的懒上传实现——首次渲染收集阶段对材质引用的纹理统一调用，
 * Pass 回调内只查 {@link Texture2D#getGpu()} 全是命中（避免命令录制中途
 * createTexture 的 fence 等待卡帧）。句柄回填资产，生命周期同 Mesh 缓冲：
 * 资产 GC 不可达 → 句柄走 RHI Cleaner（RHI 17 节）。</p>
 *
 * <p>v0.1 限制：只上传 base level（mipLevels 须为 1，mip 生成链 RHI 未实现）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class TextureUpload {

    private TextureUpload() {
    }

    /**
     * 上传纹理像素到 GPU 并回填句柄（幂等：已上传直接返回）。
     *
     * @param device   渲染设备（FrameLoop/RenderingSystem 持有，帧首收集阶段传入）
     * @param texture2D 待上传纹理（pixels 必须已填充）
     */
    public static void upload(Device device, Texture2D texture2D) {
        if (device == null || texture2D == null) {
            throw new IllegalArgumentException("device / texture2D must not be null");
        }
        if (texture2D.getGpu() != null) {
            return;   // 已上传
        }
        ByteBuffer pixels = texture2D.getPixels();
        if (pixels == null) {
            throw new IllegalStateException("纹理未填充像素（setPixels / loadImage 先）");
        }
        if (texture2D.getMipLevels() != 1) {
            throw new UnsupportedOperationException(
                    "v0.1 仅支持 mipLevels=1（mip 生成链未实现）: " + texture2D.getMipLevels());
        }
        TextureDesc desc = TextureDesc.texture2D(texture2D.getFormat(),
                texture2D.getWidth(), texture2D.getHeight(),
                Set.of(TextureUsage.TRANSFER_DST, TextureUsage.SAMPLED));
        Texture gpu = device.createTexture(desc, UploadData.of(pixels));
        texture2D.setGpu(gpu);
    }
}
