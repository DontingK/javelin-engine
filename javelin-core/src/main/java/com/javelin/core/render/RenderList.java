package com.javelin.core.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 渲染列表 —— 一帧内单个相机视图的统一渲染命令容器。
 *
 * <p>RenderList 是引擎层渲染流程的核心数据结构，职责：
 * <ul>
 *   <li><b>收集</b>：遍历 {@link Renderable}，收集 {@link RenderCommand}</li>
 *   <li><b>剔除</b>：视锥剔除 + 层级掩码剔除（预留）</li>
 *   <li><b>排序</b>：按 {@link RenderLayer} + 深度 + pipeline + vb 排序</li>
 *   <li><b>分组</b>：按 RenderLayer 分三个子列表（OPAQUE / TRANSPARENT / OVERLAY）</li>
 * </ul>
 *
 * <p><strong>生命周期</strong>：每帧每个相机创建一个 RenderList，渲染完成后丢弃。
 * 不跨帧持有，避免状态同步问题。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class RenderList {

    // ═══════════════════════════════════════════════════════════════════════
    //  输入参数
    // ═══════════════════════════════════════════════════════════════════════

    /** 相机世界空间位置（用于计算距离） */
    private final Vector3f cameraPosition;

    /** 相机近裁剪面 */
    private final float nearPlane;

    /** 相机远裁剪面 */
    private final float farPlane;

    // ═══════════════════════════════════════════════════════════════════════
    //  命令存储
    // ═══════════════════════════════════════════════════════════════════════

    /** 所有通过剔除的命令（排序前） */
    private final ArrayList<RenderCommand> allCommands = new ArrayList<>();

    /** OPAQUE 层命令（排序后） */
    private final ArrayList<RenderCommand> opaqueCommands = new ArrayList<>();

    /** TRANSPARENT 层命令（排序后） */
    private final ArrayList<RenderCommand> transparentCommands = new ArrayList<>();

    /** OVERLAY 层命令（排序后） */
    private final ArrayList<RenderCommand> overlayCommands = new ArrayList<>();

    /** 统计信息 */
    private int culledCount = 0;

    // ═══════════════════════════════════════════════════════════════════════
    //  构造
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 构造渲染列表。
     */
    public RenderList() {
        this(new Vector3f(), 0.1f, 1000.0f);
    }

    /**
     * 构造渲染列表。
     *
     * @param cameraPosition 相机世界空间位置
     * @param nearPlane      近裁剪面
     * @param farPlane       远裁剪面
     */
    public RenderList(Vector3f cameraPosition, float nearPlane, float farPlane) {
        this.cameraPosition = new Vector3f(cameraPosition);
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  收集
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 从可渲染对象列表收集命令。
     *
     * <p>遍历所有 Renderable，对每个调用 {@link Renderable#collect} 生成命令。
     * 当前版本不做剔除（保守策略，全部收集）。</p>
     *
     * @param renderables 场景中的可渲染对象列表
     */
    public void collectFrom(List<Renderable> renderables) {
        allCommands.clear();
        culledCount = 0;

        for (Renderable renderable : renderables) {
            renderable.collect(allCommands);
        }
    }

    /**
     * 直接添加单个命令（用于程序化生成命令）。
     *
     * @param cmd 渲染命令
     */
    public void addCommand(RenderCommand cmd) {
        if (cmd != null) {
            allCommands.add(cmd);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  排序
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 对所有命令排序并分组到三个子列表。
     *
     * <p>排序策略：
     * <ul>
     *   <li>OPAQUE：按前→后排序（Early-Z），depth 升序</li>
     *   <li>TRANSPARENT：按后→前排序（混合正确性），depth 降序</li>
     *   <li>OVERLAY：按提交顺序</li>
     * </ul>
     *
     * <p>同层内按 pipeline 和 vertexBuffer 排序以优化合批。</p>
     */
    public void sort() {
        opaqueCommands.clear();
        transparentCommands.clear();
        overlayCommands.clear();

        // 按层分组
        for (RenderCommand cmd : allCommands) {
            switch (cmd.renderLayer) {
                case OPAQUE -> opaqueCommands.add(cmd);
                case TRANSPARENT -> transparentCommands.add(cmd);
                case OVERLAY -> overlayCommands.add(cmd);
            }
        }

        // 对每层排序
        sortOpaque(opaqueCommands);
        sortTransparent(transparentCommands);
        // OVERLAY 保持提交顺序
    }

    /**
     * 不透明物体排序：前→后（Early-Z），同深度按 pipeline + vb 合批。
     */
    private void sortOpaque(ArrayList<RenderCommand> commands) {
        commands.sort((a, b) -> {
            // 1. 按距离排序（前→后）
            float distA = estimateDistance(a);
            float distB = estimateDistance(b);
            int distCompare = Float.compare(distA, distB);
            if (distCompare != 0) {
                return distCompare;
            }

            // 2. 同距离按 pipeline 排序（合批）
            long pipeA = a.pipeline != null ? a.pipeline.handle() : 0;
            long pipeB = b.pipeline != null ? b.pipeline.handle() : 0;
            int pipeCompare = Long.compare(pipeA, pipeB);
            if (pipeCompare != 0) {
                return pipeCompare;
            }

            // 3. 同 pipeline 按 vertexBuffer 排序
            long vbA = a.vertexBuffer != null ? a.vertexBuffer.handle() : 0;
            long vbB = b.vertexBuffer != null ? b.vertexBuffer.handle() : 0;
            return Long.compare(vbA, vbB);
        });
    }

    /**
     * 透明物体排序：后→前（混合正确性），同深度按 pipeline + vb 合批。
     */
    private void sortTransparent(ArrayList<RenderCommand> commands) {
        commands.sort((a, b) -> {
            // 1. 按距离排序（后→前）
            float distA = estimateDistance(a);
            float distB = estimateDistance(b);
            int distCompare = Float.compare(distB, distA); // 反转
            if (distCompare != 0) {
                return distCompare;
            }

            // 2. 同距离按 pipeline 排序
            long pipeA = a.pipeline != null ? a.pipeline.handle() : 0;
            long pipeB = b.pipeline != null ? b.pipeline.handle() : 0;
            int pipeCompare = Long.compare(pipeA, pipeB);
            if (pipeCompare != 0) {
                return pipeCompare;
            }

            // 3. 同 pipeline 按 vertexBuffer 排序
            long vbA = a.vertexBuffer != null ? a.vertexBuffer.handle() : 0;
            long vbB = b.vertexBuffer != null ? b.vertexBuffer.handle() : 0;
            return Long.compare(vbA, vbB);
        });
    }

    /**
     * 估算命令到相机的距离。
     * <p>使用模型矩阵的平移分量作为世界空间位置。</p>
     */
    private float estimateDistance(RenderCommand cmd) {
        float wx = cmd.modelMatrix.m30();
        float wy = cmd.modelMatrix.m31();
        float wz = cmd.modelMatrix.m32();

        float dx = wx - cameraPosition.x;
        float dy = wy - cameraPosition.y;
        float dz = wz - cameraPosition.z;

        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  访问器
    // ═══════════════════════════════════════════════════════════════════════

    public List<RenderCommand> getOpaqueCommands() {
        return Collections.unmodifiableList(opaqueCommands);
    }

    public List<RenderCommand> getTransparentCommands() {
        return Collections.unmodifiableList(transparentCommands);
    }

    public List<RenderCommand> getOverlayCommands() {
        return Collections.unmodifiableList(overlayCommands);
    }

    public List<RenderCommand> getAllCommands() {
        return Collections.unmodifiableList(allCommands);
    }

    public int getVisibleCount() {
        return allCommands.size();
    }

    public int getCulledCount() {
        return culledCount;
    }

    /**
     * 清空所有数据（用于对象池复用）。
     */
    public void clear() {
        allCommands.clear();
        opaqueCommands.clear();
        transparentCommands.clear();
        overlayCommands.clear();
        culledCount = 0;
    }
}
