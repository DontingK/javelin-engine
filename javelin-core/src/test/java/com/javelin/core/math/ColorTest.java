package com.javelin.core.math;

import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Color 值类型单测：夹取 / 工厂 / lerp / hex 解析 / 字符串 round-trip / 相等语义。
 */
public class ColorTest {

    @Test
    public void constructionClampsToUnitRange() {
        Color c = new Color(-0.5f, 0.25f, 2.0f);
        assertEquals(0f, c.r);
        assertEquals(0.25f, c.g);
        assertEquals(1f, c.b);
        assertEquals(1f, c.a);   // 三参构造 alpha 默认 1
    }

    @Test
    public void fromBytesScalesTo255() {
        Color c = Color.fromBytes(0, 128, 255, 51);
        assertEquals(0f, c.r);
        assertEquals(128 / 255f, c.g, 1e-6f);
        assertEquals(1f, c.b);
        assertEquals(51 / 255f, c.a, 1e-6f);
    }

    @Test
    public void hexParsingSupportsThreeLengths() {
        assertEquals(new Color(1, 0, 0), Color.fromHex("#F00"));
        assertEquals(Color.fromBytes(0x1A, 0x2B, 0x3C, 255), Color.fromHex("#1A2B3C"));
        assertEquals(Color.fromBytes(0x1A, 0x2B, 0x3C, 0x4D), Color.fromHex("#1A2B3C4D"));
        // # 前缀可省
        assertEquals(Color.fromHex("#FFFFFF"), Color.fromHex("FFFFFF"));
        assertThrows(IllegalArgumentException.class, () -> Color.fromHex("#12345"));
        assertThrows(IllegalArgumentException.class, () -> Color.fromHex("#ZZZZZZ"));
    }

    @Test
    public void hexRoundTrip() {
        assertEquals("#1A2B3C", Color.fromHex("#1A2B3C").toHex());
    }

    @Test
    public void lerpInterpolatesAndClampsT() {
        Color a = new Color(0, 0, 0, 0);
        Color b = new Color(1, 1, 1, 1);
        assertEquals(new Color(0.5f, 0.5f, 0.5f, 0.5f), a.lerp(b, 0.5f));
        // t 越界夹取
        assertEquals(a, a.lerp(b, -1f));
        assertEquals(b, a.lerp(b, 2f));
    }

    @Test
    public void stringRoundTripAndToArray() {
        Color c = new Color(0.25f, 0.5f, 0.75f, 1f);
        assertArrayEquals(new float[]{0.25f, 0.5f, 0.75f, 1f}, c.toArray());
        assertEquals(c, Color.fromString(c.toString()));
        assertEquals(c, Color.fromString(" 0.25 , 0.5 ,0.75, 1 "));
        assertThrows(IllegalArgumentException.class, () -> Color.fromString("0.1,0.2,0.3"));
    }

    @Test
    public void equalityIsComponentWise() {
        assertEquals(new Color(0.2f, 0.4f, 0.6f, 0.8f), new Color(0.2f, 0.4f, 0.6f, 0.8f));
        assertNotEquals(Color.WHITE, Color.TRANSPARENT);
        assertNotEquals(new Color(0.5f, 0f, 0f, 1f), new Color(0.5001f, 0f, 0f, 1f));
        assertEquals(new Color(0.2f, 0.4f, 0.6f, 0.8f).hashCode(),
                new Color(0.2f, 0.4f, 0.6f, 0.8f).hashCode());
    }

    @Test
    public void predefinedConstantsMatchContract() {
        // 占位贴图约定色（RHI 21.4）：white / bump / black
        assertEquals(Color.WHITE, Color.fromBytes(255, 255, 255, 255));
        assertEquals(Color.fromBytes(128, 128, 255, 255), Color.fromHex("#8080FF"));
        assertEquals(Color.BLACK, Color.fromBytes(0, 0, 0, 255));
    }

    @TempDir
    Path tempDir;

    @Test
    public void serializationRoundTrip() throws Exception {
        Color source = new Color(0.25f, 0.5f, 0.75f, 0.125f);
        File file = tempDir.resolve("color.asset").toFile();
        new BinaryExporter(file).export(source);
        Color restored = new BinaryImporter(file).importData(Color.class);
        assertEquals(source, restored);
    }

    @Test
    public void serializedConstantsCompareEqualsNotIdentity() throws Exception {
        File file = tempDir.resolve("white.asset").toFile();
        new BinaryExporter(file).export(Color.WHITE);
        Color restored = new BinaryImporter(file).importData(Color.class);
        // 反序列化产生新实例：equals 相等、非同一引用（常量共享语义不进文件）
        assertEquals(Color.WHITE, restored);
        assertNotEquals(System.identityHashCode(Color.WHITE), System.identityHashCode(restored));
    }
}
