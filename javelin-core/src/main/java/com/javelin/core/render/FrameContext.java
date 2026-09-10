package com.javelin.core.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 每帧全局渲染上下文 —— 不随相机变化的数据。
 *
 * <p>所有相机、所有 Pass 共享同一实例。只读访问，线程安全。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class FrameContext {

    /** 帧间隔（秒） */
    private final float deltaTime;

    /** 总运行时间（秒） */
    private final float totalTime;

    /** 帧计数器 */
    private final long frameCounter;

    /** 后备显示尺寸 */
    private final int fallbackWidth;
    private final int fallbackHeight;

    public FrameContext(float deltaTime, float totalTime, long frameCounter,
                        int fallbackWidth, int fallbackHeight) {
        this.deltaTime = deltaTime;
        this.totalTime = totalTime;
        this.frameCounter = frameCounter;
        this.fallbackWidth = fallbackWidth;
        this.fallbackHeight = fallbackHeight;
    }

    public float getDeltaTime() {
        return deltaTime;
    }

    public float getTotalTime() {
        return totalTime;
    }

    public long getFrameCounter() {
        return frameCounter;
    }

    public int getFallbackWidth() {
        return fallbackWidth;
    }

    public int getFallbackHeight() {
        return fallbackHeight;
    }
}
