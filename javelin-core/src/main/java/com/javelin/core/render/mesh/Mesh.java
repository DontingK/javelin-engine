package com.javelin.core.render.mesh;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;
import com.javelin.rhi.Buffer;
import com.javelin.rhi.VertexLayout;
import com.javelin.rhi.types.IndexType;
import com.javelin.rhi.types.PrimitiveTopology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 网格资产（Engine_Assets_Design.md 2 节最小实现，render.mesh 包）。
 *
 * <p>per-attribute 数据（Unity 式）+ 数据驱动属性推断；<b>纯 CPU 数据容器，
 * 零 GPU 代码</b>——上传在 {@link MeshUpload}（用户侧不碰 GPU 资源，
 * 与 Texture2D/TextureUpload 同一原则）：</p>
 * <ul>
 *   <li>给了哪些数组 → 就有哪些属性（无需显式声明）；set 时防御性拷贝；</li>
 *   <li>布局规范序：内置 POSITION &lt; NORMAL &lt; TANGENT &lt; UV0 &lt; COLOR，
 *       自定义属性排其后按 name 字典序；location = 规范序索引，stride = 各属性字节和；</li>
 *   <li>上传后冻结（再 set 抛异常），默认释放 CPU 副本；</li>
 *   <li>GPU 缓冲句柄持于资产（package-private），生命周期：GC 不可达 → Cleaner（RHI 17 节）。</li>
 * </ul>
 *
 * <p>序列化（Capsule 二进制协议，.smesh 导入产物，2026-09-26 落地；重构拍板：
 * <b>字段类型即序列化实体类型，存储形态 = 序列化形态，零转换零重建</b>）——
 * CPU 数据 = {@code attributes}（{@link MeshAttributeData} 节点列表，name 唯一）+
 * {@code indices}；uploaded/layout/GPU 句柄等运行态不落盘。</p>
 *
 * <p>v0.1 取舍：hostVisibleCoherent 内存（Buffer staging 上传未实现，RHI 遗留），
 * deviceLocal 化待 staging 路径落地；AABB/bounds 与视锥剔除属引擎优化点，后置
 * （用户拍板 2026-09-25）；~~recalculateNormals~~（2026-09-26 落地，索引绘制
 * TRIANGLE_LIST）/ submesh / .mesh 格式属 P2 未实现。</p>
 */
@Reflective
public class Mesh implements Serializable {

    /** 顶点属性列表（name 唯一；序列化直通字段；package-private：MeshUpload 消费） */
    ArrayList<MeshAttributeData> attributes = new ArrayList<>();
    /** 索引数据（null = 无索引绘制）；序列化直通字段 */
    int[] indices;
    /** 图元拓扑（默认三角列表；PSO 图元类型来源，随产物序列化） */
    private PrimitiveTopology topology = PrimitiveTopology.TRIANGLE_LIST;

    // 以下运行态字段 package-private：MeshUpload/MeshDraw 同包消费（用户侧零 GPU 暴露）；
    // 非 final：序列化反序列化需回写（AGENTS.md 红线 9）
    int indexCount;
    Buffer vertexBuffer;
    Buffer indexBuffer;
    IndexType indexType;
    VertexLayout layout;
    /** 规范序缓存（上传打包用；属性 set 时失效） */
    List<MeshAttributeData> canonicalAttributes;
    boolean uploaded;
    boolean keepCpuCopy;

    // ═══ 序列化（字段直通，read 只回写引用——红线 10） ═══

    @Override
    public void write(OutputCapsule output) {
        output.write("attributes", attributes);
        output.write("indices", indices);
        output.write("topology", topology);
        output.write("keepCpuCopy", keepCpuCopy);
    }

    @Override
    public void read(InputCapsule input) {
        ArrayList<MeshAttributeData> nodes = input.readArrayList("attributes", MeshAttributeData.class);
        attributes = nodes != null ? nodes : new ArrayList<>();
        indices = input.readIntArray("indices");
        PrimitiveTopology t = input.readEnum("topology", PrimitiveTopology.class);
        if (t != null) {
            topology = t;
        }
        keepCpuCopy = input.readBoolean("keepCpuCopy");
    }

    // ═══ 数据填充（上传前；防御性拷贝） ═══

    public void setPositions(float[] positions) {
        setAttribute(VertexAttribute.POSITION, positions);
    }

    public void setNormals(float[] normals) {
        setAttribute(VertexAttribute.NORMAL, normals);
    }

    public void setTangents(float[] tangents) {
        setAttribute(VertexAttribute.TANGENT, tangents);
    }

    public void setUVs(float[] uvs) {
        setAttribute(VertexAttribute.UV0, uvs);
    }

    public void setColors(float[] colors) {
        setAttribute(VertexAttribute.COLOR, colors);
    }

    public void setIndices(int[] indices) {
        checkMutable();
        if (indices == null) {
            throw new IllegalArgumentException("indices must not be null");
        }
        this.indices = indices.clone();
    }

    /** 属性通用入口（内置 setXxx / 自定义属性同机制），同名替换、新名追加 */
    public void setAttribute(VertexAttribute attribute, float[] data) {
        checkMutable();
        if (attribute == null || data == null) {
            throw new IllegalArgumentException("attribute / data must not be null");
        }
        MeshAttributeData node = new MeshAttributeData(attribute.name(),
                attribute.components(), attribute.format(), data.clone());
        int existing = indexOfAttribute(attribute.name());
        if (existing >= 0) {
            attributes.set(existing, node);
        } else {
            attributes.add(node);
        }
        canonicalAttributes = null;
    }

    public boolean isUploaded() {
        return uploaded;
    }

    /** 上传后保留 CPU 副本（物理/编辑器需要时显式开启，默认释放省内存，2.7） */
    public void setKeepCpuCopy(boolean keepCpuCopy) {
        this.keepCpuCopy = keepCpuCopy;
    }

    // ═══ 图元拓扑 ═══

    /** 图元拓扑（PSO 图元类型来源；默认三角列表） */
    public PrimitiveTopology getTopology() {
        return topology;
    }

    public void setTopology(PrimitiveTopology topology) {
        checkMutable();
        if (topology == null) {
            throw new IllegalArgumentException("topology must not be null");
        }
        this.topology = topology;
    }

    /**
     * 自动计算顶点法线（按三角形面法线加权累加后归一化，写入 NORMAL 属性）。
     *
     * <p>引擎绘制为索引路径（MeshUpload 强制 setIndices），故只支持索引数据；
     * v0.1 拓扑只实现 TRIANGLE_LIST（STRIP 等拓扑后续按需补）。</p>
     */
    public void recalculateNormals() {
        checkMutable();
        if (topology != PrimitiveTopology.TRIANGLE_LIST) {
            throw new UnsupportedOperationException(
                    "recalculateNormals 暂只支持 TRIANGLE_LIST，实际拓扑: " + topology);
        }
        float[] positions = positions();
        if (positions == null) {
            throw new IllegalStateException("recalculateNormals 需要 setPositions");
        }
        if (indices == null || indices.length == 0) {
            throw new IllegalStateException("recalculateNormals 需要 setIndices（索引绘制路径）");
        }

        int vertexCount = positions.length / 3;
        float[] normals = new float[vertexCount * 3];
        for (int i = 0; i + 2 < indices.length; i += 3) {
            int i0 = indices[i] * 3;
            int i1 = indices[i + 1] * 3;
            int i2 = indices[i + 2] * 3;
            // 叉积 (p1-p0)×(p2-p0) —— CCW 绕序朝正面
            float ax = positions[i1] - positions[i0];
            float ay = positions[i1 + 1] - positions[i0 + 1];
            float az = positions[i1 + 2] - positions[i0 + 2];
            float bx = positions[i2] - positions[i0];
            float by = positions[i2 + 1] - positions[i0 + 1];
            float bz = positions[i2 + 2] - positions[i0 + 2];
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            normals[i0] += nx;
            normals[i0 + 1] += ny;
            normals[i0 + 2] += nz;
            normals[i1] += nx;
            normals[i1 + 1] += ny;
            normals[i1 + 2] += nz;
            normals[i2] += nx;
            normals[i2 + 1] += ny;
            normals[i2 + 2] += nz;
        }
        for (int i = 0; i < vertexCount; i++) {
            int off = i * 3;
            float len = (float) Math.sqrt(normals[off] * normals[off]
                    + normals[off + 1] * normals[off + 1]
                    + normals[off + 2] * normals[off + 2]);
            if (len > 1e-6f) {
                float inv = 1.0f / len;
                normals[off] *= inv;
                normals[off + 1] *= inv;
                normals[off + 2] *= inv;
            }
        }
        setNormals(normals);
    }

    // ═══ 访问器 ═══

    /**
     * 顶点布局（上传后可用；VertexLayout 是不可变描述值，PSO 缓存键——
     * 公开语义同 Texture2D.format 元数据，非 GPU 句柄）。
     */
    public VertexLayout layout() {
        checkUploaded();
        return layout;
    }

    /** 顶点数（上传前也可由 POSITION 推得） */
    public int vertexCount() {
        float[] positions = positions();
        return positions == null ? 0 : positions.length / 3;
    }

    // ═══ 包内共享（MeshUpload/MeshDraw 消费；勿跨包使用） ═══

    float[] positions() {
        return data(VertexAttribute.POSITION.name());
    }

    /** 按属性名取顶点数据（线性查找；Mesh 属性数为个位数），没有返回 null */
    float[] data(String name) {
        MeshAttributeData node = findAttribute(name);
        return node != null ? node.getData() : null;
    }

    /** 规范序属性节点列表（内置序在前，自定义按 name 字典序；缓存到下次 set） */
    List<MeshAttributeData> canonicalAttributes() {
        if (canonicalAttributes != null) {
            return canonicalAttributes;
        }
        Map<String, MeshAttributeData> customs = new TreeMap<>();
        List<MeshAttributeData> result = new ArrayList<>(attributes.size());
        for (VertexAttribute builtin : VertexAttribute.BUILTIN_ORDER) {
            MeshAttributeData node = findAttribute(builtin.name());
            if (node != null) {
                result.add(node);
            }
        }
        for (MeshAttributeData node : attributes) {
            if (!node.toVertexAttribute().isBuiltin()) {
                customs.put(node.getName(), node);
            }
        }
        result.addAll(customs.values());
        canonicalAttributes = Collections.unmodifiableList(result);
        return canonicalAttributes;
    }

    Buffer vertexBuffer() {
        checkUploaded();
        return vertexBuffer;
    }

    Buffer indexBuffer() {
        checkUploaded();
        return indexBuffer;
    }

    IndexType indexType() {
        checkUploaded();
        return indexType;
    }

    int indexCount() {
        checkUploaded();
        return indexCount;
    }

    // ═══ 内部 ═══

    private MeshAttributeData findAttribute(String name) {
        for (MeshAttributeData node : attributes) {
            if (node.getName().equals(name)) {
                return node;
            }
        }
        return null;
    }

    private int indexOfAttribute(String name) {
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i).getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private void checkMutable() {
        if (uploaded) {
            throw new IllegalStateException("Mesh 已上传冻结，不可再修改（Engine_Assets_Design 2.1/2.7）");
        }
    }

    private void checkUploaded() {
        if (!uploaded) {
            throw new IllegalStateException("Mesh 未上传：先经 MeshUpload.upload(device, mesh)（lazy 由渲染系统触发）");
        }
    }
}
