package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.render.Platform;

/**
 * RHI 入口。
 * 对照设计文档第 26.1 节。
 */
public final class RHI {

    private RHI() {}

    /**
     * 创建 Vulkan 实例。
     *
     * @param config   实例配置（应用名、版本、是否调试）
     * @param platform 平台（决定 surface 扩展）
     * @return Instance
     */
    public static Instance createInstance(RHIInstanceConfig config, Platform platform) {
        return new VulkanInstance(config, platform);
    }

    /**
     * 开始构建一帧的渲染图（7 / 12.6 节用法）。
     * 图为纯 CPU 声明对象，构建与编译无 GPU 依赖；
     * 编译产物经 {@link RenderGraphCache} 可跨帧复用（18 节）。
     */
    public static RenderGraphBuilder beginRenderGraph() {
        return new RenderGraphImpl();
    }

    /**
     * 实例配置。
     */
    public static final class RHIInstanceConfig {
        private final String appName;
        private final int appVersion;
        private final boolean debug;

        public RHIInstanceConfig(String appName, int appVersion, boolean debug) {
            this.appName = appName;
            this.appVersion = appVersion;
            this.debug = debug;
        }

        public String appName() { return appName; }
        public int appVersion() { return appVersion; }
        public boolean debug() { return debug; }

        public static RHIInstanceConfig defaults() {
            return new RHIInstanceConfig("JavelinEngine", 1, true);
        }
    }
}
