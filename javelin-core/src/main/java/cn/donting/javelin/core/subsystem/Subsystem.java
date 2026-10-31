package cn.donting.javelin.core.subsystem;

import cn.donting.javelin.core.appstate.AppState;
import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;

/**
 * 子系统接口 —— 引擎底层全局服务的统一契约（物理、音频、输入等）。
 *
 * <p>子系统与其他扩展模块的分界：
 * <ul>
 *   <li><b>Component</b>：附加到单个 Node，管理该节点的局部数据和行为</li>
 *   <li><b>Subsystem</b>：引擎级全局服务。启动前经 {@link SubsystemManager#register} 注册，
 *       {@link SubsystemManager#initAll} 后结构冻结、<b>不允许移除</b>（暂停用 enabled 开关）</li>
 *   <li><b>AppState</b>（appstate 包）：控制程序层，运行时可动态挂卸，与底层子系统互补
 *       —— 两层的分界见 {@link AppState}</li>
 * </ul>
 *
 * <p>调度序列（JavelinEngine.mainLoop 每帧，对齐 Engine_Design §2.1）：
 * <pre>
 *   pollEvents → Time.update
 *   → 子系统 earlyUpdate（AppStateSubsystem 桥接：AppState 挂卸队列 + AppState update）
 *   → scene.update（组件 onUpdate）
 *   → 子系统 lateUpdate
 *   → 收集帧描述 → frameLoop.renderFrame
 * </pre>
 *
 * <p>渲染贡献分两级：<b>物体/视口级</b>向 {@code renderPipeline.passes()} 注册 Pass
 * （管线扩展的既定方式，见 Engine_RenderPipeline_Design）；<b>帧组装级</b> overlay
 * 走 {@link #appendOverlay} 钩子——"总在所有相机视口合成之后、无相机也画"是 Pass
 * 体系（逐视口进图）表达不了的层（DebugText 落地时泛化，用户拍板 2026-09-27）。
 * 不需要的钩子直接留 default 空实现。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public interface Subsystem {

    /**
     * 初始化。
     * <p>所有子系统注册完成、RHI 就绪后由 {@link SubsystemManager#initAll} 统一调用，
     * 分配资源、创建上下文。init 抛异常的子系统被剔除、不进入调度。</p>
     *
     * @param engine 引擎实例（{@link AbstractSubsystem} 会回填到字段）
     */
    default void init(JavelinEngine engine) {
    }

    /**
     * 早期更新：每帧 scene.update 之前。
     * <p>适合输入采集、物理步进。</p>
     *
     * @param deltaTime 帧间隔（秒）
     */
    default void earlyUpdate(float deltaTime) {
    }

    /**
     * 晚期更新：每帧 scene.update 之后、帧描述收集之前。
     * <p>适合物理结果回写 Transform、相机跟随等渲染前修正。</p>
     *
     * @param deltaTime 帧间隔（秒）
     */
    default void lateUpdate(float deltaTime) {
    }

    /**
     * 是否启用（调度过滤探针）。
     * <p>结构冻结不可移除，暂停即禁用：禁用的子系统不参与帧调度，
     * 但保留在管理器中。可变开关由 {@link AbstractSubsystem#setEnabled} 提供。</p>
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 关闭。
     * <p>引擎 shutdown 时由 {@link SubsystemManager#shutdownAll} 按注册逆序调用，
     * 时点在 GPU 设备销毁之前（子系统可能持有 GPU 资源）。</p>
     */
    default void shutdown() {
    }

    /**
     * 帧组装级 overlay 钩子：所有相机视口合成之后、写最终 backbuffer，
     * 由 {@link SubsystemManager#appendOverlays} 在 buildFrameGraph 末尾统一遍历调用。
     *
     * <p>无相机（ClearOnly 兜底）也照调。禁用（isEnabled=false）由管理器过滤；
     * 未初始化/资源未就绪等自检由实现自理（自检不过即不进图——图结构决策权在子系统）。</p>
     */
    default void appendOverlay(RenderGraphBuilder graph, Texture backbuffer, long frameIndex) {
    }

    /**
     * GPU 资源销毁钩子：引擎在 frameLoop.close()（waitIdle）之后由
     * {@link SubsystemManager#closeGpuAll} 统一遍历调用。
     *
     * <p>存在原因：shutdownAll 时点早于帧循环 waitIdle，持 GPU 资源的子系统不能在
     * {@link #shutdown} 里 dispose（in-flight 使用风险），在本钩子处安全销毁
     * （RenderingSystem.close() 同层语义）。</p>
     */
    default void closeGpu() {
    }
}
