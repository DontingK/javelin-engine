package com.javelin.core.settings;

import com.javelin.core.math.Color;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JavelinSetting 序列化 round-trip：backgroundColor（嵌套 Color）+ vsync +
 * antialiasing（枚举档，取值分支全覆盖）。
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
        source.setAntialiasing(AntialiasingMode.MSAA_4);

        File file = tempDir.resolve("javelin.settings").toFile();
        new BinaryExporter(file).export(source);
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);

        assertEquals(1920, restored.getWidth());
        assertEquals(1080, restored.getHeight());
        assertEquals("Test", restored.getTitle());
        assertTrue(!restored.isVsync());
        assertEquals(Color.fromHex("#1A2B3C"), restored.getBackgroundColor());
        assertEquals(AntialiasingMode.MSAA_4, restored.getAntialiasing());
    }

    @Test
    public void defaultsRemainWhenFileHasNoColorOrVsync() throws Exception {
        // 模拟旧设置文件（backgroundColor/vsync 键尚未写入）：read 后 backgroundColor 保默认
        File file = tempDir.resolve("legacy.settings").toFile();
        new BinaryExporter(file).export(new JavelinSetting() {
            @Override
            public void write(com.javelin.core.serialize.OutputCapsule output) {
                // 只写旧字段，模拟旧版本产物
                output.write("width", 800);
                output.write("height", 600);
                output.write("title", "Legacy");
            }
        });
        JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
        assertEquals(new Color(0.05f, 0.05f, 0.08f, 1f), restored.getBackgroundColor());
        assertEquals(AntialiasingMode.OFF, restored.getAntialiasing());
    }

    @Test
    public void antialiasingAllValuesRoundTrip() throws Exception {
        // 每个取值分支都过一遍（BLEND_NAMES 去下划线教训：验收样例须覆盖每个取值分支）
        for (AntialiasingMode mode : AntialiasingMode.values()) {
            JavelinSetting source = new JavelinSetting();
            source.setAntialiasing(mode);
            File file = tempDir.resolve("aa-" + mode.name() + ".settings").toFile();
            new BinaryExporter(file).export(source);
            JavelinSetting restored = new BinaryImporter(file).importData(JavelinSetting.class);
            assertEquals(mode, restored.getAntialiasing());
        }
    }
}
