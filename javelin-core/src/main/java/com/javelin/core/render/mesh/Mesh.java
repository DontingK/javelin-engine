package com.javelin.core.render.mesh;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;
import com.javelin.rhi.api.Buffer;
import com.javelin.rhi.api.Device;
import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.desc.VertexLayout;
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.IndexType;
import com.javelin.rhi.types.MemoryUsage;
import com.javelin.rhi.types.VertexFormat;

import java.util.EnumMap;
import java.util.EnumSet;

/**
 * Mesh 资产 —— 纯几何数据容器（顶点 + 可选索引）。
 *
 * <p><strong>共享资产</strong>：可被多个 {@link com.javelin.core.scene.component.MeshRenderer}
 * 引用，共用同一套 GPU Buffer。</p>
 *
 * <p>顶点数据按属性分离存储（{@link VertexAttribute} → float[]/byte[]），
 * 上传 GPU 时自动打包为 interleaved 格式并构建 {@link VertexLayout}。</p>
 *
 * <p>GPU 资源（Buffer）在运行时由导入管线或 {@link com.javelin.core.scene.component.MeshRenderer}
 * 创建，Mesh 本身只存储 CPU 侧原始数据或引用。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Mesh extends AbsAsset implements Serializable {

    // ═══════════════════════════════════════════════════════════════════════
    //  CPU 数据（加载时填充）
    // ═══════════════════════════════════════════════════════════════════════

    /** 顶点数据映射：Key=属性类型，Value=float[] 或 byte[] */
    private final EnumMap<VertexAttribute, Object> vertexData = new EnumMap<>(VertexAttribute.class);

    /** 索引数据（可选，null 表示无索引） */
    private int[] indexData;

    /** 索引类型 */
    private IndexType indexType = IndexType.UINT32;

    /** 数据脏标记（CPU 数据变更后需重新 upload） */
    private transient boolean dirty = true;

    // ═══════════════════════════════════════════════════════════════════════
    //  GPU 资源（运行时外部创建）
    // ═══════════════════════════════════════════════════════════════════════

    /** 顶点缓冲（上传后填充） */
    private transient Buffer vertexBuffer;

    /** 索引缓冲（可选） */
    private transient Buffer indexBuffer;

    // ═══════════════════════════════════════════════════════════════════════
    //  构造
    // ═══════════════════════════════════════════════════════════════════════

    public Mesh() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  顶点属性数据访问
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 设置指定属性的顶点数据。
     *
     * @param attr  属性类型
     * @param data  数据数组（float[] 或 byte[]）
     */
    public void setAttributeData(VertexAttribute attr, Object data) {
        if (data == null) {
            vertexData.remove(attr);
        } else {
            vertexData.put(attr, data);
        }
        dirty = true;
    }

    /**
     * 获取指定属性的顶点数据。
     *
     * @param attr 属性类型
     * @return 数据数组，找不到返回 null
     */
    public Object getAttributeData(VertexAttribute attr) {
        return vertexData.get(attr);
    }

    /**
     * 获取已激活的顶点属性集合。
     */
    public EnumSet<VertexAttribute> getActiveAttributes() {
        return EnumSet.copyOf(vertexData.keySet());
    }

    /**
     * 获取顶点数量（由位置数据推导）。
     */
    public int getVertexCount() {
        float[] positions = (float[]) vertexData.get(VertexAttribute.POSITION);
        if (positions == null || positions.length == 0) {
            return 0;
        }
        return positions.length / VertexAttribute.POSITION.componentCount;
    }

    /**
     * 获取索引数量。
     */
    public int getIndexCount() {
        return indexData != null ? indexData.length : 0;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  索引数据访问
    // ═══════════════════════════════════════════════════════════════════════

    public int[] getIndexData() {
        return indexData;
    }

    public void setIndexData(int[] indexData) {
        this.indexData = indexData;
        dirty = true;
    }

    public IndexType getIndexType() {
        return indexType;
    }

    public void setIndexType(IndexType indexType) {
        this.indexType = indexType;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GPU 资源访问
    // ═══════════════════════════════════════════════════════════════════════

    public Buffer getVertexBuffer() {
        return vertexBuffer;
    }

    public void setVertexBuffer(Buffer vertexBuffer) {
        this.vertexBuffer = vertexBuffer;
    }

    public Buffer getIndexBuffer() {
        return indexBuffer;
    }

    public void setIndexBuffer(Buffer indexBuffer) {
        this.indexBuffer = indexBuffer;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  状态查询
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 顶点缓冲是否已上传 GPU。
     */
    public boolean isUploaded() {
        return vertexBuffer != null;
    }

    /**
     * 是否使用索引绘制。
     */
    public boolean isIndexed() {
        return indexBuffer != null;
    }

    public boolean isDirty() {
        return dirty;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  VertexLayout 自动构建
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 根据当前激活的顶点属性自动构建 VertexLayout。
     *
     * @return 顶点布局描述
     */
    public VertexLayout buildVertexLayout() {
        EnumSet<VertexAttribute> activeAttrs = getActiveAttributes();
        if (activeAttrs.isEmpty()) {
            return new VertexLayout(new VertexLayout.VertexAttribute[0]);
        }

        VertexLayout.VertexAttribute[] attrs = new VertexLayout.VertexAttribute[activeAttrs.size()];
        int location = 0;
        int offset = 0;

        for (VertexAttribute attr : VertexAttribute.values()) {
            if (!activeAttrs.contains(attr)) {
                continue;
            }
            attrs[location] = new VertexLayout.VertexAttribute(
                    location, 0, attr.format, offset, attr.normalized
            );
            offset += attr.byteSize();
            location++;
        }

        return new VertexLayout(attrs);
    }

    /**
     * 计算顶点步长（字节）。
     */
    public int getVertexStride() {
        int stride = 0;
        for (VertexAttribute attr : getActiveAttributes()) {
            stride += attr.byteSize();
        }
        return stride;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  数据打包（interleaved）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 将分离的顶点属性数据打包为 interleaved 字节数组。
     *
     * @return 打包后的顶点数据
     */
    public byte[] packInterleavedData() {
        int vertexCount = getVertexCount();
        int stride = getVertexStride();
        byte[] result = new byte[vertexCount * stride];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(result).order(java.nio.ByteOrder.nativeOrder());

        for (int v = 0; v < vertexCount; v++) {
            for (VertexAttribute attr : VertexAttribute.values()) {
                Object data = vertexData.get(attr);
                if (data == null) {
                    continue;
                }
                if (data instanceof float[] floatArray) {
                    for (int c = 0; c < attr.componentCount; c++) {
                        buffer.putFloat(floatArray[v * attr.componentCount + c]);
                    }
                } else if (data instanceof byte[] byteArray) {
                    for (int c = 0; c < attr.componentCount; c++) {
                        buffer.put(byteArray[v * attr.componentCount + c]);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 将索引数据打包为字节数组。
     */
    public byte[] packIndexData() {
        if (indexData == null || indexData.length == 0) {
            return new byte[0];
        }
        byte[] result = new byte[indexData.length * Integer.BYTES];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(result).order(java.nio.ByteOrder.nativeOrder());
        for (int index : indexData) {
            buffer.putInt(index);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GPU 上传
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 上传顶点和索引数据到 GPU。
     *
     * @param device 逻辑设备
     */
    public void uploadToGpu(Device device) {
        if (!dirty && vertexBuffer != null) {
            return;
        }

        int vertexCount = getVertexCount();
        if (vertexCount == 0) {
            throw new IllegalStateException("No vertex data to upload");
        }

        // 上传顶点数据
        byte[] vertexBytes = packInterleavedData();
        logVertexData(vertexBytes, vertexCount);
        Buffer vb = device.createBuffer(new BufferDesc(
                vertexBytes.length,
                BufferUsage.VERTEX_BUFFER,
                MemoryUsage.CPU_TO_GPU
        ));
        vb.upload(vertexBytes);
        this.vertexBuffer = vb;

        // 上传索引数据
        if (indexData != null && indexData.length > 0) {
            byte[] indexBytes = packIndexData();
            logIndexData(indexBytes);
            Buffer ib = device.createBuffer(new BufferDesc(
                    indexBytes.length,
                    BufferUsage.INDEX_BUFFER,
                    MemoryUsage.CPU_TO_GPU
            ));
            ib.upload(indexBytes);
            this.indexBuffer = ib;
        }

        dirty = false;
    }

    private void logVertexData(byte[] vertexBytes, int vertexCount) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(vertexBytes).order(java.nio.ByteOrder.nativeOrder());
        int stride = getVertexStride() / Float.BYTES;
        org.slf4j.LoggerFactory.getLogger(Mesh.class).info("Mesh.uploadToGpu: vertexCount={}, stride={} floats, vertexBytes={} bytes", vertexCount, stride, vertexBytes.length);
        for (int v = 0; v < Math.min(vertexCount, 4); v++) {
            StringBuilder sb = new StringBuilder();
            sb.append("  vertex ").append(v).append(": ");
            for (int i = 0; i < stride; i++) {
                sb.append(String.format("%.2f", buffer.getFloat()));
                if (i < stride - 1) sb.append(", ");
            }
            org.slf4j.LoggerFactory.getLogger(Mesh.class).info(sb.toString());
        }
    }

    private void logIndexData(byte[] indexBytes) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(indexBytes).order(java.nio.ByteOrder.nativeOrder());
        int count = indexBytes.length / Integer.BYTES;
        org.slf4j.LoggerFactory.getLogger(Mesh.class).info("Mesh.uploadToGpu: indexCount={}", count);
        StringBuilder sb = new StringBuilder("  indices: ");
        for (int i = 0; i < Math.min(count, 10); i++) {
            sb.append(buffer.getInt());
            if (i < Math.min(count, 10) - 1) sb.append(", ");
        }
        org.slf4j.LoggerFactory.getLogger(Mesh.class).info(sb.toString());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  便捷工厂
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 从顶点数组 + 索引数组快速构建 Mesh（位置 + 法线 + UV）。
     *
     * @param vertices 顶点数组
     * @param indices  索引数组（可选，null 表示无索引）
     * @return 构建好的 Mesh
     */
    public static Mesh fromVertices(Vertex[] vertices, int[] indices) {
        float[] positions = new float[vertices.length * 3];
        float[] normals = new float[vertices.length * 3];
        float[] uvs = new float[vertices.length * 2];

        for (int i = 0; i < vertices.length; i++) {
            Vertex v = vertices[i];
            positions[i * 3] = v.x;
            positions[i * 3 + 1] = v.y;
            positions[i * 3 + 2] = v.z;
            normals[i * 3] = v.nx;
            normals[i * 3 + 1] = v.ny;
            normals[i * 3 + 2] = v.nz;
            uvs[i * 2] = v.u;
            uvs[i * 2 + 1] = v.v;
        }

        Mesh mesh = new Mesh();
        mesh.setAttributeData(VertexAttribute.POSITION, positions);
        mesh.setAttributeData(VertexAttribute.NORMAL, normals);
        mesh.setAttributeData(VertexAttribute.TEX_COORD0, uvs);
        mesh.setIndexData(indices);
        return mesh;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Serializable
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void write(OutputCapsule output) {
        // 序列化各属性数据
        for (VertexAttribute attr : VertexAttribute.values()) {
            Object data = vertexData.get(attr);
            if (data != null) {
                if (data instanceof float[] fa) {
                    output.write("va_" + attr.name() + "_float", fa);
                } else if (data instanceof byte[] ba) {
                    output.write("va_" + attr.name() + "_byte", ba);
                }
            }
        }
        output.write("indexData", indexData);
        output.write("indexType", indexType);
    }

    @Override
    public void read(InputCapsule input) {
        vertexData.clear();
        for (VertexAttribute attr : VertexAttribute.values()) {
            float[] fa = input.readFloatArray("va_" + attr.name() + "_float");
            if (fa != null) {
                vertexData.put(attr, fa);
                continue;
            }
            byte[] ba = input.readByteArray("va_" + attr.name() + "_byte");
            if (ba != null) {
                vertexData.put(attr, ba);
            }
        }
        indexData = input.readIntArray("indexData");
        indexType = input.readEnum("indexType", IndexType.class);
        dirty = true;
    }
}
