package cn.donting.javelin.editor.mesh;

import cn.donting.javelin.core.render.mesh.Mesh;

import java.util.Arrays;

/**
 * 变换 Gizmo 几何生成器（程序化建、非资产；造型规格照搬 Babylon gizmo，2026-11-08）。
 *
 * <p>照 Babylon {@code Gizmos/} 的做法——几何在运行期现建、按约 0.3 单位的小尺寸建模
 * （箭头全长 {@value #ARROW_LENGTH}、环直径 {@value #RING_DIAMETER}），真实世界尺寸由工具
 * 节点缩放统一给出（驱动侧距离自适应 × {@code TransformTools.scaleFactor()}），故此处
 * 全是无量纲比例、无物理尺寸概念。参数逐条对应 Babylon 源码：
 * {@code axisDragGizmo.ts:87-124}（箭头）、{@code axisScaleGizmo.ts:316-345}（缩放柄）、
 * {@code planeRotationGizmo.pure.ts:451-472}（环）。</p>
 *
 * <p><b>模型约定</b>：箭头/缩放柄本体朝 <b>+Y</b>、环躺在 XZ 平面（法线 +Y）——由各工具
 * 节点的一次旋转把 +Y 转到目标轴（X 轴 {@code rotationZ(-90°)}、Z 轴 {@code rotationX(+90°)}，
 * 与 MoveTool 既有写法一致）。各柄各自合并成<b>单个</b> Mesh（一个轴节点一个 MeshRenderer，
 * 轴标/高亮/拾取都按轴节点同一性判——见 {@code Axis}）。</p>
 *
 * <p><b>绕序约定</b>：右手系下三角形 {@code cross(v1-v0, v2-v0)} 朝外（与内置 plane.smesh /
 * box.smesh 同源约定，gizmo.shader 的 {@code Cull Back} 依赖它）；圆柱/圆锥/环/立方体各自
 * 含必要端盖，保证任意方向看都是不透的实心件。</p>
 *
 * <p><b>拾取体</b>：本类同时给出各柄的拾取体积尺寸（{@link #AXIS_PICK_RADIUS} /
 * {@link #RING_PICK_TOLERANCE} / {@link #CENTER_PICK_SIZE}）——与可见几何解耦、按 Babylon
 * 「加粗 collider 副本」口径取值，由工具挂成 Collider 组件（{@code MoveTool}/{@code ScaleTool}）
 * 或按解析式判定（{@code RotateTool} 环带），见各工具类。</p>
 *
 * @author javelin
 * @create 2026/11/8
 */
public final class GizmoMeshes {

    // ═══ Babylon 无量纲比例（几何按约 0.3 单位建模） ═══

    /** 轴杆直径（Babylon 0.005）与高度（0.275，轴向 +Y、起点 y=0） */
    private static final float SHAFT_DIAMETER = 0.005f;
    private static final float SHAFT_HEIGHT = 0.275f;

    /** 锥头：高 0.075、底径 0.0375（Babylon 锥头中心距 = 0.3） */
    private static final float CONE_HEIGHT = 0.075f;
    private static final float CONE_BASE_DIAMETER = 0.0375f;

    /** 缩放方块头：Babylon 0.4 立方再缩 0.1 → 0.04 立方（中心距同锥头） */
    private static final float BOX_HEAD_SIZE = 0.04f;

    /** 柄头中心距（Babylon z += 0.3） */
    private static final float HEAD_DISTANCE = 0.3f;

    /** 旋转环直径（Babylon 0.6） */
    public static final float RING_DIAMETER = 0.6f;

    /**
     * 环管径（Babylon 可见环 0.005）。拾取不走可见几何——见 {@link #RING_PICK_TOLERANCE}
     * 与 {@code RotateTool} 的环带拾取，故此处保持 Babylon 的细管径（2026-11-08 用户实测
     * 反馈「环太粗」，原为加粗到 0.012 换拾取宽容度）。
     */
    private static final float RING_TUBE_DIAMETER = 0.005f;

    // ═══ 拾取体（与可见几何解耦的加粗体积；Babylon colliderMeshes 口径） ═══
    // 可见柄细、拾取体粗——「看着细、好抓」。全部为模型单位，随工具节点缩放
    // （屏幕恒定宽容度）。

    /**
     * 轴柄拾取圆柱半径（移动/缩放轴共用）。Babylon 的加粗副本是「杆 thickness+4 →
     * 直径 0.025 / 半径 0.0125」+「锥头直径 0.075 / 半径 0.0375」两段；我方取单圆柱
     * 覆盖整条箭头（0.022 ≈ 箭头全长 6.5%），既比可见杆（0.0025）粗 8.8 倍便于抓取，
     * 又完整包住可见锥头底径（0.01875）保证「看得见就抓得到」。实测可调。
     */
    public static final float AXIS_PICK_RADIUS = 0.022f;

    /**
     * 旋转环拾取带半宽（环平面内 |r − R| ≤ 本值即命中）。Babylon 粗环管径 0.03 /
     * 半径 0.015，同值换算。
     */
    public static final float RING_PICK_TOLERANCE = 0.015f;

    /** 中心均匀柄拾取盒全尺寸（可见方块 0.04 的 1.75 倍——中心柄要更容易点到） */
    public static final float CENTER_PICK_SIZE = 0.07f;

    /** 环分段（Babylon tessellation = 32） */
    private static final int RING_MAJOR_SEGMENTS = 32;
    private static final int RING_MINOR_SEGMENTS = 8;

    /** 轴杆/锥头径向分段（Babylon tessellation = 96——轮廓圆滑优先，顶点量微不足道） */
    private static final int AXIS_SEGMENTS = 96;

    /** 中心均匀柄方块边长（与轴头同尺寸，Babylon 用极小八面体、Unity 用中心方块——取方块口径） */
    private static final float CENTER_BOX_SIZE = 0.04f;

    /**
     * 箭头全长（锥尖 y = 0.3 + 0.075/2）——工具缩放基准：各工具
     * {@code scaleFactor()} 统一取 {@code 1/本值}，即以「箭头长度」为 gizmo 单位
     * （环在同基准下直径 0.6 → 视觉为箭头 1.78 倍，Babylon 共享缩放同款观感）。
     */
    public static final float ARROW_LENGTH = HEAD_DISTANCE + CONE_HEIGHT / 2.0f;

    private GizmoMeshes() {
    }

    /** 移动箭头：细杆 + 锥头（合并为单 Mesh，全长 {@link #ARROW_LENGTH}）。 */
    public static Mesh arrow() {
        Accumulator acc = new Accumulator();
        addCylinder(acc, SHAFT_DIAMETER / 2f, SHAFT_DIAMETER / 2f, 0f, SHAFT_HEIGHT, AXIS_SEGMENTS);
        addCylinder(acc, CONE_BASE_DIAMETER / 2f, 0f,
                HEAD_DISTANCE - CONE_HEIGHT / 2f, HEAD_DISTANCE + CONE_HEIGHT / 2f, AXIS_SEGMENTS);
        return acc.toMesh();
    }

    /** 缩放柄：细杆（同移动箭头）+ 方块头（合并为单 Mesh）。 */
    public static Mesh scaleArrow() {
        Accumulator acc = new Accumulator();
        addCylinder(acc, SHAFT_DIAMETER / 2f, SHAFT_DIAMETER / 2f, 0f, SHAFT_HEIGHT, AXIS_SEGMENTS);
        addBox(acc, BOX_HEAD_SIZE, HEAD_DISTANCE);
        return acc.toMesh();
    }

    /** 旋转环：XZ 平面圆环（法线 +Y，直径 {@link #RING_DIAMETER}）。 */
    public static Mesh ring() {
        Accumulator acc = new Accumulator();
        addTorus(acc, RING_DIAMETER, RING_TUBE_DIAMETER, RING_MAJOR_SEGMENTS, RING_MINOR_SEGMENTS);
        return acc.toMesh();
    }

    /** 中心均匀缩放柄：原点处方块（无朝向语义，一柄管三轴同倍缩放）。 */
    public static Mesh centerHandle() {
        Accumulator acc = new Accumulator();
        addBox(acc, CENTER_BOX_SIZE, 0f);
        return acc.toMesh();
    }

    // ═══ 图元（+Y 轴、右手系外向绕序） ═══

    /**
     * +Y 轴向圆柱/圆锥（{@code topRadius <= 0} 即圆锥、顶端收成单点）；带两端盖。
     *
     * @param bottomRadius 底半径
     * @param topRadius    顶半径（0 = 锥尖）
     * @param yBottom      底面 y
     * @param yTop         顶面 y
     * @param segments     径向分段
     */
    private static void addCylinder(Accumulator acc, float bottomRadius, float topRadius,
                                    float yBottom, float yTop, int segments) {
        boolean apex = topRadius <= 1e-6f;
        int bottomBase = acc.vertexCount;
        for (int i = 0; i < segments; i++) {
            float angle = (float) (2.0 * Math.PI * i / segments);
            acc.addVertex((float) Math.cos(angle) * bottomRadius, yBottom,
                    (float) Math.sin(angle) * bottomRadius);
        }
        int topBase = acc.vertexCount;
        if (apex) {
            acc.addVertex(0f, yTop, 0f);
        } else {
            for (int i = 0; i < segments; i++) {
                float angle = (float) (2.0 * Math.PI * i / segments);
                acc.addVertex((float) Math.cos(angle) * topRadius, yTop,
                        (float) Math.sin(angle) * topRadius);
            }
        }
        // 侧壁：底 i → 底 i+1 → 顶 i+1 → 顶 i 的四边形，外向绕序 (v0,v2,v1)+(v0,v3,v2)
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            int v0 = bottomBase + i;
            int v1 = bottomBase + next;
            int v2 = apex ? topBase : topBase + next;
            int v3 = apex ? topBase : topBase + i;
            acc.addTriangle(v0, v2, v1);
            if (!apex) {
                acc.addTriangle(v0, v3, v2);
            }
        }
        // 底盖（法线 -Y）：(中心, 底 i, 底 i+1)
        int bottomCenter = acc.addVertex(0f, yBottom, 0f);
        for (int i = 0; i < segments; i++) {
            acc.addTriangle(bottomCenter, bottomBase + i, bottomBase + (i + 1) % segments);
        }
        // 顶盖（法线 +Y）：(中心, 顶 i+1, 顶 i)
        if (!apex) {
            int topCenter = acc.addVertex(0f, yTop, 0f);
            for (int i = 0; i < segments; i++) {
                acc.addTriangle(topCenter, topBase + (i + 1) % segments, topBase + i);
            }
        }
    }

    /**
     * XZ 平面圆环（法线 +Y）。
     *
     * @param diameter      环直径
     * @param tubeDiameter  管直径
     * @param majorSegments 大圆分段
     * @param minorSegments 管截面分段
     */
    private static void addTorus(Accumulator acc, float diameter, float tubeDiameter,
                                 int majorSegments, int minorSegments) {
        float major = diameter / 2f;
        float tube = tubeDiameter / 2f;
        int base = acc.vertexCount;
        int stride = minorSegments + 1;   // 接缝重复一圈（免绕回索引特判）
        for (int i = 0; i <= majorSegments; i++) {
            float u = (float) (2.0 * Math.PI * i / majorSegments);
            float cosU = (float) Math.cos(u);
            float sinU = (float) Math.sin(u);
            for (int j = 0; j <= minorSegments; j++) {
                float v = (float) (2.0 * Math.PI * j / minorSegments);
                float radius = major + tube * (float) Math.cos(v);
                acc.addVertex(radius * cosU, tube * (float) Math.sin(v), radius * sinU);
            }
        }
        // 外向绕序（外向法线 = ∂v × ∂u）：(a,b,d) + (b,c,d)
        for (int i = 0; i < majorSegments; i++) {
            for (int j = 0; j < minorSegments; j++) {
                int a = base + i * stride + j;
                int b = base + i * stride + j + 1;
                int c = base + (i + 1) * stride + j + 1;
                int d = base + (i + 1) * stride + j;
                acc.addTriangle(a, b, d);
                acc.addTriangle(b, c, d);
            }
        }
    }

    /** +Y 轴上以 {@code centerY} 为中心的轴对齐立方体（边长 {@code size}）。 */
    private static void addBox(Accumulator acc, float size, float centerY) {
        float h = size / 2f;
        int base = acc.vertexCount;
        // 底面 y=-h：0..3；顶面 y=+h：4..7
        acc.addVertex(-h, centerY - h, -h);
        acc.addVertex(h, centerY - h, -h);
        acc.addVertex(h, centerY - h, h);
        acc.addVertex(-h, centerY - h, h);
        acc.addVertex(-h, centerY + h, -h);
        acc.addVertex(h, centerY + h, -h);
        acc.addVertex(h, centerY + h, h);
        acc.addVertex(-h, centerY + h, h);
        int[][] faces = {
                {0, 1, 2, 0, 2, 3},   // -Y
                {4, 6, 5, 4, 7, 6},   // +Y
                {1, 6, 2, 1, 5, 6},   // +X
                {0, 7, 4, 0, 3, 7},   // -X
                {3, 2, 6, 3, 6, 7},   // +Z
                {0, 5, 1, 0, 4, 5},   // -Z
        };
        for (int[] face : faces) {
            acc.addTriangle(base + face[0], base + face[1], base + face[2]);
            acc.addTriangle(base + face[3], base + face[4], base + face[5]);
        }
    }

    // ═══ 顶点/索引累积器（init 期一次性构建，非热路径） ═══

    private static final class Accumulator {

        private float[] positions = new float[3 * 256];
        private int[] indices = new int[3 * 256];
        private int vertexCount;
        private int indexCount;

        int addVertex(float x, float y, float z) {
            if ((vertexCount + 1) * 3 > positions.length) {
                positions = Arrays.copyOf(positions, positions.length * 2);
            }
            int at = vertexCount * 3;
            positions[at] = x;
            positions[at + 1] = y;
            positions[at + 2] = z;
            return vertexCount++;
        }

        void addTriangle(int a, int b, int c) {
            if (indexCount + 3 > indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            indices[indexCount++] = a;
            indices[indexCount++] = b;
            indices[indexCount++] = c;
        }

        Mesh toMesh() {
            Mesh mesh = new Mesh();
            mesh.setPositions(Arrays.copyOf(positions, vertexCount * 3));
            mesh.setIndices(Arrays.copyOf(indices, indexCount));
            return mesh;
        }
    }
}
