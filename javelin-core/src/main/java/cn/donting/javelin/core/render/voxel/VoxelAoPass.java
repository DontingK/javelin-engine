package cn.donting.javelin.core.render.voxel;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.core.settings.VoxelAoSettings;
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
import cn.donting.javelin.rhi.types.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 体素 AO 计算 Pass（Engine_VoxelAO_Design §4，批 C）：两个 compute Pass
 * （AO 求值 → 3D 模糊），恒挂载、仅在网格重建或 AO 参数变化后派发一次。
 *
 * <p>依赖 {@link VoxelizationPass} 的网格：占用图走采样读（图执行器自动做
 * GENERAL → SHADER_READ_ONLY 转换），因此 AO 天然排在体素化之后；AO 图与模糊图
 * 各自写、各自读到第二张图（ping-pong，避免读写同图屏障竞态）。</p>
 */
public class VoxelAoPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(VoxelAoPass.class);

    private static final String AO_SHADER_PATH = "core/voxel/voxelAO.compute";
    private static final String BLUR_SHADER_PATH = "core/voxel/voxelAOBlur.compute";
    private static final String PASS_NAME = "VoxelAO";
    /** 与两份 .compute 的 local_size 一致（4×4×4） */
    private static final int LOCAL_SIZE = 4;

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final VoxelizationPass voxelizationPass;
    private final AssetManager assetManager;

    private ComputePipeline aoPipeline;
    private ComputePipeline blurPipeline;
    private DescriptorSetLayout layout;
    private DescriptorSet aoSet;
    private DescriptorSet blurSet;

    /** 当前描述符集绑定的网格（换网格即重建集） */
    private VoxelGrid boundGrid;
    /** 上次求值用的参数（网格重建 / 参数变化才重算） */
    private long boundStamp = -1;
    private int boundAoResolution = -1;
    private int boundRayCount = -1;
    private float boundMaxDistance = Float.NaN;
    private float boundIntensity = Float.NaN;

    /** 本帧待派发 AO / 模糊（首个视口消费，其余视口空派发保图结构稳定；命令线程可请求故 volatile） */
    private volatile boolean pendingAo;
    private volatile boolean pendingBlur;
    private boolean assetWarningLogged;
    /** 排障：blur 派发后的下一帧读回 blur 图打统计日志（一次性，读后清零） */
    private boolean debugReadbackPending;

    public VoxelAoPass(Device device, RenderingSystem renderingSystem,
                       VoxelizationPass voxelizationPass, AssetManager assetManager) {
        if (device == null || renderingSystem == null || voxelizationPass == null) {
            throw new IllegalArgumentException("device / renderingSystem / voxelizationPass must not be null");
        }
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.voxelizationPass = voxelizationPass;
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
            // 网格已由 VoxelizationPass 释放：描述符集不拆会指向已销毁的纹理视图
            if (boundGrid != null) {
                releaseSets();
            }
            return;
        }
        if (!ensureReady(settings)) {
            return;
        }
        VoxelGrid grid = voxelizationPass.grid();
        String prefix = ctx.view().name() + ":";
        graph.addComputePass(prefix + PASS_NAME, pass -> {
            pass.setPipeline(aoPipeline);
            pass.addTextureRead(grid.occupancy());
            pass.addTextureStorageWrite(grid.ao());
            pass.addBufferRead(grid.params());
            pass.setRenderCallback(this::dispatchAo);
        });
        graph.addComputePass(prefix + PASS_NAME + "Blur", pass -> {
            pass.setPipeline(blurPipeline);
            pass.addTextureRead(grid.ao());
            pass.addTextureStorageWrite(grid.aoBlur());
            pass.addBufferRead(grid.params());
            pass.setRenderCallback(this::dispatchBlur);
        });
    }

    /** 强制重算（编辑器命令 / 场景变更） */
    public void requestRebuild() {
        pendingAo = true;
        pendingBlur = true;
    }

    private boolean ensureReady(VoxelAoSettings settings) {
        if (!ensurePipelines()) {
            return false;
        }
        // 排障读回（一次性）：blur 在上一帧派发完毕（fence 已过、图执行器已把 blur 图
        // 转到 SHADER_READ_ONLY），此刻整图读回打统计——不与任何在飞命令缓冲竞争
        if (debugReadbackPending) {
            debugReadbackPending = false;
            VoxelGrid dbgGrid = voxelizationPass.grid();
            if (dbgGrid != null) {
                try {
                    VoxelAoDebugReadback.readAndLog(device, dbgGrid.aoBlur(),
                            dbgGrid.aoResolution(), "stamp=" + voxelizationPass.buildStamp());
                    // 表面定向读数（「整体暗」断案）：占用图空邻居外推处的实际采样值
                    VoxelAoDebugReadback.readSurfaceSamples(device, dbgGrid.occupancy(),
                            dbgGrid.aoBlur(), dbgGrid.resolution(), dbgGrid.aoResolution(),
                            "stamp=" + voxelizationPass.buildStamp());
                } catch (Exception e) {
                    log.warn("[VoxelAoDebug] 读回失败: {}", e.getMessage());
                }
            }
        }
        VoxelGrid grid = voxelizationPass.grid();
        if (grid == null) {
            return false;   // 体素化未就绪（无网格 / 未重建）
        }
        int aoResolution = Math.max(1, settings.getAoResolution());
        long stamp = voxelizationPass.buildStamp();
        boolean needs = pendingAo
                || grid != boundGrid
                || stamp != boundStamp
                || aoResolution != boundAoResolution
                || settings.getRayCount() != boundRayCount
                || Math.abs(settings.getMaxDistance() - boundMaxDistance) > 1e-4f
                || Math.abs(settings.getIntensity() - boundIntensity) > 1e-4f;
        if (!needs) {
            return true;
        }
        if (grid != boundGrid) {
            retireSets();
            aoSet = device.createDescriptorSet(layout);
            blurSet = device.createDescriptorSet(layout);
            boundGrid = grid;
        }
        // 参数块在派发前重写（射线数 / 距离只改参数，不需要重体素化）
        grid.writeParams(settings.getRayCount(), settings.getMaxDistance(),
                settings.getIntensity(), settings.isEnabled());
        aoSet.update(
                DescriptorUpdate.sampledTexture(0, grid.occupancy(), grid.samplerNearest()),
                DescriptorUpdate.storageTexture(1, grid.ao()),
                DescriptorUpdate.uniformBuffer(2, grid.params(), 0, VoxelGrid.PARAM_BYTES));
        blurSet.update(
                DescriptorUpdate.sampledTexture(0, grid.ao(), grid.samplerNearest()),
                DescriptorUpdate.storageTexture(1, grid.aoBlur()),
                DescriptorUpdate.uniformBuffer(2, grid.params(), 0, VoxelGrid.PARAM_BYTES));
        boundStamp = stamp;
        boundAoResolution = aoResolution;
        boundRayCount = settings.getRayCount();
        boundMaxDistance = settings.getMaxDistance();
        boundIntensity = settings.getIntensity();
        pendingAo = true;
        pendingBlur = true;
        log.info("体素 AO 求值准备完成：AO {}³, 射线 {}, 最大距离 {}, 强度 {}",
                aoResolution, settings.getRayCount(), settings.getMaxDistance(), settings.getIntensity());
        return true;
    }

    private boolean ensurePipelines() {
        if (aoPipeline != null && blurPipeline != null) {
            return true;
        }
        ComputeShader aoShader = loadCompute(AO_SHADER_PATH);
        ComputeShader blurShader = loadCompute(BLUR_SHADER_PATH);
        if (aoShader == null || blurShader == null) {
            return false;
        }
        DescriptorSetLayoutDesc layoutDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1, ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.STORAGE_IMAGE, 1, ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.UNIFORM_BUFFER, 1, ShaderStage.COMPUTE)));
        layout = device.createDescriptorSetLayout(layoutDesc);
        aoPipeline = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE, aoShader.getSpirv()),
                List.of(layoutDesc), 0));
        blurPipeline = device.createComputePipeline(new ComputePipelineDesc(
                new ShaderDesc(ShaderStage.COMPUTE, blurShader.getSpirv()),
                List.of(layoutDesc), 0));
        return true;
    }

    private ComputeShader loadCompute(String path) {
        ComputeShader shader = assetManager == null ? null : assetManager.loadAsset(path, ComputeShader.class);
        if (shader == null || !shader.isValid()) {
            if (!assetWarningLogged) {
                assetWarningLogged = true;
                log.error("体素 AO compute 资产不可用（{}）：检查 builtin-asset-core/voxel 下的源码、"
                        + "伴生 .meta 与导入日志（stub 产物 = 编译失败）", path);
            }
            return null;
        }
        return shader;
    }

    private void dispatchAo(CommandBuffer cmd) {
        VoxelGrid grid = voxelizationPass.grid();
        if (!pendingAo || grid == null || aoSet == null) {
            return;
        }
        pendingAo = false;
        cmd.bindDescriptorSet(0, aoSet, 0, null);
        int groups = groupsOf(grid.aoResolution());
        cmd.dispatch(groups, groups, groups);
    }

    private void dispatchBlur(CommandBuffer cmd) {
        VoxelGrid grid = voxelizationPass.grid();
        if (!pendingBlur || grid == null || blurSet == null) {
            return;
        }
        pendingBlur = false;
        debugReadbackPending = true;
        cmd.bindDescriptorSet(0, blurSet, 0, null);
        int groups = groupsOf(grid.aoResolution());
        cmd.dispatch(groups, groups, groups);
    }

    private static int groupsOf(int aoResolution) {
        return (aoResolution + LOCAL_SIZE - 1) / LOCAL_SIZE;
    }

    private void retireSets() {
        if (aoSet != null) {
            renderingSystem.retireResource(aoSet::dispose);
            aoSet = null;
        }
        if (blurSet != null) {
            renderingSystem.retireResource(blurSet::dispose);
            blurSet = null;
        }
    }

    /** 关开关时的资源拆卸：描述符集退役 + 记账复位（下次打开走一次完整重建） */
    private void releaseSets() {
        retireSets();
        boundGrid = null;
        boundStamp = -1;
        boundAoResolution = -1;
        boundRayCount = -1;
        boundMaxDistance = Float.NaN;
        boundIntensity = Float.NaN;
        pendingAo = false;
        pendingBlur = false;
    }

    /** 释放 GPU 资源（须 device waitIdle 后；直接销毁，不走 retire 两帧延迟） */
    public void close() {
        if (aoSet != null) {
            aoSet.dispose();
            aoSet = null;
        }
        if (blurSet != null) {
            blurSet.dispose();
            blurSet = null;
        }
        boundGrid = null;
        if (aoPipeline != null) {
            aoPipeline.dispose();
            aoPipeline = null;
        }
        if (blurPipeline != null) {
            blurPipeline.dispose();
            blurPipeline = null;
        }
        if (layout != null) {
            layout.dispose();
            layout = null;
        }
    }
}
