package cn.donting.javelin.core;

import cn.donting.javelin.core.render.ddgi.DdgiProbeGrid;
import cn.donting.javelin.core.settings.DdgiSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDGI 探针网格 / atlas 排版纯逻辑单测（Engine_DDGI_Design.md §3/§4，P4-05 批 B1）。
 *
 * <p>验四件事：①相机居中原点按 spacing 吸附（相机平移只整格跳，探针不游动）；
 * ②线性索引 ↔ 世界坐标往返一致、越界抛错；③atlas 尺寸与字节账（default 16×4×16 → 1024 探针、
 * 辐照度 320×320、深度 576×576）；④网格内/外判定与归一化坐标。</p>
 *
 * <p>纯逻辑，不建 GPU 资源。</p>
 */
public class DdgiProbeGridTest {

    private static DdgiSettings defaults() {
        return new DdgiSettings();
    }

    /** 缺省 16×4×16 / 间距 2m：跨度 30×6×30，原点按 spacing 吸附到 −16 / −4 / −16 */
    @Test
    public void cameraCenteredSnapsOriginToSpacing() {
        DdgiProbeGrid grid = DdgiProbeGrid.cameraCentered(0f, 0f, 0f, defaults());
        assertEquals(16, grid.countX());
        assertEquals(4, grid.countY());
        assertEquals(16, grid.countZ());
        assertEquals(1024, grid.probeCount());
        assertEquals(-16f, grid.originX(), 1e-6f);
        assertEquals(-4f, grid.originY(), 1e-6f);
        assertEquals(-16f, grid.originZ(), 1e-6f);
        assertEquals(30f, grid.extentX(), 1e-6f);
        assertEquals(6f, grid.extentY(), 1e-6f);
        assertEquals(30f, grid.extentZ(), 1e-6f);
    }

    /** atlas 尺寸：tilesPerRow = ceil(sqrt(1024)) = 32；辐照度 32×10 = 320、深度 32×18 = 576 */
    @Test
    public void atlasSizesMatchTileGrid() {
        DdgiProbeGrid grid = DdgiProbeGrid.cameraCentered(0f, 0f, 0f, defaults());
        assertEquals(32, grid.tilesPerRow());
        assertEquals(32, grid.tileRows());
        assertEquals(320, grid.irradianceAtlasWidth());
        assertEquals(320, grid.irradianceAtlasHeight());
        assertEquals(576, grid.depthAtlasWidth());
        assertEquals(576, grid.depthAtlasHeight());
        assertEquals(320L * 320L * 8L, grid.irradianceAtlasBytes());
        assertEquals(576L * 576L * 4L, grid.depthAtlasBytes());
    }

    /** 线性索引 ↔ 位置：首尾与中间点往返一致 */
    @Test
    public void probeIndexRoundTripsWithPosition() {
        DdgiProbeGrid grid = DdgiProbeGrid.cameraCentered(0f, 0f, 0f, defaults());
        assertEquals(0, grid.probeIndex(0, 0, 0));
        assertEquals(1023, grid.probeIndex(15, 3, 15));

        float[] pos = new float[3];
        grid.probePosition(0, pos);
        assertEquals(-16f, pos[0], 1e-6f);
        assertEquals(-4f, pos[1], 1e-6f);
        assertEquals(-16f, pos[2], 1e-6f);

        int idx = grid.probeIndex(7, 2, 9);
        grid.probePosition(idx, pos);
        assertEquals(-16f + 7 * 2f, pos[0], 1e-6f);
        assertEquals(-4f + 2 * 2f, pos[1], 1e-6f);
        assertEquals(-16f + 9 * 2f, pos[2], 1e-6f);
    }

    /** 越界坐标在索引期就抛，不等着色器算错 */
    @Test
    public void probeIndexRejectsOutOfRange() {
        DdgiProbeGrid grid = DdgiProbeGrid.cameraCentered(0f, 0f, 0f, defaults());
        assertThrows(IllegalArgumentException.class, () -> grid.probeIndex(16, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> grid.probeIndex(0, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> grid.probeIndex(0, 0, -1));
    }

    /** 网格内/外判定 + 归一化坐标（消费面「网格外回落 ambient」依赖它） */
    @Test
    public void containsAndNormalizeFollowExtent() {
        DdgiProbeGrid grid = DdgiProbeGrid.cameraCentered(0f, 0f, 0f, defaults());
        assertTrue(grid.contains(-16f, -4f, -16f));
        assertTrue(grid.contains(14f, 2f, 14f));
        assertFalse(grid.contains(20f, 0f, 0f));
        assertFalse(grid.contains(0f, 0f, -20f));

        float[] rel = new float[3];
        grid.normalized(-16f, -4f, -16f, rel);
        assertEquals(0f, rel[0], 1e-6f);
        assertEquals(0f, rel[1], 1e-6f);
        assertEquals(0f, rel[2], 1e-6f);
        grid.normalized(14f, 2f, 14f, rel);
        assertEquals(15f, rel[0], 1e-6f);
        assertEquals(3f, rel[1], 1e-6f);
        assertEquals(15f, rel[2], 1e-6f);
    }

    /** 参数钳制与派生值：间距/数量越界被夹、一圈帧数按预算算 */
    @Test
    public void settingsClampAndSweepFrames() {
        DdgiSettings s = defaults();
        s.setSpacing(0.1f);                       // 下限 0.5
        assertEquals(0.5f, s.getSpacing(), 1e-6f);
        s.setSpacing(20f);                        // 上限 8
        assertEquals(8f, s.getSpacing(), 1e-6f);
        s.setProbesX(1);                          // 下限 2 → 夹住在 2，不是回到 16
        assertEquals(2, s.getProbesX());
        s.setProbesY(100);                        // 上限 32
        assertEquals(32, s.getProbesY());
        // 积按「夹完之后的实际值」算：2 × 32 × 16（Z 向仍是缺省 16）
        assertEquals(2 * 32 * 16, s.probeCount());
        s.setProbesPerFrame(64);
        assertEquals((2 * 32 * 16 + 63) / 64, s.framesPerSweep());
    }

    /** 最小网格（2×2×2 = 8 探针）：tilesPerRow = ceil(sqrt(8)) = 3，行数 3（末行留 1 空 tile） */
    @Test
    public void minimalGridPadsLastTileRow() {
        DdgiSettings s = defaults();
        s.setProbesX(2);
        s.setProbesY(2);
        s.setProbesZ(2);
        DdgiProbeGrid grid = DdgiProbeGrid.cameraCentered(0f, 0f, 0f, s);
        assertEquals(8, grid.probeCount());
        assertEquals(3, grid.tilesPerRow());
        assertEquals(3, grid.tileRows());
        assertEquals(30, grid.irradianceAtlasWidth());
        assertEquals(30, grid.irradianceAtlasHeight());
        assertEquals(54, grid.depthAtlasWidth());
        assertEquals(54, grid.depthAtlasHeight());
    }

    /**
     * 夹进体素场：网格比场小 → 原点被夹在 [min, max−extent] 内且仍吸附 spacing；
     * 网格比场大（相机拉到 30 m 高空那种）→ 居中于场，两侧溢出。
     */
    @Test
    public void probeGridClampsIntoVoxelBounds() {
        DdgiSettings small = defaults();
        small.setProbesX(2);
        small.setProbesY(2);
        small.setProbesZ(2);          // 跨度 = 1 × 2m = 2m，场 10m 比网格大
        DdgiProbeGrid inside = DdgiProbeGrid.cameraCenteredWithin(
                100f, 100f, 100f, small, 0f, 0f, 0f, 10f, 10f, 10f);
        assertTrue(inside.originX() >= 0f && inside.originX() + inside.extentX() <= 10f);
        assertTrue(inside.originY() >= 0f && inside.originY() + inside.extentY() <= 10f);
        assertTrue(inside.originZ() >= 0f && inside.originZ() + inside.extentZ() <= 10f);
        assertEquals(0f, inside.originX() - Math.floor(inside.originX() / small.getSpacing())
                * small.getSpacing(), 1e-6f);   // 原点仍是 spacing 整数倍

        DdgiSettings big = defaults();   // 16×4×16 × 2m 远大于 10m 场 → 居中于场
        DdgiProbeGrid centered = DdgiProbeGrid.cameraCenteredWithin(
                100f, 100f, 100f, big, 0f, 0f, 0f, 10f, 10f, 10f);
        assertTrue(centered.originX() + centered.extentX() > 10f);   // 场比网格小：两侧溢出
        float gridCenterY = centered.originY() + centered.extentY() * 0.5f;
        assertEquals(5f, gridCenterY, big.getSpacing());             // 中心对齐场中心
    }
}
