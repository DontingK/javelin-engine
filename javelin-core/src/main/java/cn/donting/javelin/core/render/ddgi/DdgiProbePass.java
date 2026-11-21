package cn.donting.javelin.core.render.ddgi;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.RenderCamera;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.core.settings.DdgiSettings;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.ComputePipeline;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.ComputePipelineDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.SamplerDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.desc.TextureDesc;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.ShaderStage;
import cn.donting.javelin.rhi.types.TextureUsage;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * DDGI 探针追踪 + 更新 Pass（Engine_DDGI_Design.md §4.1/§4.2，P4-05 批 B2）。
 *
 * <p><b>每帧两段 compute</b>：`ddgi_trace.compute`（每探针 64 方向体素 DDA → 临时 radiance /
 * 距离 tile）→ `ddgi_update.compute`（余弦加权积辐照度 + 深度矩 + 时域混合）。两者都只处理
 * 「本帧切片」的探针（`probesPerFrame`，缺省 64），一圈 `ceil(N/64)` 帧。</p>
 *
 * <p><b>探针网格</b>：每帧按首个相机位置做相机居中网格（{@link DdgiProbeGrid#cameraCentered}），
 * 网格一变（探针数/间距/原点吸附跳格）→ atlas 换代、切片游标归零、首圈重来。</p>
 *
 * <p><b>首圈不混合</b>：atlas 初值是全零，若一上来就按 hysteresis 混，辐照度会从黑往上爬好几圈。
 * 宿主在首圈把 hysteresis 传 0（完全用本帧结果），游标第一次绕回 0 之后才用设置里的值。</p>
 *
 * <p><b>B2 的已知缺口</b>：①tile 1px 边框不填（八面体接缝在消费面按内区 clamp 处理）；
 * ②命中点直射项只算 `ndl`，未接 CSM 阴影（阴影贴图读取属 B3）；③未命中回落 `ambient`，
 * 不接天空盒。这些都在设计档 §11 记为 B2 残留。</p>
 */
public class DdgiProbePass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(DdgiProbePass.class);

    private static final String TRACE_SHADER = "core/voxel/ddgi_trace.compute";
    private static final String UPDATE_SHADER = "core/voxel/ddgi_update.compute";
    private static final String TRACE_PASS_NAME = "DdgiTrace";
    private static final String UPDATE_PASS_NAME = "DdgiUpdate";
    /** push 区字节数（两段共用：sliceStart/probeCount/tilesPerRow + 一个 float） */
    private static final int PUSH_BYTES = 16;
    /** 探针网格 UBO（64B：origin+spacing / counts+tilesPerRow / tile 边长 / 保留） */
    private static final int GRID_UBO_BYTES = 64;
    /** 光照 UBO（64B：sunDir / sunColor / ambient / 保留） */
    private static final int LIGHT_UBO_BYTES = 64;
    /** 单条射线最长距离（世界单位；先固定，B5 再按场景尺寸自适应） */
    private static final float MAX_RAY_DISTANCE = 60f;
    /** 首圈完成后做一次辐照度读回打印的探针数 */
    private static final int DEBUG_PROBES = 8;
    /** 诊断 SSBO 字节数（每探针 4 float = 16B） */
    private static final int DEBUG_BYTES = DEBUG_PROBES * 16;

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final AssetManager assetManager;
    private final DdgiVoxelizer voxelizer;

    private ComputePipeline tracePipeline;
    private ComputePipeline updatePipeline;
    private DescriptorSetLayout traceLayout;
    private DescriptorSetLayout updateLayout;
    private DescriptorSet traceSet;
    private DescriptorSet updateSet;

    private Buffer gridUbo;
    private Buffer lightUbo;
    /** 诊断 SSBO（update 段写，宿主读回打印） */
    private Buffer debugBuffer;
    private final ByteBuffer debugReadData = ByteBuffer.allocateDirect(DEBUG_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private Sampler samplerNearest;
    private final ByteBuffer gridUboData = ByteBuffer.allocateDirect(GRID_UBO_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer lightUboData = ByteBuffer.allocateDirect(LIGHT_UBO_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final float[] lighting = new float[12];

    /** 临时 radiance / 距离 tile（与辐照度 atlas 同版面：10×10 tile，内区 8×8） */
    private Texture radianceTemp;
    private Texture distanceTemp;

    /** 当前 atlas 对与网格（换代判据） */
    private DdgiAtlases atlases;
    private DdgiProbeGrid grid;
    private String atlasesCamera;
    /** 描述符里绑定的体素场（体素化重建后要重指） */
    private DdgiVoxelField boundField;

    /** 本帧切片游标（探针线性索引） */
    private int sliceCursor;
    /** 首圈是否已完成（决定 hysteresis 用 0 还是设置值） */
    private boolean firstSweepDone;
    /** 本帧正是首圈最后一刀（这一刀也必须 hysteresis=0，否则最后一批探针会混进黑历史） */
    private boolean frameWrapsFirstSweep;
    private boolean debugLogged;
    private boolean assetsWarned;
    /** 本帧派发参数（两段共用） */
    private int frameSliceStart;
    private int frameSliceCount;

    public DdgiProbePass(Device device, RenderingSystem renderingSystem, AssetManager assetManager,
                         DdgiVoxelizer voxelizer) {
        if (device == null || renderingSystem == null) {
            throw new IllegalArgumentException("device / renderingSystem must not be null");
        }
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.assetManager = assetManager;
        this.voxelizer = voxelizer;
    }

    @Override
    public String name() {
        return TRACE_PASS_NAME;
    }

    /** 探针总数（0 = 未就绪） */
    public int probeCount() {
        return grid == null ? 0 : grid.probeCount();
    }

    /** 已完成的圈数（调试与验收用） */
    public int completedSweeps() {
        return firstSweepDone ? 1 : 0;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        DdgiSettings settings = renderingSystem.ddgiSettings();
        if (settings == null || !settings.isEnabled() || !isPrimaryView(ctx)) {
            return;
        }
        DdgiVoxelField field = voxelizer == null ? null : voxelizer.field();
        if (field == null) {
            // 体素图还没建好：追踪段无从求交，整段跳过（B1 的体素化在首帧末才就绪）
            return;
        }
        if (!ensurePipelines() || !ensureAtlases(settings)) {
            return;
        }
        DdgiVoxelField currentField = voxelizer.field();
        if (currentField != boundField) {
            // 体素图换代（场景改过）→ 追踪段描述符里的 voxels/params 要重指
            boundField = currentField;
            rebindSets();
        }
        sliceFrame(probeCount(), settings.getProbesPerFrame());
        if (frameSliceCount <= 0) {
            return;
        }
        writeGridUbo(settings);
        writeLightUbo();
        if (firstSweepDone && !frameWrapsFirstSweep) {
            // 首圈那一刀本身还没派发，此时读只会读到零；等下一帧再读才有全量结果
            logIrradianceSamplesOnce();
        }

        graph.addComputePass(ctx.view().name() + ":" + TRACE_PASS_NAME, pass -> {
            pass.setPipeline(tracePipeline);
            pass.addTextureRead(field.voxels());
            pass.addBufferRead(field.params());
            pass.addTextureStorageWrite(radianceTemp);
            pass.addTextureStorageWrite(distanceTemp);
            pass.addBufferRead(gridUbo);
            pass.addBufferRead(lightUbo);
            pass.setRenderCallback(cmd -> dispatchTrace(cmd));
        });

        graph.addComputePass(ctx.view().name() + ":" + UPDATE_PASS_NAME, pass -> {
            pass.setPipeline(updatePipeline);
            pass.addTextureRead(radianceTemp);
            pass.addTextureRead(distanceTemp);
            pass.addTextureStorageWrite(atlases.irradiance());
            pass.addTextureStorageWrite(atlases.depth());
            pass.addBufferRead(gridUbo);
            pass.addBufferWrite(debugBuffer);
            pass.setRenderCallback(cmd -> dispatchUpdate(cmd, settings.getHysteresis()));
        });
    }

    /** 只在本帧第一个视口挂载（探针数据全局共享） */
    private boolean isPrimaryView(PipelineContext ctx) {
        List<RenderCamera> cameras = renderingSystem.cameras();
        if (cameras.isEmpty() || ctx.view() == null) {
            return true;
        }
        return cameras.get(0).name().equals(ctx.view().name());
    }

    private boolean ensurePipelines() {
        if (tracePipeline != null && updatePipeline != null) {
            return true;
        }
        ComputeShader trace = assetManager == null ? null
                : assetManager.loadAsset(TRACE_SHADER, ComputeShader.class);
        ComputeShader update = assetManager == null ? null
                : assetManager.loadAsset(UPDATE_SHADER, ComputeShader.class);
        if (trace == null || update == null || !trace.isValid() || !update.isValid()) {
            if (!assetsWarned) {
                assetsWarned = true;
                log.error("DDGI compute 资产不可用（{} / {}）：检查 builtin-asset-core/voxel 下的 .compute "
                        + "与伴生 .meta、导入日志（stub 产物 = 编译失败）", TRACE_SHADER, UPDATE_SHADER);
            }
            return false;
        }
        DescriptorSetLayoutDesc traceDesc = new DescriptorSetLayoutDesc(List.of(
                // 着色器里写的是 uniform sampler3D = COMBINED_IMAGE_SAMPLER；声明成 SAMPLED_IMAGE
                // 会撞 VUID-VkComputePipelineCreateInfo-layout-07990（描述符类型不匹配）
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.UNIFORM_BUFFER, 1, ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.STORAGE_IMAGE, 1, ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(3, DescriptorType.STORAGE_IMAGE, 1, ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(4, DescriptorType.UNIFORM_BUFFER, 1, ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(5, DescriptorType.UNIFORM_BUFFER, 1, ShaderStage.COMPUTE)));
        DescriptorSetLayoutDesc updateDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.STORAGE_IMAGE, 1, ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(3, DescriptorType.STORAGE_IMAGE, 1, ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(4, DescriptorType.UNIFORM_BUFFER, 1, ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(5, DescriptorType.STORAGE_BUFFER, 1, ShaderStage.COMPUTE)));
        traceLayout = device.createDescriptorSetLayout(traceDesc);
        updateLayout = device.createDescriptorSetLayout(updateDesc);
        tracePipeline = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE, trace.getSpirv()), List.of(traceDesc), PUSH_BYTES));
        updatePipeline = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE, update.getSpirv()), List.of(updateDesc), PUSH_BYTES));
        gridUbo = device.createBuffer(new BufferDesc(GRID_UBO_BYTES, Set.of(BufferUsage.UNIFORM),
                MemoryPreference.hostVisibleCoherent()), null);
        lightUbo = device.createBuffer(new BufferDesc(LIGHT_UBO_BYTES, Set.of(BufferUsage.UNIFORM),
                MemoryPreference.hostVisibleCoherent()), null);
        // 诊断 SSBO：8 探针 × 16B（rgb + 平均距离），宿主可 map 读回
        debugBuffer = device.createBuffer(new BufferDesc(DEBUG_BYTES, Set.of(BufferUsage.STORAGE),
                MemoryPreference.hostVisibleCoherent()), null);
        samplerNearest = device.createSampler(SamplerDesc.nearest());
        return true;
    }

    /**
     * 探针 atlas 与临时 tile。
     *
     * <p><b>只有版面变才换代</b>：atlas 的 tile 排布只看三向探针数（tile 边长是常量），
     * 相机平移导致原点吸附跳格时探针数不变——此时换图纯属浪费（每走两米重建两张 atlas），
     * 只需把游标归零、下一圈全量覆盖旧值。探针数/换相机才真正重建两张 atlas 与临时 tile。</p>
     */
    private boolean ensureAtlases(DdgiSettings settings) {
        RenderCamera camera = renderingSystem.cameras().isEmpty()
                ? null : renderingSystem.cameras().get(0);
        Vector3f position = camera == null ? new Vector3f() : camera.position();
        DdgiVoxelField field = boundField != null ? boundField
                : (voxelizer == null ? null : voxelizer.field());
        // 探针网格夹进体素场 AABB：不然相机拉高时探针全在场外，投射线第一格就出界、整片回落环境色
        DdgiProbeGrid newGrid;
        if (field == null) {
            newGrid = DdgiProbeGrid.cameraCentered(position.x, position.y, position.z, settings);
        } else {
            Vector3f min = field.origin();
            Vector3f size = field.voxelSize();
            int res = field.resolution();
            newGrid = DdgiProbeGrid.cameraCenteredWithin(position.x, position.y, position.z, settings,
                    min.x, min.y, min.z,
                    min.x + size.x * res, min.y + size.y * res, min.z + size.z * res);
        }
        String cameraName = camera == null ? "default" : camera.name();

        boolean layoutSame = grid != null && atlases != null
                && cameraName.equals(atlasesCamera)
                && grid.countX() == newGrid.countX()
                && grid.countY() == newGrid.countY()
                && grid.countZ() == newGrid.countZ();
        if (layoutSame) {
            boolean moved = !sameGrid(grid, newGrid);
            grid = newGrid;   // 原点/间距可能变了，UBO 与探针位置要用新的
            if (moved) {
                // 网格整格跳动：旧探针值对不上号，本圈全量重扫（首圈规则同样适用）
                sliceCursor = 0;
                firstSweepDone = false;
                frameWrapsFirstSweep = false;
                // debugLogged 不复位：调试打印一场会话只出一次，否则每走两米刷一行
            }
            return true;
        }

        atlases = renderingSystem.ddgiAtlasesFor(cameraName, newGrid);
        if (radianceTemp != null) {
            renderingSystem.retireResource(radianceTemp::dispose);
            radianceTemp = null;
        }
        if (distanceTemp != null) {
            renderingSystem.retireResource(distanceTemp::dispose);
            distanceTemp = null;
        }
        radianceTemp = createTemp(Format.R16G16B16A16_SFLOAT, newGrid);
        distanceTemp = createTemp(Format.R16G16_SFLOAT, newGrid);
        grid = newGrid;
        atlasesCamera = cameraName;
        sliceCursor = 0;
        firstSweepDone = false;
        frameWrapsFirstSweep = false;
        debugLogged = false;
        rebindSets();
        log.info("DDGI 探针数据面就绪：{}，radiance/distance 临时 tile {}x{}，每帧 {} 探针",
                grid, newGrid.irradianceAtlasWidth(), newGrid.irradianceAtlasHeight(),
                Math.max(1, settings.getProbesPerFrame()));
        return true;
    }

    private static boolean sameGrid(DdgiProbeGrid a, DdgiProbeGrid b) {
        return a.probeCount() == b.probeCount()
                && a.originX() == b.originX() && a.originY() == b.originY() && a.originZ() == b.originZ()
                && a.spacing() == b.spacing();
    }

    /** 临时 tile（与辐照度 atlas 同版面；只作 compute 中转，用完即弃） */
    private Texture createTemp(Format format, DdgiProbeGrid probeGrid) {
        return device.createTexture(TextureDesc.texture2D(format,
                probeGrid.irradianceAtlasWidth(), probeGrid.irradianceAtlasHeight(),
                Collections.unmodifiableSet(EnumSet.of(TextureUsage.STORAGE, TextureUsage.SAMPLED,
                        TextureUsage.TRANSFER_DST))), null);
    }

    /** 两段描述符集重建（atlas / 临时 tile / 体素场换代都会走到） */
    private void rebindSets() {
        if (traceLayout == null || atlases == null || radianceTemp == null) {
            return;
        }
        DdgiVoxelField field = boundField != null ? boundField
                : (voxelizer == null ? null : voxelizer.field());
        if (field == null) {
            return;
        }
        boundField = field;
        if (traceSet != null) {
            renderingSystem.retireResource(traceSet::dispose);
            traceSet = null;
        }
        if (updateSet != null) {
            renderingSystem.retireResource(updateSet::dispose);
            updateSet = null;
        }
        traceSet = device.createDescriptorSet(traceLayout);
        traceSet.update(
                DescriptorUpdate.sampledTexture(0, field.voxels(), samplerNearest),
                DescriptorUpdate.uniformBuffer(1, field.params(), 0, DdgiVoxelField.PARAM_BYTES),
                DescriptorUpdate.storageTexture(2, radianceTemp),
                DescriptorUpdate.storageTexture(3, distanceTemp),
                DescriptorUpdate.uniformBuffer(4, gridUbo, 0, GRID_UBO_BYTES),
                DescriptorUpdate.uniformBuffer(5, lightUbo, 0, LIGHT_UBO_BYTES));
        updateSet = device.createDescriptorSet(updateLayout);
        updateSet.update(
                DescriptorUpdate.sampledTexture(0, radianceTemp, samplerNearest),
                DescriptorUpdate.sampledTexture(1, distanceTemp, samplerNearest),
                DescriptorUpdate.storageTexture(2, atlases.irradiance()),
                DescriptorUpdate.storageTexture(3, atlases.depth()),
                DescriptorUpdate.uniformBuffer(4, gridUbo, 0, GRID_UBO_BYTES),
                DescriptorUpdate.storageBuffer(5, debugBuffer));
    }

    /** 本帧切片：从游标起取 N 个探针，游标绕回 0 时标记首圈完成 */
    private void sliceFrame(int totalProbes, int probesPerFrame) {
        frameWrapsFirstSweep = false;
        if (atlases == null || totalProbes <= 0) {
            frameSliceCount = 0;
            return;
        }
        int perFrame = Math.max(1, probesPerFrame);
        if (sliceCursor >= totalProbes) {
            sliceCursor = 0;
        }
        frameSliceStart = sliceCursor;
        frameSliceCount = Math.min(perFrame, totalProbes - sliceCursor);
        sliceCursor += frameSliceCount;
        if (sliceCursor >= totalProbes) {
            sliceCursor = 0;
            if (!firstSweepDone) {
                firstSweepDone = true;
                frameWrapsFirstSweep = true;
                log.info("DDGI 首圈完成：{} 个探针 × {} 方向，之后按 hysteresis 混合", totalProbes,
                        DdgiProbeGrid.IRRADIANCE_INNER * DdgiProbeGrid.IRRADIANCE_INNER);
            }
        }
    }

    private void writeGridUbo(DdgiSettings settings) {
        gridUboData.clear();
        gridUboData.putFloat(grid.originX()).putFloat(grid.originY()).putFloat(grid.originZ())
                .putFloat(grid.spacing());
        gridUboData.putInt(grid.countX()).putInt(grid.countY()).putInt(grid.countZ())
                .putInt(grid.tilesPerRow());
        gridUboData.putFloat(DdgiProbeGrid.IRRADIANCE_TILE).putFloat(DdgiProbeGrid.DEPTH_TILE)
                .putFloat(DdgiProbeGrid.IRRADIANCE_INNER).putFloat(DdgiProbeGrid.DEPTH_INNER);
        gridUboData.putFloat(settings.getRaysPerProbe()).putFloat(MAX_RAY_DISTANCE)
                .putFloat(settings.getIntensity()).putFloat(0f);
        gridUboData.flip();
        gridUbo.update(0, gridUboData);
    }

    private void writeLightUbo() {
        renderingSystem.packDdgiLighting(lighting);
        lightUboData.clear();
        lightUboData.putFloat(lighting[0]).putFloat(lighting[1]).putFloat(lighting[2]).putFloat(0f);
        lightUboData.putFloat(lighting[3]).putFloat(lighting[4]).putFloat(lighting[5]).putFloat(0f);
        lightUboData.putFloat(lighting[6]).putFloat(lighting[7]).putFloat(lighting[8])
                .putFloat(lighting[9]);
        lightUboData.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
        lightUboData.flip();
        lightUbo.update(0, lightUboData);
    }

    /**
     * 首圈完成后一次性读回诊断 SSBO，打印前 8 个探针的辐照度与命中距离。
     *
     * <p>B2 的「看得见」验收：没有 debug 可视化也能判断探针是否真算出非零辐照度、颜色是否偏成
     * 某一面（例如全偏红说明投射线只打到了红墙）。走 buffer map 而不是纹理读回——后者在 RHI
     * 里还是 {@code UnsupportedOperationException}。</p>
     */
    private void logIrradianceSamplesOnce() {
        if (debugLogged || atlases == null || debugBuffer == null) {
            return;
        }
        debugLogged = true;
        try {
            device.waitIdle();   // 一次性：保证上一帧的 compute 已落地（每帧都等会拖慢管线）
            debugReadData.clear();
            debugBuffer.read(0, DEBUG_BYTES, debugReadData);
            debugReadData.rewind();
            int probes = Math.min(DEBUG_PROBES, atlases.probeCount());
            for (int i = 0; i < probes; i++) {
                float r = debugReadData.getFloat(i * 16);
                float g = debugReadData.getFloat(i * 16 + 4);
                float b = debugReadData.getFloat(i * 16 + 8);
                float meanDistance = debugReadData.getFloat(i * 16 + 12);
                log.info("探针[中心块#{}] 辐照度 ≈ ({}, {}, {})、内区角命中距离 ≈ {}", i, r, g, b,
                        meanDistance);
            }
        } catch (Throwable t) {
            log.warn("DDGI 诊断读回失败（仅调试打印，不影响渲染）: {}", t.toString());
        }
    }

    /** 追踪段派发：一 workgroup 一探针（workgroup = 8×8 = 64 线程 = 64 方向） */
    private void dispatchTrace(CommandBuffer cmd) {
        if (traceSet == null || frameSliceCount <= 0) {
            return;
        }
        pushData.clear();
        pushData.putInt(frameSliceStart).putInt(frameSliceCount).putInt(grid.tilesPerRow())
                .putFloat(MAX_RAY_DISTANCE);
        pushData.flip();
        cmd.bindDescriptorSet(0, traceSet, 0, null);
        cmd.pushConstants(0, pushData);
        cmd.dispatch(frameSliceCount, 1, 1);
    }

    /** 更新段派发（同一切片；首圈（含首圈最后一刀）hysteresis 传 0） */
    private void dispatchUpdate(CommandBuffer cmd, float hysteresis) {
        if (updateSet == null || frameSliceCount <= 0) {
            return;
        }
        float blend = (firstSweepDone && !frameWrapsFirstSweep) ? hysteresis : 0f;
        pushData.clear();
        pushData.putInt(frameSliceStart).putInt(frameSliceCount).putInt(grid.tilesPerRow())
                .putFloat(blend);
        pushData.flip();
        cmd.bindDescriptorSet(0, updateSet, 0, null);
        cmd.pushConstants(0, pushData);
        cmd.dispatch(frameSliceCount, 1, 1);
    }

    /** 释放 GPU 资源（须 device waitIdle 后） */
    public void close() {
        if (traceSet != null) {
            traceSet.dispose();
            traceSet = null;
        }
        if (updateSet != null) {
            updateSet.dispose();
            updateSet = null;
        }
        if (radianceTemp != null) {
            radianceTemp.dispose();
            radianceTemp = null;
        }
        if (distanceTemp != null) {
            distanceTemp.dispose();
            distanceTemp = null;
        }
        if (tracePipeline != null) {
            tracePipeline.dispose();
            tracePipeline = null;
        }
        if (updatePipeline != null) {
            updatePipeline.dispose();
            updatePipeline = null;
        }
        if (traceLayout != null) {
            traceLayout.dispose();
            traceLayout = null;
        }
        if (updateLayout != null) {
            updateLayout.dispose();
            updateLayout = null;
        }
        if (gridUbo != null) {
            gridUbo.dispose();
            gridUbo = null;
        }
        if (lightUbo != null) {
            lightUbo.dispose();
            lightUbo = null;
        }
        if (debugBuffer != null) {
            debugBuffer.dispose();
            debugBuffer = null;
        }
        if (samplerNearest != null) {
            samplerNearest.dispose();
            samplerNearest = null;
        }
    }
}
