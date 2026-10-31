package cn.donting.javelin.core.render;


import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 灯收集器（Engine_Lighting_Design §2/§4 收集段，批次②）：分类 → 视空间打包 →
 * 按强度预排序 → 视锥剔除。纯逻辑无 GPU 依赖（单测直跑）。
 *
 * <p>产出两组数据（引擎侧中转，GPU 喂给归 RenderingSystem）：
 * <ul>
 *   <li>{@code pointItems()}：点/聚光 LightItem（视空间 16 float/灯，SSBO 排布；
 *       容量 4096——已预排序，超容截尾即去最弱）；</li>
 *   <li>{@code directionals()}：方向光（≤8 截尾，<b>槽 0 = 主光</b>；视空间方向）。</li>
 * </ul>
 * 预排序评分 = intensity / distanceSq（§3.1 简化口径——同强度近者覆盖像素多者排前，
 * 逆距离平方量级即「屏幕投影面积²」的替身）。</p>
 *
 * <p>视锥剔除自写视空间球测试，不走 JOML FrustumIntersection——引擎 viewProj 含
 * Y 翻转投影（反射矩阵），其平面提取朝向不可信（JOML 提轴坑同族）。本类只收
 * <b>view 矩阵</b>（无翻转）+ proj 元素（取绝对值，翻转不敏感）。</p>
 */
public final class LightCollector {

    /** lightList 容量上限（§3.1 常量起步；剔除后仍超 = 取强度前 4096） */
    public static final int MAX_POINT_LIGHTS = 4096;
    /** 方向光 UBO 数组上限（§3.3；超限截尾） */
    public static final int MAX_DIRECTIONAL = 8;
    /** LightItem 16 float（4×vec4，§3.1） */
    public static final int LIGHT_ITEM_FLOATS = 16;

    /** 方向灯条目（视空间：dir 3 + castShadow 1 / color 3 + intensity 1；
     *  列表序 = 槽 0 主光（最强）在前、其余按列表序） */
    public static final class DirectionalItem {
        public final Vector3f viewDir = new Vector3f();
        public final float[] colorIntensity = new float[4];
        public boolean castShadow;
    }

    private final List<DirectionalItem> directionals = new ArrayList<>();
    /** 点/聚光条目（视空间 16 float/灯；顺序 = 强度降序） */
    private final List<float[]> pointItems = new ArrayList<>();
    /** 剔除阶段暂存（预排序候选，收集期复用） */
    private final List<RenderLight> pending = new ArrayList<>();
    private final List<Float> pendingScores = new ArrayList<>();
    /** 视空间打包暂存（每灯复用） */
    private final Vector3f viewPos = new Vector3f();
    private final Vector3f viewDir = new Vector3f();

    private int collectedPointCount;
    private int culledCount;

    /**
     * 收集一帧（每视口调用；结果经 getter 取，实例跨帧复用）。
     *
     * @param lights 引擎侧灯列表（SceneRenderBridge 已过滤非激活并填充位姿）
     * @param view   本视口 view 矩阵（世界 → 视空间，RH lookAt：前向 = -Z，无翻转）
     * @param proj   本视口投影矩阵（元素取绝对值消费——Y 翻转不敏感）
     * @param orthographic 正交相机 = true（线性剔除分支）
     * @param nearZ farZ 相机裁剪面（Z 界）
     */
    public void collect(List<RenderLight> lights, Matrix4f view, Matrix4f proj,
                        boolean orthographic, float nearZ, float farZ) {
        directionals.clear();
        pointItems.clear();
        pending.clear();
        pendingScores.clear();
        collectedPointCount = 0;
        culledCount = 0;

        // 1. 方向光两遍收：主光（最强激活方向光，对齐 Unity 自动裁决——引擎侧
        //    裁决入口，tie 取列表序首个）占槽 0（§3.3），其余按强度降序补位（超 8 截尾）
        int sunIndex = -1;
        float sunIntensity = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < lights.size(); i++) {
            RenderLight light = lights.get(i);
            if (light.type() == RenderLight.Type.DIRECTIONAL && light.colorIntensity()[3] > sunIntensity) {
                sunIntensity = light.colorIntensity()[3];
                sunIndex = i;
            }
        }
        if (sunIndex >= 0) {
            addDirectional(lights.get(sunIndex), view);
        }
        for (int i = 0; i < lights.size(); i++) {
            if (i == sunIndex) {
                continue;
            }
            RenderLight light = lights.get(i);
            if (light.type() == RenderLight.Type.DIRECTIONAL) {
                if (directionals.size() >= MAX_DIRECTIONAL) {
                    break;
                }
                addDirectional(light, view);
            }
        }

        // 2. 点/聚光入候选（评分 = intensity / distSq，视空间距离）
        for (int i = 0; i < lights.size(); i++) {
            RenderLight light = lights.get(i);
            if (light.type() == RenderLight.Type.DIRECTIONAL) {
                continue;
            }
            light.worldPos().mulPosition(view, viewPos);
            float distSq = Math.max(viewPos.lengthSquared(), 1e-8f);
            pending.add(light);
            pendingScores.add(light.colorIntensity()[3] / distSq);
            collectedPointCount++;
        }

        // 3. 预排序（降序稳定：列表序即截尾序——超容/超 64 丢弃的是更弱者）
        for (int i = 1; i < pending.size(); i++) {
            RenderLight key = pending.get(i);
            float keyScore = pendingScores.get(i);
            int j = i - 1;
            while (j >= 0 && pendingScores.get(j) < keyScore) {
                pending.set(j + 1, pending.get(j));
                pendingScores.set(j + 1, pendingScores.get(j));
                j--;
            }
            pending.set(j + 1, key);
            pendingScores.set(j + 1, keyScore);
        }

        // 4. 剔除 + 打包（已排序：先出队的是强灯，截尾语义稳定）
        for (int i = 0; i < pending.size(); i++) {
            RenderLight light = pending.get(i);
            viewPos.set(light.worldPos()).mulPosition(view);
            if (!sphereInView(viewPos, light.range(), proj, orthographic, nearZ, farZ)) {
                culledCount++;
                continue;
            }
            if (pointItems.size() >= MAX_POINT_LIGHTS) {
                break;   // 后面全是更弱的，整段丢弃
            }
            pointItems.add(packItem(light, viewPos, view));
        }
    }

    private void addDirectional(RenderLight light, Matrix4f view) {
        DirectionalItem d = new DirectionalItem();
        d.viewDir.set(light.worldDir()).mulDirection(view).normalize();
        float[] ci = light.colorIntensity();
        d.colorIntensity[0] = ci[0];
        d.colorIntensity[1] = ci[1];
        d.colorIntensity[2] = ci[2];
        d.colorIntensity[3] = ci[3];
        d.castShadow = light.isCastShadow();
        directionals.add(d);
    }

    /**
     * 视空间球-视锥测试：球心已转视空间。JOML RH lookAt 约定<b>前向 = -Z</b>（可
     * 见物 z &lt; 0），深度取 d = -z 与 [near, far] 相交判；透视分支锥角由 proj 元素
     * 倒推（|m11| = 1/tanHalfY，Y 翻转不改变绝对值），正交分支半宽/半高 =
     * 1/|m00|、1/|m11|。xy 对称于翻转，取绝对值消费。
     */
    private boolean sphereInView(Vector3f center, float radius, Matrix4f proj,
                                 boolean orthographic, float nearZ, float farZ) {
        float r = Math.abs(radius);
        float d = -center.z;   // 视空间深度（-Z 前向）
        if (d - r > farZ || d + r < nearZ) {
            return false;
        }
        if (orthographic) {
            float m00 = Math.abs(proj.m00());
            float m11 = Math.abs(proj.m11());
            float halfW = m00 > 1e-12f ? 1.0f / m00 : Float.MAX_VALUE;
            float halfH = m11 > 1e-12f ? 1.0f / m11 : Float.MAX_VALUE;
            return Math.abs(center.x) <= halfW + r && Math.abs(center.y) <= halfH + r;
        }
        if (d <= 0.0f) {
            return true;   // 球心在相机后方：Z 界已判相交（必穿近平面）
        }
        float tanY = Math.abs(proj.m11()) > 1e-12f ? 1.0f / Math.abs(proj.m11()) : 0.0f;
        float tanX = Math.abs(proj.m00()) > 1e-12f ? 1.0f / Math.abs(proj.m00()) : 0.0f;
        // 球心投影到锥界平面：xy 容差 = r × 锥界法线斜率（√(1+tan²) 补偿）
        float yLimit = d * tanY + r * (float) Math.sqrt(1.0f + tanY * tanY);
        float xLimit = d * tanX + r * (float) Math.sqrt(1.0f + tanX * tanX);
        return Math.abs(center.y) <= yLimit && Math.abs(center.x) <= xLimit;
    }

    /** LightItem 打包（§3.1 布局：posType / dirRadius / colorIntensity / spotParams） */
    private float[] packItem(RenderLight light, Vector3f viewPos, Matrix4f view) {
        float[] out = new float[LIGHT_ITEM_FLOATS];
        float[] ci = light.colorIntensity();
        out[0] = viewPos.x;
        out[1] = viewPos.y;
        out[2] = viewPos.z;
        out[3] = light.type() == RenderLight.Type.SPOT ? 1.0f : 0.0f;
        if (out[3] > 0.0f) {
            viewDir.set(light.worldDir()).mulDirection(view).normalize();
            out[4] = viewDir.x;
            out[5] = viewDir.y;
            out[6] = viewDir.z;
        }
        out[7] = light.range();
        out[8] = ci[0];
        out[9] = ci[1];
        out[10] = ci[2];
        out[11] = ci[3];
        out[12] = light.cosInner();
        out[13] = light.cosOuter();
        return out;
    }

    /** 点/聚光视空间条目（16 float/灯；已预排序 + 剔除，容量 ≤ MAX_POINT_LIGHTS） */
    public List<float[]> pointItems() {
        return pointItems;
    }

    /** 方向光条目（视空间；槽 0 = 主光=最强激活方向光（引擎自动裁决，对齐 Unity），
     *  无方向光时从 0 起普通序） */
    public List<DirectionalItem> directionals() {
        return directionals;
    }

    /** 进剔除阶段的点/聚光数（含被剔除；F3「在册」口径 = 此数 − culled） */
    public int collectedPointCount() {
        return collectedPointCount;
    }

    /** 被视锥剔除的灯数 */
    public int culledCount() {
        return culledCount;
    }
}
