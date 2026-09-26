package com.javelin.editor.mesh;

import com.javelin.core.render.mesh.Mesh;
import com.javelin.rhi.types.PrimitiveTopology;

/**
 * 编辑器网格辅助线生成器（engine2 GridMesh 移植，2026-09-26）。
 *
 * <p>在 XZ 平面生成以原点为中心、等间距分布的正交线段网格，
 * 供 {@code EditGridAppState} 三层网格（step = 1 / 10 / 100）复用。
 * 编辑器辅助显示用：运行时代码生成、非资产、不序列化。</p>
 *
 * <p>引擎绘制为索引路径（MeshUpload 强制索引），线段走 LINE_LIST 拓扑 +
 * 全序索引（每条线 2 个独立顶点，共享无意义）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class GridMesh {

    private GridMesh() {
    }

    /**
     * 生成网格线 Mesh。
     *
     * @param size      网格总尺寸（世界单位，线距 = size / divisions）
     * @param divisions 分割数（每方向格子数，线数 = divisions + 1）
     * @return 已填 POSITION 属性 + 全序索引 + LINE_LIST 拓扑的 Mesh
     */
    public static Mesh build(float size, int divisions) {
        if (size <= 0) {
            throw new IllegalArgumentException("网格尺寸必须大于 0: " + size);
        }
        if (divisions < 1) {
            throw new IllegalArgumentException("分割数必须至少为 1: " + divisions);
        }

        float halfSize = size / 2.0f;
        float step = size / divisions;
        int lineCount = divisions + 1;

        // 每方向 lineCount 条线、每条线 2 个顶点、共两个方向
        int vertexCount = lineCount * 2 * 2;
        float[] positions = new float[vertexCount * 3];
        int idx = 0;

        // X 方向平行线（固定 x，z 从 -halfSize 到 +halfSize）
        for (int i = 0; i < lineCount; i++) {
            float x = -halfSize + i * step;
            positions[idx++] = x;
            positions[idx++] = 0.0f;
            positions[idx++] = -halfSize;

            positions[idx++] = x;
            positions[idx++] = 0.0f;
            positions[idx++] = halfSize;
        }

        // Z 方向平行线（固定 z，x 从 -halfSize 到 +halfSize）
        for (int i = 0; i < lineCount; i++) {
            float z = -halfSize + i * step;
            positions[idx++] = -halfSize;
            positions[idx++] = 0.0f;
            positions[idx++] = z;

            positions[idx++] = halfSize;
            positions[idx++] = 0.0f;
            positions[idx++] = z;
        }

        Mesh mesh = new Mesh();
        mesh.setPositions(positions);
        int[] indices = new int[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            indices[i] = i;
        }
        mesh.setIndices(indices);
        mesh.setTopology(PrimitiveTopology.LINE_LIST);
        return mesh;
    }
}
