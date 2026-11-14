package cn.donting.javelin.core.settings;

import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JavelinSetting 序列化 round-trip：windowSettings（嵌套 WindowSetting）+ backgroundColor
 * （嵌套 Color）+ renderPipeline（枚举档，全取值）+ postProcessSettings / shadowSettings
 * （嵌套对象）+ 旧档根键一次性迁移（窗口五项 / 顶层三效果开关）。
 */
public class JavelinSettingTest {

    @TempDir
    Path tempDir;

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        JavelinSetting source = new JavelinSetting();
        source.getWindowSettings().setWidth(1920);
        source.getWindowSettings().setHeight(1080);
        source.getWindowSettings().setTitle("Test");
        source.getWindowSettings().setVsync(false);
        source.getWindowSettings().setMaxFps(240);
        source.getGraphSettings().setBackgroundColor(Color.fromHex("#1A2B3C"));
        source.getGraphSettings().setRenderPipeline(PipelineProfile.FORWARD);
        source.getGraphSettings().getPostProcessSettings().setTonemapEnabled(true);
        source.getGraphSettings().getSsaoSettings().setEnabled(true);
        source.getGraphSettings().getSsaoSettings().setRadius(1.1f);

        File file = tempDir.resolve("javelin.settings").toFile();
        new BinaryExporter(file).export(source);
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);

        assertEquals(1920, restored.getWindowSettings().getWidth());
        assertEquals(1080, restored.getWindowSettings().getHeight());
        assertEquals("Test", restored.getWindowSettings().getTitle());
        assertTrue(!restored.getWindowSettings().isVsync());
        assertEquals(240, restored.getWindowSettings().getMaxFps());
        assertEquals(Color.fromHex("#1A2B3C"), restored.getGraphSettings().getBackgroundColor());
        assertEquals(PipelineProfile.FORWARD, restored.getGraphSettings().getRenderPipeline());
        assertTrue(restored.getGraphSettings().getPostProcessSettings().isTonemapEnabled());
        assertTrue(restored.getGraphSettings().getSsaoSettings().isEnabled());
        assertEquals(1.1f, restored.getGraphSettings().getSsaoSettings().getRadius(), 1e-6f);
    }

    @Test
    public void defaultsRemainWhenFileHasNoColorOrVsync() throws Exception {
        // 模拟旧设置文件（backgroundColor/vsync/renderPipeline 键尚未写入）：read 后保默认
        File file = tempDir.resolve("legacy.settings").toFile();
        new BinaryExporter(file).export(new JavelinSetting() {
            @Override
            public void write(OutputCapsule output) {
                // 只写旧字段，模拟旧版本产物
                output.write("width", 800);
                output.write("height", 600);
                output.write("title", "Legacy");
            }
        });
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
        // 窗口五项迁自旧档根键（width/height/title 有值，vsync/maxFps 缺键取默认）
        assertEquals(800, restored.getWindowSettings().getWidth());
        assertEquals(600, restored.getWindowSettings().getHeight());
        assertEquals("Legacy", restored.getWindowSettings().getTitle());
        assertTrue(restored.getWindowSettings().isVsync());
        assertEquals(144, restored.getWindowSettings().getMaxFps());
        assertEquals(new Color(0.05f, 0.05f, 0.08f, 1f), restored.getGraphSettings().getBackgroundColor());
        // 默认管线 = HYBRID（2026-11-13 主口径改向）；缺键保默认
        assertEquals(PipelineProfile.HYBRID, restored.getGraphSettings().getRenderPipeline());
        // 旧文件缺 postProcessing 键 → 迁移读取 false、嵌套开关默认（关 = 直通）
        assertTrue(!restored.getGraphSettings().getPostProcessSettings().isTonemapEnabled());
    }

    /**
     * 一次性迁移（2026-11-14 窗口五项迁入 WindowSetting）：旧文件根键
     * {@code width/height/title/vsync/maxFps} 读后全部进嵌套对象。
     */
    @Test
    public void legacyRootWindowFieldsMigrateIntoWindowSetting() throws Exception {
        File file = tempDir.resolve("legacy-window.settings").toFile();
        new BinaryExporter(file).export(new JavelinSetting() {
            @Override
            public void write(OutputCapsule output) {
                output.write("width", 1600);
                output.write("height", 900);
                output.write("title", "Legacy Window");
                output.write("vsync", false);
                output.write("maxFps", 90);
            }
        });
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
        WindowSetting window = restored.getWindowSettings();
        assertEquals(1600, window.getWidth());
        assertEquals(900, window.getHeight());
        assertEquals("Legacy Window", window.getTitle());
        assertTrue(!window.isVsync(), "旧根 vsync=false 应迁入");
        assertEquals(90, window.getMaxFps());
    }

    /**
     * 一次性迁移（2026-11-14 开关收拢进 PostProcessSettings）：旧文件顶层
     * {@code postProcessing/bloom/ssao}=true → 读后迁进嵌套开关（旧工程开关不丢）；
     * 新文件不写顶层键（读到 false 无副作用）。
     */
    @Test
    public void legacyTopLevelTogglesMigrateIntoPostProcessSettings() throws Exception {
        File file = tempDir.resolve("legacy-toggles.settings").toFile();
        new BinaryExporter(file).export(new JavelinSetting() {
            @Override
            public void write(OutputCapsule output) {
                output.write("width", 800);
                output.write("height", 600);
                output.write("title", "Legacy");
                output.write("postProcessing", true);
                output.write("bloom", true);
                output.write("ssao", true);
            }
        });
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
        assertTrue(restored.getGraphSettings().getPostProcessSettings().isTonemapEnabled(), "旧顶层 postProcessing 应迁入嵌套");
        assertTrue(restored.getGraphSettings().getPostProcessSettings().isBloomEnabled(), "旧顶层 bloom 应迁入嵌套");
        assertTrue(restored.getGraphSettings().getSsaoSettings().isEnabled(),
                "旧顶层 ssao 应迁入独立组");
    }

    @Test
    public void renderPipelineAllValuesRoundTrip() throws Exception {
        // 每个取值分支都过一遍（BLEND_NAMES 去下划线教训：验收样例须覆盖每个取值分支）
        for (PipelineProfile profile : PipelineProfile.values()) {
            JavelinSetting source = new JavelinSetting();
            source.getGraphSettings().setRenderPipeline(profile);
            File file = tempDir.resolve("rp-" + profile.name() + ".settings").toFile();
            new BinaryExporter(file).export(source);
            JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
            assertEquals(profile, restored.getGraphSettings().getRenderPipeline());
        }
    }

    /** 阴影参数（嵌套 ShadowSettings：品质档 + 五项浮点）round-trip */
    @Test
    public void shadowSettingsRoundTrip() throws Exception {
        JavelinSetting source = new JavelinSetting();
        ShadowSettings shadow = source.getGraphSettings().getShadowSettings();
        shadow.setQuality(ShadowQuality.SOFT_HIGH);
        shadow.setStrength(0.4f);
        shadow.setPcfRadius(2.5f);
        shadow.setRefDepthOffset(0.3f);
        shadow.setBiasConstant(3.5f);
        shadow.setBiasSlope(2.25f);
        shadow.setCascadeCount(4);
        shadow.setShadowDistance(150.0f);
        shadow.setCascadeLambda(0.7f);

        File file = tempDir.resolve("shadow.settings").toFile();
        new BinaryExporter(file).export(source);
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);

        ShadowSettings r = restored.getGraphSettings().getShadowSettings();
        assertEquals(ShadowQuality.SOFT_HIGH, r.getQuality());
        assertEquals(0.4f, r.getStrength(), 1e-6f);
        assertEquals(2.5f, r.getPcfRadius(), 1e-6f);
        assertEquals(0.3f, r.getRefDepthOffset(), 1e-6f);
        assertEquals(3.5f, r.getBiasConstant(), 1e-6f);
        assertEquals(2.25f, r.getBiasSlope(), 1e-6f);
        assertEquals(4, r.getCascadeCount());
        assertEquals(150.0f, r.getShadowDistance(), 1e-6f);
        assertEquals(0.7f, r.getCascadeLambda(), 1e-6f);
    }

    /** 品质档每个取值分支都过一遍（枚举序即 UBO 编码，改动须双向一致） */
    @Test
    public void shadowQualityAllValuesRoundTrip() throws Exception {
        for (ShadowQuality quality : ShadowQuality.values()) {
            JavelinSetting source = new JavelinSetting();
            source.getGraphSettings().getShadowSettings().setQuality(quality);
            File file = tempDir.resolve("sq-" + quality.name() + ".settings").toFile();
            new BinaryExporter(file).export(source);
            JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
            assertEquals(quality, restored.getGraphSettings().getShadowSettings().getQuality());
        }
    }

    /** 旧设置文件无 shadowSettings 键 → 保默认（缺键 null 判定，非读 0） */
    @Test
    public void shadowSettingsDefaultsRemainOnLegacyFile() throws Exception {
        File file = tempDir.resolve("legacy-shadow.settings").toFile();
        new BinaryExporter(file).export(new JavelinSetting() {
            @Override
            public void write(OutputCapsule output) {
                output.write("width", 800);
                output.write("height", 600);
                output.write("title", "Legacy");
            }
        });
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
        ShadowSettings r = restored.getGraphSettings().getShadowSettings();
        // 缺键若走 readFloat 会得 0（强度 0 = 无阴影、半径 0 = 无软核）——嵌套对象保默认为正确语义
        assertEquals(ShadowQuality.SOFT_LOW, r.getQuality());
        assertEquals(1.0f, r.getStrength(), 1e-6f);
        assertEquals(1.0f, r.getPcfRadius(), 1e-6f);
        assertEquals(0.0f, r.getRefDepthOffset(), 1e-6f);
        assertEquals(2.0f, r.getBiasConstant(), 1e-6f);
        assertEquals(1.5f, r.getBiasSlope(), 1e-6f);
        // CSM 三项缺键取字段初值（默认值重载，不再是「读 0 + 合法性守卫」）
        assertEquals(2, r.getCascadeCount());
        assertEquals(100.0f, r.getShadowDistance(), 1e-6f);
        assertEquals(0.5f, r.getCascadeLambda(), 1e-6f);
    }
}
