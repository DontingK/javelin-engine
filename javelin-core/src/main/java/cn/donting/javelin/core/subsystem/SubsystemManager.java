package cn.donting.javelin.core.subsystem;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 子系统管理器 —— 引擎底层全局服务的注册与调度。
 *
 * <p>结构冻结规则（用户拍板 2026-09-26）：子系统在 {@link #initAll} 之前注册、
 * 之后<b>冻结、不允许移除</b>（与 appstate 控制层的动态挂卸相对）；暂停用
 * {@link Subsystem#isEnabled()} 过滤，开关由 {@link AbstractSubsystem#setEnabled} 驱动。</p>
 *
 * <p>调度序列由 JavelinEngine.mainLoop 接线（对齐 Engine_Design §2.1）：
 * <pre>
 *   earlyUpdate(dt)   —— scene.update 之前（含 AppStateSubsystem 桥接的挂卸队列与 AppState update）
 *   scene.update
 *   lateUpdate(dt)    —— scene.update 之后、帧描述收集之前
 * </pre>
 * 每个钩子逐个 try/catch：单个子系统抛错不拖死引擎，log.error 带堆栈继续调度其余子系统。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class SubsystemManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubsystemManager.class);

    private final List<Subsystem> subsystems = new ArrayList<>();
    /** shutdownAll 时的已关账副本——closeGpuAll 在列表清空后仍需可达（waitIdle 之后才销 GPU） */
    private final List<Subsystem> shutdownSubsystems = new ArrayList<>();
    private boolean initialized = false;

    /**
     * 注册子系统。
     * <p>
     * 必须在 {@link #initAll(JavelinEngine)} 之前调用，初始化后结构冻结，禁止再注册。
     * </p>
     *
     * @param subsystem 子系统实例
     * @throws IllegalStateException 如果已初始化
     */
    public void register(Subsystem subsystem) {
        if (subsystem == null) {
            throw new NullPointerException("subsystem is null");
        }
        if (initialized) {
            throw new IllegalStateException(
                "Cannot register subsystem after init. " +
                "Subsystems must be registered before initAll() is called."
            );
        }
        subsystems.add(subsystem);
        LOGGER.debug("Registered subsystem: {}", subsystem.getClass().getSimpleName());
    }

    /**
     * 初始化所有子系统。
     * <p>
     * 调用后结构冻结，禁止再注册子系统。按注册顺序调用 {@link Subsystem#init}；
     * init 抛异常的子系统被<b>剔除</b>，不进入后续调度（避免半初始化状态每帧被驱动）。
     * </p>
     */
    public void initAll(JavelinEngine javelinEngine) {
        if (initialized) {
            LOGGER.warn("SubsystemManager already initialized");
            return;
        }
        LOGGER.info("Initializing {} subsystems...", subsystems.size());
        subsystems.removeIf(subsystem -> {
            try {
                subsystem.init(javelinEngine);
                return false;
            } catch (Exception e) {
                LOGGER.error("Failed to initialize subsystem: {}, removed from scheduling",
                        subsystem.getClass().getSimpleName(), e);
                return true;
            }
        });
        initialized = true;
    }

    /**
     * 执行所有启用子系统的 earlyUpdate（scene.update 之前，mainLoop 接线）。
     */
    public void earlyUpdate(float deltaTime) {
        for (Subsystem subsystem : subsystems) {
            if (!subsystem.isEnabled()) {
                continue;
            }
            try {
                subsystem.earlyUpdate(deltaTime);
            } catch (Exception e) {
                LOGGER.error("Error in {}.earlyUpdate: {}",
                        subsystem.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 执行所有启用子系统的 lateUpdate（scene.update 之后、帧描述收集之前）。
     */
    public void lateUpdate(float deltaTime) {
        for (Subsystem subsystem : subsystems) {
            if (!subsystem.isEnabled()) {
                continue;
            }
            try {
                subsystem.lateUpdate(deltaTime);
            } catch (Exception e) {
                LOGGER.error("Error in {}.lateUpdate: {}",
                        subsystem.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 关闭所有子系统（逆序）。
     * <p>
     * 后初始化的先关闭，避免依赖问题。JavelinEngine.shutdown 的第一步调用
     * （在 GPU 设备销毁之前）。关闭后可通过重新创建 SubsystemManager 实例来重新配置。
     * </p>
     */
    public void shutdownAll() {
        if (!initialized) {
            return;
        }
        LOGGER.info("Shutting down {} subsystems...", subsystems.size());
        shutdownSubsystems.clear();
        shutdownSubsystems.addAll(subsystems);
        List<Subsystem> reversed = new ArrayList<>(subsystems);
        Collections.reverse(reversed);
        for (Subsystem subsystem : reversed) {
            try {
                subsystem.shutdown();
            } catch (Exception e) {
                LOGGER.error("Error shutting down subsystem: {}",
                        subsystem.getClass().getSimpleName(), e);
            }
        }
        initialized = false;
        subsystems.clear();
    }

    /**
     * 帧组装级 overlay 统一遍历（JavelinEngine.buildFrameGraph 视口循环后调用）。
     * <p>禁用子系统跳过（与 earlyUpdate 同过滤口径）；未就绪等自检由子系统自理。</p>
     */
    public void appendOverlays(RenderGraphBuilder graph, Texture backbuffer, long frameIndex) {
        for (Subsystem subsystem : subsystems) {
            if (!subsystem.isEnabled()) {
                continue;
            }
            try {
                subsystem.appendOverlay(graph, backbuffer, frameIndex);
            } catch (Exception e) {
                LOGGER.error("Error in {}.appendOverlay: {}",
                        subsystem.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * GPU 资源统一销毁（注册逆序，与 shutdownAll 同向）：JavelinEngine.shutdown 在
     * frameLoop.close()（waitIdle）之后调用。基于 shutdownAll 留存的已关账副本——
     * 那时注册列表已清空。幂等：跑一次即清。
     */
    public void closeGpuAll() {
        if (shutdownSubsystems.isEmpty()) {
            return;
        }
        List<Subsystem> reversed = new ArrayList<>(shutdownSubsystems);
        Collections.reverse(reversed);
        for (Subsystem subsystem : reversed) {
            try {
                subsystem.closeGpu();
            } catch (Exception e) {
                LOGGER.error("Error in {}.closeGpu: {}",
                        subsystem.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
        shutdownSubsystems.clear();
    }

    /**
     * 是否已初始化。
     */
    public boolean isInitialized() {
        return initialized;
    }
}
