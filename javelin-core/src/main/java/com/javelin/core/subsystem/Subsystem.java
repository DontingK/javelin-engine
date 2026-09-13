package com.javelin.core.subsystem;

import com.javelin.core.JavelinEngine;

/**
 * 子系统接口 —— 引擎扩展模块的统一契约。
 *
 * <p>子系统是全局服务级模块，与 Component 的区别：
 * <ul>
 *   <li><b>Component</b>：附加到单个 Node，管理该节点的局部数据和行为</li>
 *   <li><b>Subsystem</b>：全局单例，管理跨节点的系统级逻辑（如物理世界、粒子管理器、音频上下文）</li>
 * </ul>
 *
 * <p>执行顺序由方法名直接体现：
 * <pre>
 *   每帧更新：earlyUpdate → scene.update(组件onUpdate) → lateUpdate
 *   每帧渲染：preRender → RenderingPipeline.render → render → postRender
 * </pre>
 *
 * <p>不需要的阶段直接空实现即可。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public interface Subsystem {

    /**
     * 初始化。
     * <p>所有子系统注册完成后调用，分配资源、创建上下文。</p>
     */
    default void init(JavelinEngine engine) {
    }

    /**
     * 早期更新。
     * <p>在 scene.update() 之前执行。适合物理步进、动画采样、输入预处理。</p>
     *
     * @param deltaTime 帧间隔（秒）
     */
    default void earlyUpdate(float deltaTime) {
    }

    /**
     * 正常更新。
     * <p>在 scene.update() 之后执行。适合 AI、游戏逻辑、粒子发射器更新。</p>
     *
     * @param deltaTime 帧间隔（秒）
     */
    default void update(float deltaTime) {
    }

    /**
     * 晚期更新。
     * <p>在 update() 之后执行。适合物理同步到 Transform、相机跟随。</p>
     *
     * @param deltaTime 帧间隔（秒）
     */
    default void lateUpdate(float deltaTime) {
    }

    /**
     * 渲染前准备。
     * <p>在 RenderingPipeline.render() 之前执行。适合粒子生成、LOD 计算。</p>
     *
     * @param context 渲染上下文，可分配 viewId、设置视口
     */
    default void preRender() {
    }

    /**
     * 渲染贡献。
     * <p>在 RenderingPipeline.render() 之后执行。适合提交额外的 RenderCommand（如粒子、调试线框）。</p>
     *
     */
    default void render() {
    }

    /**
     * 渲染后处理。
     * <p>在所有渲染完成后执行。适合调试绘制、Gizmo、UI。</p>
     *
     */
    default void postRender() {
    }

    /**
     * 关闭。
     * <p>释放资源、保存状态、清理 GPU 对象。</p>
     */
    default void shutdown() {
    }
}
