package com.javelin.core.render.pass;

import com.javelin.core.render.*;
import com.javelin.rhi.api.CommandBuffer;
import com.javelin.rhi.api.GraphicsPipeline;
import com.javelin.rhi.types.ShaderStage;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
            log.warn("ForwardRenderPass.execute: renderList is null");
            return;
        }

        log.info("ForwardRenderPass.execute: opaqueCommands={}, transparentCommands={}, overlayCommands={}",
                renderList.getOpaqueCommands().size(),
                renderList.getTransparentCommands().size(),
                renderList.getOverlayCommands().size());

        // 预计算 VP 矩阵（view * proj 已在 CameraContext 中准备好）
        Matrix4f viewMatrix = cameraContext.getViewMatrix();
        Matrix4f projMatrix = cameraContext.getProjectionMatrix();
        Matrix4f vpMatrix = new Matrix4f(projMatrix).mul(viewMatrix);

        // 渲染 OPAQUE
        List<RenderCommand> opaqueCommands = renderList.getOpaqueCommands();
        if (!opaqueCommands.isEmpty()) {
            log.info("ForwardRenderPass.execute: drawing {} opaque commands", opaqueCommands.size());
            for (RenderCommand command : opaqueCommands) {
                drawCommand(cmd, command, vpMatrix);
            }
        }

        // 渲染 TRANSPARENT
        List<RenderCommand> transparentCommands = renderList.getTransparentCommands();
        if (!transparentCommands.isEmpty()) {
            log.info("ForwardRenderPass.execute: drawing {} transparent commands", transparentCommands.size());
            for (RenderCommand command : transparentCommands) {
                drawCommand(cmd, command, vpMatrix);
            }
        }

        // 渲染 OVERLAY
        List<RenderCommand> overlayCommands = renderList.getOverlayCommands();
        if (!overlayCommands.isEmpty()) {
            log.info("ForwardRenderPass.execute: drawing {} overlay commands", overlayCommands.size());
            for (RenderCommand command : overlayCommands) {
                drawCommand(cmd, command, vpMatrix);
            }
        }
    }

    /**
     * 提交单个绘制命令到命令缓冲区。
     */
    private void drawCommand(CommandBuffer cmd, RenderCommand command, Matrix4f vpMatrix) {
        if (command == null) {
            log.warn("ForwardRenderPass.drawCommand: command is null");
            return;
        }

        log.info("ForwardRenderPass.drawCommand: pipeline={}, vertexBuffer={}, indexBuffer={}, vertexCount={}, indexCount={}, indexed={}",
                command.pipeline, command.vertexBuffer, command.indexBuffer,
                command.vertexCount, command.indexCount, command.isIndexed());

        // 绑定管线
        if (command.pipeline != null) {
            cmd.bindPipeline(command.pipeline);
        }

        // 绑定顶点缓冲
        if (command.vertexBuffer != null) {
            cmd.bindVertexBuffer(0, command.vertexBuffer, command.vertexOffset);
        } else {
            log.warn("ForwardRenderPass.drawCommand: vertexBuffer is null");
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

        // 临时方案：通过 push constant 传入 MVP 矩阵
        if (command.pipelineLayout != null) {
            Matrix4f mvp = new Matrix4f(vpMatrix).mul(command.modelMatrix);
            byte[] mvpBytes = new byte[64];
            ByteBuffer bb = ByteBuffer.wrap(mvpBytes).order(ByteOrder.LITTLE_ENDIAN);
            bb.putFloat(mvp.m00()).putFloat(mvp.m01()).putFloat(mvp.m02()).putFloat(mvp.m03());
            bb.putFloat(mvp.m10()).putFloat(mvp.m11()).putFloat(mvp.m12()).putFloat(mvp.m13());
            bb.putFloat(mvp.m20()).putFloat(mvp.m21()).putFloat(mvp.m22()).putFloat(mvp.m23());
            bb.putFloat(mvp.m30()).putFloat(mvp.m31()).putFloat(mvp.m32()).putFloat(mvp.m33());
            cmd.pushConstants(command.pipelineLayout, ShaderStage.VERTEX, 0, mvpBytes);
            log.info("ForwardRenderPass.drawCommand: pushConstants MVP matrix");
        }

        // 绘制
        if (command.isIndexed()) {
            log.info("ForwardRenderPass.drawCommand: drawIndexed({}, {}, 0, 0, 0)", command.indexCount, command.instanceCount);
            cmd.drawIndexed(command.indexCount, command.instanceCount, 0, 0, 0);
        } else {
            log.info("ForwardRenderPass.drawCommand: draw({}, {}, 0, 0)", command.vertexCount, command.instanceCount);
            cmd.draw(command.vertexCount, command.instanceCount, 0, 0);
        }
    }
}
