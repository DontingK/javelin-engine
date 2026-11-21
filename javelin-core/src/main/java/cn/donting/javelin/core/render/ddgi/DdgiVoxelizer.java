package cn.donting.javelin.core.render.ddgi;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.physics.collision.Aabb;
import cn.donting.javelin.core.render.RenderCamera;
import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.render.mesh.MeshVoxelGeometry;
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
import cn.donting.javelin.rhi.desc.ComputePipelineDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.IndexType;
import cn.donting.javelin.rhi.types.ShaderStage;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * DDGI 体素化 Pass（Engine_DDGI_Design.md §4.1，P4-05 批 B1 残留②）。
 *
 * <p><b>做什么</b>：把「静态 + 不透明」的网格实例盖进 {@link DdgiVoxelField} 的 N³ 体素图
 * （{@code rgb} = 基色、{@code a} = 占用位），供 B2 的 {@code ddgi_trace.comp} 走 DDA 求交。</p>
 *
 * <p><b>集合口径（不复用 {@code castShadow}）</b>：参与体素化 = 不透明（{@code
 * !isTransparent()}）+ {@code castShadow} + 网格可体素化。{@code castShadow} 一标志两用是
 * P4-17 的老坑，这里只用它挡「编辑器网格辅助线」（那条线曾把包围盒撑到 ±4226 → 体素粒度
 * 66 米、追踪一步跳空）。</p>
 *
 * <p><b>门控 = 签名 + 两帧稳定</b>：每帧算一次 {@link StaticSetSignature}（集合身份 = 网格实例
 * + 世界矩阵 + 基色）。签名与「已建签名」不同 → 候选；候选**连续两帧相同**才重建——物体
 * 正在移动时签名每帧都变，此时重建等于每帧全量重刷（BUG-13 的教训）。停住两帧后重建一次，
 * 期间用旧体素（动态物体滞后本就由 SSAO 接触层兜）。</p>
 *
 * <p><b>派发</b>：一个网格实例一个 dispatch（三角形数 / 64），默认只在本帧第一个视口挂载
 * （体素图与视口无关，多视口重复派发纯属浪费）。</p>
 */
public class DdgiVoxelizer implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(DdgiVoxelizer.class);

    /** 内置 compute 资产 loadAsset 路径（builtin-asset-core/voxel 释放到 .javelin/assets 后） */
    private static final String SHADER_PATH = "core/voxel/voxelize.compute";
    private static final String PASS_NAME = "DdgiVoxelize";
    /** 与 .compute 的 local_size_x 一致 */
    private static final int LOCAL_SIZE = 64;
    /**
     * push 区字节数：mat4(64) + 5 × uint(20) + 12 填充 + vec4 albedo(16) = 112
     * （vec4 必须 16 字节对齐，故 uint 之后补 12）。
     */
    private static final int PUSH_BYTES = 112;
    /** 包围盒外扩比例（防表面贴边被裁） */
    private static final float BOUNDS_PAD_RATIO = 0.02f;
    /** 基色缺省（材质没声明 _Color 时的兜底，中性灰） */
    private static final float DEFAULT_ALBEDO = 0.8f;

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final AssetManager assetManager;

    private ComputePipeline pipeline;
    private DescriptorSetLayoutDesc layoutDesc;
    private DescriptorSetLayout layout;
    private DdgiVoxelField field;
    private final List<MeshEntry> entries = new ArrayList<>();

    /** 已建体素图对应的签名（null = 还没有图） */
    private StaticSetSignature builtSignature;
    /** 上一帧的候选签名（两帧稳定判据） */
    private StaticSetSignature candidateSignature;
    private int lastResolution = -1;
    private boolean pendingDispatch;
    private long buildStamp;
    private boolean assetWarningLogged;

    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    /** 世界矩阵工作变量（Aabb.transform 用 Matrix4f，取 RenderObject 的拷贝另存 float[16]） */
    private final org.joml.Matrix4f matrixTmp = new org.joml.Matrix4f();
    private final float[] worldTmp = new float[16];

    public DdgiVoxelizer(Device device, RenderingSystem renderingSystem, AssetManager assetManager) {
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

    /** 当前体素场（未启用/未建好时为 null；B2 追踪 Pass 与 debug 视图读取） */
    public DdgiVoxelField field() {
        return field;
    }

    /** 重建序号（每次重建自增；消费方据此判断历史是否要重置） */
    public long buildStamp() {
        return buildStamp;
    }

    /** 强制重建（编辑器命令 / 设置变更后调用）：下一帧重新走一遍两帧稳定判定 */
    public void requestRebuild() {
        builtSignature = null;
        candidateSignature = null;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        DdgiSettings settings = renderingSystem.ddgiSettings();
        if (settings == null || !settings.isEnabled()) {
            if (field != null || !entries.isEmpty()) {
                releaseField();
            }
            return;
        }
        if (!isPrimaryView(ctx)) {
            return;
        }
        if (!ensureReady(settings)) {
            return;
        }
        graph.addComputePass(ctx.view().name() + ":" + PASS_NAME, pass -> {
            pass.setPipeline(pipeline);
            pass.addTextureStorageWrite(field.voxels());
            pass.addBufferRead(field.params());
            for (MeshEntry entry : entries) {
                pass.addBufferRead(entry.vertex);
                pass.addBufferRead(entry.index);
            }
            pass.setRenderCallback(this::dispatchAll);
        });
    }

    /** 只在本帧第一个视口挂载（体素图全局共享，多视口重复派发是纯浪费） */
    private boolean isPrimaryView(PipelineContext ctx) {
        List<RenderCamera> cameras = renderingSystem.cameras();
        if (cameras.isEmpty() || ctx.view() == null) {
            return true;
        }
        return cameras.get(0).name().equals(ctx.view().name());
    }

    private boolean ensureReady(DdgiSettings settings) {
        if (pipeline == null && !createPipeline()) {
            return false;
        }
        int resolution = Math.max(1, settings.getVoxelResolution());
        long now = System.nanoTime();
        StaticSetSignature signature = collect();
        if (signature == null) {
            // 无可体素化几何：清掉旧图（留着一张空图只会让人误以为 GI 在工作）
            if (field != null) {
                log.info("体素化释放：场景内已无可体素化的（不透明 + 投射阴影）网格");
                releaseField();
            }
            return false;
        }
        boolean resolutionChanged = resolution != lastResolution;
        boolean sameAsBuilt = builtSignature != null && signature.equals(builtSignature);
        if (!sameAsBuilt || resolutionChanged) {
            boolean stableTwoFrames = signature.equals(candidateSignature);
            candidateSignature = signature;
            if (!stableTwoFrames) {
                // 集合还在动：等它停住（连续两帧同签名）
                return field != null && !entries.isEmpty();
            }
            prepare(resolution, signature, System.nanoTime() - now);
        }
        return field != null && !entries.isEmpty();
    }

    private boolean createPipeline() {
        ComputeShader shader = assetManager == null ? null
                : assetManager.loadAsset(SHADER_PATH, ComputeShader.class);
        if (shader == null || !shader.isValid()) {
            if (!assetWarningLogged) {
                assetWarningLogged = true;
                log.error("体素化 compute 资产不可用（{}）：检查 builtin-asset-core/voxel/voxelize.compute "
                        + "与伴生 .meta、导入日志（stub 产物 = 编译失败）；DDGI 投射线将无体素图可用",
                        SHADER_PATH);
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
        return true;
    }

    /**
     * 收集参与体素化的网格实例并算签名（每帧都跑，纯 CPU 轻量）。
     *
     * @return 签名；没有任何候选时返回 null
     */
    private StaticSetSignature collect() {
        List<MeshInstance> instances = new ArrayList<>();
        List<Mesh> meshes = new ArrayList<>();
        List<float[]> worlds = new ArrayList<>();
        Aabb bounds = null;
        Aabb meshBounds = new Aabb();

        for (RenderObject ro : renderingSystem.renderers()) {
            if (ro.isTransparent() || !ro.isCastShadow()) {
                continue;
            }
            Mesh mesh = ro.mesh();
            if (!MeshVoxelGeometry.isVoxelizable(mesh)) {
                continue;
            }
            ro.world().get(worldTmp);
            float[] world = new float[16];
            System.arraycopy(worldTmp, 0, world, 0, 16);
            if (containsMatrix(worlds, world, meshes, mesh)) {
                continue;   // 同网格同世界变换（多材质/多子网格派生对象）只体素化一次
            }
            float[] albedo = albedoOf(ro);
            meshes.add(mesh);
            worlds.add(world);
            instances.add(new MeshInstance(mesh, world, albedo));

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

        this.collectedInstances = instances;
        this.collectedBounds = bounds;
        if (instances.isEmpty()) {
            return null;
        }
        StaticSetSignature.Builder builder = StaticSetSignature.builder();
        for (MeshInstance instance : instances) {
            builder.add(instance.mesh, instance.world, instance.albedo);
        }
        return builder.build();
    }

    /** 本轮收集结果（collect → prepare 之间传递，避免二次遍历 renderers） */
    private List<MeshInstance> collectedInstances = List.of();
    private Aabb collectedBounds;

    /** 旧图退役 + 建新图 + 逐网格描述符集（录制前完成；耗时打进日志供验收读） */
    private void prepare(int resolution, StaticSetSignature signature, long collectNanos) {
        long start = System.nanoTime();
        for (MeshEntry entry : entries) {
            if (entry.set != null) {
                renderingSystem.retireResource(entry.set::dispose);
            }
        }
        entries.clear();

        Aabb bounds = collectedBounds;
        if (bounds == null) {
            log.warn("体素化跳过：可体素化网格没有包围盒（mesh.getBounds() 全为空）");
            releaseField();
            return;
        }
        Vector3f min = new Vector3f(bounds.getMin());
        Vector3f max = new Vector3f(bounds.getMax());
        float pad = Math.max(1e-3f, max.distance(min) * BOUNDS_PAD_RATIO);
        // JOML 的 Vector3f 只收向量重载：标量外扩须三参写法
        min.sub(pad, pad, pad);
        max.add(pad, pad, pad);

        DdgiVoxelField old = field;
        field = DdgiVoxelField.create(device, resolution, min, max);
        field.writeParams();
        lastResolution = resolution;
        buildStamp++;
        if (old != null) {
            renderingSystem.retireResource(old::dispose);
        }

        for (MeshInstance instance : collectedInstances) {
            MeshEntry entry = new MeshEntry(instance.mesh, instance.world, instance.albedo);
            entry.set = device.createDescriptorSet(layout);
            entry.set.update(
                    DescriptorUpdate.storageBuffer(0, entry.vertex),
                    DescriptorUpdate.storageBuffer(1, entry.index),
                    DescriptorUpdate.storageTexture(2, field.voxels()),
                    DescriptorUpdate.uniformBuffer(3, field.params(), 0, DdgiVoxelField.PARAM_BYTES));
            entries.add(entry);
        }

        builtSignature = signature;
        pendingDispatch = true;
        long totalMs = (System.nanoTime() - start) / 1_000_000L;
        long collectMs = collectNanos / 1_000_000L;
        log.info("体素化重建完成：体素图 {}³（{} MB）、实例 {} 个、包围盒 [{}, {}]"
                        + "、收集 {} ms、建资源 {} ms（{}）",
                resolution, field.bytes() / (1024 * 1024), entries.size(), min, max,
                collectMs, totalMs, signature);
    }

    /** 释放体素场与逐网格描述符集（关开关 / 场景无可体素化网格时） */
    private void releaseField() {
        for (MeshEntry entry : entries) {
            if (entry.set != null) {
                renderingSystem.retireResource(entry.set::dispose);
            }
        }
        entries.clear();
        if (field != null) {
            renderingSystem.retireResource(field::dispose);
            field = null;
        }
        builtSignature = null;
        candidateSignature = null;
        lastResolution = -1;
        pendingDispatch = false;
    }

    /** 基色（材质 {@code _Color} 的 rgb；没有该属性则回退中性灰） */
    private float[] albedoOf(RenderObject ro) {
        try {
            float[] color = ro.material().getColor("_Color");
            if (color != null && color.length >= 3) {
                return new float[]{color[0], color[1], color[2]};
            }
        } catch (RuntimeException e) {
            // 材质未声明 _Color（或未绑 shader）：按缺省灰，不是错误
        }
        return new float[]{DEFAULT_ALBEDO, DEFAULT_ALBEDO, DEFAULT_ALBEDO};
    }

    private static boolean containsMatrix(List<float[]> recorded, float[] world, List<Mesh> meshes, Mesh mesh) {
        for (int i = 0; i < recorded.size(); i++) {
            if (meshes.get(i) != mesh) {
                continue;
            }
            float[] candidate = recorded.get(i);
            boolean equal = true;
            for (int j = 0; j < 16; j++) {
                if (Math.abs(candidate[j] - world[j]) > 1e-6f) {
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
        if (!pendingDispatch || field == null) {
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
            while (pushData.position() < 96) {        // vec4 对齐前的填充
                pushData.put((byte) 0);
            }
            pushData.putFloat(entry.albedo[0]).putFloat(entry.albedo[1]).putFloat(entry.albedo[2])
                    .putFloat(1f);
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
        if (field != null) {
            field.dispose();
            field = null;
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

    /** 收集期的网格实例（网格 + 世界矩阵 + 基色） */
    private static final class MeshInstance {
        final Mesh mesh;
        final float[] world;
        final float[] albedo;

        MeshInstance(Mesh mesh, float[] world, float[] albedo) {
            this.mesh = mesh;
            this.world = world;
            this.albedo = albedo;
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
        final float[] albedo;
        DescriptorSet set;

        MeshEntry(Mesh mesh, float[] world, float[] albedo) {
            this.vertex = MeshVoxelGeometry.vertexBuffer(mesh);
            this.index = MeshVoxelGeometry.indexBuffer(mesh);
            this.triangles = MeshVoxelGeometry.indexCount(mesh) / 3;
            this.strideFloats = MeshVoxelGeometry.vertexStride(mesh) / Float.BYTES;
            this.posOffsetFloats = MeshVoxelGeometry.positionOffset(mesh) / Float.BYTES;
            this.indexU16 = MeshVoxelGeometry.indexType(mesh) == IndexType.UINT16;
            this.world = world;
            this.albedo = albedo;
        }
    }
}
