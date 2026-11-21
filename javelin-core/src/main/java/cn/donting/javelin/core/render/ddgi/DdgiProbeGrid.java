package cn.donting.javelin.core.render.ddgi;

import cn.donting.javelin.core.settings.DdgiSettings;

/**
 * DDGI 探针网格与 atlas 排版的**纯数学**（Engine_DDGI_Design.md §3/§4，P4-05 批 B1）。
 *
 * <p>网格形态 = 轴对齐、相机居中、按 {@code spacing} 吸附（避免相机平移时探针「游动」导致
 * 辐照度在 tile 之间来回搬运）。三向探针数取自 {@link DdgiSettings}，跨度 =
 * {@code (count−1) × spacing}；探针点落在网格节点上。</p>
 *
 * <p>atlas 排版 = 单张 2D 图按 tile 拼接（不选 2D 数组，避开分层屏障，RHI-14 不阻塞）。
 * 辐照度 tile {@code 8×8 + 1px 边框 = 10×10}（边框用于八面体邻接 wrap）、深度 tile
 * {@code 16×16 + 1px 边框 = 18×18}；tile 按接近正方形排（{@code cols = ceil(sqrt(N))}）。</p>
 *
 * <p>本类不建 GPU 资源、不碰 LWJGL，可直接单测（{@code DdgiProbeGridTest}）。</p>
 */
public final class DdgiProbeGrid {

    /** 辐照度 tile 内区边长（八面体方向图 texel 数） */
    public static final int IRRADIANCE_INNER = 8;
    /** 辐照度 tile 外框边长（内区 + 两侧各 1px 边框） */
    public static final int IRRADIANCE_TILE = IRRADIANCE_INNER + 2;
    /** 深度（可见性矩）tile 内区边长 */
    public static final int DEPTH_INNER = 16;
    /** 深度 tile 外框边长 */
    public static final int DEPTH_TILE = DEPTH_INNER + 2;
    /** 辐照度 texel 字节数（RGBA16F） */
    public static final int IRRADIANCE_TEXEL_BYTES = 8;
    /** 深度 texel 字节数（RG16F） */
    public static final int DEPTH_TEXEL_BYTES = 4;

    private final float originX;
    private final float originY;
    private final float originZ;
    private final float spacing;
    private final int countX;
    private final int countY;
    private final int countZ;
    private final int tilesPerRow;
    private final int tileRows;

    private DdgiProbeGrid(float originX, float originY, float originZ, float spacing,
                          int countX, int countY, int countZ) {
        if (spacing <= 0f) {
            throw new IllegalArgumentException("探针间距必须为正，got: " + spacing);
        }
        if (countX < 2 || countY < 2 || countZ < 2) {
            throw new IllegalArgumentException("三向探针数都必须 ≥ 2，got: "
                    + countX + "x" + countY + "x" + countZ);
        }
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.spacing = spacing;
        this.countX = countX;
        this.countY = countY;
        this.countZ = countZ;
        int tiles = probeCount();
        this.tilesPerRow = (int) Math.ceil(Math.sqrt(tiles));
        this.tileRows = (tiles + tilesPerRow - 1) / tilesPerRow;
    }

    /**
     * 相机居中网格：跨度 = (count−1)×spacing，原点 = 相机 − 跨度/2 再按 spacing 吸附
     * （吸附保证相机连续平移时网格只整格跳，探针不「游动」）。
     */
    public static DdgiProbeGrid cameraCentered(float camX, float camY, float camZ,
                                              DdgiSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("DdgiSettings must not be null");
        }
        float spacing = settings.getSpacing();
        float extentX = (settings.getProbesX() - 1) * spacing;
        float extentY = (settings.getProbesY() - 1) * spacing;
        float extentZ = (settings.getProbesZ() - 1) * spacing;
        float originX = snap(camX - extentX * 0.5f, spacing);
        float originY = snap(camY - extentY * 0.5f, spacing);
        float originZ = snap(camZ - extentZ * 0.5f, spacing);
        return new DdgiProbeGrid(originX, originY, originZ, spacing,
                settings.getProbesX(), settings.getProbesY(), settings.getProbesZ());
    }

    /** 吸附到 spacing 的整数倍（floor） */
    private static float snap(float value, float spacing) {
        return (float) Math.floor(value / spacing) * spacing;
    }

    /**
     * 相机居中、但整块网格夹在给定包围盒内（体素场 AABB）。
     *
     * <p><b>为什么必须夹</b>：体素场是「场景静态集」的世界 AABB，而探针体积是相机居中的大盒子
     * （缺省 16×4×16 × 2m ≈ 30×6×30 m）。相机拉到高空俯视时，探针中心离体素场几十米，
     * 追踪线第一格就出界、全部走「未命中回落环境色」——结果就是满屏 π 的均匀白（实测日志
     * 探针辐照度 ≈ (π, π, π)、命中距离 = maxDistance）。夹进 AABB 后至少场内的探针能真求交。</p>
     *
     * <p>体素场比网格还小时退化为「居中于场」（此时边界探针仍在场外，属预期）。夹后原点仍吸附
     * 在 spacing 整数倍上，保持「不游动」。</p>
     */
    public static DdgiProbeGrid cameraCenteredWithin(float camX, float camY, float camZ,
                                                     DdgiSettings settings,
                                                     float minX, float minY, float minZ,
                                                     float maxX, float maxY, float maxZ) {
        DdgiProbeGrid desired = cameraCentered(camX, camY, camZ, settings);
        float spacing = desired.spacing;
        float ox = clampOrigin(desired.originX, minX, maxX, desired.extentX(), spacing);
        float oy = clampOrigin(desired.originY, minY, maxY, desired.extentY(), spacing);
        float oz = clampOrigin(desired.originZ, minZ, maxZ, desired.extentZ(), spacing);
        return new DdgiProbeGrid(ox, oy, oz, spacing, desired.countX, desired.countY, desired.countZ);
    }

    private static float clampOrigin(float origin, float min, float max, float extent, float spacing) {
        if (extent >= max - min) {
            // 网格比场还大：居中于场（两侧溢出，边缘探针注定打空）
            return snap(min + (max - min - extent) * 0.5f, spacing);
        }
        float lo = (float) Math.ceil(min / spacing) * spacing;
        float hi = (float) Math.floor((max - extent) / spacing) * spacing;
        if (hi < lo) {
            return snap(min + (max - min - extent) * 0.5f, spacing);
        }
        return Math.min(Math.max(origin, lo), hi);
    }

    public float originX() { return originX; }
    public float originY() { return originY; }
    public float originZ() { return originZ; }
    public float spacing() { return spacing; }
    public int countX() { return countX; }
    public int countY() { return countY; }
    public int countZ() { return countZ; }

    /** 探针总数 */
    public int probeCount() {
        return countX * countY * countZ;
    }

    /** 网格 X 向跨度（世界单位） */
    public float extentX() { return (countX - 1) * spacing; }
    public float extentY() { return (countY - 1) * spacing; }
    public float extentZ() { return (countZ - 1) * spacing; }

    /** 线性探针索引 = x + countX×(y + countY×z)（追踪 Pass 的 dispatch 与 atlas tile 序同源） */
    public int probeIndex(int x, int y, int z) {
        if (x < 0 || x >= countX || y < 0 || y >= countY || z < 0 || z >= countZ) {
            throw new IllegalArgumentException("探针坐标越界: (" + x + ", " + y + ", " + z + ")");
        }
        return x + countX * (y + countY * z);
    }

    /** 按线性索引取世界坐标（写入 [out] 三元组，避免每次分配） */
    public void probePosition(int index, float[] out) {
        int xy = countX * countY;
        int z = index / xy;
        int rem = index - z * xy;
        int y = rem / countX;
        int x = rem - y * countX;
        out[0] = originX + x * spacing;
        out[1] = originY + y * spacing;
        out[2] = originZ + z * spacing;
    }

    /** 世界坐标是否落在网格盒内（含边界）——消费面「网格外回落 ambient」的判定口径 */
    public boolean contains(float x, float y, float z) {
        return x >= originX && x <= originX + extentX()
                && y >= originY && y <= originY + extentY()
                && z >= originZ && z <= originZ + extentZ();
    }

    /**
     * 网格归一化坐标（每轴 [0, count−1]，供三线性权重）：调用方应先 {@link #contains} 判定。
     * 越界值不夹紧——夹紧会把网格外的像素也算进边界探针，权重会失真。
     */
    public void normalized(float x, float y, float z, float[] out) {
        out[0] = (x - originX) / spacing;
        out[1] = (y - originY) / spacing;
        out[2] = (z - originZ) / spacing;
    }

    /** atlas 每行 tile 数 */
    public int tilesPerRow() { return tilesPerRow; }
    /** atlas 行数 */
    public int tileRows() { return tileRows; }
    /** tile 内线性序号 → 列 */
    public int tileColumn(int probe) { return probe % tilesPerRow; }
    /** tile 内线性序号 → 行 */
    public int tileRow(int probe) { return probe / tilesPerRow; }

    /** 辐照度 atlas 宽度（px） */
    public int irradianceAtlasWidth() { return tilesPerRow * IRRADIANCE_TILE; }
    /** 辐照度 atlas 高度（px） */
    public int irradianceAtlasHeight() { return tileRows * IRRADIANCE_TILE; }
    /** 深度 atlas 宽度（px） */
    public int depthAtlasWidth() { return tilesPerRow * DEPTH_TILE; }
    /** 深度 atlas 高度（px） */
    public int depthAtlasHeight() { return tileRows * DEPTH_TILE; }

    /** 辐照度 atlas 字节数（B1 验收读得到的显存账） */
    public long irradianceAtlasBytes() {
        return (long) irradianceAtlasWidth() * irradianceAtlasHeight() * IRRADIANCE_TEXEL_BYTES;
    }

    /** 深度 atlas 字节数 */
    public long depthAtlasBytes() {
        return (long) depthAtlasWidth() * depthAtlasHeight() * DEPTH_TEXEL_BYTES;
    }

    @Override
    public String toString() {
        return "DdgiProbeGrid{" + countX + "x" + countY + "x" + countZ
                + " probes=" + probeCount()
                + ", spacing=" + spacing
                + ", origin=(" + originX + ", " + originY + ", " + originZ + ")"
                + ", irradianceAtlas=" + irradianceAtlasWidth() + "x" + irradianceAtlasHeight()
                + ", depthAtlas=" + depthAtlasWidth() + "x" + depthAtlasHeight() + "}";
    }
}
