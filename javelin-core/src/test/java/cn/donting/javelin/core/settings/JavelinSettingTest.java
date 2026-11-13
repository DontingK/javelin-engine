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
        source.setPostProcessing(true);

        File file = tempDir.resolve("javelin.settings").toFile();
        new BinaryExporter(file).export(source);
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);

        assertEquals(1920, restored.getWidth());
        assertEquals(1080, restored.getHeight());
        assertEquals("Test", restored.getTitle());
        assertTrue(!restored.isVsync());
        assertEquals(Color.fromHex("#1A2B3C"), restored.getBackgroundColor());
        assertEquals(PipelineProfile.FORWARD, restored.getRenderPipeline());
        assertTrue(restored.isPostProcessing());
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
        // 旧文件缺 postProcessing 键 → readBoolean=false，恰为默认（关 = 直通）
        assertTrue(!restored.isPostProcessing());
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
}
