package com.javelin.core.render.pass;

import com.javelin.core.render.*;
import com.javelin.rhi.api.CommandBuffer;
import com.javelin.rhi.api.GraphicsPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 前向渲染 Pass —— 基础光照渲染，支持不透明和透明物体。
 *
 * <p>遍历 {@link RenderList} 中已排序的命令，按顺序提交到 GPU。
 * 渲染顺序：OPAQUE → TRANSPARENT → OVERLAY。</p>
 *
 * <p>当前实现为简化版，未来可扩展：
 * <ul>
 *   <li>多光源 Forward+ 分块渲染</li>
 *   <li>材质系统（PBR / 自定义 Shader）</li>
 *   <li>阴影贴图采样</li>
 *   <li>环境光照（IBL）</li>
 * </ul>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class ForwardRenderPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(ForwardRenderPass.class);

    @Override
    public void execute(CameraContext cameraContext, FrameContext frameContext, CommandBuffer cmd) {
        // 注：清屏操作（beginRendering）由 RenderingPipeline 在更上层统一处理，
        // 因为需要访问当前交换链图像。Pass 只负责绘制命令。

        RenderList renderList = cameraContext.getRenderList();
        if (renderList == null) {
            return;
        }

        // 渲染 OPAQUE
        List<RenderCommand> opaqueCommands = renderList.getOpaqueCommands();
        if (!opaqueCommands.isEmpty()) {
            for (RenderCommand command : opaqueCommands) {
                drawCommand(cmd, command);
            }
        }

        // 渲染 TRANSPARENT
        List<RenderCommand> transparentCommands = renderList.getTransparentCommands();
        if (!transparentCommands.isEmpty()) {
            for (RenderCommand command : transparentCommands) {
                drawCommand(cmd, command);
            }
        }

        // 渲染 OVERLAY
        List<RenderCommand> overlayCommands = renderList.getOverlayCommands();
        if (!overlayCommands.isEmpty()) {
            for (RenderCommand command : overlayCommands) {
                drawCommand(cmd, command);
            }
        }
    }

    /**
     * 提交单个绘制命令到命令缓冲区。
     */
    private void drawCommand(CommandBuffer cmd, RenderCommand command) {
        if (command == null) {
            return;
        }

        // 绑定管线
        if (command.pipeline != null) {
            cmd.bindPipeline(command.pipeline);
        }

        // 绑定顶点缓冲
        if (command.vertexBuffer != null) {
            cmd.bindVertexBuffer(0, command.vertexBuffer, command.vertexOffset);
        }

        // 绑定索引缓冲
        if (command.indexBuffer != null) {
            cmd.bindIndexBuffer(command.indexBuffer, command.indexOffset, command.indexType);
        }

        // 绑定描述符集
        if (command.descriptorSets != null && command.descriptorSets.length > 0) {
            // TODO: 需要 PipelineLayout 参数，当前 API 需要调整
            // cmd.bindDescriptorSets(PipelineBindPoint.GRAPHICS, pipelineLayout, 0, command.descriptorSets, command.dynamicOffsets);
        }

        // 绘制
        if (command.isIndexed()) {
            cmd.drawIndexed(command.indexCount, command.instanceCount, 0, 0, 0);
        } else {
            cmd.draw(command.vertexCount, command.instanceCount, 0, 0);
        }
    }
}
