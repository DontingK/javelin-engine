package cn.donting.javelin.core.render.voxel;

import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.Fence;
import cn.donting.javelin.rhi.SubmitInfo;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.TextureRegion;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.TextureLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 体素 AO 图一次性读回诊断（Engine_VoxelAO_Design §8 验收辅助，排障件）：
 * blur 派发帧的下一帧（fence 已过、图执行器已把 blur 图转 {@code SHADER_READ_ONLY}）
 * 整图读回，统计 min/max/均值 + 16 档直方图 + 三个正交切面的采样行，日志输出。
 * 一次性：读完即释放所有临时资源，不常驻。
 */
final class VoxelAoDebugReadback {

    private static final Logger log = LoggerFactory.getLogger(VoxelAoDebugReadback.class);

    private VoxelAoDebugReadback() {
    }

    /**
     * 读回整张 3D 图（R8_UNORM，边长 edge）并打日志。调用时机 = blur 派发后的下一帧
     * 帧首（此时上一帧 fence 已过、纹理停在读布局）。
     *
     * @param tag 日志标记（如重建序号）
     */
    static void readAndLog(Device device, Texture tex, int edge, String tag) {
        long bytes = (long) edge * edge * edge;
        if (bytes > Integer.MAX_VALUE) {
            log.warn("[VoxelAoDebug] 图过大跳过读回: edge={} ({}B)", edge, bytes);
            return;
        }
        ByteBuffer data = readback(device, tex, edge);
        if (data == null) {
            return;
        }
        // ── 统计 ──
        int min = 255, max = 0;
        long sum = 0;
        int[] histogram = new int[16];
        for (int i = 0; i < bytes; i++) {
            int v = data.get(i) & 0xFF;
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
            histogram[v >> 4]++;
        }
        float mean = (float) sum / bytes / 255.0f;
        StringBuilder histLine = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            histLine.append(String.format("[%2d-%2d]=%.1f%% ", i * 16, i * 16 + 15,
                    100.0 * histogram[i] / bytes));
        }
        log.info("[VoxelAoDebug] {} blur 图读回 {}³: min={}/255 max={}/255 均值={}",
                tag, edge, min, max, String.format("%.3f", mean));
        log.info("[VoxelAoDebug] 直方图: {}", histLine);

        // ── 采样行：三条正交中轴线（穿场景中心），各 16 点 ──
        int c = edge / 2;
        String xLine = sampleLine(data, edge, "X", (x, y, z) -> idx(edge, x, c, c));
        String yLine = sampleLine(data, edge, "Y", (x, y, z) -> idx(edge, c, y, c));
        String zLine = sampleLine(data, edge, "Z", (x, y, z) -> idx(edge, c, c, z));
        log.info("[VoxelAoDebug] 中轴线采样: X[{}]", xLine);
        log.info("[VoxelAoDebug] 中轴线采样: Y[{}]", yLine);
        log.info("[VoxelAoDebug] 中轴线采样: Z[{}]", zLine);
    }

    /**
     * 表面定向读数（「整体暗」断案用）：读回占用图 + blur AO 图，对每个「贴表面」的
     * 方向采样——占用体素的空邻居方向（6 轴邻近空体素 = 表面朝向的粗略法线），取该
     * 空邻居外推 1 个 AO 体素处的 blur AO 值（≈ 注入器 2 体素偏移的落点）。输出的
     * 分布就是**表面像素真正会乘到的 ao 值**——与视觉直接对账。
     *
     * @param occEdge  占用图边长（R32_UINT）
     * @param aoEdge   AO 图边长（R8_UNORM，blur 产物）
     */
    static void readSurfaceSamples(Device device, Texture occupancy, Texture aoBlur,
                                   int occEdge, int aoEdge, String tag) {
        long occBytes = (long) occEdge * occEdge * occEdge * 4L;   // R32_UINT = 4B/体素
        long aoBytes = (long) aoEdge * aoEdge * aoEdge;
        if (occBytes > Integer.MAX_VALUE || aoBytes > Integer.MAX_VALUE) {
            log.warn("[VoxelAoDebug] 图过大跳过联合读回: {}³/{}³", occEdge, aoEdge);
            return;
        }
        ByteBuffer occ = readback(device, occupancy, occEdge);
        if (occ == null) {
            return;
        }
        // 占用图读回后布局已被还原为读布局（readback 内部 barrier 往返），aoBlur 同理
        ByteBuffer ao = readback(device, aoBlur, aoEdge);
        if (ao == null) {
            return;
        }
        float ratio = (float) occEdge / aoEdge;   // 占用体素 → AO 体素换算
        int[] histogram = new int[16];
        long count = 0;
        long sum = 0;
        int[][] dir = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int occC = occEdge / 2;
        for (int z = 1; z < occEdge - 1; z++) {
            for (int y = 1; y < occEdge - 1; y++) {
                for (int x = 1; x < occEdge - 1; x++) {
                    if (occ.getInt(idx(occEdge, x, y, z) * 4) == 0) {
                        continue;   // 空体素：不是表面
                    }
                    // 6 轴空邻居 = 表面朝向；每个朝向取「空邻居再外推 1 AO 体素」的 AO 值
                    for (int[] d : dir) {
                        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                        if (occ.getInt(idx(occEdge, nx, ny, nz) * 4) != 0) {
                            continue;   // 邻居也是实体：非表面方向
                        }
                        // 空邻居（占用格坐标）→ AO 格坐标（+0.5 取中心、再沿方向外推 1 AO 体素）
                        float ax = (nx + 0.5f) / ratio + d[0] * 1.0f;
                        float ay = (ny + 0.5f) / ratio + d[1] * 1.0f;
                        float az = (nz + 0.5f) / ratio + d[2] * 1.0f;
                        int ix = clampInt((int) Math.floor(ax), 0, aoEdge - 1);
                        int iy = clampInt((int) Math.floor(ay), 0, aoEdge - 1);
                        int iz = clampInt((int) Math.floor(az), 0, aoEdge - 1);
                        int v = ao.get(idx(aoEdge, ix, iy, iz)) & 0xFF;
                        histogram[v >> 4]++;
                        sum += v;
                        count++;
                    }
                }
            }
        }
        if (count == 0) {
            log.warn("[VoxelAoDebug] {} 表面定向读数：无样本（占用图全空？）", tag);
            return;
        }
        float mean = (float) sum / count / 255.0f;
        StringBuilder histLine = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            histLine.append(String.format("[%2d-%2d]=%.1f%% ", i * 16, i * 16 + 15,
                    100.0 * histogram[i] / count));
        }
        log.info("[VoxelAoDebug] {} 表面定向读数（空邻居外推 1 AO 体素处）: 样本={} 均值={} ——这是表面像素实际乘到的 ao",
                tag, count, String.format("%.3f", mean));
        log.info("[VoxelAoDebug] 表面值直方图: {}", histLine);
    }

    /** 一次性整图读回（R8 图按 1B/体素、R32 图按 4B/体素由调用方换算 edge 与缓冲） */
    private static ByteBuffer readback(Device device, Texture tex, int edge) {
        long bytes = (long) edge * edge * edge * (long) tex.desc().format().bytesPerPixel();
        if (bytes > Integer.MAX_VALUE) {
            log.warn("[VoxelAoDebug] 图过大跳过读回: edge={} ({}B)", edge, bytes);
            return null;
        }
        Buffer staging = null;
        CommandBuffer cmd = null;
        Fence fence = null;
        try {
            staging = device.createBuffer(new BufferDesc(bytes,
                    java.util.Set.of(BufferUsage.TRANSFER_DST),
                    MemoryPreference.hostVisibleCoherent()), null);
            cmd = device.allocateCommandBuffer(false);
            fence = device.createFence(false);
            cmd.begin();
            // barrier 的 oldLayout 以纹理自持布局为准（RHI-13），传参只是交换链图像兜底——
            // 这里给读布局 → TRANSFER_SRC → 还原读布局（消费方下一帧还要采样它）
            cmd.pipelineBarrier(tex, TextureLayout.SHADER_READ_ONLY, TextureLayout.TRANSFER_SRC);
            cmd.copyImageToBuffer(tex, staging,
                    new TextureRegion(0, 0, 0, edge, edge, edge, 0, 0));
            cmd.pipelineBarrier(tex, TextureLayout.TRANSFER_SRC, TextureLayout.SHADER_READ_ONLY);
            cmd.end();
            device.graphicsQueue().submit(cmd, SubmitInfo.of(fence));
            fence.await();
            ByteBuffer data = ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
            staging.read(0, (int) bytes, data);
            data.rewind();
            return data;
        } catch (Exception e) {
            log.warn("[VoxelAoDebug] 读回失败: {}", e.getMessage());
            return null;
        } finally {
            if (fence != null) {
                fence.dispose();
            }
            if (cmd != null) {
                cmd.dispose();
            }
            if (staging != null) {
                staging.dispose();
            }
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private interface Indexer {
        int index(int x, int y, int z);
    }

    private static int idx(int edge, int x, int y, int z) {
        return (z * edge + y) * edge + x;
    }

    /** 沿某轴的 16 点采样（值 0~9 缩成一位字符：0=暗 9=亮 a~f=饱和亮） */
    private static String sampleLine(ByteBuffer data, int edge, String axis, Indexer indexer) {
        StringBuilder sb = new StringBuilder();
        int c = edge / 2;
        for (int i = 0; i < 16; i++) {
            int t = (int) ((i + 0.5f) * edge / 16.0f);
            int x = axis.equals("X") ? t : c;
            int y = axis.equals("Y") ? t : c;
            int z = axis.equals("Z") ? t : c;
            int v = (data.get(indexer.index(x, y, z)) & 0xFF) * 10 / 256;
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
