package com.javelin.editor.appstate.gizmo;

import com.javelin.core.render.RenderObject;
import com.javelin.core.render.RenderView;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.pipeline.PipelineContext;
import com.javelin.core.render.pipeline.pass.OpaqueForwardPass;
import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.Texture;
import com.javelin.rhi.data.RHIClearDepthStencil;
import com.javelin.rhi.data.RHILoadOp;

import java.util.List;

/**
 * 变换 Gizmo 置顶 Pass（挂管线末尾，2026-10-05 定稿）。
 *
 * <p><b>两层深度语义（用户拍板）</b>：gizmo 对外恒在所有场景物体之上、不被遮挡
 * （编辑操作对象必须始终可见）——深度 CLEAR 后 LEqual 对空深度恒通过；gizmo
 * <b>内部</b>物体互有层次（三箭头相互遮挡正确）——ZWrite On，画序内自写自测
 * （先画的箭头写深度，后画者在其后即被遮挡）。即：对外丢弃深度是故意的、
 * 对内保留深度靠自写自测，两者并存。</p>
 *
 * <p>颜色 LOAD 保留已渲染画面；挂末尾的意义 = 透明物之后再绘（透明材质
 * ZWrite Off 不留深度，gizmo 绘制顺序压在其上）。CompareOp 无 ALWAYS、
 * Unity ZTest Always 不可用——清深是置顶的唯一等效形态（与相机 DEPTH_ONLY
 * 叠加分支同语义）。历史：同日曾按"立体咬合"口径试行深度 LOAD（gizmo 被
 * 场景遮挡），用户定稿需求为置顶——切回；将来若要"遮挡时仍可见"的透视感，
 * Greater 鬼影变体（被遮挡部分半透明暗色二遍）在本案基础上递增。</p>
 *
 * <p>绘制对象按<b>材质着色器身份</b>过滤（material.getShader() == 构造传入的
 * gizmo.shader 实例，AssetManager 共享加载保证同一性）：全部变换工具（移动/
 * 缩放/旋转）共用 gizmo.shader，一处过滤全覆盖；网格/轴线诊断线用各自 shader
 * 不受影响。被过滤对象在 Opaque 正常 Pass 中仍按普通深度绘制（可被遮挡），
 * 本 Pass 末尾重绘置顶——双画代价为三箭头量级可忽略。不走 Layer 过滤：层表
 * 是二进制设置文件驱动，现有项目无 Gizmo 层需迁移；后续引擎若成型 overlay
 * 语义可再换。</p>
 *
 * @author javelin
 * @create 2026/10/5
 */
public class GizmoPass extends OpaqueForwardPass {

    private final Shader gizmoShader;

    /**
     * @param gizmoShader gizmo 着色器实例（共享加载实例；材质按此身份过滤）
     */
    public GizmoPass(Shader gizmoShader) {
        this.gizmoShader = gizmoShader;
    }

    @Override
    public String name() {
        return "Gizmo";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderView view = ctx.view();
        Texture target = ctx.colorTarget();
        Texture depth = ctx.depth();
        RenderingSystem rs = ctx.renderingSystem();
        graph.addGraphicsPass(view.name() + "_Gizmo", pass -> {
            // 两层深度语义：颜色 LOAD 保画；深度 CLEAR = 对外置顶（LEqual 对空深度
            // 恒通过），ZWrite On + 画序 = 内部层次（箭头互写互测）——对外丢弃深度
            // 是故意的、对内自写自测保留。曾试深度 LOAD（被场景遮挡），需求定稿置顶切回
            pass.addColorOutput(target, RHILoadOp.LOAD, null);
            pass.addDepthOutput(depth, RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f, 0));
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            int uboOffset = view.uboOffset();
            float tpf = ctx.frame().tpf();
            pass.setRenderCallback(cmd -> {
                List<RenderObject> objects = view.list().opaque();
                if (objects.isEmpty()) {
                    return;
                }
                RenderObject first = null;
                for (RenderObject ro : objects) {
                    if (isGizmo(ro)) {
                        first = ro;
                        break;
                    }
                }
                if (first == null) {
                    return; // 本视口无 gizmo（无选中/GAME 模式）——空 Pass 常态
                }
                // set0 绑定需要已绑 PSO 提供布局：先用首个 gizmo 物体 PSO 建立
                cmd.bindPipeline(entryPipeline(rs, first));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                for (RenderObject ro : objects) {
                    if (isGizmo(ro)) {
                        drawObject(cmd, rs, ro, tpf);
                    }
                }
            });
        });
    }

    /** gizmo 物体判定：材质着色器身份（共享加载实例同一性） */
    private boolean isGizmo(RenderObject ro) {
        return ro.material() != null && ro.material().getShader() == gizmoShader;
    }
}
