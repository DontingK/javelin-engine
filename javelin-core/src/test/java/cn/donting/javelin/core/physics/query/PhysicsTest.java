package cn.donting.javelin.core.physics.query;

import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.physics.query.Physics;
import cn.donting.javelin.core.physics.query.RaycastHit;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Layer;
import cn.donting.javelin.core.scene.LayerMask;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.BoxCollider;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 场景级 raycast 单测（Physics 门面 + RaycastHit，Engine_Collision_Design.md §10）。
 *
 * <p>覆盖：最近命中择取与 out 填装（点/法线/距离/组件/节点引用）、未命中 out 不变
 * （复用缓冲契约）、maxDistance 含边界过滤、layerMask 过滤、非激活节点/禁用碰撞体
 * 跳过、裸/摘出场景碰撞体不参与、raycastAll 清空+距离升序、入口方向归一化
 * （distance 一律世界距离）、节点世界变换端到端、未绑定场景显式抛。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class PhysicsTest {

    /** 解析用例浮点容差 */
    private static final float EPS = 1e-4f;

    private GameScene scene;

    @BeforeEach
    void setUp() {
        scene = new GameScene("raycast-test");
        Physics.setScene(scene);
    }

    @AfterEach
    void tearDown() {
        Physics.setScene(null);
    }

    /** 建 2×2×2 盒碰撞体节点并挂入场景 */
    private BoxCollider addBox(float x, float y, float z) {
        Node node = new Node();
        node.getTransform().setLocalPosition(new Vector3f(x, y, z));
        BoxCollider collider = node.addComponent(BoxCollider.class);
        collider.setSize(new Vector3f(2, 2, 2));
        scene.add(node);
        return collider;
    }

    /** 自 +Z 向 −Z 的射线（origin (0,0,5)，方向归一化） */
    private Ray forwardRay() {
        return new Ray(new Vector3f(0, 0, 5), new Vector3f(0, 0, -1));
    }

    @Test
    void raycastPicksNearestAndFillsHit() {
        addBox(0, 0, -10);
        BoxCollider near = addBox(0, 0, 0);

        RaycastHit hit = new RaycastHit();
        assertTrue(Physics.raycast(forwardRay(), hit));

        // 近盒面 z=1：距离 = 5 − 1 = 4
        assertEquals(4.0f, hit.getDistance(), EPS);
        assertSame(near, hit.getCollider());
        assertSame(near.getNode(), hit.getNode());
        assertEquals(0.0f, hit.getPoint().x, EPS);
        assertEquals(0.0f, hit.getPoint().y, EPS);
        assertEquals(1.0f, hit.getPoint().z, EPS);
        assertEquals(0.0f, hit.getNormal().x, EPS);
        assertEquals(0.0f, hit.getNormal().y, EPS);
        assertEquals(1.0f, hit.getNormal().z, EPS);
    }

    @Test
    void raycastMissReturnsFalseAndLeavesOutUnchanged() {
        BoxCollider box = addBox(0, 0, 0);
        RaycastHit hit = new RaycastHit();
        assertTrue(Physics.raycast(forwardRay(), hit));
        assertEquals(4.0f, hit.getDistance(), EPS);

        // 射线移到旁侧 → 未命中；out 保持上次命中值（L0 out 参不变契约）
        Ray missRay = new Ray(new Vector3f(10, 0, 5), new Vector3f(0, 0, -1));
        assertFalse(Physics.raycast(missRay, hit));
        assertEquals(4.0f, hit.getDistance(), EPS);
        assertSame(box, hit.getCollider());
    }

    @Test
    void raycastMaxDistanceFilterIncludesBoundary() {
        addBox(0, 0, 0); // t = 4

        RaycastHit hit = new RaycastHit();
        assertFalse(Physics.raycast(forwardRay(), 3.5f, hit));
        assertTrue(Physics.raycast(forwardRay(), 4.0f, hit));
        assertEquals(4.0f, hit.getDistance(), EPS);
        assertTrue(Physics.raycast(forwardRay(), 5.0f, hit));
    }

    @Test
    void raycastLayerMaskFilters() {
        addBox(0, 0, 0); // 默认层（Layer.DEFAULT）
        RaycastHit hit = new RaycastHit();

        assertFalse(Physics.raycast(forwardRay(), Float.POSITIVE_INFINITY, LayerMask.nothing(), hit));
        assertFalse(Physics.raycast(forwardRay(), Float.POSITIVE_INFINITY, ~Layer.DEFAULT.getMask(), hit));
        assertTrue(Physics.raycast(forwardRay(), Float.POSITIVE_INFINITY, LayerMask.of(Layer.DEFAULT), hit));
        assertTrue(Physics.raycast(forwardRay(), Float.POSITIVE_INFINITY, LayerMask.everything(), hit));
    }

    @Test
    void raycastSkipsInactiveNode() {
        BoxCollider box = addBox(0, 0, 0);
        Node node = box.getNode();

        node.setActive(false);
        assertFalse(Physics.raycast(forwardRay(), new RaycastHit()));

        node.setActive(true);
        assertTrue(Physics.raycast(forwardRay(), new RaycastHit()));
    }

    @Test
    void raycastSkipsDisabledCollider() {
        BoxCollider box = addBox(0, 0, 0);
        RaycastHit hit = new RaycastHit();

        box.setEnabled(false);
        assertFalse(Physics.raycast(forwardRay(), hit));

        box.setEnabled(true);
        assertTrue(Physics.raycast(forwardRay(), hit));
    }

    @Test
    void raycastIgnoresColliderOutsideScene() {
        // 裸节点（未挂场景）不注册 → 不参与查询
        Node bare = new Node();
        bare.addComponent(BoxCollider.class);
        assertFalse(Physics.raycast(forwardRay(), new RaycastHit()));

        // 挂入后摘出同样退场（注册表注销）
        BoxCollider box = addBox(0, 0, 0);
        assertTrue(Physics.raycast(forwardRay(), new RaycastHit()));
        scene.remove(box.getNode());
        assertFalse(Physics.raycast(forwardRay(), new RaycastHit()));
    }

    @Test
    void raycastAllClearsAndSortsByDistance() {
        BoxCollider far = addBox(0, 0, -10);   // t = 14
        BoxCollider near = addBox(0, 0, 0);    // t = 4
        BoxCollider mid = addBox(0, 0, -5);    // t = 9

        // 预置一条脏数据：方法内 clear 后填充
        List<RaycastHit> out = new ArrayList<>();
        out.add(new RaycastHit());
        int count = Physics.raycastAll(forwardRay(), out);

        assertEquals(3, count);
        assertEquals(3, out.size());
        assertEquals(4.0f, out.get(0).getDistance(), EPS);
        assertEquals(9.0f, out.get(1).getDistance(), EPS);
        assertEquals(14.0f, out.get(2).getDistance(), EPS);
        assertSame(near, out.get(0).getCollider());
        assertSame(mid, out.get(1).getCollider());
        assertSame(far, out.get(2).getCollider());
    }

    @Test
    void raycastAllAppliesMaxDistanceAndLayerMask() {
        addBox(0, 0, 0);    // t = 4
        addBox(0, 0, -5);   // t = 9
        addBox(0, 0, -10);  // t = 14
        List<RaycastHit> out = new ArrayList<>();

        assertEquals(2, Physics.raycastAll(forwardRay(), 10.0f, out));
        assertEquals(0, Physics.raycastAll(forwardRay(), Float.POSITIVE_INFINITY, LayerMask.nothing(), out));
    }

    @Test
    void raycastAllEmptySceneReturnsZero() {
        List<RaycastHit> out = new ArrayList<>();
        out.add(new RaycastHit());
        assertEquals(0, Physics.raycastAll(forwardRay(), out));
        assertTrue(out.isEmpty());
    }

    @Test
    void raycastNormalizesDirectionAtEntry() {
        addBox(0, 0, 0);
        // 非归一化方向（实长 2）：distance 仍按世界距离（入口一次性归一化）
        Ray ray = new Ray(new Vector3f(0, 0, 5), new Vector3f(0, 0, -2));
        RaycastHit hit = new RaycastHit();

        assertTrue(Physics.raycast(ray, hit));
        assertEquals(4.0f, hit.getDistance(), EPS);
        assertEquals(1.0f, hit.getPoint().z, EPS);
        // 调用方射线不被改写
        assertEquals(-2.0f, ray.getDirection().z, EPS);
    }

    @Test
    void raycastUsesNodeWorldTransform() {
        // 平移 + 均匀缩放：世界半程 2 → 面 z = −8，距离 13
        Node node = new Node();
        node.getTransform().setLocalPosition(new Vector3f(0, 0, -10));
        node.getTransform().setLocalScale(new Vector3f(2, 2, 2));
        BoxCollider box = node.addComponent(BoxCollider.class);
        box.setSize(new Vector3f(2, 2, 2));
        scene.add(node);

        RaycastHit hit = new RaycastHit();
        assertTrue(Physics.raycast(forwardRay(), hit));
        assertEquals(13.0f, hit.getDistance(), EPS);
        assertEquals(-8.0f, hit.getPoint().z, EPS);
        assertEquals(1.0f, hit.getNormal().z, EPS);
    }

    @Test
    void unboundSceneThrows() {
        Physics.setScene(null);
        assertThrows(IllegalStateException.class, () -> Physics.raycast(forwardRay(), new RaycastHit()));
        assertThrows(IllegalStateException.class, () -> Physics.raycastAll(forwardRay(), new ArrayList<>()));
    }

    @Test
    void zeroDirectionRayMisses() {
        addBox(0, 0, 0);
        Ray zero = new Ray(new Vector3f(0, 0, 5), new Vector3f(0, 0, 0));
        RaycastHit hit = new RaycastHit();
        // 零方向 → 入口归一化得 NaN 方向 → BoxShape 走 tFar=+Inf 分支；
        // 有限值闸门兜住（Inf > maxDistance(∞) 为 false，限距判据挡不住），不视为命中
        assertFalse(Physics.raycast(zero, hit));
        assertNull(hit.getCollider());
    }
}
