package com.javelin.core.scene;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 节点变换 —— 承载局部 TRS、层级关系与世界矩阵缓存。
 *
 * <p>Transform 是一种特殊的 {@link Component}（Unity 同构）：每个 {@link Node}
 * 恰好一个，由 Node 构造时自动创建，不可手动添加或移除。
 * 可通过 {@code node.getComponent(Transform.class)} 统一查询。</p>
 *
 * <p><strong>脏标记惰性更新</strong>：修改局部 TRS 后仅置脏并向子孙传播，
 * 访问 {@link #worldMatrix()} 时沿父链惰性重算并缓存。
 * 通过 {@link #localPosition()} 等返回的引用直接修改 JOML 对象后，
 * 必须调用 {@link #markDirty()} 触发重算。</p>
 *
 * <p>线程模型：仅主线程操作，不做内部加锁。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class Transform extends Component implements Iterable<Transform> {

    // ═══════════════════════════════════════════════════════════════════════
    //  局部 TRS
    // ═══════════════════════════════════════════════════════════════════════

    private final Vector3f localPosition = new Vector3f();
    private final Quaternionf localRotation = new Quaternionf();
    private final Vector3f localScale = new Vector3f(1.0f, 1.0f, 1.0f);

    // ═══════════════════════════════════════════════════════════════════════
    //  层级关系
    // ═══════════════════════════════════════════════════════════════════════

    private Transform parent;
    private final List<Transform> children = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════════════
    //  矩阵缓存
    // ═══════════════════════════════════════════════════════════════════════

    /** 局部矩阵（TRS 组合，TRS 修改时立即重算） */
    private final Matrix4f localMatrix = new Matrix4f();

    /** 世界矩阵（脏标记惰性重算） */
    private final Matrix4f worldMatrix = new Matrix4f();

    /** 世界矩阵脏标记 */
    private boolean worldDirty = true;

    Transform() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  局部位置
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取局部位置引用（只读语义，修改请使用 setter 或配合 {@link #markDirty()}）。
     */
    public Vector3f localPosition() {
        return localPosition;
    }

    public void setLocalPosition(float x, float y, float z) {
        localPosition.set(x, y, z);
        markDirty();
    }

    public void setLocalPosition(Vector3f position) {
        localPosition.set(position);
        markDirty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  局部旋转
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取局部旋转引用（只读语义，修改请使用 setter 或配合 {@link #markDirty()}）。
     */
    public Quaternionf localRotation() {
        return localRotation;
    }

    public void setLocalRotation(Quaternionf rotation) {
        localRotation.set(rotation);
        markDirty();
    }

    public void setLocalRotation(float x, float y, float z, float w) {
        localRotation.set(x, y, z, w);
        markDirty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  局部缩放
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取局部缩放引用（只读语义，修改请使用 setter 或配合 {@link #markDirty()}）。
     */
    public Vector3f localScale() {
        return localScale;
    }

    public void setLocalScale(float scale) {
        localScale.set(scale, scale, scale);
        markDirty();
    }

    public void setLocalScale(float x, float y, float z) {
        localScale.set(x, y, z);
        markDirty();
    }

    public void setLocalScale(Vector3f scale) {
        localScale.set(scale);
        markDirty();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  脏标记与矩阵
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 标记局部数据已修改：重算局部矩阵，并向所有子孙传播世界脏标记。
     * <p>通过 {@link #localPosition()} 等返回的引用直接修改 JOML 对象后必须调用。</p>
     */
    public void markDirty() {
        localMatrix.translationRotateScale(localPosition, localRotation, localScale);
        markWorldDirty();
    }

    /**
     * 置自身世界脏标记并递归传播到所有子孙（子孙 local 未变但 world 需重算）。
     */
    private void markWorldDirty() {
        worldDirty = true;
        for (int i = 0; i < children.size(); i++) {
            children.get(i).markWorldDirty();
        }
    }

    /**
     * 获取局部矩阵（TRS 组合结果，TRS 修改时立即重算，始终为最新值）。
     */
    public Matrix4f localMatrix() {
        return localMatrix;
    }

    /**
     * 获取世界矩阵（脏标记惰性重算并缓存）。
     *
     * <p>计算规则：无父级时等于局部矩阵，否则为父级世界矩阵 × 局部矩阵。</p>
     *
     * @return 世界矩阵（内部缓存对象，请勿长期持有或修改）
     */
    public Matrix4f worldMatrix() {
        if (worldDirty) {
            if (parent == null) {
                worldMatrix.set(localMatrix);
            } else {
                parent.worldMatrix().mul(localMatrix, worldMatrix);
            }
            worldDirty = false;
        }
        return worldMatrix;
    }

    /**
     * 提取世界空间位置到输出向量。
     *
     * @param out 输出向量
     * @return out（便于链式调用）
     */
    public Vector3f worldPosition(Vector3f out) {
        Matrix4f m = worldMatrix();
        return out.set(m.m30(), m.m31(), m.m32());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  层级关系
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取父 Transform，无父级时返回 null。
     */
    public Transform parent() {
        return parent;
    }

    /**
     * 设置父 Transform（local TRS 保持不变，不做 keepWorld 换算）。
     *
     * <p>维护父子双向链接；父级变化可能改变激活层级状态，
     * 会同步通知所属节点刷新组件激活状态与场景引用（递归子孙）。</p>
     *
     * @param newParent 新的父 Transform，传 null 表示脱离父级
     * @throws IllegalStateException 如果 newParent 是自身的子孙（会形成环）
     */
    public void setParent(Transform newParent) {
        if (this.parent == newParent) {
            return;
        }
        if (newParent != null && isDescendantOf(newParent)) {
            throw new IllegalStateException("不能将父级设置为自己的子孙 Transform");
        }
        if (parent != null) {
            parent.children.remove(this);
        }
        parent = newParent;
        if (newParent != null) {
            newParent.children.add(this);
        }
        markWorldDirty();
        if (node() != null) {
            node().onHierarchyChanged();
        }
    }

    /**
     * 判断 candidate 是否位于以本节点为根的子树中（不含本节点自身）。
     */
    private boolean isDescendantOf(Transform candidate) {
        Transform p = candidate;
        while (p != null) {
            if (p == this) {
                return true;
            }
            p = p.parent;
        }
        return false;
    }

    /**
     * 脱离父级（不触发节点层级通知，仅供销毁流程使用）。
     */
    void removeFromParent() {
        if (parent != null) {
            parent.children.remove(this);
            parent = null;
        }
    }

    /**
     * 获取子级数量。
     */
    public int getChildCount() {
        return children.size();
    }

    /**
     * 按索引获取子 Transform。
     */
    public Transform getChild(int index) {
        return children.get(index);
    }

    /**
     * 获取只读子级列表视图（底层为活跃列表，遍历中如需结构性安全请自行拷贝）。
     */
    public List<Transform> children() {
        return Collections.unmodifiableList(children);
    }

    /**
     * 迭代直接子 Transform（只读，不支持 remove）。
     *
     * <p>便于 {@code for (Transform child : transform)} 形式遍历子节点。</p>
     */
    @Override
    public Iterator<Transform> iterator() {
        return children().iterator();
    }
}
