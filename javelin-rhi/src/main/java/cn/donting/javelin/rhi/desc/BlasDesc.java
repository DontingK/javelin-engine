package cn.donting.javelin.rhi.desc;

import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.types.IndexType;

/**
 * 底层加速结构（BLAS）构建描述：一条 BLAS 对应一个网格的**位置 + 索引**数据（P4-06 B2）。
 *
 * <p>前提：顶点/索引缓冲创建时必须声明 {@code BufferUsage.SHADER_DEVICE_ADDRESS}
 * （否则取不到设备地址），且缓冲里是紧密三角形列（indexCount 为 3 的整数倍）。</p>
 */
public final class BlasDesc {

    private final Buffer vertexBuffer;
    private final Buffer indexBuffer;
    private final int vertexStride;
    private final int positionOffset;
    private final int vertexCount;
    private final int indexCount;
    private final IndexType indexType;

    /**
     * @param vertexBuffer   顶点缓冲（须含 SHADER_DEVICE_ADDRESS 用途）
     * @param indexBuffer    索引缓冲（同上）
     * @param vertexStride   顶点步长（字节）
     * @param positionOffset 位置属性在顶点内的字节偏移
     * @param vertexCount    顶点数（用于 maxVertex = count - 1）
     * @param indexCount     索引数（须为 3 的整数倍）
     * @param indexType      索引类型
     */
    public BlasDesc(Buffer vertexBuffer, Buffer indexBuffer, int vertexStride, int positionOffset,
                    int vertexCount, int indexCount, IndexType indexType) {
        if (vertexBuffer == null || indexBuffer == null) {
            throw new IllegalArgumentException("BLAS 需要顶点与索引缓冲");
        }
        if (vertexStride <= 0) {
            throw new IllegalArgumentException("vertexStride 必须为正: " + vertexStride);
        }
        if (vertexCount <= 0) {
            throw new IllegalArgumentException("vertexCount 必须为正: " + vertexCount);
        }
        if (indexCount <= 0 || indexCount % 3 != 0) {
            throw new IllegalArgumentException("indexCount 须为 3 的整数倍: " + indexCount);
        }
        if (vertexBuffer.deviceAddress() == 0L || indexBuffer.deviceAddress() == 0L) {
            throw new IllegalStateException(
                    "BLAS 缓冲缺少设备地址——顶点/索引缓冲须声明 BufferUsage.SHADER_DEVICE_ADDRESS");
        }
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
        this.vertexStride = vertexStride;
        this.positionOffset = positionOffset;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.indexType = indexType;
    }

    public Buffer vertexBuffer() { return vertexBuffer; }
    public Buffer indexBuffer() { return indexBuffer; }
    public int vertexStride() { return vertexStride; }
    public int positionOffset() { return positionOffset; }
    public int vertexCount() { return vertexCount; }
    public int indexCount() { return indexCount; }
    public IndexType indexType() { return indexType; }

    /** 图元数（三角形列表口径） */
    public int primitiveCount() { return indexCount / 3; }
}
