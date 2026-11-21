package cn.donting.javelin.core;

import cn.donting.javelin.rhi.PassDef;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.desc.TextureDesc;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.TextureUsage;

import java.util.EnumSet;
import java.util.List;

/**
 * 格式能力查询与创建期 fail-fast 验证（RHI-15 / RHI-08，2026-11-21）。
 *
 * <p>验两件事：</p>
 * <ol>
 *   <li><b>查询口径</b>：{@code Device.formatSupport} 对规范「必支持」组合给出 true——R8G8B8A8_UNORM
 *       可采样/可作颜色附件、R16_SFLOAT 可采样/可作颜色附件/可线性过滤、R32_UINT 可作颜色附件
 *       （拾取 ID-buffer 依赖）、D32_SFLOAT 可作深度附件；而颜色格式不可作深度附件（R8G8B8A8_UNORM
 *       的 depth 位必须为 false）。</li>
 *   <li><b>fail-fast</b>：用 {@code R8G8B8A8_UNORM} 建 {@code DEPTH_STENCIL_ATTACHMENT} 纹理必须在创建期抛
 *       {@link IllegalStateException}（不走驱动兜底）。</li>
 * </ol>
 *
 * <p>运行：{@code gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.FormatCapabilityTest}
 * 验收：控制台打印格式能力表 +「断言全部通过」，窗口 ESC 退出不崩。</p>
 */
public final class FormatCapabilityTest extends RhiTestBase {

    /** 打印能力表的格式（引擎在用的主力格式） */
    private static final List<Format> REPORT_FORMATS = List.of(
            Format.R8_UNORM,
            Format.R8G8B8A8_UNORM,
            Format.R16_SFLOAT,
            Format.R16G16B16A16_SFLOAT,
            Format.R32_UINT,
            Format.D32_SFLOAT,
            Format.BC1_RGBA_UNORM,
            Format.BC7_UNORM);

    /** 入口：{@code gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.FormatCapabilityTest} */
    public static void main(String[] args) {
        new FormatCapabilityTest().run();
    }

    @Override
    protected String testName() {
        return "FormatCapability";
    }

    @Override
    protected RHIClearColor clearColor() {
        return new RHIClearColor(0.08f, 0.08f, 0.10f, 1.0f);
    }

    @Override
    protected void onInit() {
        boolean ok = true;

        // ① 能力表
        for (Format format : REPORT_FORMATS) {
            log.info("能力: {}", device.formatSupport(format));
        }

        // ② 规范「必支持」断言
        ok &= expect("R8G8B8A8_UNORM 可采样", device.formatSupport(Format.R8G8B8A8_UNORM).sampledImage());
        ok &= expect("R8G8B8A8_UNORM 可作颜色附件",
                device.formatSupport(Format.R8G8B8A8_UNORM).colorAttachment());
        ok &= expect("R8G8B8A8_UNORM 不可作深度附件",
                !device.formatSupport(Format.R8G8B8A8_UNORM).depthStencilAttachment());
        ok &= expect("R16_SFLOAT 可作颜色附件且可线性过滤",
                device.formatSupport(Format.R16_SFLOAT).colorAttachment()
                        && device.formatSupport(Format.R16_SFLOAT).linearFilter());
        ok &= expect("R16G16B16A16_SFLOAT 可采样且可作颜色附件",
                device.formatSupport(Format.R16G16B16A16_SFLOAT).sampledImage()
                        && device.formatSupport(Format.R16G16B16A16_SFLOAT).colorAttachment());
        ok &= expect("R32_UINT 可作颜色附件（拾取 ID-buffer）",
                device.formatSupport(Format.R32_UINT).colorAttachment());
        ok &= expect("D32_SFLOAT 可作深度附件",
                device.formatSupport(Format.D32_SFLOAT).depthStencilAttachment());

        // ③ 负例：颜色格式配深度用途 → 创建期抛（不是建出来后等校验层）
        boolean threw = false;
        try {
            Texture bad = device.createTexture(TextureDesc.texture2D(Format.R8G8B8A8_UNORM, 4, 4,
                    EnumSet.of(TextureUsage.DEPTH_STENCIL_ATTACHMENT)), null);
            bad.dispose();
        } catch (IllegalStateException e) {
            threw = true;
            log.info("负例按预期抛错: {}", e.getMessage());
        }
        ok &= expect("非法 usage 组合在创建期抛 IllegalStateException", threw);

        if (!ok) {
            throw new AssertionError("格式能力断言未全部通过（见上方日志）");
        }
        log.info("格式能力断言全部通过（RHI-15 / RHI-08 收口口径）");
    }

    /** 打印并返回断言结果（失败也继续跑，最后统一报） */
    private boolean expect(String what, boolean actual) {
        if (actual) {
            log.info("[通过] {}", what);
        } else {
            log.error("[失败] {}", what);
        }
        return actual;
    }

    @Override
    protected void onPass(PassDef pass) {
        // 纯清屏验证：格式能力不看画面
    }
}
