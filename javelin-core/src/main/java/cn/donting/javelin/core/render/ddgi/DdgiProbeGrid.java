package cn.donting.javelin.core.render.ddgi;

import cn.donting.javelin.core.settings.DdgiSettings;

/**
 * DDGI 探针网格与 atlas 排版的**纯数学**（Engine_DDGI_Design.md §3/§4，P4-05 批 B1）。
 *
 * <p>网格形态 = 轴对齐、相机居中、按 {@code spacing} 吸附（避免相机平移时探针「游动」导致
 * 辐照度在 tile 之间来回搬运）。三向探针数的**上限**取自 {@link DdgiSettings}，跨度 =
 * {@code (count−1) × spacing}；探针点落在网格节点上。</p>
 *
 * <p><b>缺省铺法</b>：{@link #fittedToField} —— 三向数量按体素场 AABB 收缩
 * （{@code 1 + floor(场边长/spacing)}，夹到 {@code [2, 配置值]}），保证探针装得进场；
 * {@link #cameraCentered} / {@link #cameraCenteredWithin} 是「不收缩、只夹原点」的旧口径，
 * 留作对照与单测（体素场远大于配置跨度时才等价）。</p>
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
     *
     * <p>注：本方法「不收缩网格」已不是缺省口径——宿主改用 {@link #fittedToField}，这里仅作为
     * 不收缩的对照实现保留。</p>
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

    /**
     * 相机居中、且**三向探针数按体素场 AABB 收缩**的网格（缺省口径）。
     *
     * <p><b>为什么收缩</b>：设置里的 16×4×16 / 2m 是「上限」，跨度 30×6×30 m；而体素场是规划
     * 盒（实测场景约 10.8×5.8×7.9 m）。不收缩时只有约 60/1024 个探针落在场内，其余全是
     * 「未命中回落环境色」的兜底值——B3 上屏后会变成外围恒定环境辐照度（漏光式错误间接光），
     * 且真正带信息的探针太少。</p>
     *
     * <p><b>取整口径用 floor</b>（{@code count = 1 + floor(extent/spacing)}，再夹到
     * {@code [2, 配置值]}）：保证 {@code (count−1)×spacing ≤ 场边长}，即网格严格装得进场。
     * 若用 ceil 会得到 12 m 跨度去套 10.8 m 的场，边界探针仍在场外。</p>
     *
     * <p><b>原点不按世界零点吸附</b>，而是锚在体素场最小角：世界零点格点可能与场边界不整除，
     * 硬吸附会把整块网格顶出场外（实测场 x 向 10.538 m、间距 2 m → 世界格点 −6 起，最左探针
     * 落在 −6 &lt; −5.269）。锚在场 `min` 上仍有「只整格跳、不游动」的性质，且天然装得进场。</p>
     *
     * <p>滑移余量 {@code slack = 场边长 − 网格跨度}：{@code slack < spacing} 时没有一格可滑的余量
     * → 直接居中于场（此时网格永远不动，也就不需要吸附）；{@code slack ≥ spacing} 时按整格
     * 跟随相机（步数夹在 {@code [0, floor(slack/spacing)]}）。</p>
     */
    /**
     * 锚定在体素场內的网格（<b>缺省口径，2026-11-26 改</b>）：三向数量同 {@link #fittedToField}
     * 按场 AABB 收缩，但原点**只居中于场的滑移余量、绝不跟相机**。
     *
     * <p><b>为什么不再跟相机</b>：探针 atlas 是「世界位置 → tile」的定值缓存，原点一滑动，
     * atlas 里每个 tile 的读数就整体错位一格；旧实现每跨半格把 {@code sliceCursor} 归零，
     * 于是每帧只重算开头 {@code probesPerFrame} 个探针、尾部探针永不刷新，错位旧值与部分新值
     * 混在三线性插值里——实测现象就是「旋转/平移相机时盒内暗面一档一档地闪」。GGI 本质是
     * 场景光照缓存，覆盖率本来就按场收缩，在余量里挪动没有收益，只有抖动。</p>
     *
     * <p>体素场 AABB 变化（拖墙/加删物体）仍会让网格点数/原点变，那是场景真变了，重建 atlas
     * 属预期。</p>
     */
    public static DdgiProbeGrid anchoredToField(DdgiSettings settings,
                                               float minX, float minY, float minZ,
                                               float maxX, float maxY, float maxZ) {
        if (settings == null) {
            throw new IllegalArgumentException("DdgiSettings must not be null");
        }
        float spacing = settings.getSpacing();
        int countX = fitCount(maxX - minX, spacing, settings.getProbesX());
        int countY = fitCount(maxY - minY, spacing, settings.getProbesY());
        int countZ = fitCount(maxZ - minZ, spacing, settings.getProbesZ());
        float extentX = (countX - 1) * spacing;
        float extentY = (countY - 1) * spacing;
        float extentZ = (countZ - 1) * spacing;
        return new DdgiProbeGrid(
                minX + ((maxX - minX) - extentX) * 0.5f,
                minY + ((maxY - minY) - extentY) * 0.5f,
                minZ + ((maxZ - minZ) - extentZ) * 0.5f,
                spacing, countX, countY, countZ);
    }

    public static DdgiProbeGrid fittedToField(float camX, float camY, float camZ,
                                             DdgiSettings settings,
                                             float minX, float minY, float minZ,
                                             float maxX, float maxY, float maxZ) {
        if (settings == null) {
            throw new IllegalArgumentException("DdgiSettings must not be null");
        }
        float spacing = settings.getSpacing();
        int countX = fitCount(maxX - minX, spacing, settings.getProbesX());
        int countY = fitCount(maxY - minY, spacing, settings.getProbesY());
        int countZ = fitCount(maxZ - minZ, spacing, settings.getProbesZ());
        float extentX = (countX - 1) * spacing;
        float extentY = (countY - 1) * spacing;
        float extentZ = (countZ - 1) * spacing;
        return new DdgiProbeGrid(
                fitOrigin(camX, minX, maxX, extentX, spacing),
                fitOrigin(camY, minY, maxY, extentY, spacing),
                fitOrigin(camZ, minZ, maxZ, extentZ, spacing),
                spacing, countX, countY, countZ);
    }

    /** 单轴探针数 = min(配置上限, 1 + floor(场边长/spacing))，且不少于 2 */
    private static int fitCount(float fieldExtent, float spacing, int configuredMax) {
        int fit = 1 + (int) Math.floor(fieldExtent / spacing);
        return Math.max(2, Math.min(configuredMax, fit));
    }

    /** 单轴原点：锚在 {@code min} 的 spacing 格点上整格跟随相机；余量不足一格就居中于场 */
    private static float fitOrigin(float cam, float min, float max, float extent, float spacing) {
        float slack = (max - min) - extent;
        if (slack < spacing) {
            // 没有滑移余量（含 slack ≤ 0 的「场比网格还薄」）：居中于场，两侧余量均分
            return min + slack * 0.5f;
        }
        float desired = cam - extent * 0.5f - min;
        long steps = Math.round(desired / spacing);
        long maxSteps = (long) Math.floor(slack / spacing);
        steps = Math.min(Math.max(steps, 0L), maxSteps);
        return min + (float) steps * spacing;
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
