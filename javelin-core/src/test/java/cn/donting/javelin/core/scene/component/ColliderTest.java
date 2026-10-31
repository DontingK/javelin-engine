package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.BoxCollider;
import cn.donting.javelin.core.scene.component.CylinderCollider;
import cn.donting.javelin.core.scene.component.SphereCollider;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collider 组件查询管线单测（分片②，Engine_Collision_Design.md §6/§7）。
 *
 * <p>验证 {@code collideShape} 统一管线：世界射线 → 逆矩阵变换（origin 扣 center、
 * direction 不归一化 §4.2）→ 形状例程 → 命中点经 M 回换/法线经法线矩阵归一化。
 * 覆盖：恒等/平移/旋转/非均匀缩放节点、center 偏移、世界距离不变量（t ==
 * |hit − o_w|）、法线矩阵交叉验证（invert().transpose() 独立路径对照 impl 的
 * normal()——能抓「拿 M 直接转法线」的错）、调用方射线不被改写、裸组件守卫、
 * 参数改值即时生效（每查同步）。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class ColliderTest {

    /** 解析用例浮点容差 */
    private static final float EPS = 1e-4f;

    /** 矩阵链路用例容差（invert/transform 多级舍入） */
    private static final float MATRIX_EPS = 1e-3f;

    // 未命中不变哨兵：验证 out 参复用契约
    private final Vector3f hitPoint = new Vector3f(9, 9, 9);
    private final Vector3f hitNormal = new Vector3f(8, 8, 8);

    private BoxCollider boxOnNode(Node node) {
        return node.addComponent(BoxCollider.class);
    }

    @Test
    void boxColliderWorldRayHitIdentityNode() {
        Node node = new Node();
        BoxCollider collider = boxOnNode(node);
        collider.setSize(new Vector3f(2, 2, 2));
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 1));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(4.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
        // 管线不得改写调用方射线
        assertEquals(0.0f, ray.getOrigin().x, EPS);
        assertEquals(0.0f, ray.getOrigin().y, EPS);
        assertEquals(-5.0f, ray.getOrigin().z, EPS);
        assertEquals(0.0f, ray.getDirection().x, EPS);
        assertEquals(1.0f, ray.getDirection().z, EPS);
    }

    @Test
    void boxColliderCenterOffsetShiftsHit() {
        Node node = new Node();
        BoxCollider collider = boxOnNode(node);
        collider.setSize(new Vector3f(2, 2, 2));
        collider.setCenter(new Vector3f(5, 0, 0));
        Ray ray = new Ray(new Vector3f(5, 0, -5), new Vector3f(0, 0, 1));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        // 形状本地盒经 center 平移到节点本地 [4,6]；命中量回世界含 center
        assertEquals(4.0f, t, EPS);
        assertEquals(5.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.z + 1.0f, EPS);
    }

    @Test
    void boxColliderNodeTranslationMovesHit() {
        Node node = new Node();
        node.getTransform().setLocalPosition(new Vector3f(10, 0, 0));
        BoxCollider collider = boxOnNode(node);
        collider.setSize(new Vector3f(2, 2, 2));
        Ray ray = new Ray(new Vector3f(10, 0, -5), new Vector3f(0, 0, 1));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(4.0f, t, EPS);
        assertEquals(10.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
    }

    @Test
    void boxColliderRotatedNodeHandCheck() {
        Node node = new Node();
        node.getTransform().setLocalRotation(new Quaternionf().rotationY((float) Math.toRadians(90.0)));
        BoxCollider collider = boxOnNode(node);
        // 本地 x 向长条（半尺寸 2）：rotY(90°) 后长边落世界 z 轴——盒占世界 z∈[-2,2]
        collider.setSize(new Vector3f(4, 2, 2));
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 1));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        // 入射面=本地 +x 面 → 世界法线 rotY(90°)·(1,0,0) = (0,0,-1)
        assertEquals(3.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-2.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
    }

    @Test
    void boxColliderNonUniformScaleRoundTrip() {
        Node node = new Node();
        node.getTransform().setLocalPosition(new Vector3f(3, 0, 0));
        node.getTransform().setLocalScale(new Vector3f(2, 3, 1));
        BoxCollider collider = boxOnNode(node);
        collider.setSize(new Vector3f(2, 2, 2));
        // 世界盒：中心 (3,0,0) 半尺寸 (2,3,1)

        Ray zRay = new Ray(new Vector3f(3, 0, -5), new Vector3f(0, 0, 1));
        float t = collider.collideWithRay(zRay, hitPoint, hitNormal);
        assertEquals(4.0f, t, EPS);
        assertEquals(3.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);

        Ray xRay = new Ray(new Vector3f(-3, 0, 0), new Vector3f(1, 0, 0));
        t = collider.collideWithRay(xRay, hitPoint, hitNormal);
        assertEquals(4.0f, t, EPS);
        assertEquals(1.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(0.0f, hitPoint.z, EPS);
        assertEquals(-1.0f, hitNormal.x, EPS);
    }

    @Test
    void boxColliderKitchenSinkInvariants() {
        // T·R·S 全家桶：不手算几何，用不变量钉死管线——t==世界距离、法线单位且迎着射线、
        // 命中点经逆变换回本地必在盒面上
        Node node = new Node();
        node.getTransform().setLocalPosition(new Vector3f(3, 0, 0));
        node.getTransform().setLocalRotation(new Quaternionf().rotationY((float) Math.toRadians(30.0)));
        node.getTransform().setLocalScale(new Vector3f(2, 3, 4));
        BoxCollider collider = boxOnNode(node);
        collider.setSize(new Vector3f(2, 2, 2));

        Vector3f origin = new Vector3f(3, 0, -5);
        Vector3f dir = new Vector3f(0, 0, 1);
        float t = collider.collideWithRay(new Ray(origin, dir), hitPoint, hitNormal);

        assertTrue(t >= 0.0f, "旋转+非均匀缩放节点的世界射线应命中");
        assertEquals(t, hitPoint.distance(origin), MATRIX_EPS);
        assertEquals(1.0f, hitNormal.length(), MATRIX_EPS);
        assertTrue(hitNormal.dot(dir) < 0.0f, "入射命中法线应迎着射线方向");
        // 命中点回本地在盒面上（center=0：组件本地即形状本地）
        Matrix4f inv = node.getTransform().getLocalToWorld().invert(new Matrix4f());
        Vector3f localHit = inv.transformPosition(new Vector3f(hitPoint));
        float maxAbs = Math.max(Math.abs(localHit.x), Math.max(Math.abs(localHit.y), Math.abs(localHit.z)));
        assertEquals(1.0f, maxAbs, MATRIX_EPS);
    }

    @Test
    void sphereColliderWorldRayHit() {
        Node node = new Node();
        SphereCollider collider = node.addComponent(SphereCollider.class);
        collider.setRadius(1.0f);
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 1));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(4.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
    }

    @Test
    void sphereColliderUnnormalizedWorldDirection() {
        Node node = new Node();
        SphereCollider collider = node.addComponent(SphereCollider.class);
        collider.setRadius(1.0f);
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 2));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        // 契约：t 以 worldRay.direction 实长为单位（|d|=2 → t=2，世界位移 4）
        assertEquals(2.0f, t, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
    }

    @Test
    void sphereColliderFromInsideHitsExit() {
        Node node = new Node();
        SphereCollider collider = node.addComponent(SphereCollider.class);
        collider.setRadius(1.0f);
        Ray ray = new Ray(new Vector3f(0, 0, 0), new Vector3f(0, 0, 1));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(1.0f, t, EPS);
        assertEquals(1.0f, hitPoint.z, EPS);
        assertEquals(1.0f, hitNormal.z, EPS);
    }

    @Test
    void sphereColliderNormalCrossCheckedAgainstInverseTranspose() {
        // 法线矩阵专项：旋转+非均匀缩放下球面法线 = (M⁻¹)ᵀ·p_l 方向——
        // 用 invert().transpose() 独立路径对照实现侧 normal()（能抓「拿 M 直接转法线」的错）
        Node node = new Node();
        node.getTransform().setLocalRotation(new Quaternionf().rotationY((float) Math.toRadians(45.0)));
        node.getTransform().setLocalScale(new Vector3f(2, 3, 1));
        SphereCollider collider = node.addComponent(SphereCollider.class);
        collider.setRadius(1.0f);

        Vector3f origin = new Vector3f(0, 0, -5);
        Vector3f dir = new Vector3f(0, 0, 1);
        float t = collider.collideWithRay(new Ray(origin, dir), hitPoint, hitNormal);

        assertTrue(t >= 0.0f, "过椭球中心的世界射线应命中");
        assertTrue(hitNormal.dot(dir) < 0.0f, "入射命中法线应迎着射线方向");
        // 独立重算：p_l = M⁻¹·hit_w；n_w = normalize((M⁻¹)ᵀ·p_l)（球面法线=径向）
        Matrix4f inv = node.getTransform().getLocalToWorld().invert(new Matrix4f());
        Vector3f localHit = inv.transformPosition(new Vector3f(hitPoint));
        Vector3f expected = inv.transpose(new Matrix4f()).transformDirection(localHit).normalize();
        assertEquals(expected.x, hitNormal.x, MATRIX_EPS);
        assertEquals(expected.y, hitNormal.y, MATRIX_EPS);
        assertEquals(expected.z, hitNormal.z, MATRIX_EPS);
    }

    @Test
    void colliderMissLeavesOutParamsUnchanged() {
        Node node = new Node();
        BoxCollider collider = boxOnNode(node);
        collider.setSize(new Vector3f(2, 2, 2));
        Ray ray = new Ray(new Vector3f(3, 0, -5), new Vector3f(0, 0, 1));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(-1.0f, t, EPS);
        assertEquals(9.0f, hitPoint.x, EPS);
        assertEquals(9.0f, hitPoint.y, EPS);
        assertEquals(9.0f, hitPoint.z, EPS);
        assertEquals(8.0f, hitNormal.x, EPS);
        assertEquals(8.0f, hitNormal.y, EPS);
        assertEquals(8.0f, hitNormal.z, EPS);
    }

    @Test
    void bareColliderWithoutNodeMisses() {
        BoxCollider collider = new BoxCollider();
        collider.setSize(new Vector3f(2, 2, 2));
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 1));

        assertEquals(-1.0f, collider.collideWithRay(ray, hitPoint, hitNormal), EPS);
    }

    @Test
    void sphereColliderRadiusChangeTakesEffectImmediately() {
        Node node = new Node();
        SphereCollider collider = node.addComponent(SphereCollider.class);
        collider.setRadius(1.0f);
        Ray ray = new Ray(new Vector3f(2, 0, -5), new Vector3f(0, 0, 1));

        assertEquals(-1.0f, collider.collideWithRay(ray, hitPoint, hitNormal), EPS);

        // 参数改值即时生效（每查同步，无脏标记）
        collider.setRadius(2.5f);
        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(3.5f, t, EPS);
        assertEquals(2.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.5f, hitPoint.z, EPS);
        assertEquals(0.8f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(-0.6f, hitNormal.z, EPS);
    }

    // ═══ fitFromMesh（片③） ═══

    /** [0,2]³ 立方体 8 角点（bounds 拟合无需索引） */
    private Mesh cubeMesh() {
        Mesh mesh = new Mesh();
        mesh.setPositions(new float[]{
                0, 0, 0, 2, 0, 0, 0, 2, 0, 2, 2, 0,
                0, 0, 2, 2, 0, 2, 0, 2, 2, 2, 2, 2});
        return mesh;
    }

    @Test
    void boxColliderFitFromMeshThenQueryHits() {
        Node node = new Node();
        BoxCollider collider = boxOnNode(node);
        collider.fitFromMesh(cubeMesh());

        // 拟合结果：center=(1,1,1)、size=(2,2,2)（精确贴合包围盒）
        assertEquals(1, collider.getCenter().x, EPS);
        assertEquals(1, collider.getCenter().y, EPS);
        assertEquals(1, collider.getCenter().z, EPS);
        assertEquals(2, collider.getSize().x, EPS);
        assertEquals(2, collider.getSize().y, EPS);
        assertEquals(2, collider.getSize().z, EPS);

        // 闭环：拟合出的碰撞体直接吃世界射线
        Ray ray = new Ray(new Vector3f(1, 1, -5), new Vector3f(0, 0, 1));
        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(5, t, EPS);
        assertEquals(1, hitPoint.x, EPS);
        assertEquals(1, hitPoint.y, EPS);
        assertEquals(0, hitPoint.z, EPS);
        assertEquals(0, hitNormal.x, EPS);
        assertEquals(0, hitNormal.y, EPS);
        assertEquals(-1, hitNormal.z, EPS);
    }

    @Test
    void sphereColliderFitFromMeshThenQueryHits() {
        Node node = new Node();
        SphereCollider collider = node.addComponent(SphereCollider.class);
        collider.fitFromMesh(cubeMesh());

        // 保守包围：center=(1,1,1)、radius = 半对角线 √3
        assertEquals(1, collider.getCenter().x, EPS);
        assertEquals(1, collider.getCenter().z, EPS);
        assertEquals(Math.sqrt(3.0), collider.getRadius(), EPS);

        Ray ray = new Ray(new Vector3f(1, 1, -5), new Vector3f(0, 0, 1));
        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        // 射线过球心：入射 z = 1−√3 → t = 6−√3
        assertEquals(6.0 - Math.sqrt(3.0), t, EPS);
        assertEquals(1, hitPoint.x, EPS);
        assertEquals(1, hitPoint.y, EPS);
        assertEquals(1.0 - Math.sqrt(3.0), hitPoint.z, EPS);
        assertEquals(0, hitNormal.x, EPS);
        assertEquals(0, hitNormal.y, EPS);
        assertEquals(-1, hitNormal.z, EPS);
    }

    @Test
    void fitFromMeshRejectsMeshWithoutPositions() {
        Mesh mesh = new Mesh();
        mesh.setUVs(new float[]{0, 1, 1, 1, 0.5f, 0});

        BoxCollider box = new BoxCollider();
        SphereCollider sphere = new SphereCollider();
        assertThrows(IllegalStateException.class, () -> box.fitFromMesh(mesh));
        assertThrows(IllegalStateException.class, () -> sphere.fitFromMesh(mesh));
    }

    // ═══ CylinderCollider（2026-10-05 编辑器移动轴拾取） ═══

    @Test
    void cylinderColliderWorldRayHit() {
        Node node = new Node();
        CylinderCollider collider = node.addComponent(CylinderCollider.class);
        collider.setRadius(1.0f);
        collider.setHeight(2.0f);
        Ray ray = new Ray(new Vector3f(0, 0, -5), new Vector3f(0, 0, 1));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        assertEquals(4.0f, t, EPS);
        assertEquals(0.0f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
    }

    @Test
    void cylinderColliderRotatedLikeEditorXAxis() {
        // 编辑器移动轴同款：本地 Y 圆柱经 rotationZ(-90°) 轴指向世界 +X（MoveTool X 轴）
        Node node = new Node();
        node.getTransform().setLocalRotation(new Quaternionf().rotationZ((float) Math.toRadians(-90.0)));
        CylinderCollider collider = node.addComponent(CylinderCollider.class);
        collider.setRadius(1.0f);
        collider.setHeight(2.0f);
        Ray ray = new Ray(new Vector3f(0.5f, 0, -5), new Vector3f(0, 0, 1));

        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        // 轴向世界 X：侧面入射 z=-1 → t=4（若旋转未处理，未旋转圆柱入射 t≈4.13——用例具判别性）
        assertEquals(4.0f, t, EPS);
        assertEquals(0.5f, hitPoint.x, EPS);
        assertEquals(0.0f, hitPoint.y, EPS);
        assertEquals(-1.0f, hitPoint.z, EPS);
        assertEquals(0.0f, hitNormal.x, EPS);
        assertEquals(0.0f, hitNormal.y, EPS);
        assertEquals(-1.0f, hitNormal.z, EPS);
    }

    @Test
    void cylinderColliderFitFromMeshThenQueryHits() {
        Node node = new Node();
        CylinderCollider collider = node.addComponent(CylinderCollider.class);
        collider.fitFromMesh(cubeMesh());

        // 拟合结果：center=(1,1,1)、radius=max(半宽X,半宽Z)=1、height=2
        assertEquals(1, collider.getCenter().y, EPS);
        assertEquals(1, collider.getRadius(), EPS);
        assertEquals(2, collider.getHeight(), EPS);

        Ray ray = new Ray(new Vector3f(1, 1, -5), new Vector3f(0, 0, 1));
        float t = collider.collideWithRay(ray, hitPoint, hitNormal);

        // 射线过轴心线：入射 z=0 → t=5，法线径向 (0,0,-1)
        assertEquals(5, t, EPS);
        assertEquals(1, hitPoint.x, EPS);
        assertEquals(1, hitPoint.y, EPS);
        assertEquals(0, hitPoint.z, EPS);
        assertEquals(0, hitNormal.x, EPS);
        assertEquals(0, hitNormal.y, EPS);
        assertEquals(-1, hitNormal.z, EPS);
    }
}
