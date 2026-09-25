package com.javelin.core.render.mesh;

import com.javelin.rhi.Buffer;
import com.javelin.rhi.VertexLayout;
import com.javelin.rhi.types.IndexType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * <p>v0.1 取舍：hostVisibleCoherent 内存（Buffer staging 上传未实现，RHI 遗留），
 * deviceLocal 化待 staging 路径落地；AABB/bounds 与视锥剔除属引擎优化点，后置
 * （用户拍板 2026-09-25）；recalculateNormals / submesh / .mesh 格式属 P2 未实现。</p>
 */
public class Mesh {

    // package-private：MeshUpload 上传打包时读；非 final（红线 9：序列化回写）
    /** 属性数据（name → 防御性拷贝后的数组） */
    Map<String, float[]> dataByName = new LinkedHashMap<>();
    /** 属性描述（name → VertexAttribute） */
    private final Map<String, VertexAttribute> attributeByName = new LinkedHashMap<>();

    // 以下字段 package-private：MeshUpload/MeshDraw 同包消费（用户侧零 GPU 暴露）；
    // 非 final：序列化反序列化需回写（AGENTS.md 红线 9）
    int[] indices;
    int indexCount;
    Buffer vertexBuffer;
    Buffer indexBuffer;
    IndexType indexType;
    VertexLayout layout;
    List<VertexAttribute> canonicalAttributes;
    boolean uploaded;
    boolean keepCpuCopy;

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

    /** 自定义属性通用入口，与内置 setXxx 等价（统一 VertexAttribute + float[]） */
    public void setAttribute(VertexAttribute attribute, float[] data) {
        checkMutable();
        if (attribute == null || data == null) {
            throw new IllegalArgumentException("attribute / data must not be null");
        }
        attributeByName.put(attribute.name(), attribute);
        dataByName.put(attribute.name(), data.clone());
    }

    public boolean isUploaded() {
        return uploaded;
    }

    /** 上传后保留 CPU 副本（物理/编辑器需要时显式开启，默认释放省内存，2.7） */
    public void setKeepCpuCopy(boolean keepCpuCopy) {
        this.keepCpuCopy = keepCpuCopy;
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
        return dataByName.get(VertexAttribute.POSITION.name());
    }

    /** 规范序属性列表（惰性缓存：内置序在前，自定义按 name 字典序） */
    List<VertexAttribute> canonicalAttributes() {
        if (canonicalAttributes != null) {
            return canonicalAttributes;
        }
        List<VertexAttribute> result = new ArrayList<>();
        for (VertexAttribute builtin : VertexAttribute.BUILTIN_ORDER) {
            if (dataByName.containsKey(builtin.name())) {
                result.add(attributeByName.get(builtin.name()));
            }
        }
        Map<String, VertexAttribute> customs = new TreeMap<>();
        for (Map.Entry<String, VertexAttribute> e : attributeByName.entrySet()) {
            if (!e.getValue().isBuiltin()) {
                customs.put(e.getKey(), e.getValue());
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
