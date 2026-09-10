package com.javelin.core.scene;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transform 单元测试：层级矩阵计算、脏标记缓存与父级切换。
 */
class TransformTest {

    @Test
    void worldMatrixAccumulatesParentChain() {
        Node parent = new Node("parent");
        Node child = new Node("child");
        Node grandChild = new Node("grandChild");

        parent.transform().setLocalPosition(1, 2, 3);
        child.transform().setLocalPosition(1, 0, 0);
        grandChild.transform().setLocalPosition(0, 1, 0);

        parent.addChild(child);
        child.addChild(grandChild);

        Vector3f world = grandChild.transform().worldPosition(new Vector3f());
        assertEquals(2.0f, world.x, 1e-6f);
        assertEquals(3.0f, world.y, 1e-6f);
        assertEquals(3.0f, world.z, 1e-6f);
    }

    @Test
    void parentScaleAffectsChildWorld() {
        Node parent = new Node("parent");
        Node child = new Node("child");

        parent.transform().setLocalPosition(10, 0, 0);
        parent.transform().setLocalScale(2.0f);
        child.transform().setLocalPosition(1, 0, 0);

        parent.addChild(child);

        Vector3f world = child.transform().worldPosition(new Vector3f());
        assertEquals(12.0f, world.x, 1e-6f);
        assertEquals(0.0f, world.y, 1e-6f);
        assertEquals(0.0f, world.z, 1e-6f);
    }

    @Test
    void dirtyFlagTriggersRecomputeDownTheChain() {
        Node parent = new Node("parent");
        Node child = new Node("child");
        parent.addChild(child);
        child.transform().setLocalPosition(1, 0, 0);

        // 首次计算并缓存
        assertEquals(1.0f, child.transform().worldPosition(new Vector3f()).x, 1e-6f);

        // 修改父级 TRS，子孙世界矩阵应失效并随访问重算
        parent.transform().setLocalPosition(5, 0, 0);
        Vector3f world = child.transform().worldPosition(new Vector3f());
        assertEquals(6.0f, world.x, 1e-6f);
    }

    @Test
    void setParentKeepsLocalTrs() {
        Node a = new Node("a");
        Node b = new Node("b");
        a.transform().setLocalPosition(100, 0, 0);
        b.transform().setLocalPosition(1, 2, 3);

        b.transform().setParent(a.transform());

        // local TRS 不变，世界坐标受新父级影响
        assertEquals(1.0f, b.transform().localPosition().x, 1e-6f);
        Vector3f world = b.transform().worldPosition(new Vector3f());
        assertEquals(101.0f, world.x, 1e-6f);
        assertEquals(2.0f, world.y, 1e-6f);
        assertEquals(3.0f, world.z, 1e-6f);
    }

    @Test
    void setParentRejectsCycle() {
        Node a = new Node("a");
        Node b = new Node("b");
        a.addChild(b);
        assertThrows(IllegalStateException.class, () -> a.transform().setParent(b.transform()));
    }

    @Test
    void detachFromParentMakesWorldEqualLocal() {
        Node parent = new Node("parent");
        Node child = new Node("child");
        parent.transform().setLocalPosition(7, 8, 9);
        parent.addChild(child);

        child.transform().setParent(null);

        assertNull(child.transform().parent());
        assertNull(child.getParent());
        assertEquals(0, parent.transform().getChildCount());
        Vector3f world = child.transform().worldPosition(new Vector3f());
        assertEquals(0.0f, world.x, 1e-6f);
        assertEquals(0.0f, world.y, 1e-6f);
        assertEquals(0.0f, world.z, 1e-6f);
    }

    @Test
    void markDirtySupportsDirectJomlMutation() {
        Node node = new Node("n");
        node.transform().localPosition().set(3, 4, 5);
        node.transform().markDirty();

        Vector3f world = node.transform().worldPosition(new Vector3f());
        assertEquals(3.0f, world.x, 1e-6f);
        assertEquals(4.0f, world.y, 1e-6f);
        assertEquals(5.0f, world.z, 1e-6f);
    }

    @Test
    void rotationAffectsChildWorld() {
        Node parent = new Node("parent");
        Node child = new Node("child");
        // 绕 Z 轴旋转 90 度：(1,0,0) → (0,1,0)
        parent.transform().setLocalRotation(new Quaternionf().rotateZ((float) Math.PI / 2));
        child.transform().setLocalPosition(1, 0, 0);
        parent.addChild(child);

        Vector3f world = child.transform().worldPosition(new Vector3f());
        assertEquals(0.0f, world.x, 1e-5f);
        assertEquals(1.0f, world.y, 1e-5f);
        assertEquals(0.0f, world.z, 1e-5f);
    }

    @Test
    void iteratorVisitsDirectChildren() {
        Node parent = new Node("parent");
        Node a = new Node("a");
        Node b = new Node("b");
        Node grandChild = new Node("grand");
        parent.addChild(a);
        parent.addChild(b);
        a.addChild(grandChild);

        int count = 0;
        for (Transform child : parent.transform()) {
            count++;
            assertSame(parent.transform(), child.parent());
        }
        assertEquals(2, count);
    }
}
