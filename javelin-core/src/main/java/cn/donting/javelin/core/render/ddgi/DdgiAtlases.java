package cn.donting.javelin.core.render.ddgi;

import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.FormatSupport;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.TextureDesc;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.TextureType;
import cn.donting.javelin.rhi.types.TextureUsage;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * DDGI 两张 atlas 的 GPU 资源（Engine_DDGI_Design.md v0.2 §3，P4-05 批 B1 残留①）。
 *
 * <p><b>形态</b>：单张 2D 纹理按 tile 拼（版面与尺寸全靠 {@link DdgiProbeGrid} 算，
 * 本类只负责「按那个尺寸把图开出来」），格式辐照度 {@code RGBA16F}（八面体辐照度，
 * 含 border 的 4 通道）、深度 {@code RG16F}（{@code E(d)} / {@code E(d²)} 两矩）。</p>
 *
 * <p><b>用途位</b> = {@code SAMPLED | STORAGE | TRANSFER_SRC | TRANSFER_DST}：STORAGE 供
 * {@code ddgi_update.comp} 写、SAMPLED 供消费面与 debug 视图读、TRANSFER_DST 供
 * 首帧清零上传（不清零会读到未定义内存，与体素占用图同套路）、TRANSFER_SRC 供
 * debug/验收期读回。<b>不用 COLOR_ATTACHMENT</b>——两张图全程只经 compute 通道。</p>
 *
 * <p><b>格式能力前置</b>：创建前先查 {@code RGBA16F} / {@code RG16F} 的 STORAGE
 * 与 SAMPLED 位（RHI-15 快照），缺位抛带设备名与能力快照的 {@link IllegalStateException}
 * ——换机验收时这行比驱动层的通用报错好定位。逐用途的最终校验仍在
 * {@code VulkanTexture} 创建期做（这里只是提前给出 DDGI 口径的说明）。</p>
 *
 * <p><b>生命周期</b>：per-view、惰性创建、按 grid 换代（探针数 / 间距变了图就得换；
 * 与交换链尺寸无关，resize 不动）。销毁走调用方的两帧退役队列，不由本类直接 dispose。</p>
 */
public final class DdgiAtlases {

    /** 辐照度 atlas 格式（八面体辐照度 RGBA，A 位留给未来的置信度/权重） */
    public static final Format IRRADIANCE_FORMAT = Format.R16G16B16A16_SFLOAT;
    /** 深度（可见性矩）atlas 格式（RG = E(d) / E(d²)） */
    public static final Format DEPTH_FORMAT = Format.R16G16_SFLOAT;

    /**
     * 两张 atlas 共用用途位：STORAGE（`ddgi_update.comp` 写）、SAMPLED（消费面 / debug 读图）、
     * TRANSFER_DST（首帧清零上传，不清会读到未定义内存）、TRANSFER_SRC（B4 debug 视图与
     * 验收期读回）。
     */
    public static final Set<TextureUsage> USAGE = Collections.unmodifiableSet(
            EnumSet.of(TextureUsage.SAMPLED, TextureUsage.STORAGE,
                    TextureUsage.TRANSFER_SRC, TextureUsage.TRANSFER_DST));

    private final Texture irradiance;
    private final Texture depth;
    private final int probeCount;
    private final int irradianceWidth;
    private final int irradianceHeight;
    private final int depthWidth;
    private final int depthHeight;

    private DdgiAtlases(Texture irradiance, Texture depth, DdgiProbeGrid grid) {
        this.irradiance = irradiance;
        this.depth = depth;
        this.probeCount = grid.probeCount();
        this.irradianceWidth = grid.irradianceAtlasWidth();
        this.irradianceHeight = grid.irradianceAtlasHeight();
        this.depthWidth = grid.depthAtlasWidth();
        this.depthHeight = grid.depthAtlasHeight();
    }

    /**
     * 按网格尺寸创建两张 atlas（尺寸 / tile 版面全部取自 {@link DdgiProbeGrid}）。
     * 格式能力不满足时先抛 DDGI 口径的错，不落到驱动的通用报错。
     */
    public static DdgiAtlases create(Device device, DdgiProbeGrid grid) {
        if (device == null || grid == null) {
            throw new IllegalArgumentException("device / grid must not be null");
        }
        requireStorageSampled(device, IRRADIANCE_FORMAT, "辐照度");
        requireStorageSampled(device, DEPTH_FORMAT, "深度");
        Texture irradiance = createAtlas(device, IRRADIANCE_FORMAT,
                grid.irradianceAtlasWidth(), grid.irradianceAtlasHeight());
        try {
            Texture depth = createAtlas(device, DEPTH_FORMAT,
                    grid.depthAtlasWidth(), grid.depthAtlasHeight());
            return new DdgiAtlases(irradiance, depth, grid);
        } catch (RuntimeException e) {
            // 深度图建失败时别把已建好的辐照度图漏在显存里
            irradiance.dispose();
            throw e;
        }
    }

    private static Texture createAtlas(Device device, Format format, int width, int height) {
        return device.createTexture(new TextureDesc(TextureType.TYPE_2D, format,
                width, height, 1, 1, 1, SampleCount.COUNT_1, USAGE,
                MemoryPreference.deviceLocal()), null);
    }

    /** 格式能力前置（STORAGE 写 + SAMPLED 读两必需位；缺位立即抛） */
    private static void requireStorageSampled(Device device, Format format, String role) {
        FormatSupport support = device.formatSupport(format);
        if (support == null) {
            return;
        }
        if (!support.storageImage()) {
            throw new IllegalStateException("DDGI " + role + " atlas 创建失败：格式 " + format
                    + " 在设备[" + device.gpuInfo().deviceName() + "] 上不支持 STORAGE（"
                    + "ddgi_update.comp 无法写入）；能力快照 = " + support);
        }
        if (!support.sampledImage()) {
            throw new IllegalStateException("DDGI " + role + " atlas 创建失败：格式 " + format
                    + " 在设备[" + device.gpuInfo().deviceName() + "] 上不支持 SAMPLED（"
                    + "消费面无法采样）；能力快照 = " + support);
        }
    }

    public Texture irradiance() { return irradiance; }
    public Texture depth() { return depth; }
    public int probeCount() { return probeCount; }
    public int irradianceWidth() { return irradianceWidth; }
    public int irradianceHeight() { return irradianceHeight; }
    public int depthWidth() { return depthWidth; }
    public int depthHeight() { return depthHeight; }

    /**
     * 是否与给定网格同版面（探针数 + 两张图的宽高全等）：不等即需换代。
     *
     * <p>比尺寸而不比 {@code DdgiProbeGrid} 实例／间距——间距变了但探针数与 atlas
     * 尺寸恰好相同的组合仍可复用，只是内容要重刷（由 B5 的历史重置负责）。</p>
     */
    public boolean matches(DdgiProbeGrid grid) {
        return grid != null
                && grid.probeCount() == probeCount
                && grid.irradianceAtlasWidth() == irradianceWidth
                && grid.irradianceAtlasHeight() == irradianceHeight
                && grid.depthAtlasWidth() == depthWidth
                && grid.depthAtlasHeight() == depthHeight;
    }

    @Override
    public String toString() {
        return "DdgiAtlases{probes=" + probeCount
                + ", irradiance=" + irradianceWidth + "x" + irradianceHeight + "x" + IRRADIANCE_FORMAT
                + ", depth=" + depthWidth + "x" + depthHeight + "x" + DEPTH_FORMAT + "}";
    }
}
