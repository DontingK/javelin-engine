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
 * JavelinSetting 序列化 round-trip：backgroundColor（嵌套 Color）+ vsync +
 * renderPipeline（枚举档，全取值）+ postProcessing（布尔）+ engineDebug。
 */
public class JavelinSettingTest {

    @TempDir
    Path tempDir;

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        JavelinSetting source = new JavelinSetting();
        source.setWidth(1920);
        source.setHeight(1080);
        source.setTitle("Test");
        source.setVsync(false);
        source.setBackgroundColor(Color.fromHex("#1A2B3C"));
        source.setRenderPipeline(PipelineProfile.FORWARD);
        source.getPostProcessSettings().setTonemapEnabled(true);

        File file = tempDir.resolve("javelin.settings").toFile();
        new BinaryExporter(file).export(source);
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);

        assertEquals(1920, restored.getWidth());
        assertEquals(1080, restored.getHeight());
        assertEquals("Test", restored.getTitle());
        assertTrue(!restored.isVsync());
        assertEquals(Color.fromHex("#1A2B3C"), restored.getBackgroundColor());
        assertEquals(PipelineProfile.FORWARD, restored.getRenderPipeline());
        assertTrue(restored.getPostProcessSettings().isTonemapEnabled());
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
        assertEquals(new Color(0.05f, 0.05f, 0.08f, 1f), restored.getBackgroundColor());
        // 默认管线 = HYBRID（2026-11-13 主口径改向）；缺键保默认
        assertEquals(PipelineProfile.HYBRID, restored.getRenderPipeline());
        // 旧文件缺 postProcessing 键 → 迁移读取 false、嵌套开关默认（关 = 直通）
        assertTrue(!restored.getPostProcessSettings().isTonemapEnabled());
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
        assertTrue(restored.getPostProcessSettings().isTonemapEnabled(), "旧顶层 postProcessing 应迁入嵌套");
        assertTrue(restored.getPostProcessSettings().isBloomEnabled(), "旧顶层 bloom 应迁入嵌套");
        assertTrue(restored.getPostProcessSettings().isSsaoEnabled(), "旧顶层 ssao 应迁入嵌套");
    }

    @Test
    public void renderPipelineAllValuesRoundTrip() throws Exception {
        // 每个取值分支都过一遍（BLEND_NAMES 去下划线教训：验收样例须覆盖每个取值分支）
        for (PipelineProfile profile : PipelineProfile.values()) {
            JavelinSetting source = new JavelinSetting();
            source.setRenderPipeline(profile);
            File file = tempDir.resolve("rp-" + profile.name() + ".settings").toFile();
            new BinaryExporter(file).export(source);
            JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
            assertEquals(profile, restored.getRenderPipeline());
        }
    }

    /** 阴影参数（嵌套 ShadowSettings：品质档 + 五项浮点）round-trip */
    @Test
    public void shadowSettingsRoundTrip() throws Exception {
        JavelinSetting source = new JavelinSetting();
        ShadowSettings shadow = source.getShadowSettings();
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

        ShadowSettings r = restored.getShadowSettings();
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
            source.getShadowSettings().setQuality(quality);
            File file = tempDir.resolve("sq-" + quality.name() + ".settings").toFile();
            new BinaryExporter(file).export(source);
            JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
            assertEquals(quality, restored.getShadowSettings().getQuality());
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
        ShadowSettings r = restored.getShadowSettings();
        // 缺键若走 readFloat 会得 0（强度 0 = 无阴影、半径 0 = 无软核）——嵌套对象保默认为正确语义
        assertEquals(ShadowQuality.SOFT_LOW, r.getQuality());
        assertEquals(1.0f, r.getStrength(), 1e-6f);
        assertEquals(1.0f, r.getPcfRadius(), 1e-6f);
        assertEquals(0.0f, r.getRefDepthOffset(), 1e-6f);
        assertEquals(2.0f, r.getBiasConstant(), 1e-6f);
        assertEquals(1.5f, r.getBiasSlope(), 1e-6f);
        // CSM 三项缺键：级联数读 0 不进 1/2/4 保默认 2；距离读 0 保 100；lambda 读 0 合法（均匀）
        assertEquals(2, r.getCascadeCount());
        assertEquals(100.0f, r.getShadowDistance(), 1e-6f);
        assertEquals(0.0f, r.getCascadeLambda(), 1e-6f);
    }
}
