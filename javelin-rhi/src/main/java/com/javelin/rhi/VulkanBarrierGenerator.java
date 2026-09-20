package com.javelin.rhi;

import com.javelin.rhi.types.AccessFlags;
import com.javelin.rhi.types.PipelineStage;
import com.javelin.rhi.types.TextureLayout;

/**
 * RHI 屏障语义 → Vulkan 常量映射表（24.3 屏障插入算法的后端翻译层）。
 * 纯静态映射，无 GPU 状态；实际的 vkCmdPipelineBarrier 录制在 Vulkan 命令缓冲实现中消费本表（Stage 5）。
 *
 * 常量取值对照 Vulkan 规范（VkImageLayout / VkPipelineStageFlagBits / VkAccessFlagBits），
 * 非 LWJGL 专有定义。
 */
public final class VulkanBarrierGenerator {

    private VulkanBarrierGenerator() {}

    /**
     * 一次布局转换的同步参数。
     * srcAccess 为 null 表示 0（无访问等待，如从 UNDEFINED 转换）。
     */
    public static final class BarrierSync {

        public final PipelineStage srcStage;
        public final AccessFlags srcAccess;   // null = 0（无访问位）
        public final PipelineStage dstStage;
        public final AccessFlags dstAccess;   // null = 0（无访问位）

        BarrierSync(PipelineStage srcStage, AccessFlags srcAccess,
                    PipelineStage dstStage, AccessFlags dstAccess) {
            this.srcStage = srcStage;
            this.srcAccess = srcAccess;
            this.dstStage = dstStage;
            this.dstAccess = dstAccess;
        }
    }

    /**
     * 推导布局转换的同步参数（旧布局 → 新布局）。
     * oldLayout == newLayout 时返回 null（调用方应先做冗余消除）。
     *
     * @param computePass 目标读布局为 SHADER_READ_ONLY 且用于计算 Pass 时为 true（同步域用 COMPUTE_SHADER）
     */
    public static BarrierSync syncFor(TextureLayout oldLayout, TextureLayout newLayout, boolean computePass) {
        if (oldLayout == newLayout) {
            return null;
        }
        if (oldLayout == TextureLayout.UNDEFINED || oldLayout == TextureLayout.TRANSFER_DST) {
            // 首次使用 / 上传后首用：无需等待先前访问（上传同步由上传 fence 保证，25.5）
            return switch (newLayout) {
                case COLOR_ATTACHMENT -> new BarrierSync(PipelineStage.TOP_OF_PIPE, null,
                        PipelineStage.COLOR_ATTACHMENT_OUTPUT, AccessFlags.COLOR_ATTACHMENT_WRITE);
                case DEPTH_STENCIL_ATTACHMENT -> new BarrierSync(PipelineStage.TOP_OF_PIPE, null,
                        PipelineStage.EARLY_FRAGMENT_TESTS, AccessFlags.DEPTH_STENCIL_ATTACHMENT_WRITE);
                case SHADER_READ_ONLY -> new BarrierSync(PipelineStage.TOP_OF_PIPE, null,
                        computePass ? PipelineStage.COMPUTE_SHADER : PipelineStage.FRAGMENT_SHADER,
                        AccessFlags.SHADER_READ);
                default -> coarse(oldLayout, newLayout);
            };
        }
        if (newLayout == TextureLayout.PRESENT_SRC) {
            // 图尾转回呈现：present 由 renderFinished semaphore 衔接（24.1），只需等颜色附件写完成
            return new BarrierSync(PipelineStage.COLOR_ATTACHMENT_OUTPUT, AccessFlags.COLOR_ATTACHMENT_WRITE,
                    PipelineStage.BOTTOM_OF_PIPE, null);
        }
        return switch (newLayout) {
            case COLOR_ATTACHMENT -> new BarrierSync(
                    oldLayout == TextureLayout.PRESENT_SRC
                            ? PipelineStage.COLOR_ATTACHMENT_OUTPUT
                            : readStageOf(oldLayout, computePass),
                    oldLayout == TextureLayout.PRESENT_SRC ? AccessFlags.COLOR_ATTACHMENT_WRITE : readAccessOf(oldLayout),
                    PipelineStage.COLOR_ATTACHMENT_OUTPUT, AccessFlags.COLOR_ATTACHMENT_WRITE);
            case DEPTH_STENCIL_ATTACHMENT -> new BarrierSync(readStageOf(oldLayout, computePass),
                    readAccessOf(oldLayout),
                    PipelineStage.EARLY_FRAGMENT_TESTS, AccessFlags.DEPTH_STENCIL_ATTACHMENT_WRITE);
            case SHADER_READ_ONLY -> {
                PipelineStage dst = computePass ? PipelineStage.COMPUTE_SHADER : PipelineStage.FRAGMENT_SHADER;
                yield switch (oldLayout) {
                    case COLOR_ATTACHMENT -> new BarrierSync(PipelineStage.COLOR_ATTACHMENT_OUTPUT,
                            AccessFlags.COLOR_ATTACHMENT_WRITE, dst, AccessFlags.SHADER_READ);
                    case DEPTH_STENCIL_ATTACHMENT -> new BarrierSync(PipelineStage.LATE_FRAGMENT_TESTS,
                            AccessFlags.DEPTH_STENCIL_ATTACHMENT_WRITE, dst, AccessFlags.SHADER_READ);
                    case DEPTH_STENCIL_READ_ONLY -> new BarrierSync(
                            computePass ? PipelineStage.COMPUTE_SHADER : PipelineStage.FRAGMENT_SHADER,
                            AccessFlags.SHADER_READ, dst, AccessFlags.SHADER_READ);
                    default -> coarse(oldLayout, newLayout);
                };
            }
            default -> coarse(oldLayout, newLayout);
        };
    }

    /** 兜底：粗粒度全同步（正确性优先） */
    private static BarrierSync coarse(TextureLayout oldLayout, TextureLayout newLayout) {
        return new BarrierSync(PipelineStage.ALL_COMMANDS, null,
                PipelineStage.ALL_COMMANDS, null);
    }

    private static PipelineStage readStageOf(TextureLayout layout, boolean computePass) {
        return switch (layout) {
            case SHADER_READ_ONLY, DEPTH_STENCIL_READ_ONLY ->
                    computePass ? PipelineStage.COMPUTE_SHADER : PipelineStage.FRAGMENT_SHADER;
            case TRANSFER_SRC -> PipelineStage.TRANSFER;
            default -> PipelineStage.ALL_COMMANDS;
        };
    }

    private static AccessFlags readAccessOf(TextureLayout layout) {
        return switch (layout) {
            case SHADER_READ_ONLY, DEPTH_STENCIL_READ_ONLY -> AccessFlags.SHADER_READ;
            case TRANSFER_SRC -> AccessFlags.TRANSFER_READ;
            default -> null;
        };
    }

    /** RHI 布局 → VkImageLayout（Vulkan 规范取值） */
    public static long toVkImageLayout(TextureLayout layout) {
        return switch (layout) {
            case UNDEFINED -> 0;                          // VK_IMAGE_LAYOUT_UNDEFINED
            case GENERAL -> 1;                            // VK_IMAGE_LAYOUT_GENERAL
            case COLOR_ATTACHMENT -> 2;                   // VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
            case DEPTH_STENCIL_ATTACHMENT -> 3;           // VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
            case DEPTH_STENCIL_READ_ONLY -> 4;            // VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
            case SHADER_READ_ONLY -> 5;                   // VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
            case TRANSFER_SRC -> 6;                       // VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
            case TRANSFER_DST -> 7;                       // VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
            case PRESENT_SRC -> 1000001002L;              // VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
        };
    }

    /** RHI 管线阶段 → VkPipelineStageFlagBits（Vulkan 规范位值） */
    public static long toVkStage(PipelineStage stage) {
        return switch (stage) {
            case TOP_OF_PIPE -> 0x00000001L;
            case DRAW_INDIRECT -> 0x00000002L;
            case VERTEX_INPUT -> 0x00000004L;
            case VERTEX_SHADER -> 0x00000008L;
            case TESSELLATION_CONTROL_SHADER -> 0x00000010L;
            case TESSELLATION_EVALUATION_SHADER -> 0x00000020L;
            case GEOMETRY_SHADER -> 0x00000040L;
            case FRAGMENT_SHADER -> 0x00000080L;
            case EARLY_FRAGMENT_TESTS -> 0x00000100L;
            case LATE_FRAGMENT_TESTS -> 0x00000200L;
            case COLOR_ATTACHMENT_OUTPUT -> 0x00000400L;
            case COMPUTE_SHADER -> 0x00000800L;
            case TRANSFER -> 0x00001000L;
            case BOTTOM_OF_PIPE -> 0x00002000L;
            case HOST -> 0x00004000L;
            case ALL_GRAPHICS -> 0x00008000L;
            case ALL_COMMANDS -> 0x00010000L;
        };
    }

    /** RHI 访问标志 → VkAccessFlagBits（Vulkan 规范位值）；null → 0 */
    public static long toVkAccess(AccessFlags access) {
        if (access == null) {
            return 0;
        }
        return switch (access) {
            case INDIRECT_COMMAND_READ -> 0x00000001L;
            case INDEX_READ -> 0x00000002L;
            case VERTEX_ATTRIBUTE_READ -> 0x00000004L;
            case UNIFORM_READ -> 0x00000008L;
            case INPUT_ATTACHMENT_READ -> 0x00000010L;
            case SHADER_READ -> 0x00000020L;
            case SHADER_WRITE -> 0x00000040L;
            case COLOR_ATTACHMENT_READ -> 0x00000080L;
            case COLOR_ATTACHMENT_WRITE -> 0x00000100L;
            case DEPTH_STENCIL_ATTACHMENT_READ -> 0x00000200L;
            case DEPTH_STENCIL_ATTACHMENT_WRITE -> 0x00000400L;
            case TRANSFER_READ -> 0x00000800L;
            case TRANSFER_WRITE -> 0x00001000L;
            case HOST_READ -> 0x00002000L;
            case HOST_WRITE -> 0x00004000L;
            case MEMORY_READ -> 0x00008000L;
            case MEMORY_WRITE -> 0x00010000L;
        };
    }
}
