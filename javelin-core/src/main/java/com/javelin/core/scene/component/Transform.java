package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.inspector.InsField;
import com.javelin.core.inspector.InsHeader;
import com.javelin.core.inspector.InsRotation;
import com.javelin.core.scene.Component;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * 变换组件（每个 Node 必有且唯一，构造时自动挂载）。
 *
 * <p>节点层级即 Transform 树：Transform 的 children 即子节点的 Transform，
 * 可直接 for-each 遍历子变换（{@code for (Transform child : transform)}）。
 * localToWorld 矩阵带脏标记缓存——仅当 local 变换或父链变化时重算自身并向下传播，
 * 不每帧全量重算（见场景设计文档 2.4 节）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
@InsHeader(label = "ins.transform", icon = "transform")
public class Transform extends Component implements Iterable<Transform> {

    private Transform parent;
    // 非 final：序列化反序列化需回写（红线 9）
    // 类型用 ArrayList 直通（红线 10：序列化集合字段不经包装，直传字段本体）
    private ArrayList<Transform> children = new ArrayList<>();

    @InsField(label = "ins.transform.position")
    private Vector3f localPosition = new Vector3f();
    @InsRotation
    @InsField(label = "ins.transform.rotation")
    private Quaternionf localRotation = new Quaternionf();
    @InsField(label = "ins.transform.scale")
    private Vector3f localScale = new Vector3f(1.0f, 1.0f, 1.0f);

    private Matrix4f localMatrix = new Matrix4f();
    private Matrix4f localToWorld = new Matrix4f();
    private boolean dirty = true;

    public Transform() {
    }

    public Transform getParent() {
        return parent;
    }

    /**
     * 设置父变换。父为 null 即挂到场景根。
     *
     * <p>保持自身 local 变换不变（世界矩阵随之变化）；不允许将自身或
     * 自己的后代设为父节点（成环）。</p>
     */
    public void setParent(Transform newParent) {
        if (newParent == this) {
            throw new IllegalArgumentException("不能将自身设为父节点");
        }
        for (Transform p = newParent; p != null; p = p.parent) {
            if (p == this) {
                throw new IllegalArgumentException("父节点不能是自己的后代");
            }
        }
        if (parent != null) {
            parent.children.remove(this);
        }
        parent = newParent;
        if (newParent != null) {
            newParent.children.add(this);
        }
        markDirty();
        // 挂到已属场景的父节点下 → 整棵子树自动入场景（Node.onParentChanged 内归属检查）
        if (getNode() != null) {
            getNode().onParentChanged();
        }
    }

    public int getChildCount() {
        return children.size();
    }

    public Transform getChild(int index) {
        return children.get(index);
    }

    /**
     * 迭代子变换（for-each 直接遍历 children）。
     */
    @Override
    public Iterator<Transform> iterator() {
        return children.iterator();
    }

    public Vector3f getLocalPosition() {
        return localPosition;
    }

    public void setLocalPosition(Vector3f localPosition) {
        this.localPosition.set(localPosition);
        markDirty();
    }

    public Quaternionf getLocalRotation() {
        return localRotation;
    }

    public void setLocalRotation(Quaternionf localRotation) {
        this.localRotation.set(localRotation);
        markDirty();
    }

    public Vector3f getLocalScale() {
        return localScale;
    }

    public void setLocalScale(Vector3f localScale) {
        this.localScale.set(localScale);
        markDirty();
    }

    /**
     * 局部到世界矩阵（脏时重算，结果缓存在内部对象中，勿长期持有修改）。
     */
    public Matrix4f getLocalToWorld() {
        if (dirty) {
            localMatrix.translationRotateScale(localPosition, localRotation, localScale);
            if (parent != null) {
                parent.getLocalToWorld().mul(localMatrix, localToWorld);
            } else {
                localToWorld.set(localMatrix);
            }
            dirty = false;
        }
        return localToWorld;
    }

    /**
     * 世界坐标位置（写入 out，避免分配）。
     */
    public Vector3f getWorldPosition(Vector3f out) {
        return getLocalToWorld().getTranslation(out);
    }

    private void markDirty() {
        if (dirty) {
            // 自身已脏则子树必然也已脏，无需向下传播
            return;
        }
        dirty = true;
        for (Transform child : children) {
            child.markDirty();
        }
    }

    /**
     * 序列化数据面：parent/children 双向回链（树形本体）+ local TRS。
     *
     * <p>localMatrix/localToWorld/dirty 为运行时派生缓存不落盘——read 后
     * dirty 默认 true，首次 getLocalToWorld 重算。children 直传字段本体
     * （红线 10，框架 id 引用保共享）；read 直接回写 parent/children
     * <b>不走 setParent</b>——不触发 onParentChanged 场景联动（反序列化不触发
     * 生命周期，语义见设计档 §9.3；started 由 update 首帧补发 onStart）。</p>
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("parent", parent);
        output.write("children", children);
        output.write("localPosition", localPosition);
        output.write("localRotation", localRotation);
        output.write("localScale", localScale);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        parent = input.readSerializable("parent", Transform.class);
        ArrayList<Transform> childList = input.readArrayList("children", Transform.class);
        children = childList != null ? childList : new ArrayList<>();
        localPosition = input.readVector3f("localPosition");
        localRotation = input.readQuaternionf("localRotation");
        localScale = input.readVector3f("localScale");
    }
}
