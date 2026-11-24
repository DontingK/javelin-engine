package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.debug.DebugText;
import cn.donting.javelin.core.debug.DebugTextColor;
import cn.donting.javelin.core.input.Input;
import cn.donting.javelin.core.input.KeyCode;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.Scene;
import cn.donting.javelin.core.scene.component.DirectionalLight;
import cn.donting.javelin.core.scene.component.Light;
import cn.donting.javelin.core.scene.component.PointLight;
import cn.donting.javelin.core.scene.component.SpotLight;
import cn.donting.javelin.editor.appstate.AbsEditorAppState;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.scene.EditorScene;
import cn.donting.javelin.editor.selection.EditorSelection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 光照 gizmo（REND-13，Engine_Lighting_Design §10 语义）：编辑器里给场景全部灯画线框——
 * 方向光画主光方向箭头（+Z 即光行进方向，与 {@code SceneRenderBridge} 的 worldDir 同源）、
 * 点光画 range 三轴大圆、聚光画外角锥 + 末端圆；选中灯用高亮色叠画一份。
 *
 * <p><b>不走选中门控</b>：光照 gizmo 的价值是「一眼看清全场景灯位」，故每帧遍历
 * {@link GameScene#getLights()} 全量绘制，与选中抽屉（选中才画）互不影响。</p>
 *
 * <p><b>提交方式</b>：与 DDGI 探针视图同款——本状态不自行 {@code DebugDrawLine.clear()}，
 * 由 {@link DebugLineAppState} 每帧 clear 后调 {@link #emitLightLines(JavelinEngine)} 代提交，
 * 避免清掉选中绿框。</p>
 *
 * <p><b>入口</b>：菜单「工具 | 光照 gizmo」两项单选 + F7 边沿切换；状态行占
 * {@link #HINT_ROW}（RenderStats 0~9 / G-Buffer 10 / DDGI 11 / 阴影图 12 之后）。</p>
 */
public class LightGizmoAppState extends AbsEditorAppState {

    public static final int MODE_OFF = 0;
    public static final int MODE_ON = 1;

    /** 提示行：0~9 RenderStats / 10 G-Buffer / 11 DDGI / 12 阴影图 / 13 本状态 */
    private static final int HINT_ROW = 13;

    /** 方向光箭头长度（世界单位）与箭头头部长度 */
    private static final float ARROW_LENGTH = 3.0f;
    private static final float ARROW_HEAD = 0.45f;
    /** 点光 range 圆绘制半径上限（range 很大时不至于铺满视口） */
    private static final float POINT_RADIUS_LIMIT = 20.0f;
    /** 聚光锥长度上限 */
    private static final float SPOT_LENGTH_LIMIT = 10.0f;
    /** 圆周分段数 */
    private static final int SEGMENTS = 20;

    private static final Color DIRECTIONAL_COLOR = Color.fromBytes(255, 220, 0, 255);
    private static final Color POINT_COLOR = Color.fromBytes(120, 200, 255, 255);
    private static final Color SPOT_COLOR = Color.fromBytes(160, 255, 180, 255);
    /** 选中灯高亮（叠画一份白线） */
    private static final Color SELECTED_COLOR = Color.fromBytes(255, 255, 255, 255);

    private static volatile int desiredMode = MODE_OFF;
    /** 上一帧绘制的灯数（状态行用，引擎帧写 EDT 读） */
    private static volatile int drawnLights;

    private static final Vector3f POS = new Vector3f();
    private static final Vector3f DIR = new Vector3f();
    private static final Vector3f FROM = new Vector3f();
    private static final Vector3f TO = new Vector3f();
    private static final Vector3f P0 = new Vector3f();
    private static final Vector3f P1 = new Vector3f();
    private static final Vector3f P2 = new Vector3f();

    public static int getMode() {
        return desiredMode;
    }

    public static void requestMode(int mode) {
        desiredMode = mode == MODE_ON ? MODE_ON : MODE_OFF;
    }

    public static int getDrawnLights() {
        return drawnLights;
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        if (Input.wasPressed(KeyCode.F7)) {
            requestMode(desiredMode == MODE_ON ? MODE_OFF : MODE_ON);
        }
        refreshStatusRow();
    }

    private void refreshStatusRow() {
        if (desiredMode == MODE_OFF) {
            DebugText.clear(HINT_ROW);
            return;
        }
        DebugText.clear(HINT_ROW);
        DebugText.printf(HINT_ROW, EditorLang.text("tools.lightGizmo.status",
                        String.valueOf(drawnLights)),
                DebugTextColor.attr(DebugTextColor.YELLOW, DebugTextColor.BLACK));
    }

    /**
     * 全场景灯 gizmo 提交（世界空间线框）。
     *
     * <p>调用方是 {@link DebugLineAppState}——每帧 {@code clear()} 之后、选中抽屉之前；
     * 本方法不得自行 clear。</p>
     */
    public static void emitLightLines(JavelinEngine engine) {
        if (desiredMode != MODE_ON || engine == null) {
            return;
        }
        Scene scene = engine.scene();
        if (!(scene instanceof EditorScene editorScene)) {
            return;
        }
        GameScene userScene = editorScene.getGameScene();
        if (userScene == null) {
            return;
        }
        Node selected = EditorSelection.getSelectedNode();
        int count = 0;
        for (Light light : userScene.getLights()) {
            Node node = light.getNode();
            if (node == null) {
                continue;
            }
            Matrix4f m = node.getTransform().getLocalToWorld();
            m.transformPosition(POS.set(0.0f, 0.0f, 0.0f));
            // 光行进方向 = 节点 +Z（与 SceneRenderBridge 建 RenderLight 的 worldDir 同口径）
            m.transformDirection(DIR.set(0.0f, 0.0f, 1.0f)).normalize();
            Color base = colorOf(light);
            boolean isSelected = selected == node;
            drawLight(light, POS, DIR, base);
            if (isSelected) {
                drawLight(light, POS, DIR, SELECTED_COLOR);
            }
            count++;
        }
        drawnLights = count;
    }

    private static Color colorOf(Light light) {
        Color c = light.getColor();
        if (c == null) {
            return switch (light.getType()) {
                case POINT -> POINT_COLOR;
                case SPOT -> SPOT_COLOR;
                default -> DIRECTIONAL_COLOR;
            };
        }
        // 灯自身颜色可能与背景撞色，向白色拉 40%（保证线框在暗场景可见）
        return Color.fromBytes(
                (int) (c.r * 255 * 0.6f) + 102,
                (int) (c.g * 255 * 0.6f) + 102,
                (int) (c.b * 255 * 0.6f) + 102, 255);
    }

    private static void drawLight(Light light, Vector3f pos, Vector3f dir, Color color) {
        if (light instanceof DirectionalLight) {
            drawArrow(pos, dir, color);
        } else if (light instanceof SpotLight spot) {
            float length = Math.min(spot.getRange(), SPOT_LENGTH_LIMIT);
            drawCone(pos, dir, length, spot.getOuterAngle(), color);
        } else if (light instanceof PointLight point) {
            drawSphere(pos, Math.min(point.getRange(), POINT_RADIUS_LIMIT), color);
        } else {
            // 未知灯型兜底：一个小十字，至少能看见位置
            cross(pos, 0.3f, color);
        }
    }

    /** 箭头单侧两条边：从尖端往后收 headLen，再向 side 张开 ±headW */
    private static void headEdge(Vector3f tip, Vector3f dir, Vector3f side,
                                 float headLen, float headW, Color color) {
        FROM.set(tip).add(-dir.x * headLen, -dir.y * headLen, -dir.z * headLen)
                .add(side.x * headW, side.y * headW, side.z * headW);
        DebugDrawLine.line(tip, FROM, color);
        FROM.set(tip).add(-dir.x * headLen, -dir.y * headLen, -dir.z * headLen)
                .sub(side.x * headW, side.y * headW, side.z * headW);
        DebugDrawLine.line(tip, FROM, color);
    }

    /** 方向光：行进方向箭头（杆 + 两面箭头头部） */
    private static void drawArrow(Vector3f pos, Vector3f dir, Color color) {
        TO.set(dir).mul(ARROW_LENGTH).add(pos);
        DebugDrawLine.line(pos, TO, color);
        // 箭头头部：用与 dir 正交的两基向量张开
        Vector3f a = orthoBasisA(dir);
        Vector3f b = orthoBasisB(dir);
        float headLen = ARROW_HEAD;
        float headW = ARROW_HEAD * 0.55f;
        headEdge(TO, dir, a, headLen, headW, color);
        headEdge(TO, dir, b, headLen, headW, color);
        cross(pos, 0.25f, color);
    }

    /** 点光：三轴大圆（XY / XZ / YZ 平面），半径 = range */
    private static void drawSphere(Vector3f center, float radius, Color color) {
        if (radius <= 0.0f) {
            return;
        }
        circle(center, new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), radius, color);
        circle(center, new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), radius, color);
        circle(center, new Vector3f(0, 1, 0), new Vector3f(0, 0, 1), radius, color);
        cross(center, 0.2f, color);
    }

    /** 聚光：四条外角母线 + 末端圆（末端圆半径 = tan(outer) × 长度） */
    private static void drawCone(Vector3f pos, Vector3f dir, float length, float outerAngleDeg,
                                 Color color) {
        if (length <= 0.0f) {
            return;
        }
        Vector3f a = orthoBasisA(dir);
        Vector3f b = orthoBasisB(dir);
        float tanOuter = (float) Math.tan(Math.toRadians(Math.abs(outerAngleDeg)));
        float endRadius = tanOuter * length;
        TO.set(dir).mul(length).add(pos);
        for (int i = 0; i < 4; i++) {
            float ang = (float) (Math.PI * 0.5 * i);
            float ca = (float) Math.cos(ang);
            float sa = (float) Math.sin(ang);
            FROM.set(TO)
                    .add(a.x * ca * endRadius + b.x * sa * endRadius,
                            a.y * ca * endRadius + b.y * sa * endRadius,
                            a.z * ca * endRadius + b.z * sa * endRadius);
            DebugDrawLine.line(pos, FROM, color);
        }
        circle(TO, a, b, endRadius, color);
        cross(pos, 0.2f, color);
    }

    private static void circle(Vector3f center, Vector3f u, Vector3f v, float radius, Color color) {
        if (radius <= 0.0f) {
            return;
        }
        P0.set(center).add(u.x * radius, u.y * radius, u.z * radius);
        for (int i = 1; i <= SEGMENTS; i++) {
            float ang = (float) (Math.PI * 2.0 * i / SEGMENTS);
            float ca = (float) Math.cos(ang);
            float sa = (float) Math.sin(ang);
            P1.set(center).add(u.x * ca * radius + v.x * sa * radius,
                    u.y * ca * radius + v.y * sa * radius,
                    u.z * ca * radius + v.z * sa * radius);
            DebugDrawLine.line(P0, P1, color);
            P2.set(P0);
            P0.set(P1);
            P1.set(P2);
        }
    }

    private static void cross(Vector3f center, float arm, Color color) {
        DebugDrawLine.line(FROM.set(center).add(arm, 0, 0), TO.set(center).add(-arm, 0, 0), color);
        DebugDrawLine.line(FROM.set(center).add(0, arm, 0), TO.set(center).add(0, -arm, 0), color);
        DebugDrawLine.line(FROM.set(center).add(0, 0, arm), TO.set(center).add(0, 0, -arm), color);
    }

    /** 与 dir 正交的基向量 A：取 |dir| 不正交的世界轴做参考，叉乘后归一 */
    private static Vector3f orthoBasisA(Vector3f dir) {
        Vector3f ref = Math.abs(dir.y) < 0.9f ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        Vector3f out = new Vector3f(dir).cross(ref);
        if (out.lengthSquared() < 1e-6f) {
            out.set(dir).cross(new Vector3f(0, 0, 1));
        }
        return out.normalize();
    }

    private static Vector3f orthoBasisB(Vector3f dir) {
        return new Vector3f(dir).cross(orthoBasisA(dir)).normalize();
    }

    @Override
    public void onDetach(AppStateManager stateManager) {
        DebugText.clear(HINT_ROW);
        super.onDetach(stateManager);
    }
}
