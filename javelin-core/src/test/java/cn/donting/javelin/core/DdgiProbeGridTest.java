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
     * 收缩铺法（缺省口径）：16×4×16 / 2m 的上限去套 10.8×5.8×7.9 的体素场 → 6×3×4 = 72 探针，
     * 全部探针落在场 AABB 内，且原点仍是 spacing 整数倍（不游动）。
     */
    @Test
    public void fittedGridShrinksIntoVoxelField() {
        DdgiSettings s = defaults();
        float minX = -5.269f, minY = -0.436f, minZ = -2.769f;
        float maxX = 5.269f, maxY = 5.319f, maxZ = 5.129f;
        DdgiProbeGrid grid = DdgiProbeGrid.fittedToField(0f, 0f, 0f, s, minX, minY, minZ, maxX, maxY, maxZ);

        // 1 + floor(10.538/2) = 6、1 + floor(5.755/2) = 3、1 + floor(7.898/2) = 4
        assertEquals(6, grid.countX());
        assertEquals(3, grid.countY());
        assertEquals(4, grid.countZ());
        assertEquals(72, grid.probeCount());
        assertEquals(10f, grid.extentX(), 1e-6f);
        assertEquals(4f, grid.extentY(), 1e-6f);
        assertEquals(6f, grid.extentZ(), 1e-6f);

        // 每个探针都在场 AABB 内（含边界）
        float[] pos = new float[3];
        for (int i = 0; i < grid.probeCount(); i++) {
            grid.probePosition(i, pos);
            assertTrue(pos[0] >= minX - 1e-4f && pos[0] <= maxX + 1e-4f, "X 越界 @ " + i);
            assertTrue(pos[1] >= minY - 1e-4f && pos[1] <= maxY + 1e-4f, "Y 越界 @ " + i);
            assertTrue(pos[2] >= minZ - 1e-4f && pos[2] <= maxZ + 1e-4f, "Z 越界 @ " + i);
        }

        // 原点装在场内（锚在场 min、余量不足一格时居中；不按世界零点吸附——那样会顶出场外）
        assertTrue(grid.originX() >= minX - 1e-4f && grid.originX() + grid.extentX() <= maxX + 1e-4f);
        assertTrue(grid.originY() >= minY - 1e-4f && grid.originY() + grid.extentY() <= maxY + 1e-4f);
        assertTrue(grid.originZ() >= minZ - 1e-4f && grid.originZ() + grid.extentZ() <= maxZ + 1e-4f);
        // 相机拉高到 26 m（实测踩过的场景）：原点仍被夹在场内，探针不会整块飘到场外
        DdgiProbeGrid high = DdgiProbeGrid.fittedToField(0f, 26f, 0f, s, minX, minY, minZ, maxX, maxY, maxZ);
        assertTrue(high.originY() >= minY - 1e-4f && high.originY() + high.extentY() <= maxY + 1e-4f);
        assertEquals(72, high.probeCount());
    }

    /** 场比配置跨度大时保留配置上限（16×4×16 不回退成更小），且原点按整格跟随相机 */
    @Test
    public void fittedGridKeepsConfiguredMaxForLargeField() {
        DdgiSettings s = defaults();
        DdgiProbeGrid grid = DdgiProbeGrid.fittedToField(0f, 0f, 0f, s, -100f, -100f, -100f, 100f, 100f, 100f);
        assertEquals(16, grid.countX());
        assertEquals(4, grid.countY());
        assertEquals(16, grid.countZ());
        assertEquals(1024, grid.probeCount());
        assertTrue(grid.originX() >= -100f && grid.originX() + grid.extentX() <= 100f);

        // 相机沿 X 平移 10 m（5 格）→ 原点跟着走 10 m，仍是「整格跳」而不是每帧滑一点
        DdgiProbeGrid moved = DdgiProbeGrid.fittedToField(10f, 0f, 0f, s, -100f, -100f, -100f, 100f, 100f, 100f);
        assertEquals(10f, moved.originX() - grid.originX(), 1e-4f);
        assertTrue(moved.originX() + moved.extentX() <= 100f);
    }

    /** 退化：某轴场边长小于 spacing → 该轴只剩 2 个探针，居中于场（边界探针可能溢出一半） */
    @Test
    public void fittedGridDegeneratesToTwoWhenFieldThinnerThanSpacing() {
        DdgiSettings s = defaults();   // spacing 2m
        DdgiProbeGrid grid = DdgiProbeGrid.fittedToField(
                0f, 0f, 0f, s, -50f, -0.5f, -50f, 50f, 0.5f, 50f);
        assertEquals(2, grid.countY());
        assertEquals(2f, grid.extentY(), 1e-6f);
        // 居中于场，但原点仍吸附 spacing（最多差半格）
        assertTrue(Math.abs(grid.originY() + grid.extentY() * 0.5f) <= s.getSpacing() * 0.5f + 1e-5f);
    }

    /**
     * 夹进体素场（旧口径对照）：网格比场小 → 原点被夹在 [min, max−extent] 内且仍吸附 spacing；
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

    /**
     * 锚定铺法（2026-11-26 起的宿主口径）：原点 = 场 min + 余量的一半，**与相机完全无关**。
     * 这是「旋转相机时盒内暗面闪」的治本断言——网格一旦跟相机，atlas 里每个 tile 的读数就整体
     * 错位一格，旧值与新值混进三线性插值就会闪。
     */
    @Test
    public void anchoredGridStaysInsideFieldAndIgnoresCamera() {
        DdgiSettings s = defaults();
        float minX = -5.269f, minY = -0.436f, minZ = -2.769f;
        float maxX = 5.269f, maxY = 5.319f, maxZ = 5.129f;
        DdgiProbeGrid g = DdgiProbeGrid.anchoredToField(s, minX, minY, minZ, maxX, maxY, maxZ);

        assertEquals(6, g.countX());
        assertEquals(3, g.countY());
        assertEquals(4, g.countZ());
        assertEquals(72, g.probeCount());
        // 余量 0.538 / 1.755 / 1.898 → 原点 = min + 一半
        assertEquals(minX + 0.269f, g.originX(), 1e-3f);
        assertEquals(minY + 0.8775f, g.originY(), 1e-3f);
        assertEquals(minZ + 0.949f, g.originZ(), 1e-3f);
        assertTrue(g.originX() >= minX - 1e-4f && g.originX() + g.extentX() <= maxX + 1e-4f);
        assertTrue(g.originY() >= minY - 1e-4f && g.originY() + g.extentY() <= maxY + 1e-4f);
        assertTrue(g.originZ() >= minZ - 1e-4f && g.originZ() + g.extentZ() <= maxZ + 1e-4f);

        float[] pos = new float[3];
        for (int i = 0; i < g.probeCount(); i++) {
            g.probePosition(i, pos);
            assertTrue(pos[0] >= minX - 1e-4f && pos[0] <= maxX + 1e-4f, "X 越界 @ " + i);
            assertTrue(pos[1] >= minY - 1e-4f && pos[1] <= maxY + 1e-4f, "Y 越界 @ " + i);
            assertTrue(pos[2] >= minZ - 1e-4f && pos[2] <= maxZ + 1e-4f, "Z 越界 @ " + i);
        }

        // 确定性：同一场反复调用逐位相同（不含任何相机输入，结构上就不可能游动）
        DdgiProbeGrid again = DdgiProbeGrid.anchoredToField(s, minX, minY, minZ, maxX, maxY, maxZ);
        assertEquals(g.originX(), again.originX());
        assertEquals(g.originY(), again.originY());
        assertEquals(g.originZ(), again.originZ());
        assertTrue(g.toString().equals(again.toString()));
    }
}
