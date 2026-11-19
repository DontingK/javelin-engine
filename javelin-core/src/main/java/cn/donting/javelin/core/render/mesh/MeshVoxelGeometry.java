package cn.donting.javelin.core.render.mesh;

import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.IndexType;

/**
 * 网格 GPU 几何只读视图（体素化 compute 的几何入口，Engine_VoxelAO_Design §3.3）。
 *
 * <p>同包桥接：{@link Mesh} 的 GPU 句柄是 package-private，体素系统（{@code render.voxel}）
 * 不直接摸字段，经本类取只读三件套（顶点/索引缓冲 + 布局）。返回的 Buffer 只允许作
 * SSBO 绑定读，不得写。</p>
 */
public final class MeshVoxelGeometry {

    private MeshVoxelGeometry() {
    }

    /** 网格是否具备体素化条件（已上传且几何完整） */
    public static boolean isVoxelizable(Mesh mesh) {
        return mesh != null && mesh.uploaded
                && mesh.vertexBuffer != null && mesh.indexBuffer != null
                && mesh.indexCount > 0 && mesh.indexType != null && mesh.layout != null;
    }

    /** 顶点缓冲（作 SSBO 读） */
    public static Buffer vertexBuffer(Mesh mesh) {
        requireVoxelizable(mesh);
        return mesh.vertexBuffer;
    }

    /** 索引缓冲（作 SSBO 读） */
    public static Buffer indexBuffer(Mesh mesh) {
        requireVoxelizable(mesh);
        return mesh.indexBuffer;
    }

    /** 索引类型（UINT16 / UINT32） */
    public static IndexType indexType(Mesh mesh) {
        requireVoxelizable(mesh);
        return mesh.indexType;
    }

    /** 索引总数（含所有子网格区间） */
    public static int indexCount(Mesh mesh) {
        requireVoxelizable(mesh);
        return mesh.indexCount;
    }

    /** 顶点步长（字节） */
    public static int vertexStride(Mesh mesh) {
        requireVoxelizable(mesh);
        return mesh.layout.stride();
    }

    /**
     * POSITION 属性在顶点步长内的字节偏移。
     *
     * @throws IllegalStateException 布局里没有 POSITION（不满足引擎上传约束，属异常态）
     */
    public static int positionOffset(Mesh mesh) {
        requireVoxelizable(mesh);
        VertexLayout layout = mesh.layout;
        for (VertexLayout.Attribute attribute : layout.attributes()) {
            // 引擎布局规范序：POSITION = location 0（Engine_Assets_Design §2.5）
            if (attribute.location() == 0 && attribute.format() == Format.R32G32B32_SFLOAT) {
                return attribute.offset();
            }
        }
        throw new IllegalStateException("网格布局缺少 POSITION(location 0, R32G32B32_SFLOAT)——"
                + "上传契约被破坏，无法体素化");
    }

    private static void requireVoxelizable(Mesh mesh) {
        if (!isVoxelizable(mesh)) {
            throw new IllegalStateException("网格未上传或几何不完整（vertexBuffer/indexBuffer/layout 缺失）");
        }
    }
}
