package com.javelin.core.assets;

import com.javelin.rhi.Buffer;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 网格资产（Engine_Assets_Design.md 2 节最小实现）。
 *
 * <p>per-attribute 数据（Unity 式）+ 数据驱动属性推断 + VertexLayout 规范化排序：
 * <ul>
 *   <li>给了哪些数组 → 就有哪些属性（无需显式声明）；set 时防御性拷贝；</li>
 *   <li>layout 规范序：内置 POSITION &lt; NORMAL &lt; TANGENT &lt; UV0 &lt; COLOR，
 *       自定义属性排其后按 name 字典序；location = 规范序索引，stride = 各属性字节和；</li>
 *   <li>首次上传后冻结（再 set 抛异常），默认释放 CPU 副本；</li>
 *   <li>GPU 缓冲生命周期：GC 不可达 → Cleaner（RHI 17 节），资产层不手动销毁。</li>
 * </ul>
 *
 * <p>v0.1 取舍：用 hostVisibleCoherent 内存（Buffer staging 上传未实现，RHI 遗留），
 * deviceLocal 化待 staging 路径落地；AABB/bounds 与视锥剔除属引擎优化点，后置
 * （用户拍板 2026-09-25）；recalculateNormals / submesh / .mesh 格式属 P2 未实现。
 */
public class Mesh {

    /** 属性数据（name → 防御性拷贝后的数组） */
    private final Map<String, float[]> dataByName = new LinkedHashMap<>();
    /** 属性描述（name → VertexAttribute） */
    private final Map<String, VertexAttribute> attributeByName = new LinkedHashMap<>();

    private int[] indices;
    private int indexCount;

    private Buffer vertexBuffer;
    private Buffer indexBuffer;
    private IndexType indexType;
    private VertexLayout layout;
    private List<VertexAttribute> canonicalAttributes;
    private boolean uploaded;
    private boolean keepCpuCopy;

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

    // ═══ 上传（lazy 由调用方触发，首次后冻结） ═══

    /**
     * 上传 CPU 数据到 GPU 缓冲（重复调用为幂等空操作）。
     *
     * <p>顶点数据按规范序 interleave 打包（单绑定点 0，小端）；
     * 索引类型按顶点数自动选 UINT16（≤65535）/ UINT32（2.6）。
     */
    public void upload(Device device) {
        if (uploaded) {
            return;
        }
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        float[] positions = positions();
        if (positions == null) {
            throw new IllegalStateException("upload 需要 setPositions（POSITION 为必选属性）");
        }
        if (indices == null || indices.length == 0) {
            throw new IllegalStateException("upload 需要 setIndices（必选项）");
        }

        int vertexCount = positions.length / 3;
        List<VertexAttribute> canonical = canonicalAttributes();
        int stride = 0;
        for (VertexAttribute a : canonical) {
            stride += a.byteSize();
        }

        ByteBuffer vertexData = ByteBuffer.allocateDirect(vertexCount * stride)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int v = 0; v < vertexCount; v++) {
            for (VertexAttribute a : canonical) {
                float[] data = dataByName.get(a.name());
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
        vertexBuffer = device.createBuffer(
                new BufferDesc(vertexData.remaining(), Set.of(BufferUsage.VERTEX),
                        MemoryPreference.hostVisibleCoherent()),
                UploadData.of(vertexData));

        indexType = vertexCount <= 0xFFFF ? IndexType.UINT16 : IndexType.UINT32;
        indexCount = indices.length;
        ByteBuffer indexData = ByteBuffer.allocateDirect(indices.length * (indexType == IndexType.UINT16 ? 2 : 4))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int idx : indices) {
            if (indexType == IndexType.UINT16) {
                indexData.putShort((short) idx);
            } else {
                indexData.putInt(idx);
            }
        }
        indexData.flip();
        indexBuffer = device.createBuffer(
                new BufferDesc(indexData.remaining(), Set.of(BufferUsage.INDEX),
                        MemoryPreference.hostVisibleCoherent()),
                UploadData.of(indexData));

        layout = buildLayout(canonical, stride);
        uploaded = true;
        if (!keepCpuCopy) {
            dataByName.clear();
            indices = null;
        }
    }

    public boolean isUploaded() {
        return uploaded;
    }

    /** 上传后保留 CPU 副本（物理/编辑器需要时显式开启，默认释放省内存，2.7） */
    public void setKeepCpuCopy(boolean keepCpuCopy) {
        this.keepCpuCopy = keepCpuCopy;
    }

    // ═══ 访问器（渲染收集期消费） ═══

    public Buffer vertexBuffer() {
        checkUploaded();
        return vertexBuffer;
    }

    public Buffer indexBuffer() {
        checkUploaded();
        return indexBuffer;
    }

    public IndexType indexType() {
        checkUploaded();
        return indexType;
    }

    public int indexCount() {
        checkUploaded();
        return indexCount;
    }

    public VertexLayout layout() {
        checkUploaded();
        return layout;
    }

    /** 顶点数（上传前也可由 POSITION 推得） */
    public int vertexCount() {
        float[] positions = positions();
        return positions == null ? 0 : positions.length / 3;
    }

    private float[] positions() {
        return dataByName.get(VertexAttribute.POSITION.name());
    }

    private List<VertexAttribute> canonicalAttributes() {
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

    private void checkMutable() {
        if (uploaded) {
            throw new IllegalStateException("Mesh 已上传冻结，不可再修改（Engine_Assets_Design 2.1/2.7）");
        }
    }

    private void checkUploaded() {
        if (!uploaded) {
            throw new IllegalStateException("Mesh 未上传：先调用 upload(device)（lazy 由渲染系统触发）");
        }
    }
}
