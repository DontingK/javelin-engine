package cn.donting.javelin.core.render.voxel;

import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.Texture;
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
import java.util.Set;

/**
 * 体素网格资源族（Engine_VoxelAO_Design §3.1）：占用图 + AO 图（原图 / 模糊图）+ 参数 UBO + 采样器。
 *
 * <p>占用图 = N³ {@code R32_UINT}（STORAGE 写 / SAMPLED 读），体素化 compute 用
 * {@code imageAtomicOr} 打标记；AO 图 = M³ {@code R8_UNORM}（1.0 = 开阔），
 * 三张图创建时全零上传（布局转换会丢内容，未清零会读到假数据）。参数块 64B，
 * 布局与三个 compute 的 {@code VoxelParams} 块一致。</p>
 */
public final class VoxelGrid {

    /** 参数块字节数（4 × vec4） */
    public static final int PARAM_BYTES = 64;

    private final Texture occupancy;
    private final Texture ao;
    private final Texture aoBlur;
    private final Buffer params;
    private final Sampler samplerNearest;
    private final Sampler samplerLinear;
    private final int resolution;
    private final int aoResolution;
    private final Vector3f origin = new Vector3f();
    private final Vector3f voxelSize = new Vector3f();

    private VoxelGrid(Device device, int resolution, int aoResolution) {
        this.resolution = resolution;
        this.aoResolution = aoResolution;
        // 全零上传：布局转换 UNDEFINED → GENERAL 会丢弃内容，未清零的旧数据会留下假占用；
        // TRANSFER_DST 供上传路径使用（createTexture 内部先转 TRANSFER_DST 再拷）；
        // AO 图另带 TRANSFER_SRC 供排障读回（VoxelAoDebugReadback）
        this.occupancy = device.createTexture(TextureDesc.texture3D(
                Format.R32_UINT, resolution, resolution, resolution,
                Set.of(TextureUsage.STORAGE, TextureUsage.SAMPLED, TextureUsage.TRANSFER_DST)),
                cn.donting.javelin.rhi.UploadData.of(zeros(resolution, Integer.BYTES)));
        this.ao = device.createTexture(TextureDesc.texture3D(
                Format.R8_UNORM, aoResolution, aoResolution, aoResolution,
                Set.of(TextureUsage.STORAGE, TextureUsage.SAMPLED, TextureUsage.TRANSFER_DST,
                        TextureUsage.TRANSFER_SRC)),
                cn.donting.javelin.rhi.UploadData.of(ones(aoResolution, Byte.BYTES)));
        this.aoBlur = device.createTexture(TextureDesc.texture3D(
                Format.R8_UNORM, aoResolution, aoResolution, aoResolution,
                Set.of(TextureUsage.STORAGE, TextureUsage.SAMPLED, TextureUsage.TRANSFER_DST,
                        TextureUsage.TRANSFER_SRC)),
                cn.donting.javelin.rhi.UploadData.of(ones(aoResolution, Byte.BYTES)));
        this.params = device.createBuffer(new BufferDesc(PARAM_BYTES,
                Set.of(BufferUsage.UNIFORM), MemoryPreference.hostVisibleCoherent()), null);
        // 占用图是 r32ui，整数格式采样必须 NEAREST（最简单最稳）；AO 图运行期要三线性
        this.samplerNearest = device.createSampler(SamplerDesc.nearest());
        this.samplerLinear = device.createSampler(SamplerDesc.linear());
    }

    private static ByteBuffer zeros(int edge, int bytesPerVoxel) {
        int bytes = edge * edge * edge * bytesPerVoxel;
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** 全 1 填充（{@code R8_UNORM} ⇒ 0xFF = 1.0 = 完全开阔）：AO 图未求值前不得呈现「全遮蔽」 */
    private static ByteBuffer ones(int edge, int bytesPerVoxel) {
        int bytes = edge * edge * edge * bytesPerVoxel;
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bytes; i++) {
            buffer.put(i, (byte) 0xFF);
        }
        return buffer;
    }

    /**
     * 按世界包围盒创建网格（各轴独立体素尺寸，网格恰好覆盖包围盒）。
     *
     * @param resolution   占用网格单边体素数
     * @param aoResolution AO 网格单边体素数
     * @param min          世界包围盒最小角
     * @param max          世界包围盒最大角
     */
    public static VoxelGrid create(Device device, int resolution, int aoResolution, Vector3f min, Vector3f max) {
        if (resolution <= 0 || aoResolution <= 0) {
            throw new IllegalArgumentException("resolution / aoResolution must be positive, got: "
                    + resolution + " / " + aoResolution);
        }
        VoxelGrid grid = new VoxelGrid(device, resolution, aoResolution);
        grid.origin.set(min);
        grid.voxelSize.set((max.x - min.x) / resolution,
                (max.y - min.y) / resolution,
                (max.z - min.z) / resolution);
        return grid;
    }

    /** 占用图（体素化写 / AO 读） */
    public Texture occupancy() {
        return occupancy;
    }

    /** AO 原图（voxelAO 写 / 模糊读） */
    public Texture ao() {
        return ao;
    }

    /** AO 模糊图（模糊写 / 光照采样读，即渲染侧最终消费面） */
    public Texture aoBlur() {
        return aoBlur;
    }

    /** 参数 UBO（三个 compute 的 set0 binding = 参数块） */
    public Buffer params() {
        return params;
    }

    /** 整数格式采样器（占用图读取） */
    public Sampler samplerNearest() {
        return samplerNearest;
    }

    /** 线性采样器（AO 图三线性采样，光照侧用） */
    public Sampler samplerLinear() {
        return samplerLinear;
    }

    public int resolution() {
        return resolution;
    }

    public int aoResolution() {
        return aoResolution;
    }

    /** 世界原点（网格 [0,0,0] 体素角） */
    public Vector3f origin() {
        return origin;
    }

    /** 单体素世界尺寸（非立方时各轴独立） */
    public Vector3f voxelSize() {
        return voxelSize;
    }

    /** AO 单体素世界尺寸（占用体素按分辨率比例放大） */
    public Vector3f aoVoxelSize() {
        float ratio = (float) resolution / (float) aoResolution;
        // 标量放大走三参重载（Vector3f 无 mul(float)）
        return new Vector3f(voxelSize).mul(ratio, ratio, ratio);
    }

    /** 重写参数块（占用几何 + AO 求值参数；AO 计算派发前调用即覆盖旧值） */
    public void writeParams(int rayCount, float maxDistance, float intensity, boolean enabled) {
        ByteBuffer bb = ByteBuffer.allocateDirect(PARAM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        bb.putFloat(origin.x).putFloat(origin.y).putFloat(origin.z).putFloat(resolution);
        bb.putFloat(voxelSize.x).putFloat(voxelSize.y).putFloat(voxelSize.z).putFloat(aoResolution);
        bb.putFloat(rayCount).putFloat(maxDistance).putFloat(intensity).putFloat(enabled ? 1.0f : 0.0f);
        bb.putFloat(origin.x + voxelSize.x * resolution)
                .putFloat(origin.y + voxelSize.y * resolution)
                .putFloat(origin.z + voxelSize.z * resolution)
                .putFloat(0.0f);
        bb.flip();
        params.update(0, bb);
    }

    public void dispose() {
        occupancy.dispose();
        ao.dispose();
        aoBlur.dispose();
        params.dispose();
        samplerNearest.dispose();
        samplerLinear.dispose();
    }
}
