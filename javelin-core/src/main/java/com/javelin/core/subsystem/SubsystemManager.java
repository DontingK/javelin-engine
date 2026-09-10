package com.javelin.core.subsystem;

import com.javelin.core.JavelinEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 子系统管理器 —— 统一管理所有 {@link Subsystem} 的注册、生命周期和调度。
 *
 * <p>执行顺序由方法名直接体现，同阶段内按注册顺序遍历：
 * <pre>
 *   updateAll(dt):
 *       for each subsystem: earlyUpdate(dt)
 *       for each subsystem: update(dt)
 *       for each subsystem: lateUpdate(dt)
 *
 *   renderAll():
 *       for each subsystem: preRender()
 *       for each subsystem: render()
 *       for each subsystem: postRender()
 * </pre>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class SubsystemManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubsystemManager.class);

    private final List<Subsystem> subsystems = new ArrayList<>();
    private boolean initialized = false;

    /**
     * 注册子系统。
     * <p>
     * 必须在 {@link #initAll()} 之前调用，初始化后结构冻结，禁止再注册。
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
     * 调用后结构冻结，禁止再注册子系统。
     * 按注册顺序调用 {@link Subsystem#init()}。
     * </p>
     */
    public void initAll(JavelinEngine javelinEngine) {
        if (initialized) {
            LOGGER.warn("SubsystemManager already initialized");
            return;
        }
        LOGGER.info("Initializing {} subsystems...", subsystems.size());
        for (Subsystem s : subsystems) {
            try {
                s.init(javelinEngine);
            } catch (Exception e) {
                LOGGER.error("Failed to initialize subsystem: {}", s.getClass().getSimpleName(), e);
            }
        }
        initialized = true;
    }

    /**
     * 关闭所有子系统（逆序）。
     * <p>
     * 后初始化的先关闭，避免依赖问题。
     * 关闭后可通过重新创建 SubsystemManager 实例来重新配置。
     * </p>
     */
    public void shutdownAll() {
        if (!initialized) {
            return;
        }
        LOGGER.info("Shutting down {} subsystems...", subsystems.size());
        List<Subsystem> reversed = new ArrayList<>(subsystems);
        Collections.reverse(reversed);
        for (Subsystem s : reversed) {
            try {
                s.shutdown();
            } catch (Exception e) {
                LOGGER.error("Error shutting down subsystem: {}", s.getClass().getSimpleName(), e);
            }
        }
        initialized = false;
        subsystems.clear();
    }

    /**
     * 执行所有子系统的 earlyUpdate。
     */
    public void earlyUpdate(float deltaTime) {
        for (Subsystem s : subsystems) {
            try {
                s.earlyUpdate(deltaTime);
            } catch (Exception e) {
                LOGGER.error("Error in {}.earlyUpdate: {}", s.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 执行所有子系统的 update。
     */
    public void update(float deltaTime) {
        for (Subsystem s : subsystems) {
            try {
                s.update(deltaTime);
            } catch (Exception e) {
                LOGGER.error("Error in {}.update: {}", s.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 执行所有子系统的 lateUpdate。
     */
    public void lateUpdate(float deltaTime) {
        for (Subsystem s : subsystems) {
            try {
                s.lateUpdate(deltaTime);
            } catch (Exception e) {
                LOGGER.error("Error in {}.lateUpdate: {}", s.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 执行所有子系统的 preRender。
     *
     */
    public void preRender() {
        for (Subsystem s : subsystems) {
            try {
                s.preRender();
            } catch (Exception e) {
                LOGGER.error("Error in {}.preRender: {}", s.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 执行所有子系统的 render。
     *
     */
    public void render() {
        for (Subsystem s : subsystems) {
            try {
                s.render();
            } catch (Exception e) {
                LOGGER.error("Error in {}.render: {}", s.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 执行所有子系统的 postRender。
     *
     */
    public void postRender() {
        for (Subsystem s : subsystems) {
            try {
                s.postRender();
            } catch (Exception e) {
                LOGGER.error("Error in {}.postRender: {}", s.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 获取子系统数量。
     */
    public int getCount() {
        return subsystems.size();
    }

    /**
     * 是否已初始化。
     */
    public boolean isInitialized() {
        return initialized;
    }
}
