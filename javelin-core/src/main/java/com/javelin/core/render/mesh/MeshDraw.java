package com.javelin.core.render.mesh;

import com.javelin.rhi.CommandBuffer;

/**
 * 网格绘制桥接：把 Mesh 的 GPU 缓冲消费封在包内（用户侧零 GPU 暴露）。
 *
 * <p>Pass 回调的标准序列（Vulkan 合法序，与直写等价）：</p>
 * <pre>
 * bindPipeline(...)              // 管线侧（PSO 键取 mesh.layout()，公开元数据）
 * bindBuffers(cmd, mesh)         // 顶点/索引缓冲绑定
 * 材质 set + push constants      // 材质/物体层，在绑定与绘制之间
 * drawIndexed(cmd, mesh)
 * </pre>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class MeshDraw {

    private MeshDraw() {
    }

    /**
     * 绑定顶点/索引缓冲（未上传抛 IllegalStateException，先经 MeshUpload）。
     */
    public static void bindBuffers(CommandBuffer cmd, Mesh mesh) {
        if (cmd == null || mesh == null) {
            throw new IllegalArgumentException("cmd / mesh must not be null");
        }
        cmd.bindVertexBuffer(mesh.vertexBuffer(), 0);
        cmd.bindIndexBuffer(mesh.indexBuffer(), mesh.indexType(), 0);
    }

    /**
     * 发起索引绘制（indexCount 实例 × 1）。
     */
    public static void drawIndexed(CommandBuffer cmd, Mesh mesh) {
        if (cmd == null || mesh == null) {
            throw new IllegalArgumentException("cmd / mesh must not be null");
        }
        cmd.drawIndexed(mesh.indexCount(), 1);
    }

    /**
     * 发起子网格范围绘制（Vulkan firstIndex 范围，子网格 i 的索引区间；
     * 子网格序号越界抛 IllegalArgumentException）。
     */
    public static void drawIndexed(CommandBuffer cmd, Mesh mesh, int submesh) {
        if (cmd == null || mesh == null) {
            throw new IllegalArgumentException("cmd / mesh must not be null");
        }
        cmd.drawIndexed(mesh.getSubmeshIndexCount(submesh), 1, mesh.getSubmeshFirstIndex(submesh));
    }
}
