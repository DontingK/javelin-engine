package cn.donting.javelin.core.settings;

/**
 * 抗锯齿模式（{@link JavelinSetting#getAntialiasing()}）。
 *
 * <p>按 name 字符串落盘（readEnum/write Enum 通道），与 RHI 的
 * {@code SampleCount} 值域解耦——RHI 侧改名/改档位不伤旧设置文件；
 * 档位换算由引擎消费侧完成（{@link #samples()}）。v0.1 仅 MSAA 档
 * （离屏 MSAA 附件 + resolve 回 backbuffer，overlay 恒 1x，
 * Engine_RenderPipeline_Design MSAA 方案）；FXAA 等后处理档将来直接追加枚举值。
 * 引擎启动期消费（vsync 同口径），运行期切换需管线/附件失效协议，后置。
 */
public enum AntialiasingMode {

    /** 关闭（场景直写 backbuffer，默认路径） */
    OFF,
    /** 2x MSAA */
    MSAA_2,
    /** 4x MSAA（常用档） */
    MSAA_4,
    /** 8x MSAA */
    MSAA_8;

    /**
     * 每像素采样数（OFF = 1，与 Vulkan 单采样语义对齐）。
     * 引擎消费侧以此换算 RHI {@code SampleCount}。
     */
    public int samples() {
        return switch (this) {
            case OFF -> 1;
            case MSAA_2 -> 2;
            case MSAA_4 -> 4;
            case MSAA_8 -> 8;
        };
    }
}
