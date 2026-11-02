package cn.donting.javelin.core;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.Time;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.scene.Scene;
import cn.donting.javelin.core.settings.JavelinSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class JavelinEngineTestBase extends JavelinEngine {
    private static final Logger log = LoggerFactory.getLogger(JavelinEngineTestBase.class);

    /** 性能观测开关：子类置 true 后每秒打印 [perf] fps/最差帧/平均帧（读法见 update 注释） */
    protected boolean perfLogEnabled = false;

    private long perfWindowStartNanos;
    private int perfFrames;
    private float perfWorstFrameSeconds;

    public JavelinEngineTestBase(JavelinSetting setting) {
        super(setting);
    }

    /**
     * 测试侧资产管理器：SpvShader 内置源 Library miss 时 tool 现编译返回——
     * runTest 无编辑器导入链，ShadowPass/SkyboxPass 等覆盖制 Pass 的装载闭环
     * （2026-11-02 拍板：测试类自行编译加载）。
     */
    @Override
    protected AssetManager createAssetManager() {
        return new TestAssetManager(new File("./Library"));
    }

    @Override
    protected Scene loadScene() {
        return null;
    }

    /**
     * 每秒窗口性能观测：FPS + 窗口内最差单帧 + 平均帧耗时。
     * 读法——avg ≈ 1/刷新率 = FIFO 宽帧（vsync 锁帧）；worst « 2×avg = 节律问题
     * 而非掉帧（顿挫看 worst/avg 比值，见 Vulkan_Pitfalls #21）；
     * avg 本身超预算 = 稳定性慢（渲染/驱动），与抖动区分。
     */
    @Override
    protected void update() {
        if (!perfLogEnabled) {
            return;
        }
        long now = System.nanoTime();
        if (perfWindowStartNanos == 0L) {
            perfWindowStartNanos = now;
            return;
        }
        float frameSeconds = Time.getUnscaledDeltaTime();
        if (frameSeconds > perfWorstFrameSeconds) {
            perfWorstFrameSeconds = frameSeconds;
        }
        perfFrames++;
        long elapsed = now - perfWindowStartNanos;
        if (elapsed >= 1_000_000_000L) {
            float seconds = elapsed / 1.0e9f;
            log.info("[perf] {} fps | worst {} ms | avg {} ms",
                    Math.round(perfFrames / seconds),
                    String.format("%.1f", perfWorstFrameSeconds * 1000f),
                    String.format("%.1f", seconds * 1000f / perfFrames));
            perfWindowStartNanos = now;
            perfFrames = 0;
            perfWorstFrameSeconds = 0f;
        }
    }
}
