package com.javelin.core.render.mesh;

import com.javelin.core.physics.collision.Aabb;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Mesh.bounds 单测（片③，Engine_Collision_Design.md §5）。
 *
 * <p>覆盖三条计算路径：setPositions 即算（程序化构建）；导入产物 read 后
 * 首次 getBounds 惰性兜底（read 不可派生——红线 10 嵌套字段可未 populate，
 * roundtrip 验证）；无 POSITION 返回 null。</p>
 *
 * @author javelin
 * @since 1.0
 */
public class MeshBoundsTest {

    @TempDir
    Path tempDir;

    @Test
    void setPositionsComputesBoundsImmediately() {
        Mesh mesh = new Mesh();
        mesh.setPositions(new float[]{
                -1, -2, -3,
                4, 5, 6,
                0, 0, 0});

        Aabb bounds = mesh.getBounds();
        assertNotNull(bounds);
        assertEquals(-1, bounds.getMin().x, 1e-6f);
        assertEquals(-2, bounds.getMin().y, 1e-6f);
        assertEquals(-3, bounds.getMin().z, 1e-6f);
        assertEquals(4, bounds.getMax().x, 1e-6f);
        assertEquals(5, bounds.getMax().y, 1e-6f);
        assertEquals(6, bounds.getMax().z, 1e-6f);
    }

    @Test
    void boundsLazilyComputedAfterRoundtrip() throws Exception {
        // read 路径不派生（红线 10）——导入产物 bounds 为 null，首次 getBounds 惰性算
        Mesh source = new Mesh();
        source.setPositions(new float[]{
                -1, -2, -3,
                4, 5, 6,
                0, 0, 0});
        source.setIndices(new int[]{0, 1, 2});

        File file = tempDir.resolve("bounds.smesh").toFile();
        new BinaryExporter(file).export(source);
        Mesh restored = new BinaryImporter(file).importData(Mesh.class);

        Aabb bounds = restored.getBounds();
        assertNotNull(bounds);
        assertEquals(-1, bounds.getMin().x, 1e-6f);
        assertEquals(-2, bounds.getMin().y, 1e-6f);
        assertEquals(-3, bounds.getMin().z, 1e-6f);
        assertEquals(4, bounds.getMax().x, 1e-6f);
        assertEquals(5, bounds.getMax().y, 1e-6f);
        assertEquals(6, bounds.getMax().z, 1e-6f);
    }

    @Test
    void meshWithoutPositionsHasNullBounds() {
        Mesh mesh = new Mesh();
        mesh.setUVs(new float[]{0, 1, 1, 1, 0.5f, 0});

        assertNull(mesh.getBounds());
    }

    @Test
    void setPositionsClearsStaleBoundsOnDegenerateData() {
        Mesh mesh = new Mesh();
        mesh.setPositions(new float[]{-1, -2, -3, 4, 5, 6});
        assertNotNull(mesh.getBounds());

        // 换短数据（长度 < 3）：旧盒不得残留（set 即失效清除）
        mesh.setPositions(new float[]{1, 2});
        assertNull(mesh.getBounds());

        // 换回有效数据：重算新盒
        mesh.setPositions(new float[]{0, 0, 0, 1, 1, 1});
        Aabb bounds = mesh.getBounds();
        assertNotNull(bounds);
        assertEquals(0, bounds.getMin().x, 1e-6f);
        assertEquals(1, bounds.getMax().x, 1e-6f);
        assertEquals(1, bounds.getMax().z, 1e-6f);
    }
}
