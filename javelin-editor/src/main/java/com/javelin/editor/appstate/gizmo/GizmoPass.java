package com.javelin.editor.appstate.gizmo;

import com.javelin.core.render.RenderObject;
import com.javelin.core.render.RenderView;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.pipeline.PipelineContext;
import com.javelin.core.render.pipeline.pass.OpaqueForwardPass;
import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.Texture;
import com.javelin.rhi.data.RHIClearDepthStencil;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.types.SampleCount;

import java.util.List;

/**
 * 变换 Gizmo 置顶 Pass（挂管线末尾，2026-10-05 定稿；2026-10-17 改 Pass 名分桶）。
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
 * <p><b>绘制对象 = RenderList 的 "Gizmo" Pass 桶</b>（严格制，拍板 2026-10-17）：
 * gizmo.shader 只声明 {@code Pass Gizmo}（无 Forward）→ 物体不进前向主通道，
 * 本 Pass 是其唯一绘制（原"Opaque 先画一遍 + 末尾重绘置顶"双画与材质着色器
 * 身份过滤一并废除——Pass 名即阶段归属，收集侧已分桶）。网格/轴线诊断线用
 * Forward 声明仍走主通道按普通深度绘制，不受影响。cullingMask/层级裁剪在
 * 收集侧已过滤（同主通道口径）。</p>
 *
 * @author javelin
 * @create 2026/10/5
 */
public class GizmoPass extends OpaqueForwardPass {

    @Override
    public String name() {
        return "Gizmo";
    }

    /** 本 Pass 消费 shader 的 "Gizmo" Pass（块名即阶段归属，严格制 2026-10-17） */
    @Override
    public String shaderPassName() {
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
                // 收集侧按 Pass 名分桶（严格制）：gizmo.shader 无 Forward 声明，
                // 物体只在本桶——本 Pass 即唯一绘制，无主通道双画
                List<RenderObject> objects = view.list().passObjects("Gizmo");
                if (objects.isEmpty()) {
                    return; // 本视口无 gizmo（无选中/GAME 模式）——空 Pass 常态
                }
                // set0 绑定需要已绑 PSO 提供布局：先用首个 gizmo 物体 PSO 建立
                // （overlay 恒 1x：直写 backbuffer，不随场景 MSAA 变档）
                cmd.bindPipeline(entryPipeline(rs, objects.get(0), SampleCount.COUNT_1));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                for (RenderObject ro : objects) {
                    drawObject(cmd, rs, ro, tpf, SampleCount.COUNT_1);
                }
            });
        });
    }
}
