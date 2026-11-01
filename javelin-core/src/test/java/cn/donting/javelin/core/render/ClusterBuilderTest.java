package cn.donting.javelin.core.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cluster 构建单测（三期批次③，Engine_Lighting_Design §5/§6 修订版——CPU 构建
 * 2026-11-01 拍板）：cell 覆盖投影（含 Y 翻转保号）/ 强度序 64 截尾 / 池前缀紧凑链 /
 * 透视指数与正交线性切分 / 平近平面全范围。
 *
 * <p>手算参数：视口 320×320（tile 16 → 20×20）、numZ=4、near=1、far=16——透视
 * Z 边界 = near·(far/near)^(s/4) = 1/2/4/8（log 切分可手算）。透视 m11=-1
 * （引擎 Y 翻转投影）——tile 定位必须保号，取绝对值会镜像出错（专项用例）。</p>
 */
public class ClusterBuilderTest {

    private final ClusterBuilder builder = new ClusterBuilder();

    /** 20×20 tile × 4 切片，透视（m11 翻转保号口径） */
    private static ClusterBuilder.Params persp() {
        return new ClusterBuilder.Params(20, 20, 4, 320, 320,
                1.0f, 16.0f, false, 1.0f, -1.0f);
    }

    private ClusterBuilder.Params ortho() {
        return new ClusterBuilder.Params(20, 20, 4, 320, 320,
                1.0f, 16.0f, true, 1.0f, -1.0f);
    }

    private static float[] item(float x, float y, float z, float range) {
        float[] out = new float[LightCollector.LIGHT_ITEM_FLOATS];
        out[0] = x;
        out[1] = y;
        out[2] = z;
        out[7] = range;
        return out;
    }

    private IntBuffer buildGrid(List<float[]> items, ClusterBuilder.Params p) {
        builder.build(items, p);
        ByteBuffer bb = ByteBuffer.allocateDirect(builder.gridInts() * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        builder.writeGrid(bb);
        bb.flip();
        return bb.asIntBuffer();
    }

    private IntBuffer buildPool(List<float[]> items, ClusterBuilder.Params p) {
        ByteBuffer bb = ByteBuffer.allocateDirect(Math.max(builder.poolUsed(), 1) * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        builder.writePool(bb);
        bb.flip();
        return bb.asIntBuffer();
    }

    private static int countAt(IntBuffer grid, ClusterBuilder.Params p, int tx, int ty, int s) {
        return grid.get((tx + ty * p.gridX + s * p.gridX * p.gridY) * 2 + 1);
    }

    private static int offsetAt(IntBuffer grid, ClusterBuilder.Params p, int tx, int ty, int s) {
        return grid.get((tx + ty * p.gridX + s * p.gridX * p.gridY) * 2);
    }

    @Test
    void perspectiveCoverageMatchesHandMath() {
        // 灯 (0,0,-10) r=2：d∈[8,12] → 全落切片 3（边界 8=第 3 段底）；
        // NDC x∈[-0.25,0.25] → 像素 120..200 → tile 7..12（两轴对称）
        List<float[]> items = new ArrayList<>(List.of(item(0, 0, -10, 2)));
        ClusterBuilder.Params p = persp();
        IntBuffer grid = buildGrid(items, p);

        assertEquals(1, countAt(grid, p, 7, 7, 3));
        assertEquals(1, countAt(grid, p, 12, 12, 3));
        assertEquals(0, countAt(grid, p, 6, 7, 3));
        assertEquals(0, countAt(grid, p, 13, 12, 3));
        // 切片 2（z∈[4,8)）不触——灯 z 深 [8,12]
        assertEquals(0, countAt(grid, p, 10, 10, 2));
        // 覆盖 cell 数 = 6×6×1，每 cell 单灯 → 池用量 36
        assertEquals(36, builder.poolUsed());
        assertFalse(builder.poolOverflow());
        IntBuffer pool = buildPool(items, p);
        assertEquals(36, pool.remaining());
        // 唯一灯索引 0 写满池
        for (int i = 0; i < 36; i++) {
            assertEquals(0, pool.get(i));
        }
    }

    @Test
    void yFlipSignPreservedInTileMapping() {
        // 灯 (0, 2, -10) r=0.5：m11=-1 → ndcY∈[-0.263,-0.143] → tile 7..8；
        // 若错取 |m11|（镜像）→ ndcY∈[0.143,0.263] → tile 11..12 区段——专项防错
        List<float[]> items = new ArrayList<>(List.of(item(0, 2, -10, 0.5f)));
        ClusterBuilder.Params p = persp();
        IntBuffer grid = buildGrid(items, p);

        assertEquals(1, countAt(grid, p, 9, 8, 3));
        assertEquals(0, countAt(grid, p, 9, 12, 3));
    }

    @Test
    void truncationKeepsStrongestByListOrder() {
        // 同位 70 盏覆盖同一批 cell → 每 cell 计数封 64；池段 = 列表前 64 个索引
        List<float[]> items = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            items.add(item(0, 0, -10, 2));
        }
        ClusterBuilder.Params p = persp();
        IntBuffer grid = buildGrid(items, p);
        int segmentOffset = offsetAt(grid, p, 10, 10, 3);

        assertEquals(64, countAt(grid, p, 10, 10, 3));
        IntBuffer pool = buildPool(items, p);
        for (int k = 0; k < 64; k++) {
            assertEquals(k, pool.get(segmentOffset + k), "列表序=强度序，前 64 强者保留");
        }
        // 36 个覆盖 cell × 64
        assertEquals(36 * 64, builder.poolUsed());
    }

    @Test
    void poolOffsetsFormCompactPrefix() {
        // 两盏错位灯：所有非空 cell 段按 offset 排序后首尾相接、总量 = poolUsed
        List<float[]> items = new ArrayList<>(List.of(
                item(0, 0, -10, 2),        // tile 7..12 切片 3
                item(-100, 0, -10, 1)));    // 出视口左缘，clamp 吃边界列
        ClusterBuilder.Params p = persp();
        IntBuffer grid = buildGrid(items, p);

        List<int[]> used = new ArrayList<>();   // {offset, count}
        for (int i = 0; i < grid.remaining() / 2; i++) {
            int offset = grid.get(i * 2);
            int count = grid.get(i * 2 + 1);
            if (count > 0) {
                used.add(new int[]{offset, count});
            }
        }
        used.sort((a, b) -> Integer.compare(a[0], b[0]));
        int run = 0;
        for (int[] seg : used) {
            assertEquals(run, seg[0], "段 offset 必为前缀和（紧凑无空洞）");
            run += seg[1];
        }
        assertEquals(run, builder.poolUsed());
    }

    @Test
    void orthographicLinearSlicing() {
        // 正交：ndc = m00·x（w=1）→ 灯 (0,0,-5) r=1 tile 全列 clamp；切片线性
        // d∈[4,6] → floor(4·(d-1)/15) = 0..1 两切片
        List<float[]> items = new ArrayList<>(List.of(item(0, 0, -5, 1)));
        ClusterBuilder.Params p = ortho();
        IntBuffer grid = buildGrid(items, p);

        assertEquals(1, countAt(grid, p, 0, 0, 0));
        assertEquals(1, countAt(grid, p, 19, 19, 1));
        assertEquals(0, countAt(grid, p, 10, 10, 2));
        // 20×20 tile × 2 切片，每 cell 单灯
        assertEquals(20 * 20 * 2, builder.poolUsed());
    }

    @Test
    void nearPlaneCrossingCoversAllTiles() {
        // 球心近平面前 (0,0,-0.5) r=2：穿近平面 → tile 全范围保守覆盖；
        // 可见深度段 d∈[1,2.5] → 切片 0..1
        List<float[]> items = new ArrayList<>(List.of(item(0, 0, -0.5f, 2)));
        ClusterBuilder.Params p = persp();
        IntBuffer grid = buildGrid(items, p);

        assertEquals(1, countAt(grid, p, 0, 0, 0));
        assertEquals(1, countAt(grid, p, 19, 19, 0));
        assertEquals(1, countAt(grid, p, 19, 19, 1));
        assertEquals(0, countAt(grid, p, 10, 10, 2));
    }

    @Test
    void zeroRangeLightCoversNothing() {
        List<float[]> items = new ArrayList<>(List.of(item(0, 0, -10, 0)));
        ClusterBuilder.Params p = persp();
        IntBuffer grid = buildGrid(items, p);

        assertEquals(0, builder.poolUsed());
        assertFalse(builder.poolOverflow());
        for (int i = 0; i < grid.remaining() / 2; i++) {
            assertEquals(0, grid.get(i * 2 + 1), "零 range 灯不应覆盖任何 cell");
        }
    }

    @Test
    void rebuildReusesAcrossViews() {
        // 跨视口/跨帧复用：先小网格后大网格（扩容路径）+ 内容互不残留
        List<float[]> items = new ArrayList<>(List.of(item(0, 0, -10, 2)));
        buildGrid(items, persp());
        ClusterBuilder.Params big = new ClusterBuilder.Params(40, 40, 32, 640, 640,
                1.0f, 16.0f, false, 1.0f, -1.0f);
        IntBuffer grid = buildGrid(items, big);

        boolean anyCount = false;
        for (int i = 0; i < grid.remaining() / 2; i++) {
            if (grid.get(i * 2 + 1) > 1) {
                anyCount = true;
            }
        }
        assertFalse(anyCount, "复用后单灯 cell 计数不应残留超 1");
        assertTrue(builder.poolUsed() > 0);
    }
}
