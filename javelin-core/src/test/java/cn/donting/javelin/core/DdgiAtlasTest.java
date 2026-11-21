package cn.donting.javelin.core;

import cn.donting.javelin.core.render.ddgi.DdgiAtlases;
import cn.donting.javelin.core.render.ddgi.DdgiProbeGrid;
import cn.donting.javelin.core.settings.DdgiSettings;
import cn.donting.javelin.rhi.PassDef;
import cn.donting.javelin.rhi.data.RHIClearColor;

/**
 * DDGI 两张 atlas 的 GPU 资源验证（Engine_DDGI_Design.md v0.2 §3，P4-05 批 B1 残留①）。
 *
 * <p>验四件事：</p>
 * <ol>
 *   <li><b>能开出来</b>：{@code RGBA16F} / {@code RG16F} 按 {@code SAMPLED|STORAGE|TRANSFER_SRC|TRANSFER_DST}
 *       创建成功（RHI-15 闸门放行 = 本设备确有 storage 位；放行不了会在创建期抛）。</li>
 *   <li><b>尺寸对账</b>：缺省网格 16×4×16（1024 探针）→ 辐照度 320×320、深度 576×576，
 *       与 {@link DdgiProbeGrid} 的字节账一致。</li>
 *   <li><b>换代判据</b>：同版面 {@code matches} 为真；改探针数后为假（触发重建，
 *       不重建就会按旧图采样、版面错位成花屏）。</li>
 *   <li><b>换代能真的重建</b>：第二组网格独立开一对图并正常释放（不泄漏、不抛）。</li>
 * </ol>
 *
 * <p>运行：{@code gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.DdgiAtlasTest}
 * 验收：控制台打印两组 atlas 的尺寸/格式/显存账 +「断言全部通过」，窗口 ESC 退出不崩。
 * <b>本件不验 STORAGE 实写</b>——写通道属 B2（{@code ddgi_update.comp}）的验收面。</p>
 */
public final class DdgiAtlasTest extends RhiTestBase {

    private DdgiAtlases defaultAtlases;
    private DdgiAtlases secondAtlases;

    /** 入口：{@code gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.DdgiAtlasTest} */
    public static void main(String[] args) {
        new DdgiAtlasTest().run();
    }

    @Override
    protected String testName() {
        return "DdgiAtlas";
    }

    @Override
    protected RHIClearColor clearColor() {
        return new RHIClearColor(0.08f, 0.08f, 0.10f, 1.0f);
    }

    @Override
    protected void onInit() {
        boolean ok = true;

        // ① 格式能力前置（RHI-15 快照：两张图都必须能 storage 写 + sampled 读）
        ok &= expect("RGBA16F 可 STORAGE（辐照度 atlas 写通道）",
                device.formatSupport(DdgiAtlases.IRRADIANCE_FORMAT).storageImage());
        ok &= expect("RGBA16F 可 SAMPLED（辐照度 atlas 读通道）",
                device.formatSupport(DdgiAtlases.IRRADIANCE_FORMAT).sampledImage());
        ok &= expect("RG16F 可 STORAGE（深度矩 atlas 写通道）",
                device.formatSupport(DdgiAtlases.DEPTH_FORMAT).storageImage());
        ok &= expect("RG16F 可 SAMPLED（深度矩 atlas 读通道）",
                device.formatSupport(DdgiAtlases.DEPTH_FORMAT).sampledImage());

        // ② 缺省网格：16×4×16 探针、间距 2m、相机在原点
        DdgiSettings settings = new DdgiSettings();
        DdgiProbeGrid grid = DdgiProbeGrid.cameraCentered(0f, 0f, 0f, settings);
        log.info("缺省网格: {}", grid);
        defaultAtlases = DdgiAtlases.create(device, grid);
        log.info("atlas 已创建: {}（显存 {} KB）", defaultAtlases,
                (grid.irradianceAtlasBytes() + grid.depthAtlasBytes()) / 1024);

        ok &= expect("辐照度 atlas 尺寸 = 网格账（" + grid.irradianceAtlasWidth() + "x"
                        + grid.irradianceAtlasHeight() + "）",
                defaultAtlases.irradianceWidth() == grid.irradianceAtlasWidth()
                        && defaultAtlases.irradianceHeight() == grid.irradianceAtlasHeight());
        ok &= expect("深度 atlas 尺寸 = 网格账（" + grid.depthAtlasWidth() + "x"
                        + grid.depthAtlasHeight() + "）",
                defaultAtlases.depthWidth() == grid.depthAtlasWidth()
                        && defaultAtlases.depthHeight() == grid.depthAtlasHeight());
        ok &= expect("探针数 = " + grid.probeCount(),
                defaultAtlases.probeCount() == grid.probeCount());
        ok &= expect("纹理描述符版面与网格一致（宽高像素对得上）",
                defaultAtlases.irradiance().desc().width() == grid.irradianceAtlasWidth()
                        && defaultAtlases.irradiance().desc().height() == grid.irradianceAtlasHeight()
                        && defaultAtlases.depth().desc().width() == grid.depthAtlasWidth()
                        && defaultAtlases.depth().desc().height() == grid.depthAtlasHeight());

        // ③ 换代判据：同版面复用、改探针数必须换
        ok &= expect("同网格 matches 为真（复用不重建）", defaultAtlases.matches(grid));

        DdgiSettings bigger = new DdgiSettings();
        bigger.setProbesX(20);
        DdgiProbeGrid biggerGrid = DdgiProbeGrid.cameraCentered(0f, 0f, 0f, bigger);
        ok &= expect("改探针数后 matches 为假（触发换代）", !defaultAtlases.matches(biggerGrid));

        // ④ 换代实建：第二组按 20×4×16 开一对新图（并正常释放）
        secondAtlases = DdgiAtlases.create(device, biggerGrid);
        log.info("第二组 atlas: {}（显存 {} KB）", secondAtlases,
                (biggerGrid.irradianceAtlasBytes() + biggerGrid.depthAtlasBytes()) / 1024);
        ok &= expect("换代后尺寸随网格变（辐照度 " + biggerGrid.irradianceAtlasWidth() + "x"
                        + biggerGrid.irradianceAtlasHeight() + "）",
                secondAtlases.irradianceWidth() == biggerGrid.irradianceAtlasWidth()
                        && secondAtlases.irradianceHeight() == biggerGrid.irradianceAtlasHeight());

        if (!ok) {
            throw new AssertionError("DDGI atlas 断言未全部通过（见上方日志）");
        }
        log.info("DDGI atlas 断言全部通过（B1 残留①收口口径：能力/尺寸/换代/重建）");
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
        // 纯清屏验证：atlas 只在显存里，不上屏
    }

    @Override
    protected void onDispose() {
        // 帧循环已关闭、无在飞帧：直接释放（不走两帧退役队列）
        if (defaultAtlases != null) {
            defaultAtlases.irradiance().dispose();
            defaultAtlases.depth().dispose();
        }
        if (secondAtlases != null) {
            secondAtlases.irradiance().dispose();
            secondAtlases.depth().dispose();
        }
    }
}
