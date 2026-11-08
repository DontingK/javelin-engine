package cn.donting.javelin.editor.appstate.gizmo;

import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import org.joml.Vector3f;

/**
 * 变换 Gizmo 视觉单点（颜色/材质/三态同步，2026-11-08 自 MoveTool 上提——三工具共用）。
 *
 * <p>柄视觉三态：<b>正常</b>（各柄本色）/ <b>高亮</b>（hover 与拖拽共用，黄）/ <b>不可选</b>
 * （工具几何语义判定不可用，灰）。三工具按「柄节点数组 + 状态数组 + 可选性闸门」交给
 * {@link #sync} 每帧幂等同步——不可选判定与各工具 {@code isSelectable} 必须同源（视觉与
 * 可选性一致，见 {@code TransformTools#isSelectable}）；优先级高亮 &gt; 变灰（hover 入口
 * 已被可选性闸门，高亮柄必可选）。</p>
 *
 * <p>着色走内置 gizmo.shader（unlit 纯色 {@code _Color}）——每柄独立 Material 实例，
 * MaterialBlock 每次调用重打包 setColor 即时生效，切换状态才写（状态数组去重）。</p>
 *
 * @author javelin
 * @create 2026/11/8
 */
final class GizmoVisuals {

    /** 内置 Gizmo 着色器 loadAsset 路径（只声明 Pass Gizmo——严格制下物体进 "Gizmo" 桶、由 GizmoPass 单画） */
    static final String SHADER_PATH = "editor/shader/gizmo.shader";

    /** 颜色属性名（gizmo.shader 的 _Color） */
    static final String COLOR_PROPERTY = "_Color";

    /** 轴本色（RGB = XYZ 惯例；数组下标 = Axis 枚举序 X/Y/Z） */
    static final float[][] AXIS_COLORS = {
            {1.0f, 0.0f, 0.0f, 1.0f},
            {0.0f, 1.0f, 0.0f, 1.0f},
            {0.0f, 0.0f, 1.0f, 1.0f},
    };

    /** 旋转环本色（与轴同色——环法线即该轴） */
    static final float[][] RING_COLORS = AXIS_COLORS;

    /** hover/拖拽高亮色（Unity 口径：交互中的柄变黄） */
    static final float[] HIGHLIGHT_COLOR = {1.0f, 0.92f, 0.2f, 1.0f};

    /** 不可选置灰（实测可调） */
    static final float[] GRAY_COLOR = {0.5f, 0.5f, 0.5f, 1.0f};

    /** 中心均匀柄本色（Unity/Babylon 口径：中性灰，与三轴区分） */
    static final float[] CENTER_COLOR = {0.62f, 0.62f, 0.62f, 1.0f};

    /** 柄视觉三态 */
    enum State {
        /** 正常（本色） */
        NORMAL,
        /** 高亮（hover/拖拽中，黄） */
        HIGHLIGHT,
        /** 不可选（几何语义不可用，灰） */
        DISABLED
    }

    /** 柄可选性闸门（各工具实现——判定须与 {@code isSelectable} 同源） */
    interface HandleGate {
        boolean selectable(Node node, Vector3f viewDir);
    }

    private GizmoVisuals() {
    }

    /**
     * 每帧同步一组柄的视觉状态（幂等：状态未变不重写颜色）。
     *
     * @param handles       柄节点数组（init 失败位可留 null，跳过）
     * @param states        与 handles 平行的状态数组（null = 尚未写过色，首帧必写）
     * @param normalColors  与 handles 平行的本色
     * @param highlightedNode 本工具内 hover/拖拽锁定的柄，null = 无
     * @param viewDir       视线方向（相机 → 选中目标，与鼠标位置无关，勿改写）
     * @param gate          可选性闸门
     */
    static void sync(Node[] handles, State[] states, float[][] normalColors,
                     Node highlightedNode, Vector3f viewDir, HandleGate gate) {
        for (int i = 0; i < handles.length; i++) {
            Node node = handles[i];
            if (node == null) {
                continue;   // init 失败防御
            }
            State desired = node == highlightedNode ? State.HIGHLIGHT
                    : gate.selectable(node, viewDir) ? State.NORMAL : State.DISABLED;
            if (desired != states[i]) {
                states[i] = desired;
                setColor(node, colorOf(desired, normalColors[i]));
            }
        }
    }

    /** 状态对应颜色（正常 = 传入本色） */
    static float[] colorOf(State state, float[] normalColor) {
        return switch (state) {
            case HIGHLIGHT -> HIGHLIGHT_COLOR;
            case DISABLED -> GRAY_COLOR;
            case NORMAL -> normalColor;
        };
    }

    /** 建一个柄材质（每柄独立实例——颜色按柄单独下发） */
    static Material newMaterial(Shader shader, float[] rgba) {
        Material material = new Material(shader);
        material.setColor(COLOR_PROPERTY, rgba[0], rgba[1], rgba[2], rgba[3]);
        return material;
    }

    /** 写柄节点材质颜色（MaterialBlock 每次调用重打包，setColor 即时生效） */
    static void setColor(Node node, float[] rgba) {
        MeshRenderer renderer = node.getComponent(MeshRenderer.class);
        if (renderer != null && renderer.getMaterial() != null) {
            renderer.getMaterial().setColor(COLOR_PROPERTY, rgba[0], rgba[1], rgba[2], rgba[3]);
        }
    }
}
