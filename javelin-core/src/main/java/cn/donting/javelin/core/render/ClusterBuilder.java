package cn.donting.javelin.core.render;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * Cluster 构建（Engine_Lighting_Design §5/§6，批次③ CPU 构建拍板 2026-11-01）：
 * lightList（视空间、强度预排序）→ clusterGrid {offset,count}/cell + clusterIndices 变长池。
 *
 * <p>纯逻辑无 GPU 依赖（LightCollector 同族，单测直跑）。切分几何与片元侧定位公式
 * 同源（§5）：tile = 视口像素 16 切分、Z = 透视角 Z 指数切分 / 正交线性切分、
 * cell 线性索引 = tileX + tileY×gridX + zSlice×gridX×gridY——GLSL 侧为
 * ShaderInjector 注入的 {@code javelinLighting} 段，<b>修改公式须两处同改</b>。</p>
 *
 * <p>两遍构建 + 前缀和（§6 修订版：GPU 原子写序无法兑现强度截尾，CPU 列表序写入
 * 精确「去最弱」——2026-11-01 拍板转 CPU；GPU 化归 §10 后置）。池溢出截断尾段
 * 并置 {@link #poolOverflow()}（消费方 WARN 显性化），cell 实际计数同步收缩。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class ClusterBuilder {

    /** X/Y tile 像素尺寸（§5 默认参数，与 fs 注入段 gridDim.w 消费同源） */
    public static final int TILE_SIZE = 16;
    /** Z 切片数（§5 默认参数） */
    public static final int Z_SLICES = 32;
    /** 每 cell 光索引上限（§6 截尾语义：超容丢弃列表尾部更弱者） */
    public static final int MAX_LIGHTS_PER_CELL = 64;
    /** 索引池定容（uint 数；§3.2 1M 起步，溢出截尾 + WARN） */
    public static final int MAX_POOL_UINTS = 1 << 20;

    /** 每视口构建参数（grid 规模随视口像素尺寸；相机/灯变化不失效——每帧全量重建） */
    public static final class Params {

        public final int gridX;
        public final int gridY;
        public final int numZ;
        public final float viewW;
        public final float viewH;
        public final float nearZ;
        public final float farZ;
        public final boolean orthographic;
        /** 投影 m00/m11（<b>带符号</b>——tile 定位非对称，Y 翻转必须保号；与 LightCollector 取绝对值的剔除口径不同源） */
        public final float projM00;
        public final float projM11;

        public Params(int gridX, int gridY, int numZ, float viewW, float viewH,
                      float nearZ, float farZ, boolean orthographic,
                      float projM00, float projM11) {
            if (gridX <= 0 || gridY <= 0 || numZ <= 0) {
                throw new IllegalArgumentException("grid dims must be positive");
            }
            if (viewW <= 0 || viewH <= 0) {
                throw new IllegalArgumentException("viewport size must be positive");
            }
            this.gridX = gridX;
            this.gridY = gridY;
            this.numZ = numZ;
            this.viewW = viewW;
            this.viewH = viewH;
            this.nearZ = nearZ;
            this.farZ = farZ;
            this.orthographic = orthographic;
            this.projM00 = projM00;
            this.projM11 = projM11;
        }

        public int cellCount() {
            return gridX * gridY * numZ;
        }
    }

    /** 单灯覆盖 cell 范围（tx/ty tile 对 + z 切片对，闭区间） */
    private static final class Coverage {
        int tx0, tx1, ty0, ty1, s0, s1;
    }

    // ── 复用暂存（热路径规矩：预分配 + 每帧清，网格扩容时重分配）──
    private int[] counts = new int[0];
    private int[] quotas = new int[0];
    private int[] offsets = new int[0];
    private int[] cursors = new int[0];
    private int[] pool = new int[0];
    private int cells;
    private int poolUsed;
    private boolean poolOverflow;
    private final Coverage cov = new Coverage();

    /**
     * 构建一视口的 cluster（每视口每帧调用，实例跨帧/跨视口复用）。
     *
     * @param items LightItem 列表（LightCollector.pointItems 同排布：[0..2]=视空间位置、
     *              [3]=isSpot、[7]=range；<b>列表序 = 强度序</b>——截尾语义的依据）
     */
    public void build(List<float[]> items, Params params) {
        cells = params.cellCount();
        ensureCells(cells);
        Arrays.fill(counts, 0, cells, 0);
        Arrays.fill(cursors, 0, cells, 0);
        if (pool.length == 0) {
            pool = new int[MAX_POOL_UINTS];
        }
        poolUsed = 0;
        poolOverflow = false;

        // pass 1：每灯覆盖 cell 计数
        for (float[] item : items) {
            if (!coverage(item, params)) {
                continue;
            }
            appendCoverage(params, counts, (a, cell) -> a[cell]++);
        }

        // 前缀和：quota = min(count, 64)，池 offset 紧凑前缀
        int run = 0;
        for (int c = 0; c < cells; c++) {
            int quota = Math.min(counts[c], MAX_LIGHTS_PER_CELL);
            quotas[c] = quota;
            offsets[c] = run;
            run += quota;
        }

        // pass 2：按列表序重走覆盖写入——超 quota 即列表尾部更弱者（截尾）；
        // 池溢出截断段尾（该 cell 计数随 cursors 收缩）
        for (int i = 0; i < items.size(); i++) {
            if (!coverage(items.get(i), params)) {
                continue;
            }
            final int lightIndex = i;
            appendCoverage(params, cursors, (a, cell) -> {
                if (a[cell] >= quotas[cell]) {
                    return;   // 截尾 = 去最弱（强度序列表保证）
                }
                int idx = offsets[cell] + a[cell];
                if (idx >= pool.length) {
                    poolOverflow = true;
                    return;
                }
                pool[idx] = lightIndex;
                a[cell]++;
                if (idx + 1 > poolUsed) {
                    poolUsed = idx + 1;
                }
            });
        }
    }

    /** 覆盖范围累计遍历（cov 有效时走 tile × 切片闭区间） */
    private void appendCoverage(Params params, int[] target, CellOp op) {
        for (int s = cov.s0; s <= cov.s1; s++) {
            int sliceBase = s * params.gridX * params.gridY;
            for (int ty = cov.ty0; ty <= cov.ty1; ty++) {
                int rowBase = sliceBase + ty * params.gridX;
                for (int tx = cov.tx0; tx <= cov.tx1; tx++) {
                    op.apply(target, rowBase + tx);
                }
            }
        }
    }

    @FunctionalInterface
    private interface CellOp {
        void apply(int[] target, int cell);
    }

    /**
     * 计算单灯覆盖（存入 {@link #cov}）：视空间球取 AABB 8 角投影到 NDC 的包围盒
     * （保守正确）。GLSL 侧片段定位用 gl_FragCoord 同映射。球穿近平面 → tile 全范围。
     *
     * @return false = 无覆盖（空转）
     */
    private boolean coverage(float[] item, Params p) {
        float cx = item[0];
        float cy = item[1];
        float cz = item[2];
        float r = Math.abs(item[7]);
        float dC = -cz;   // 视空间深度（-Z 前向）
        if (r <= 0.0f) {
            return false;
        }
        float d0 = Math.max(dC - r, p.nearZ);
        float d1 = Math.min(dC + r, p.farZ);
        if (d0 > d1) {
            return false;
        }
        cov.s0 = sliceOf(d0, p);
        cov.s1 = sliceOf(d1, p);
        if (cov.s1 < cov.s0) {
            cov.s1 = cov.s0;
        }
        if (dC - r < p.nearZ) {
            // 球心包住近平面：投影包围无意义，保守全 tile 覆盖
            cov.tx0 = 0;
            cov.tx1 = p.gridX - 1;
            cov.ty0 = 0;
            cov.ty1 = p.gridY - 1;
            return true;
        }
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    float x = cx + sx * r;
                    float y = cy + sy * r;
                    float w = -(cz + sz * r);
                    float ndcX;
                    float ndcY;
                    if (p.orthographic) {
                        ndcX = p.projM00 * x;
                        ndcY = p.projM11 * y;
                    } else {
                        ndcX = p.projM00 * x / w;
                        ndcY = p.projM11 * y / w;
                    }
                    minX = Math.min(minX, ndcX);
                    maxX = Math.max(maxX, ndcX);
                    minY = Math.min(minY, ndcY);
                    maxY = Math.max(maxY, ndcY);
                }
            }
        }
        cov.tx0 = tileOf(minX, p.viewW, p.gridX);
        cov.tx1 = tileOf(maxX, p.viewW, p.gridX);
        cov.ty0 = tileOf(minY, p.viewH, p.gridY);
        cov.ty1 = tileOf(maxY, p.viewH, p.gridY);
        return true;
    }

    /**
     * 深度 → Z 切片（§5 公式，与 fs 注入段同源）：透视 = numZ·log(z/near)/log(far/near)
     * 指数切分；正交 = 线性切分。结果 clamp 到 [0, numZ-1]。
     */
    private static int sliceOf(float d, Params p) {
        float t;
        if (p.orthographic) {
            t = p.numZ * (d - p.nearZ) / (p.farZ - p.nearZ);
        } else {
            float ratio = (float) (Math.log(d / p.nearZ) / Math.log(p.farZ / p.nearZ));
            t = p.numZ * ratio;
        }
        int slice = (int) Math.floor(t);
        return Math.max(0, Math.min(p.numZ - 1, slice));
    }

    /** NDC → tile 索引：clamp 进 [0, grid-1]（视口外球角投影越界吃边界） */
    private static int tileOf(float ndc, float viewSize, int gridDim) {
        float pixel = (ndc + 1.0f) * 0.5f * viewSize;
        int tile = (int) Math.floor(pixel / TILE_SIZE);
        return Math.max(0, Math.min(gridDim - 1, tile));
    }

    /** grid 序列化：每 cell 2 uint {offset, count}（count = 实际写入数，池截断同步收缩） */
    public void writeGrid(ByteBuffer out) {
        for (int c = 0; c < cells; c++) {
            out.putInt(offsets[c]).putInt(cursors[c]);
        }
    }

    /** 索引池序列化：有效段 poolUsed 个 uint（尾部旧数据不可达，不写不零填） */
    public void writePool(ByteBuffer out) {
        for (int i = 0; i < poolUsed; i++) {
            out.putInt(pool[i]);
        }
    }

    /** 本帧 grid int 数（cells × 2；buffer.update 长度与容量判据用） */
    public int gridInts() {
        return cells * 2;
    }

    /** 本帧索引池有效 uint 数 */
    public int poolUsed() {
        return poolUsed;
    }

    /** 池溢出（截断保护发生）：消费方 WARN 显性化（§6） */
    public boolean poolOverflow() {
        return poolOverflow;
    }

    private void ensureCells(int cellCount) {
        if (counts.length >= cellCount) {
            return;
        }
        counts = new int[cellCount];
        quotas = new int[cellCount];
        offsets = new int[cellCount];
        cursors = new int[cellCount];
    }
}
