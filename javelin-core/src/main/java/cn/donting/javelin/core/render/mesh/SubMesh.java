package cn.donting.javelin.core.render.mesh;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * Mesh 子网格（索引区间节点，Engine_Assets_Design §2）：firstIndex/indexCount
 * 指向 Mesh 单一 indices 数组的连续区间（单索引缓冲不变，Vulkan firstIndex
 * 范围绘制）。
 *
 * <p>Mesh 内部为 {@code ArrayList<SubMesh>}（序列化字段）；无显式子网格时 Mesh
 * 以单一隐式子网格（全量索引）兜底（{@link Mesh#getSubmeshCount()} 兜 1）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public final class SubMesh implements Serializable {

    /** 区间起点（指向 Mesh.indices；非 final：反序列化需回写，AGENTS.md 红线 9） */
    private int firstIndex;
    /** 区间索引数 */
    private int indexCount;

    /** 反序列化用无参构造（ReflectiveFactory 重建） */
    public SubMesh() {
    }

    public SubMesh(int firstIndex, int indexCount) {
        this.firstIndex = firstIndex;
        this.indexCount = indexCount;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("firstIndex", firstIndex);
        output.write("indexCount", indexCount);
    }

    @Override
    public void read(InputCapsule input) {
        firstIndex = input.readInt("firstIndex");
        indexCount = input.readInt("indexCount");
    }

    public int getFirstIndex() {
        return firstIndex;
    }

    public int getIndexCount() {
        return indexCount;
    }
}
