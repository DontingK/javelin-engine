package com.javelin.core.render.material;

import com.javelin.core.assets.Asset;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Material 资产序列化单元测试：Shader GUID 引用与参数覆盖值往返。
 */
class MaterialSerializeTest {

    @TempDir
    Path tempDir;

    /** 假资产管理器：按 GUID 返回预置 Shader 实例 */
    static class FakeAssetManager implements AssetManager {
        final Map<String, Asset> assets = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Asset> T loadGuid(String guid, Class<T> assetClass) {
            return (T) assets.get(guid);
        }

        @Override
        public <T extends Asset> T loadGuidInstance(String guid, Class<T> assetClass) {
            return loadGuid(guid, assetClass);
        }

        @Override
        public <T extends Asset> T loadAsset(String path, Class<T> assetClass) {
            return null;
        }

        @Override
        public <T extends Asset> T loadAssetInstance(String path, Class<T> assetClass) {
            return null;
        }
    }

    private File tempFile(String name) {
        return tempDir.resolve(name).toFile();
    }

    /** 预置一个带 GUID 的 Shader 供材质引用 */
    private static Shader newShader(String guid) {
        Shader shader = new Shader();
        shader.setGuid(guid);
        ShaderPass pass = new ShaderPass();
        pass.setName("Forward");
        shader.addPass(pass);
        return shader;
    }

    @Test
    void shaderReferenceWrittenAsGuid() throws Exception {
        Shader shader = newShader("guid-shader-default");
        Material material = new Material(shader);
        material.setGuid("guid-material-1");

        File file = tempFile("material.dat");
        new BinaryExporter(file).export(material);

        // 导入侧必须提供 AssetManager 才能解析 GUID 引用
        FakeAssetManager manager = new FakeAssetManager();
        manager.assets.put("guid-shader-default", shader);
        Material r = new BinaryImporter(file, manager).importData(Material.class);

        assertEquals("guid-material-1", r.getGuid());
        // 解析出的 Shader 来自 AssetManager 同一实例（ASSET 机制）
        assertSame(shader, r.getShader());
        assertEquals("Forward", r.getShader().getPasses().get(0).getName());
    }

    @Test
    void propertyOverridesAllValueKindsRoundTrip() throws Exception {
        Shader shader = newShader("guid-shader-default");
        Material material = new Material(shader);
        material.setProperty("_FloatVal", 1.5f);
        material.setProperty("_IntVal", 3);
        material.setProperty("_BoolVal", Boolean.TRUE);
        material.setProperty("_Color", new float[]{1f, 0f, 0f, 1f});
        // TEXTURE 值：纹理资产类型本期未建立，以 GUID 字符串占位
        material.setProperty("_MainTex", "guid-texture-white");

        File file = tempFile("overrides.dat");
        new BinaryExporter(file).export(material);

        FakeAssetManager manager = new FakeAssetManager();
        manager.assets.put("guid-shader-default", shader);
        Material r = new BinaryImporter(file, manager).importData(Material.class);

        Map<String, Object> overrides = r.getPropertyOverrides();
        assertEquals(5, overrides.size());
        assertEquals(1.5f, (Float) overrides.get("_FloatVal"));
        assertEquals(3, (Integer) overrides.get("_IntVal"));
        assertEquals(Boolean.TRUE, overrides.get("_BoolVal"));
        assertArrayEquals(new float[]{1f, 0f, 0f, 1f}, (float[]) overrides.get("_Color"));
        assertEquals("guid-texture-white", overrides.get("_MainTex"));
    }

    @Test
    void emptyMaterialRoundTrip() throws Exception {
        Material material = new Material();

        File file = tempFile("empty.dat");
        new BinaryExporter(file).export(material);

        // shader 为 null，导入侧也无需 AssetManager
        Material r = new BinaryImporter(file).importData(Material.class);

        assertNull(r.getShader());
        assertTrue(r.getPropertyOverrides().isEmpty());
    }

    @Test
    void getPropertyAndOverridesView() {
        Shader shader = newShader("guid-shader-default");
        Material material = new Material(shader);
        assertNull(material.getProperty("_Color"));

        material.setProperty("_Color", new float[]{0f, 1f, 0f, 1f});
        assertArrayEquals(new float[]{0f, 1f, 0f, 1f}, (float[]) material.getProperty("_Color"));

        // 只读视图
        assertThrows(UnsupportedOperationException.class,
            () -> material.getPropertyOverrides().put("_X", 1.0f));
    }
}
