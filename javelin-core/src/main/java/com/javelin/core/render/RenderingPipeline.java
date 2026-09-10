package com.javelin.core.render;

import com.javelin.rhi.api.CommandBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 引擎侧渲染管线 —— 按顺序调度多个 {@link RenderPass} 完成一帧渲染。
 *
 * <p>支持多相机渲染，每个相机独立构建 {@link CameraContext} 和 {@link RenderList}。
 * 未来可扩展为并行录制：每个相机分配独立 {@link CommandBuffer}，多线程执行后合并。</p>
 *
 * <p>渲染流程：
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
    private int fallbackWidth = 1280;
    private int fallbackHeight = 720;

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

    /**
     * 设置后备显示尺寸。
     */
    public void setFallbackSize(int width, int height) {
        this.fallbackWidth = width;
        this.fallbackHeight = height;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  核心渲染
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 渲染单相机一帧。
     *
     * @param renderables 可渲染对象列表
     * @param cameraPos   相机世界位置
     * @param viewMatrix  视图矩阵
     * @param projMatrix  投影矩阵
     * @param viewportW   视口宽度
     * @param viewportH   视口高度
     * @param deltaTime   帧间隔（秒）
     * @param cmd         RHI 命令缓冲区
     */
    public void render(List<Renderable> renderables,
                       Vector3f cameraPos,
                       Matrix4f viewMatrix,
                       Matrix4f projMatrix,
                       int viewportW, int viewportH,
                       float deltaTime,
                       CommandBuffer cmd) {
        // 更新全局时间
        totalTime += deltaTime;
        frameCounter++;

        // 构建帧全局上下文
        FrameContext frameCtx = new FrameContext(deltaTime, totalTime, frameCounter,
                                                  fallbackWidth, fallbackHeight);

        // 构建相机上下文
        CameraContext cameraCtx = buildCameraContext(renderables, cameraPos,
                                                      viewMatrix, projMatrix,
                                                      viewportW, viewportH);

        // 执行所有 Pass
        executePasses(cameraCtx, frameCtx, cmd);
    }

    /**
     * 多相机渲染 —— 每个相机独立构建上下文，顺序执行（未来可并行）。
     *
     * @param renderables 可渲染对象列表
     * @param cameras     相机参数列表
     * @param deltaTime   帧间隔（秒）
     * @param cmd         RHI 命令缓冲区（主命令缓冲区）
     */
    public void renderMultiCamera(List<Renderable> renderables,
                                   List<CameraParams> cameras,
                                   float deltaTime,
                                   CommandBuffer cmd) {
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
                                                  fallbackWidth, fallbackHeight);

        for (int i = 0; i < cameras.size(); i++) {
            CameraParams cam = cameras.get(i);
            CameraContext cameraCtx = buildCameraContext(renderables,
                                                          cam.cameraPos,
                                                          cam.viewMatrix,
                                                          cam.projMatrix,
                                                          cam.viewportW,
                                                          cam.viewportH);

            log.debug("Rendering camera {}/{}: {} commands",
                      i + 1, cameras.size(),
                      cameraCtx.getRenderList().getVisibleCount());

            executePasses(cameraCtx, frameCtx, cmd);
        }
    }

    /**
     * 简化版单相机渲染（使用默认相机参数）。
     */
    public void render(List<Renderable> renderables, CommandBuffer cmd, float deltaTime) {
        render(renderables,
               new Vector3f(0, 0, 5),
               new Matrix4f().lookAt(0, 0, 5, 0, 0, 0, 0, 1, 0),
               new Matrix4f().perspective((float) Math.toRadians(60), 16.0f / 9.0f, 0.1f, 1000.0f),
               fallbackWidth, fallbackHeight,
               deltaTime,
               cmd);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  内部方法
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 构建相机上下文和渲染列表。
     */
    private CameraContext buildCameraContext(List<Renderable> renderables,
                                              Vector3f cameraPos,
                                              Matrix4f viewMatrix,
                                              Matrix4f projMatrix,
                                              int viewportW, int viewportH) {
        CameraContext ctx = new CameraContext();
        ctx.setCameraPosition(cameraPos);
        ctx.setViewMatrix(viewMatrix);
        ctx.setProjectionMatrix(projMatrix);
        ctx.setViewportSize(viewportW, viewportH);

        // 构建渲染列表
        RenderList renderList = new RenderList(cameraPos, 0.1f, 1000.0f);
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
    private void executePasses(CameraContext cameraCtx, FrameContext frameCtx, CommandBuffer cmd) {
        for (RenderPass pass : passes) {
            cameraCtx.setPassName(pass.getName());
            try {
                pass.execute(cameraCtx, frameCtx, cmd);
            } catch (Exception e) {
                log.error("RenderPass {} failed", pass.getName(), e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  相机参数（用于多相机渲染）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 相机参数 —— 多相机渲染时传入。
     */
    public static class CameraParams {
        public final Vector3f cameraPos;
        public final Matrix4f viewMatrix;
        public final Matrix4f projMatrix;
        public final int viewportW;
        public final int viewportH;

        public CameraParams(Vector3f cameraPos, Matrix4f viewMatrix, Matrix4f projMatrix,
                            int viewportW, int viewportH) {
            this.cameraPos = new Vector3f(cameraPos);
            this.viewMatrix = new Matrix4f(viewMatrix);
            this.projMatrix = new Matrix4f(projMatrix);
            this.viewportW = viewportW;
            this.viewportH = viewportH;
        }
    }
}
