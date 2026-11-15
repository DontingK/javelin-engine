package cn.donting.javelin.core.settings;

import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JavelinSetting 序列化 round-trip：五域一律嵌套对象（窗口 / 图形 / 图层 / 标签 / 调试开关），
 * 缺键即字段声明初值。
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
        source.getSceneSetting().setDefaultSceneGuid("guid-scene-1");

        File file = tempDir.resolve("javelin.settings").toFile();
        new BinaryExporter(file).export(source);
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);

        assertEquals(1920, restored.getWindowSettings().getWidth());
        assertEquals(1080, restored.getWindowSettings().getHeight());
        assertEquals("Test", restored.getWindowSettings().getTitle());
        assertFalse(restored.getWindowSettings().isVsync());
        assertEquals(240, restored.getWindowSettings().getMaxFps());
        assertEquals(Color.fromHex("#1A2B3C"), restored.getGraphSettings().getBackgroundColor());
        assertEquals(PipelineProfile.FORWARD, restored.getGraphSettings().getRenderPipeline());
        assertTrue(restored.getGraphSettings().getPostProcessSettings().isTonemapEnabled());
        assertTrue(restored.getGraphSettings().getSsaoSettings().isEnabled());
        assertEquals(1.1f, restored.getGraphSettings().getSsaoSettings().getRadius(), 1e-6f);
        assertEquals("guid-scene-1", restored.getSceneSetting().getDefaultSceneGuid());
    }

    /** 各域缺键 → 保字段声明初值（嵌套对象 null 判定，不做平铺根键的兼容读取） */
    @Test
    public void missingNestedSettingsFallBackToDefaults() throws Exception {
        File file = tempDir.resolve("sparse.settings").toFile();
        new BinaryExporter(file).export(new JavelinSetting() {
            @Override
            public void write(OutputCapsule output) {
                output.write("engineDebug", false);
            }
        });
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);

        assertEquals(1080, restored.getWindowSettings().getWidth());
        assertEquals(720, restored.getWindowSettings().getHeight());
        assertEquals("Javelin Engine 5", restored.getWindowSettings().getTitle());
        assertTrue(restored.getWindowSettings().isVsync());
        assertEquals(144, restored.getWindowSettings().getMaxFps());
        assertEquals(new Color(0.05f, 0.05f, 0.08f, 1f), restored.getGraphSettings().getBackgroundColor());
        assertEquals(PipelineProfile.HYBRID, restored.getGraphSettings().getRenderPipeline());
        assertFalse(restored.getGraphSettings().getPostProcessSettings().isTonemapEnabled());
        assertFalse(restored.getGraphSettings().getSsaoSettings().isEnabled());
        assertFalse(restored.isEngineDebug(), "未知键不受影响，已知键照读");
    }

    /** 图层 / 标签（并入唯一设置文件）：数组与列表 round-trip */
    @Test
    public void layerAndTagSettingsRoundTrip() throws Exception {
        JavelinSetting source = new JavelinSetting();
        source.getLayerSettings().getLayerNames()[0] = "Default";
        source.getLayerSettings().getLayerNames()[5] = "UI";
        source.getTagSettings().add("Untagged");
        source.getTagSettings().add("Enemy");

        File file = tempDir.resolve("layer-tag.settings").toFile();
        new BinaryExporter(file).export(source);
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);

        String[] names = restored.getLayerSettings().getLayerNames();
        assertEquals(31, names.length, "层槽位数不变");
        assertEquals("Default", names[0]);
        assertEquals("UI", names[5]);
        assertEquals(List.of("Untagged", "Enemy"), restored.getTagSettings().getTags());
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

    /** 缺 shadowSettings 键 → 保默认（嵌套对象 null 判定，非读 0） */
    @Test
    public void shadowSettingsDefaultsRemainWhenNestedKeyMissing() throws Exception {
        File file = tempDir.resolve("sparse-shadow.settings").toFile();
        new BinaryExporter(file).export(new JavelinSetting() {
            @Override
            public void write(OutputCapsule output) {
                output.write("engineDebug", true);
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
