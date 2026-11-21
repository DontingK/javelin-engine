package cn.donting.javelin.core.render.ddgi;

import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.FormatSupport;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.UploadData;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.SamplerDesc;
import cn.donting.javelin.rhi.desc.TextureDesc;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.TextureUsage;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * DDGI 体素场资源族（Engine_DDGI_Design.md §4.1，P4-05 批 B1 残留②）：体素图 + 参数块 + 采样器。
 *
 * <p><b>体素图 = 单张 N³ {@code RGBA8}</b>（{@code STORAGE} 写 / {@code SAMPLED} 读 /
 * {@code TRANSFER_DST} 首帧清零）：{@code rgb} = 命中面基色（GI 反弹颜色的来源，退役的 AO
 * 版没有这一通道）、{@code a} = 占用位。选 {@code RGBA8} 而非「占用 {@code R32_UINT} +
 * 基色另一张图」的理由 = 一张图一次采样取全（DDA 每步只需一次 texture/image 读），显存
 * 4 字节/体素；代价是基色量化到 8 bit 且不能用 {@code imageAtomicOr}（并发写为「后写者胜」，
 * GI 无所谓）。</p>
 *
 * <p><b>参数块 64B</b>（与 {@code voxelize.compute} 的 {@code VoxelParams} 同布局，
 * 追踪段还要读）：{@code originResolution} / {@code voxelSize} / {@code gridBounds} /
 * {@code reserved}。</p>
 *
 * <p><b>清零</b>：创建时上传全零（布局转换会丢内容，未清零的残留会被当成实体体素）。
 * 分辨率越高这张 host 侧零图越大（128³ ≈ 8MB），所以分辨率上限在设置侧钳到 256。</p>
 */
public final class DdgiVoxelField {

    /** 体素图格式（rgb = 基色，a = 占用位） */
    public static final Format FORMAT = Format.R8G8B8A8_UNORM;
    /** 参数块字节数（4 × vec4） */
    public static final int PARAM_BYTES = 64;
    /** 体素图用途位（compute 写 / 追踪读 / 首帧清零） */
    public static final Set<TextureUsage> USAGE = Collections.unmodifiableSet(
            EnumSet.of(TextureUsage.STORAGE, TextureUsage.SAMPLED, TextureUsage.TRANSFER_DST));
    /** 占用位阈值（8 bit UNORM ⇒ 0.5 对应 128；写入恒为 1.0） */
    public static final float OCCUPANCY_THRESHOLD = 0.5f;

    private final Texture voxels;
    private final Buffer params;
    private final Sampler samplerLinear;
    private final Sampler samplerNearest;
    private final int resolution;
    private final Vector3f origin = new Vector3f();
    private final Vector3f voxelSize = new Vector3f();

    private DdgiVoxelField(Device device, int resolution) {
        this.resolution = resolution;
        this.voxels = device.createTexture(TextureDesc.texture3D(FORMAT,
                resolution, resolution, resolution, USAGE), UploadData.of(zeros(resolution)));
        this.params = device.createBuffer(new BufferDesc(PARAM_BYTES,
                Set.of(BufferUsage.UNIFORM), MemoryPreference.hostVisibleCoherent()), null);
        // 追踪段按线性插值穿行体素（DDA 命中判定仍用精确 texel 中心，另一路 NEAREST）
        this.samplerLinear = device.createSampler(SamplerDesc.linear());
        this.samplerNearest = device.createSampler(SamplerDesc.nearest());
    }

    /** 全零体素图（RGBA8 ⇒ 4 字节/体素） */
    private static ByteBuffer zeros(int edge) {
        long bytes = (long) edge * edge * edge * 4L;
        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("体素图过大（" + edge + "³ = " + bytes
                    + " 字节），分辨率上限 256");
        }
        return ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * 按世界包围盒建网格（各轴独立体素边长，网格恰好覆盖包围盒，外扩由调用方给）。
     *
     * @param resolution 单边体素数
     * @param min        世界包围盒最小角
     * @param max        世界包围盒最大角
     */
    public static DdgiVoxelField create(Device device, int resolution, Vector3f min, Vector3f max) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        if (resolution <= 0) {
            throw new IllegalArgumentException("resolution must be positive, got: " + resolution);
        }
        if (min == null || max == null) {
            throw new IllegalArgumentException("min / max must not be null");
        }
        requireStorageSampled(device);
        DdgiVoxelField field = new DdgiVoxelField(device, resolution);
        field.origin.set(min);
        field.voxelSize.set((max.x - min.x) / resolution,
                (max.y - min.y) / resolution,
                (max.z - min.z) / resolution);
        return field;
    }

    /** 格式能力前置：RGBA8 必须有 STORAGE（体素化写）与 SAMPLED（追踪读） */
    private static void requireStorageSampled(Device device) {
        FormatSupport support = device.formatSupport(FORMAT);
        if (support == null) {
            return;
        }
        if (!support.storageImage()) {
            throw new IllegalStateException("DDGI 体素场创建失败：格式 " + FORMAT
                    + " 在设备[" + device.gpuInfo().deviceName() + "] 上不支持 STORAGE"
                    + "（voxelize.compute 无法写入）；能力快照 = " + support);
        }
        if (!support.sampledImage()) {
            throw new IllegalStateException("DDGI 体素场创建失败：格式 " + FORMAT
                    + " 在设备[" + device.gpuInfo().deviceName() + "] 上不支持 SAMPLED"
                    + "（投射线无法采样）；能力快照 = " + support);
        }
    }

    public Texture voxels() {
        return voxels;
    }

    /** 参数 UBO（体素化 / 追踪共读） */
    public Buffer params() {
        return params;
    }

    /** 线性采样器（追踪段穿行用） */
    public Sampler samplerLinear() {
        return samplerLinear;
    }

    /** 最近邻采样器（逐 texel 精确读） */
    public Sampler samplerNearest() {
        return samplerNearest;
    }

    public int resolution() {
        return resolution;
    }

    /** 世界原点（体素 [0,0,0] 的最小角） */
    public Vector3f origin() {
        return origin;
    }

    /** 单体素世界尺寸（非立方时各轴独立） */
    public Vector3f voxelSize() {
        return voxelSize;
    }

    /** 体素图字节数（显存账 + 日志） */
    public long bytes() {
        return (long) resolution * resolution * resolution * 4L;
    }

    /** 重写参数块（原点/分辨率/体素边长/上界；追踪段还会按需追加） */
    public void writeParams() {
        ByteBuffer bb = ByteBuffer.allocateDirect(PARAM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bb.putFloat(origin.x).putFloat(origin.y).putFloat(origin.z).putFloat(resolution);
        bb.putFloat(voxelSize.x).putFloat(voxelSize.y).putFloat(voxelSize.z).putFloat(0f);
        bb.putFloat(origin.x + voxelSize.x * resolution)
                .putFloat(origin.y + voxelSize.y * resolution)
                .putFloat(origin.z + voxelSize.z * resolution)
                .putFloat(0f);
        bb.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
        bb.flip();
        params.update(0, bb);
    }

    /** 世界点 → 体素整数坐标（各轴 floor；调用方自行判越界，不夹紧） */
    public void voxelIndex(float x, float y, float z, int[] out) {
        out[0] = (int) Math.floor((x - origin.x) / voxelSize.x);
        out[1] = (int) Math.floor((y - origin.y) / voxelSize.y);
        out[2] = (int) Math.floor((z - origin.z) / voxelSize.z);
    }

    public void dispose() {
        voxels.dispose();
        params.dispose();
        samplerLinear.dispose();
        samplerNearest.dispose();
    }

    @Override
    public String toString() {
        return "DdgiVoxelField{" + resolution + "³ " + FORMAT
                + ", origin=(" + origin.x + ", " + origin.y + ", " + origin.z + ")"
                + ", voxelSize=(" + voxelSize.x + ", " + voxelSize.y + ", " + voxelSize.z + ")"
                + ", bytes=" + bytes() + "}";
    }
}
