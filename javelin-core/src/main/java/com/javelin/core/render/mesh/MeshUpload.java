package com.javelin.core.render.mesh;

import com.javelin.rhi.Device;
import com.javelin.rhi.UploadData;
import com.javelin.rhi.VertexLayout;
import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.desc.MemoryPreference;
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.IndexType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 网格上传桥接（渲染侧）：Mesh 的 CPU 顶点/索引数据 → GPU 缓冲。
 *
 * <p>上传逻辑全在此类——Mesh 是纯 CPU 数据容器（与 Texture2D/TextureUpload
 * 同一分工）。帧首收集阶段对注册对象统一调用，Pass 回调内不触发带 fence
 * 等待的缓冲创建。句柄持于资产，生命周期：Mesh GC 不可达 → 缓冲不可达 →
 * RHI Cleaner（RHI 17 节）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class MeshUpload {

    private MeshUpload() {
    }

    /**
     * 上传网格数据到 GPU 并冻结（幂等：已上传直接返回）。
     *
     * <p>顶点数据按规范序 interleave 打包（单绑定点 0，小端）；
     * 索引类型按顶点数自动选 UINT16（≤65535）/ UINT32。</p>
     *
     * @param device 渲染设备（FrameLoop/RenderingSystem 持有，帧首收集阶段传入）
     * @param mesh   待上传网格（positions/indices 必须已填充）
     */
    public static void upload(Device device, Mesh mesh) {
        if (device == null || mesh == null) {
            throw new IllegalArgumentException("device / mesh must not be null");
        }
        if (mesh.uploaded) {
            return;
        }
        float[] positions = mesh.positions();
        if (positions == null) {
            throw new IllegalStateException("upload 需要 setPositions（POSITION 为必选属性）");
        }
        if (mesh.indices == null || mesh.indices.length == 0) {
            throw new IllegalStateException("upload 需要 setIndices（必选项）");
        }

        int vertexCount = positions.length / 3;
        List<VertexAttribute> canonical = mesh.canonicalAttributes();
        int stride = 0;
        for (VertexAttribute a : canonical) {
            stride += a.byteSize();
        }

        ByteBuffer vertexData = ByteBuffer.allocateDirect(vertexCount * stride)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int v = 0; v < vertexCount; v++) {
            for (VertexAttribute a : canonical) {
                float[] data = mesh.dataByName.get(a.name());
                int base = v * a.components();
                if (base + a.components() > data.length) {
                    throw new IllegalStateException("属性 " + a.name() + " 数据长度 "
                            + data.length + " 与顶点数 " + vertexCount + " 不匹配");
                }
                for (int k = 0; k < a.components(); k++) {
                    vertexData.putFloat(data[base + k]);
                }
            }
        }
        vertexData.flip();
        mesh.vertexBuffer = device.createBuffer(
                new BufferDesc(vertexData.remaining(), Set.of(BufferUsage.VERTEX),
                        MemoryPreference.hostVisibleCoherent()),
                UploadData.of(vertexData));

        mesh.indexType = vertexCount <= 0xFFFF ? IndexType.UINT16 : IndexType.UINT32;
        mesh.indexCount = mesh.indices.length;
        ByteBuffer indexData = ByteBuffer.allocateDirect(
                        mesh.indices.length * (mesh.indexType == IndexType.UINT16 ? 2 : 4))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int idx : mesh.indices) {
            if (mesh.indexType == IndexType.UINT16) {
                indexData.putShort((short) idx);
            } else {
                indexData.putInt(idx);
            }
        }
        indexData.flip();
        mesh.indexBuffer = device.createBuffer(
                new BufferDesc(indexData.remaining(), Set.of(BufferUsage.INDEX),
                        MemoryPreference.hostVisibleCoherent()),
                UploadData.of(indexData));

        mesh.layout = buildLayout(canonical, stride);
        mesh.uploaded = true;
        if (!mesh.keepCpuCopy) {
            mesh.dataByName.clear();
            mesh.indices = null;
        }
    }

    /**
     * location 按固定表分配（Engine_Assets_Design 2.5：POSITION=0、NORMAL=1、TANGENT=2、
     * UV0=3、COLOR=4、自定义 5+ 按 name 字典序）——.shader 顶点输入按此固定表声明，避免错位；
     * 偏移仍按已填属性紧密排列（stride = 各属性字节和）。
     */
    private static VertexLayout buildLayout(List<VertexAttribute> canonical, int stride) {
        List<VertexLayout.Attribute> attributes = new ArrayList<>(canonical.size());
        int offset = 0;
        int customIndex = 0;
        for (VertexAttribute a : canonical) {
            int location = a.isBuiltin() ? builtinLocation(a) : 5 + customIndex++;
            attributes.add(new VertexLayout.Attribute(location, a.format(), offset));
            offset += a.byteSize();
        }
        return VertexLayout.of(attributes, stride);
    }

    private static int builtinLocation(VertexAttribute attribute) {
        for (int i = 0; i < VertexAttribute.BUILTIN_ORDER.length; i++) {
            if (VertexAttribute.BUILTIN_ORDER[i].name().equals(attribute.name())) {
                return i;
            }
        }
        throw new IllegalStateException("非内置属性走了内置 location 分支: " + attribute.name());
    }
}
