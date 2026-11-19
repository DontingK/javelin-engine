package cn.donting.javelin.core.render.voxel;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.physics.collision.Aabb;
import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.render.mesh.MeshVoxelGeometry;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.core.settings.VoxelAoSettings;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.ComputePipeline;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.desc.ComputePipelineDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.IndexType;
import cn.donting.javelin.rhi.types.ShaderStage;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 体素化 Pass（Engine_VoxelAO_Design §4.1）：覆盖制 compute Pass，每帧恒挂载，
 * 仅重建请求帧派发（派发一次后标志即消费）。
 *
 * <p>一个网格实例一次 dispatch：set0 = b0 顶点 SSBO / b1 索引 SSBO /
 * b2 占用 storage image / b3 参数 UBO；push = world 矩阵 + 三角形区间 + 步长/偏移。</p>
 */
public class VoxelizationPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(VoxelizationPass.class);

    /** 内置 compute 资产 loadAsset 路径（builtin-asset-core/voxel 释放到 .javelin/assets 后的相对路径） */
    private static final String SHADER_PATH = "core/voxel/voxelize.compute";
    private static final String PASS_NAME = "Voxelize";
    /** 与 .compute 的 local_size_x 一致 */
    private static final int LOCAL_SIZE = 64;
    /** push 区字节数：mat4(64) + 5 × uint(20) → 补齐 96 */
    private static final int PUSH_BYTES = 96;

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final AssetManager assetManager;

    private ComputePipeline pipeline;
    private DescriptorSetLayoutDesc layoutDesc;
    private DescriptorSetLayout layout;
    private VoxelGrid grid;
    private final List<MeshEntry> entries = new ArrayList<>();

    /** 下一帧需要重新收集网格 + 重建资源（编辑器命令线程写入，故 volatile） */
    private volatile boolean rebuildRequested = true;
    /** 已准备好、待派发一次 */
    private boolean pendingDispatch;
    private int lastResolution = -1;
    private int lastAoResolution = -1;
    private int lastRendererCount = -1;
    /** 重建序号（AO 计算侧按它判断是否要重算；每次 prepare 自增） */
    private long buildStamp;
    private boolean assetWarningLogged;
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final Matrix4f matrixTmp = new Matrix4f();

    public VoxelizationPass(Device device, RenderingSystem renderingSystem, AssetManager assetManager) {
        if (device == null || renderingSystem == null) {
            throw new IllegalArgumentException("device / renderingSystem must not be null");
        }
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.assetManager = assetManager;
    }

    @Override
    public String name() {
        return PASS_NAME;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        VoxelAoSettings settings = renderingSystem.voxelAoSettings();
        if (settings == null || !settings.isEnabled()) {
            // 关开关即释放（网格 + 逐网格描述符集 + set1 采样面登记）——AO 图不再被采样，
            // 白占 8 MB 以上没有意义；重新打开走一次完整重建
            if (grid != null || !entries.isEmpty()) {
                releaseGrid();
            }
            return;
        }
        if (!ensureReady(settings)) {
            return;
        }
        graph.addComputePass(ctx.view().name() + ":" + PASS_NAME, pass -> {
            pass.setPipeline(pipeline);
            pass.addTextureStorageWrite(grid.occupancy());
            for (MeshEntry entry : entries) {
                pass.addBufferRead(entry.vertex);
                pass.addBufferRead(entry.index);
            };
            pass.setRenderCallback(this::dispatchAll);
        });
    }

    /** 重建请求（设置变更 / 编辑器命令）：下一帧重新收集并派发 */
    public void requestRebuild() {
        rebuildRequested = true;
    }

    /** 当前体素网格（未就绪 / 被关闭时为 null；AO 计算侧读取） */
    public VoxelGrid grid() {
        return grid;
    }

    /** 网格重建序号（每次重建立即自增；AO 侧据此判断是否重算） */
    public long buildStamp() {
        return buildStamp;
    }

    private boolean ensureReady(VoxelAoSettings settings) {
        if (pipeline == null) {
            ComputeShader shader = assetManager == null ? null
                    : assetManager.loadAsset(SHADER_PATH, ComputeShader.class);
            if (shader == null || !shader.isValid()) {
                if (!assetWarningLogged) {
                    assetWarningLogged = true;
                    log.error("体素化 compute 资产不可用（{}）：检查 builtin-asset-core/voxel/voxelize.compute "
                            + "与伴生 .meta、导入日志（stub 产物 = 编译失败）", SHADER_PATH);
                }
                return false;
            }
            layoutDesc = new DescriptorSetLayoutDesc(List.of(
                    new DescriptorSetLayoutDesc.Binding(0, DescriptorType.STORAGE_BUFFER, 1, ShaderStage.COMPUTE),
                    new DescriptorSetLayoutDesc.Binding(1, DescriptorType.STORAGE_BUFFER, 1, ShaderStage.COMPUTE),
                    new DescriptorSetLayoutDesc.Binding(2, DescriptorType.STORAGE_IMAGE, 1, ShaderStage.COMPUTE),
                    new DescriptorSetLayoutDesc.Binding(3, DescriptorType.UNIFORM_BUFFER, 1, ShaderStage.COMPUTE)));
            layout = device.createDescriptorSetLayout(layoutDesc);
            pipeline = device.createComputePipeline(new ComputePipelineDesc(
                    new ShaderDesc(ShaderStage.COMPUTE, shader.getSpirv()),
                    List.of(layoutDesc), PUSH_BYTES));
        }
        int resolution = Math.max(1, settings.getVoxelResolution());
        int aoResolution = Math.max(1, settings.getAoResolution());
        int rendererCount = renderingSystem.renderers().size();
        if (rendererCount != lastRendererCount) {
            lastRendererCount = rendererCount;
            rebuildRequested = true;
        }
        // AO 分辨率变化同批重建（AO 图随网格一起换新——首版不单独重建 AO 资源族）
        if (rebuildRequested || resolution != lastResolution || aoResolution != lastAoResolution) {
            prepare(resolution, aoResolution, settings);
        }
        return grid != null && !entries.isEmpty();
    }

    /** 收集网格实例、算包围盒、建/换网格资源与逐网格描述符集（录制前完成） */
    private void prepare(int resolution, int aoResolution, VoxelAoSettings settings) {
        // 旧描述符集退役（延迟两帧，GPU in-flight 安全）
        for (MeshEntry entry : entries) {
            if (entry.set != null) {
                renderingSystem.retireResource(entry.set::dispose);
            }
        }
        entries.clear();

        List<RenderObject> renderers = renderingSystem.renderers();
        Map<Mesh, List<float[]>> worlds = new HashMap<>();
        List<float[]> geometryWorlds = new ArrayList<>();
        List<Mesh> geometryMeshes = new ArrayList<>();
        Aabb bounds = null;
        Aabb meshBounds = new Aabb();

        for (RenderObject ro : renderers) {
            // 不参与遮蔽投影的对象不体素化（与阴影投射同口径）：编辑器网格辅助线
            // （GRID_SIZE 8000、castShadow=false）曾把场景包围盒撑到 ±4226 → 占用体素
            // 66 米/格、maxDistance 10 米一步跳空 → AO 恒 1.0 全场无效果（2026-11-19 实机）
            if (!ro.isCastShadow()) {
                continue;
            }
            Mesh mesh = ro.mesh();
            if (!MeshVoxelGeometry.isVoxelizable(mesh)) {
                continue;
            }
            float[] world = new float[16];
            ro.world().get(world);
            List<float[]> recorded = worlds.computeIfAbsent(mesh, m -> new ArrayList<>());
            if (containsMatrix(recorded, world)) {
                continue;   // 同网格同世界变换（多材质/多子网格派生对象）只体素化一次
            }
            recorded.add(world);
            geometryMeshes.add(mesh);
            geometryWorlds.add(world);

            Aabb local = mesh.getBounds();
            if (local != null) {
                matrixTmp.set(world[0], world[1], world[2], world[3],
                        world[4], world[5], world[6], world[7],
                        world[8], world[9], world[10], world[11],
                        world[12], world[13], world[14], world[15]);
                local.transform(matrixTmp, meshBounds);
                if (bounds == null) {
                    bounds = new Aabb(meshBounds.getMin(), meshBounds.getMax());
                } else {
                    bounds.set(new Vector3f(Math.min(bounds.getMin().x, meshBounds.getMin().x),
                                    Math.min(bounds.getMin().y, meshBounds.getMin().y),
                                    Math.min(bounds.getMin().z, meshBounds.getMin().z)),
                            new Vector3f(Math.max(bounds.getMax().x, meshBounds.getMax().x),
                                    Math.max(bounds.getMax().y, meshBounds.getMax().y),
                                    Math.max(bounds.getMax().z, meshBounds.getMax().z)));
                }
            }
        }

        if (bounds == null) {
            log.warn("体素化跳过：场景内没有可体素化的网格（bounds 为空）");
            releaseGrid();
            return;
        }
        Vector3f min = new Vector3f(bounds.getMin());
        Vector3f max = new Vector3f(bounds.getMax());
        // 外扩 2% 防止表面贴边被裁
        float pad = Math.max(1e-3f, max.distance(min) * 0.02f);
        // JOML 的 Vector3f 只收向量重载：标量外扩须三参写法（否则 float → Vector3fc 编译失败）
        min.sub(pad, pad, pad);
        max.add(pad, pad, pad);

        // 每次重建换新图（旧图内容无法原地清零——布局转换丢弃 + 残留占用会留下假体素）
        VoxelGrid old = grid;
        grid = VoxelGrid.create(device, resolution, aoResolution, min, max);
        grid.writeParams(settings.getRayCount(), settings.getMaxDistance(),
                settings.getIntensity(), settings.isEnabled());
        lastResolution = resolution;
        lastAoResolution = aoResolution;
        buildStamp++;
        if (old != null) {
            renderingSystem.retireResource(old::dispose);
        }
        // 登记到渲染系统：各视口 set1 binding8/9 下一帧按身份比对重指（AO 图/参数块）
        renderingSystem.setVoxelGrid(grid);

        for (int i = 0; i < geometryMeshes.size(); i++) {
            Mesh mesh = geometryMeshes.get(i);
            MeshEntry entry = new MeshEntry(mesh, geometryWorlds.get(i));
            entry.set = device.createDescriptorSet(layout);
            entry.set.update(
                    DescriptorUpdate.storageBuffer(0, entry.vertex),
                    DescriptorUpdate.storageBuffer(1, entry.index),
                    DescriptorUpdate.storageTexture(2, grid.occupancy()),
                    DescriptorUpdate.uniformBuffer(3, grid.params(), 0, VoxelGrid.PARAM_BYTES));
            entries.add(entry);
        }

        rebuildRequested = false;
        pendingDispatch = true;
        log.info("体素化准备完成：占用 {}³ / AO {}³, 实例 {}, 包围盒 [{}, {}]",
                resolution, aoResolution, entries.size(), min, max);
    }

    /**
     * 释放网格与逐网格描述符集（关开关 / 场景无可体素化网格时）：两者都走 retire 两帧退役
     * （GPU 可能在飞），并把渲染系统的 set1 采样面登记清空；分辨率记账复位让下次重建必然重跑。
     */
    private void releaseGrid() {
        for (MeshEntry entry : entries) {
            if (entry.set != null) {
                renderingSystem.retireResource(entry.set::dispose);
            }
        }
        entries.clear();
        if (grid != null) {
            renderingSystem.setVoxelGrid(null);
            renderingSystem.retireResource(grid::dispose);
            grid = null;
        }
        lastResolution = -1;
        lastAoResolution = -1;
        rebuildRequested = false;
        pendingDispatch = false;
    }

    private static boolean containsMatrix(List<float[]> recorded, float[] world) {
        for (float[] candidate : recorded) {
            boolean equal = true;
            for (int i = 0; i < 16; i++) {
                if (Math.abs(candidate[i] - world[i]) > 1e-6f) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return true;
            }
        }
        return false;
    }

    private void dispatchAll(CommandBuffer cmd) {
        if (!pendingDispatch || grid == null) {
            return;
        }
        pendingDispatch = false;
        for (MeshEntry entry : entries) {
            if (entry.triangles <= 0) {
                continue;
            }
            pushData.clear();
            for (int i = 0; i < 16; i++) {
                pushData.putFloat(entry.world[i]);
            }
            pushData.putInt(0);                       // indexBase
            pushData.putInt(entry.triangles);
            pushData.putInt(entry.strideFloats);
            pushData.putInt(entry.posOffsetFloats);
            pushData.putInt(entry.indexU16 ? 1 : 0);
            while (pushData.position() < PUSH_BYTES) {
                pushData.put((byte) 0);
            }
            pushData.flip();
            cmd.bindDescriptorSet(0, entry.set, 0, null);
            cmd.pushConstants(0, pushData);
            cmd.dispatch((entry.triangles + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
        }
    }

    /** 释放 GPU 资源（须 device waitIdle 后） */
    public void close() {
        for (MeshEntry entry : entries) {
            if (entry.set != null) {
                entry.set.dispose();
            }
        }
        entries.clear();
        if (grid != null) {
            grid.dispose();
            grid = null;
        }
        if (pipeline != null) {
            pipeline.dispose();
            pipeline = null;
        }
        if (layout != null) {
            layout.dispose();
            layout = null;
        }
    }

    /** 单个网格实例的派发参数 */
    private static final class MeshEntry {
        final Buffer vertex;
        final Buffer index;
        final int triangles;
        final int strideFloats;
        final int posOffsetFloats;
        final boolean indexU16;
        final float[] world;
        DescriptorSet set;

        MeshEntry(Mesh mesh, float[] world) {
            this.vertex = MeshVoxelGeometry.vertexBuffer(mesh);
            this.index = MeshVoxelGeometry.indexBuffer(mesh);
            this.triangles = MeshVoxelGeometry.indexCount(mesh) / 3;
            this.strideFloats = MeshVoxelGeometry.vertexStride(mesh) / Float.BYTES;
            this.posOffsetFloats = MeshVoxelGeometry.positionOffset(mesh) / Float.BYTES;
            this.indexU16 = MeshVoxelGeometry.indexType(mesh) == IndexType.UINT16;
            this.world = world;
        }
    }
}
