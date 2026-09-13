package com.javelin.core.scene;

import com.javelin.core.render.Renderable;
import com.javelin.core.scene.component.Camera;

import java.util.List;

/**
 * 场景管理器 —— 管理活动场景，向引擎主循环提供统一的更新与渲染收集入口。
 *
 * <p>当前仅支持单个活动场景，多场景 additive 加载为预留扩展。</p>
 *
 * <p>引擎主循环时序（与 {@link com.javelin.core.subsystem.Subsystem} 文档约定一致）：
 * <pre>
 *   flushExecuteQueue
 *   subsystemManager.earlyUpdate(dt)   ← 物理步进、输入预处理
 *   sceneManager.update(dt)            ← 组件 onUpdate（本类）
 *   update()                           ← 子类游戏逻辑
 *   subsystemManager.update(dt)        ← AI、游戏逻辑
 *   subsystemManager.lateUpdate(dt)    ← 物理同步、相机跟随
 *   renderer.beginFrame
 *   subsystemManager.preRender → render() → subsystemManager.render → postRender
 *   renderer.endFrame
 * </pre></p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class SceneManager {

    /** 当前活动场景 */
    private Scene activeScene;

    /**
     * 创建一个新场景（不会自动设为活动场景）。
     */
    public GameScene createScene(String name) {
        return new GameScene(name);
    }

    public Scene getActiveScene() {
        return activeScene;
    }

    public void setActiveScene(Scene scene) {
        this.activeScene = scene;
    }

    /**
     * 每帧更新活动场景（组件 onUpdate 与帧末销毁处理）。
     */
    public void update(float deltaTime) {
        if (activeScene != null) {
            activeScene.update(deltaTime);
        }
    }

    /**
     * 收集活动场景中处于激活状态的 {@link Renderable} 组件。
     *
     * @param out 输出列表（由调用方清空/复用）
     */
    public void collectRenderables(List<Renderable> out) {
        if (activeScene != null) {
            activeScene.collectRenderables(out);
        }
    }

    /**
     * 收集活动场景中处于激活状态的 {@link Camera} 组件。
     *
     * @param out 输出列表（由调用方清空/复用）
     */
    public void collectCameras(List<Camera> out) {
        if (activeScene != null) {
            activeScene.collectCameras(out);
        }
    }

    /**
     * 关闭：销毁活动场景内全部节点。
     * <p>必须在渲染器销毁之前调用（组件可能持有 GPU 资源引用）。</p>
     */
    public void shutdown() {
        if (activeScene != null) {
            activeScene.dispose();
            activeScene = null;
        }
    }
}
