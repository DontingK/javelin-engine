package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.TextureUsage;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;

/**
 * 单格式的「最优平铺」能力快照（RHI-15 / RHI-08，2026-11-21）。
 *
 * <p>数据源 = {@code vkGetPhysicalDeviceFormatProperties} 的
 * {@code optimalTilingFeatures}（引擎纹理一律 OPTIMAL tiling，线性平铺只用于读回中转，
 * 不参与能力判定）。用途 = 资源/管线**创建期 fail-fast**：以前「`STORAGE` + `RGBA16F`」
 * 这类组合全靠驱动兜，失败只在绘制期被校验层指出，离创建点很远。</p>
 *
 * <p>{@link #supports(TextureUsage)} 是 usage → feature 的映射口径，缺项即报错的对象。</p>
 */
public final class FormatSupport {

    private final Format format;
    private final int features;

    FormatSupport(Format format, int optimalTilingFeatures) {
        this.format = format;
        this.features = optimalTilingFeatures;
    }

    public Format format() {
        return format;
    }

    /** 可作为采样纹理 */
    public boolean sampledImage() {
        return has(VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT);
    }

    /** 可被线性/各向异性采样器过滤（NEAREST 不需此项） */
    public boolean linearFilter() {
        return has(VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
    }

    /** 可作为 storage image（compute/片元写入） */
    public boolean storageImage() {
        return has(VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT);
    }

    /** 作为颜色附件 */
    public boolean colorAttachment() {
        return has(VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT);
    }

    /** 作为颜色附件且可混合（`blendEnable = true` 的 PSO 需要） */
    public boolean colorAttachmentBlend() {
        return has(VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT);
    }

    /** 作为深度/模板附件 */
    public boolean depthStencilAttachment() {
        return has(VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT);
    }

    public boolean transferSrc() {
        return has(VK_FORMAT_FEATURE_TRANSFER_SRC_BIT);
    }

    public boolean transferDst() {
        return has(VK_FORMAT_FEATURE_TRANSFER_DST_BIT);
    }

    /**
     * usage → feature 能力的映射（缺项即该纹理不可按此用途创建）。
     * 两个特例：`INPUT_ATTACHMENT` 无对应格式位，按「可作颜色或深度附件」判定；
     * `TRANSIENT_ATTACHMENT` 是内存策略（须 LAZILY_ALLOCATED），不查格式能力。
     */
    public boolean supports(TextureUsage usage) {
        return switch (usage) {
            case SAMPLED -> sampledImage();
            case STORAGE -> storageImage();
            case COLOR_ATTACHMENT -> colorAttachment();
            case DEPTH_STENCIL_ATTACHMENT -> depthStencilAttachment();
            case TRANSFER_SRC -> transferSrc();
            case TRANSFER_DST -> transferDst();
            case INPUT_ATTACHMENT -> colorAttachment() || depthStencilAttachment();
            case TRANSIENT_ATTACHMENT -> true;
        };
    }

    private boolean has(int feature) {
        return (features & feature) != 0;
    }

    @Override
    public String toString() {
        return "FormatSupport{" + format
                + " sampled=" + sampledImage()
                + " filterLinear=" + linearFilter()
                + " storage=" + storageImage()
                + " color=" + colorAttachment()
                + " blend=" + colorAttachmentBlend()
                + " depth=" + depthStencilAttachment()
                + " xferSrc=" + transferSrc()
                + " xferDst=" + transferDst()
                + "}";
    }
}
