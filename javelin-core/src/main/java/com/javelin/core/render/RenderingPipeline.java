package com.javelin.core.render;

import com.javelin.core.scene.component.Camera;
import com.javelin.rhi.api.*;
import com.javelin.rhi.types.*;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 引擎侧渲染管线 —— 按顺序调度多个 {@link RenderPass} 完成一帧渲染。
 *
 * <p>支持多相机渲染，每个相机独立构建 {@link CameraContext} 和 {@link RenderList}。
 * 未来可扩展为并行录制：每个相机分配独立 {@link CommandBuffer}，多线程执行后合并。</p>
 *
 * <p>渲染流程：</p>
 * <pre>
 *   // 1. 构建帧全局上下文（一次）
 *   FrameContext frameCtx = new FrameContext(deltaTime, ...);
 *
 *   // 2. 对每个相机（可并行）
 *   for each camera:
 *       CameraContext cameraCtx = buildCameraContext(camera, renderables);
 *       for each pass in pipeline:
 *           pass.execute(cameraCtx, frameCtx, cmd);
 * </pre>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class RenderingPipeline {

    private static final Logger log = LoggerFactory.getLogger(RenderingPipeline.class);

    private final List<RenderPass> passes = new ArrayList<>();

    private long frameCounter = 0;
    private float totalTime = 0;

    /**
     * 构造空渲染管线（无默认 Pass）。
     */
    public RenderingPipeline() {
    }

    /**
     * 添加 Pass 到管线末尾。
     *
     * @param pass 渲染 Pass
     */
    public void addPass(RenderPass pass) {
        if (pass == null) {
            throw new NullPointerException("pass is null");
        }
        passes.add(pass);
    }

    /**
     * 获取当前所有 Pass 的只读视图。
     */
    public List<RenderPass> getPasses() {
        return Collections.unmodifiableList(passes);
    }

    /**
     * 清空所有 Pass。
     */
    public void clearPasses() {
        passes.clear();
    }



    // ═══════════════════════════════════════════════════════════════════════
    //  核心渲染
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 多相机渲染 —— 每个相机独立构建上下文，顺序执行（未来可并行）。
     *
     * @param renderables 可渲染对象列表
     * @param cameras     场景相机列表
     * @param deltaTime   帧间隔（秒）
     * @param cmd         RHI 命令缓冲区（主命令缓冲区）
     */
    public void render(List<Renderable> renderables,
                       List<Camera> cameras,
                       float deltaTime,
                       int windowWidth, int windowHeight,
                       CommandBuffer cmd,
                       TextureView swapChainImageView) {
        if (cameras == null || cameras.isEmpty()) {
            return;
        }
        if (passes.isEmpty()) {
            log.warn("No render passes configured");
            return;
        }

        totalTime += deltaTime;
        frameCounter++;

        FrameContext frameCtx = new FrameContext(deltaTime, totalTime, frameCounter,
                windowWidth, windowHeight);
        for (Camera camera : cameras) {
            if (camera == null || !camera.isActivated()) {
                log.warn("RenderingPipeline.render: skipping inactive/null camera");
                continue;
            }

            log.info("RenderingPipeline.render: building camera context for camera={}", camera);
            CameraContext cameraCtx = buildCameraContext(renderables, camera, windowWidth, windowHeight);
            executePasses(cameraCtx, frameCtx, cmd, swapChainImageView);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 使用 Camera 组件构建相机上下文和渲染列表。
     */
    private CameraContext buildCameraContext(List<Renderable> renderables, Camera camera, int windowWidth, int windowHeight) {
        CameraContext ctx = new CameraContext();
        ctx.setCameraPosition(camera.getWorldPosition());
        ctx.setViewMatrix(camera.getViewMatrix());

        // 从相机的视口比例计算像素坐标
        int x = (int) (camera.getViewportLeft() * windowWidth);
        int y = (int) (camera.getViewportTop() * windowHeight);
        int w = (int) (camera.getViewportRight() * windowWidth) - x;
        int h = (int) (camera.getViewportBottom() * windowHeight) - y;
        ctx.setViewport(x, y, w, h);

        // 计算宽高比并设置投影矩阵
        float aspectRatio = w > 0 && h > 0 ? (float) w / h : (float) windowWidth / windowHeight;
        ctx.setProjectionMatrix(camera.getProjectionMatrix(aspectRatio));

        // 设置清屏颜色
        float[] clearColor = camera.getClearColor();
        if (clearColor != null && clearColor.length >= 4) {
            ctx.setClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        }

        // 构建渲染列表
        RenderList renderList = new RenderList(ctx.getCameraPosition(), camera.getNearClip(), camera.getFarClip());
        if (renderables != null) {
            renderList.collectFrom(renderables);
            renderList.sort();
        }
        ctx.setRenderList(renderList);

        return ctx;
    }

    /**
     * 对给定相机上下文执行所有 Pass。
     */
    private void executePasses(CameraContext cameraCtx, FrameContext frameCtx, CommandBuffer cmd,
                               TextureView swapChainImageView) {
        // 统一设置视口（所有 Pass 共享同一视口）
        cmd.setViewport(cameraCtx.getViewportX(), cameraCtx.getViewportY(),
                cameraCtx.getViewportWidth(), cameraCtx.getViewportHeight(),
                0.0f, 1.0f);

        // 设置裁剪区域与视口一致
        cmd.setScissor(cameraCtx.getViewportX(), cameraCtx.getViewportY(),
                cameraCtx.getViewportWidth(), cameraCtx.getViewportHeight());

        // 动态渲染：beginRendering
        boolean renderingStarted = false;
        if (swapChainImageView != null) {
            float[] cc = cameraCtx.getClearColor();
            CommandBuffer.ClearValue clearValue = (cc != null && cc.length >= 4)
                    ? new CommandBuffer.ClearValue(cc[0], cc[1], cc[2], cc[3])
                    : new CommandBuffer.ClearValue(0.0f, 0.0f, 0.0f, 1.0f);

            // 转换交换链图像布局从 UNDEFINED 到 COLOR_ATTACHMENT_OPTIMAL
            cmd.pipelineBarrier(
                PipelineStage.TOP_OF_PIPE, PipelineStage.COLOR_ATTACHMENT_OUTPUT,
                DependencyFlags.NONE,
                null, null,
                new CommandBuffer.TextureMemoryBarrier[]{
                    new CommandBuffer.TextureMemoryBarrier(
                        PipelineStage.TOP_OF_PIPE, PipelineStage.COLOR_ATTACHMENT_OUTPUT,
                        AccessFlags.NONE, AccessFlags.COLOR_ATTACHMENT_WRITE,
                        TextureLayout.UNDEFINED, TextureLayout.COLOR_ATTACHMENT_OPTIMAL,
                        -1, -1,
                        swapChainImageView.texture(),
                        new CommandBuffer.TextureSubresourceRange(
                            com.javelin.rhi.desc.TextureViewDesc.AspectFlags.COLOR,
                            0, 1, 0, 1
                        )
                    )
                }
            );

            CommandBuffer.RenderingAttachmentInfo colorAttachment = new CommandBuffer.RenderingAttachmentInfo(
                    swapChainImageView,
                    TextureLayout.COLOR_ATTACHMENT_OPTIMAL,
                    AttachmentLoadOp.CLEAR,
                    AttachmentStoreOp.STORE,
                    clearValue,
                    null,
                    null
            );

            CommandBuffer.RenderingInfo renderingInfo = new CommandBuffer.RenderingInfo(
                    new CommandBuffer.Rect2D(cameraCtx.getViewportX(), cameraCtx.getViewportY(),
                            cameraCtx.getViewportWidth(), cameraCtx.getViewportHeight()),
                    1,
                    new CommandBuffer.RenderingAttachmentInfo[]{colorAttachment},
                    null,
                    null
            );

            cmd.beginRendering(renderingInfo);
            renderingStarted = true;
        }

        try {
            for (RenderPass pass : passes) {
                cameraCtx.setPassName(pass.getName());
                try {
                    pass.execute(cameraCtx, frameCtx, cmd);
                } catch (Exception e) {
                    log.error("RenderPass {} failed", pass.getName(), e);
                }
            }
        } finally {
            if (renderingStarted) {
                cmd.endRendering();
                // 转换交换链图像布局从 COLOR_ATTACHMENT_OPTIMAL 到 PRESENT_SRC
                cmd.pipelineBarrier(
                    PipelineStage.COLOR_ATTACHMENT_OUTPUT, PipelineStage.BOTTOM_OF_PIPE,
                    DependencyFlags.NONE,
                    null, null,
                    new CommandBuffer.TextureMemoryBarrier[]{
                        new CommandBuffer.TextureMemoryBarrier(
                            PipelineStage.COLOR_ATTACHMENT_OUTPUT, PipelineStage.BOTTOM_OF_PIPE,
                            AccessFlags.COLOR_ATTACHMENT_WRITE, AccessFlags.NONE,
                            TextureLayout.COLOR_ATTACHMENT_OPTIMAL, TextureLayout.PRESENT_SRC,
                            -1, -1,
                            swapChainImageView.texture(),
                            new CommandBuffer.TextureSubresourceRange(
                                com.javelin.rhi.desc.TextureViewDesc.AspectFlags.COLOR,
                                0, 1, 0, 1
                            )
                        )
                    }
                );
            }
        }
    }
}
