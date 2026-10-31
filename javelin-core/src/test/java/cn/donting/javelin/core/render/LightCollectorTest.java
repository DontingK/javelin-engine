package cn.donting.javelin.core.render;


import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灯收集器单测（三期批次②，Engine_Lighting_Design §2/§4 收集段）：
 * 视空间打包 / 视锥剔除（-Z 前向口径）/ 强度预排序 / 主光槽 0 / 容量截尾。
 *
 * <p>相机用 RenderCamera 真矩阵（默认位姿：pos(0,0,5) 看原点、fov 60°、near 0.1、
 * far 100、视口 640×480）——view 为 JOML RH lookAt（前向 = -Z，可物 z&lt;0）。</p>
 */
public class LightCollectorTest {

    private static final float EPS = 1e-4f;

    private final LightCollector collector = new LightCollector();
    private final List<RenderLight> lights = new ArrayList<>();
    private RenderCamera camera;

    @BeforeEach
    void setUp() {
        collector.pointItems().clear();
        lights.clear();
        camera = new RenderCamera("test");
        camera.viewport(0, 0, 640, 480);
        camera.recalculate();
    }

    private RenderLight point(float x, float y, float z, float intensity, float range) {
        RenderLight light = new RenderLight(RenderLight.Type.POINT, false, range, 1.0f, 0.0f);
        light.worldPos().set(x, y, z);
        light.colorIntensity()[0] = 1.0f;
        light.colorIntensity()[1] = 0.5f;
        light.colorIntensity()[2] = 0.25f;
        light.colorIntensity()[3] = intensity;
        return light;
    }

    private RenderLight directional(float intensity, boolean castShadow, Vector3f worldDir) {
        RenderLight light = new RenderLight(RenderLight.Type.DIRECTIONAL, castShadow, 10.0f, 1.0f, 0.0f);
        light.worldDir().set(worldDir).normalize();
        light.colorIntensity()[3] = intensity;
        return light;
    }

    private void collect() {
        collector.collect(lights, camera.view(), camera.proj(), false,
                camera.nearZ(), camera.farZ());
    }

    @Test
    void pointLightPacksViewSpace() {
        lights.add(point(0, 0, 0, 2.0f, 10.0f));

        collect();

        assertEquals(1, collector.pointItems().size());
        float[] item = collector.pointItems().get(0);
        // 原点距相机 5 单位：视空间 (0,0,-5)（-Z 前向）；w=类型 0=POINT
        assertEquals(0.0f, item[0], EPS);
        assertEquals(0.0f, item[1], EPS);
        assertEquals(-5.0f, item[2], EPS);
        assertEquals(0.0f, item[3], EPS);
        // dirRadius.xyz 空（非 SPOT）、w=range
        assertEquals(0.0f, item[4], EPS);
        assertEquals(0.0f, item[5], EPS);
        assertEquals(0.0f, item[6], EPS);
        assertEquals(10.0f, item[7], EPS);
        // colorIntensity
        assertEquals(1.0f, item[8], EPS);
        assertEquals(0.5f, item[9], EPS);
        assertEquals(0.25f, item[10], EPS);
        assertEquals(2.0f, item[11], EPS);
        assertEquals(1.0f, item[12], EPS);
        assertEquals(0.0f, item[13], EPS);
    }

    @Test
    void spotLightPacksViewSpaceDirection() {
        RenderLight spot = new RenderLight(RenderLight.Type.SPOT, false, 8.0f,
                0.866f, 0.5f);
        spot.worldPos().set(0, 0, 2);
        spot.worldDir().set(0, 0, -1);
        spot.colorIntensity()[3] = 3.0f;
        lights.add(spot);

        collect();

        assertEquals(1, collector.pointItems().size());
        float[] item = collector.pointItems().get(0);
        // SPOT：w=类型 1；方向（0,0,-1) 与视轴向一致（相机看 -Z）→ 视空间不变
        assertEquals(1.0f, item[3], EPS);
        assertEquals(0.0f, item[4], EPS);
        assertEquals(0.0f, item[5], EPS);
        assertEquals(-1.0f, item[6], EPS);
        assertEquals(0.866f, item[12], EPS);
        assertEquals(0.5f, item[13], EPS);
    }

    @Test
    void cullsLightsOutsideViewFrustum() {
        lights.add(point(0, 0, 10, 1.0f, 1.0f));     // 相机背后（z_view=+5）
        lights.add(point(0, 0, -200, 1.0f, 1.0f));   // 远于 far=100
        lights.add(point(100, 0, 0, 1.0f, 1.0f));    // 视锥侧外（x 极大）
        lights.add(point(0, 0, 0, 1.0f, 1.0f));      // 视锥内（对照）

        collect();

        assertEquals(1, collector.pointItems().size());
        assertEquals(3, collector.culledCount());
        assertEquals(0, collector.pointItems().get(0)[11], 1.0f);   // 存活 = 视锥内那盏
    }

    @Test
    void cullToleratesRangeStraddlingNearPlane() {
        // 球心在近平面前但 range 跨近平面：保留（剔除是保守判定，宁留勿错杀）
        lights.add(point(0, 0, 4.9f, 1.0f, 10.0f));   // 距相机 0.1、range 10

        collect();

        assertEquals(1, collector.pointItems().size());
        assertEquals(0, collector.culledCount());
    }

    @Test
    void sortsPointLightsByIntensityScoreDescending() {
        // 同距不同强度：强者在先（截尾=去最弱的前提）
        lights.add(point(0, -2, 0, 1.0f, 10.0f));
        lights.add(point(0, 2, 0, 5.0f, 10.0f));

        collect();

        assertEquals(5.0f, collector.pointItems().get(0)[11], EPS);
        assertEquals(1.0f, collector.pointItems().get(1)[11], EPS);

        // 同强度不同距：近者在先（评分 = intensity / distSq）
        lights.clear();
        lights.add(point(0, -2, 0, 1.0f, 10.0f));   // 距相机 √29
        lights.add(point(0, 2, 4, 1.0f, 10.0f));   // 距相机 √5（近）
        collect();
        assertEquals(2.0f, collector.pointItems().get(0)[1], EPS);   // 近者视空间 (0,2,-1)
        assertEquals(-1.0f, collector.pointItems().get(0)[2], EPS);
    }

    @Test
    void strongestDirectionalTakesSlotZero() {
        Vector3f down = new Vector3f(0, -1, 0);
        lights.add(directional(1.0f, false, down));       // 弱方向光先入列表
        lights.add(directional(5.0f, true, down));        // 强方向光后入（自动裁决主光）

        collect();

        // 最强方向光占槽 0（引擎自动裁决，对齐 Unity；无 isMain 标志）
        assertEquals(2, collector.directionals().size());
        assertEquals(5.0f, collector.directionals().get(0).colorIntensity[3], EPS);
        assertTrue(collector.directionals().get(0).castShadow);
        assertEquals(1.0f, collector.directionals().get(1).colorIntensity[3], EPS);
        // 相机看 -Z、up +Y：世界 (0,-1,0) 视空间不变
        assertEquals(0.0f, collector.directionals().get(0).viewDir.x, EPS);
        assertEquals(-1.0f, collector.directionals().get(0).viewDir.y, EPS);
        assertEquals(0.0f, collector.directionals().get(0).viewDir.z, EPS);
    }

    @Test
    void directionalArrayTruncatesAtEight() {
        for (int i = 0; i < 9; i++) {
            lights.add(directional(1.0f, false, new Vector3f(0, -1, 0)));
        }

        collect();

        assertEquals(LightCollector.MAX_DIRECTIONAL, collector.directionals().size());
    }

    @Test
    void pointLightListTruncatesAtCapacity() {
        for (int i = 0; i < LightCollector.MAX_POINT_LIGHTS + 7; i++) {
            lights.add(point(0, 0, 0, 1.0f, 10.0f));
        }

        collect();

        assertEquals(LightCollector.MAX_POINT_LIGHTS, collector.pointItems().size());
        assertEquals(LightCollector.MAX_POINT_LIGHTS + 7, collector.collectedPointCount());
        assertEquals(0, collector.culledCount());
    }
}
