package com.javelin.core.subsystem;

import com.javelin.core.JavelinEngine;

/**
 * 子系统基类。
 *
 * <p>提供引擎引用回填（init 时）与暂停开关；子类按需覆盖
 * {@link Subsystem} 的钩子，不需要的直接留给 default 空实现。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public abstract class AbstractSubsystem implements Subsystem {

    /**
     * 是否启用（暂停开关）。
     */
    protected boolean enabled = true;

    /**
     * 引擎实例（init 时回填）。
     */
    protected JavelinEngine engine;

    @Override
    public void init(JavelinEngine engine) {
        this.engine = engine;
    }

    /**
     * 暂停/恢复子系统：禁用后不参与帧调度，但保留在管理器中
     * （子系统结构冻结，不可移除，暂停是唯一关闭手段）。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
