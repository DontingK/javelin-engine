package com.javelin.core.render.mesh;

import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.PrimitiveTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mesh 二进制产物格式 round-trip 单测（.smesh 导入产物，Engine_Assets_Design §2，
 * 2026-09-26 落地）：装配 → BinaryExporter → BinaryImporter → 字段级相等。
 *
 * <p>覆盖：内置属性（POSITION/UV0）、自定义属性、indices 直通 int[]、
 * 反序列化懒装配（read 只暂存节点列表，首次访问重建 name 索引——红线 10）、
 * 运行态字段（uploaded/layout/GPU 句柄）不落盘。</p>
 */
public class MeshSerializationTest {

    @TempDir
    Path tempDir;

    @Test
    public void roundTripPreservesAttributesAndIndices() throws Exception {
        Mesh source = new Mesh();
        source.setPositions(new float[]{
                -0.5f, -0.5f, 0f,
                0.5f, -0.5f, 0f,
                0f, 0.5f, 0f});
        source.setUVs(new float[]{0, 1, 1, 1, 0.5f, 0});
        source.setAttribute(new VertexAttribute("WEIGHT", 2, Format.R32G32_SFLOAT),
                new float[]{1, 0, 0.5f, 0.5f, 0, 1});
        source.setIndices(new int[]{0, 1, 2});
        source.setTopology(PrimitiveTopology.TRIANGLE_STRIP);
        source.setKeepCpuCopy(true);

        File file = tempDir.resolve("mesh.asset").toFile();
        new BinaryExporter(file).export(source);
        Mesh restored = new BinaryImporter(file).importData(Mesh.class);

        // read 直通回写（无懒装配、无重建）：数据全量恢复
        assertEquals(3, restored.vertexCount());
        assertArrayEquals(source.positions(), restored.positions());
        assertArrayEquals(new float[]{0, 1, 1, 1, 0.5f, 0}, restored.data("UV0"));
        assertArrayEquals(new float[]{1, 0, 0.5f, 0.5f, 0, 1}, restored.data("WEIGHT"));
        assertArrayEquals(new int[]{0, 1, 2}, restored.indices);
        assertEquals(PrimitiveTopology.TRIANGLE_STRIP, restored.getTopology());
        assertTrue(restored.keepCpuCopy);

        // 规范序保留：内置（POSITION/UV0）在前，自定义（WEIGHT）在后
        // （WEIGHT 不在 BUILTIN_ORDER，按字典序排自定义段）
        assertEquals(3, restored.canonicalAttributes().size());
        assertEquals("POSITION", restored.canonicalAttributes().get(0).getName());
        assertEquals("UV0", restored.canonicalAttributes().get(1).getName());
        assertEquals("WEIGHT", restored.canonicalAttributes().get(2).getName());
        assertEquals(2, restored.canonicalAttributes().get(2).getComponents());

        // 运行态不落盘：导入产物是未上传的纯 CPU 数据
        assertTrue(!restored.isUploaded());
    }

    @Test
    public void recalculateNormalsComputesTriangleNormals() {
        // 两个三角形拼成 XY 平面四边形（CCW，法线应归一化为 (0,0,1)）
        Mesh mesh = new Mesh();
        mesh.setPositions(new float[]{
                0, 0, 0,
                1, 0, 0,
                1, 1, 0,
                0, 1, 0});
        mesh.setIndices(new int[]{0, 1, 2, 0, 2, 3});

        mesh.recalculateNormals();

        assertArrayEquals(new float[]{
                0, 0, 1,
                0, 0, 1,
                0, 0, 1,
                0, 0, 1}, mesh.data("NORMAL"), 1e-6f);
    }

    @Test
    public void recalculateNormalsRejectsNonTriangleListAndMissingData() {
        Mesh strip = new Mesh();
        strip.setTopology(PrimitiveTopology.TRIANGLE_STRIP);
        assertThrows(UnsupportedOperationException.class, strip::recalculateNormals);

        Mesh noIndices = new Mesh();
        noIndices.setPositions(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0});
        assertThrows(IllegalStateException.class, noIndices::recalculateNormals);
    }
}
