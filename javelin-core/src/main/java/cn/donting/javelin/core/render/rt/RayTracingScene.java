package cn.donting.javelin.core.render.rt;

import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.Tlas;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.TlasInstance;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 光追场景（P4-06 B2b）：把当前帧可见 {@link RenderObject} 装配成 TLAS。
 *
 * <p>每个对象首次出现时惰性建 BLAS（缓存在 {@link Mesh} 上，网格共享的实例只建一次）；
 * 对象 → 实例按身份表复用 {@link TlasInstance}（每帧只改世界变换）。自定义索引在首次
 * 分配后保持不变，{@link #objectAt(int)} 供 B3 的求交结果反查对象。</p>
 *
 * <p><b>调用时机</b>：{@link #build} 需在渲染录制期、上一帧已 fence 等之后调用（实例缓冲
 * 单份不双缓冲）；B3 接入 RenderGraph Pass 后由该 Pass 每帧调用。</p>
 */
public final class RayTracingScene {

    private static final Logger LOGGER = LoggerFactory.getLogger(RayTracingScene.class);
    private static final int MIN_CAPACITY = 16;

    private final Device device;
    private final Map<RenderObject, TlasInstance> slots = new IdentityHashMap<>();
    private final List<TlasInstance> instances = new ArrayList<>();
    private final List<RenderObject> indexTable = new ArrayList<>();
    private Tlas tlas;
    private DescriptorSet tlasSet;
    private DescriptorSetLayout tlasSetLayout;
    /** 描述符集缓存的 TLAS 身份（TLAS 换代即重建） */
    private Tlas setTlas;

    public RayTracingScene(Device device) {
        if (device == null || !device.rayQueryEnabled()) {
            throw new IllegalStateException("设备无光追能力，不能建光追场景");
        }
        this.device = device;
    }

    /**
     * 实例掩码口径（2026-11-28 拍板，与 CSM 施法者口径对齐）：
     * 位 0 = 参与光传输遮挡（不透明且 castShadow——阴影/GI 射线只打这类）；
     * 位 1 = 反射可见（透明物、castShadow=false 的不透明物——只进反射，不挡光）。
     * 透明物不挡光的依据同 CSM（透明不投影，Unity 口径）；反射看得见透明物（玻璃）。
     */
    public static final int MASK_LIGHT_OCCLUDER = 0x01;
    public static final int MASK_REFLECT_VISIBLE = 0x02;
    /** 反射/调试射线 cull mask（两位全要） */
    public static final int MASK_ALL = MASK_LIGHT_OCCLUDER | MASK_REFLECT_VISIBLE;

    /**
     * 重建 TLAS。
     *
     * @param cmd       录制态命令缓冲
     * @param renderers 本帧候选对象（未上传/无索引三角形/无包围盒的会被跳过）
     * @return 实际写入的实例数
     */
    public int build(CommandBuffer cmd, List<RenderObject> renderers) {
        instances.clear();
        for (RenderObject object : renderers) {
            Mesh mesh = object.mesh();
            if (mesh == null || !mesh.isUploaded() || !mesh.hasTriangles()) {
                continue;
            }
            TlasInstance instance = slots.get(object);
            if (instance == null) {
                // BLAS 缓存在 Mesh 上（共享网格只建一次）
                instance = new TlasInstance(mesh.blas(device));
                instance.customIndex(indexTable.size());
                slots.put(object, instance);
                indexTable.add(object);
            }
            instance.transform(object.world());
            instance.mask(!object.isTransparent() && object.isCastShadow()
                    ? MASK_LIGHT_OCCLUDER : MASK_REFLECT_VISIBLE);
            instances.add(instance);
        }
        ensureCapacity(instances.size());
        tlas.build(cmd, instances);
        return instances.size();
    }


    private void ensureCapacity(int count) {
        if (tlas != null && count <= tlas.capacity()) {
            return;
        }
        int capacity = Math.max(MIN_CAPACITY, Integer.highestOneBit(Math.max(count, 1)) * 2);
        if (tlas != null) {
            tlas.dispose();
        }
        tlas = device.createTlas(capacity);
        LOGGER.info("光追场景 TLAS 重建容量: {}（本帧实例 {}）", capacity, count);
    }

    /** 当前 TLAS（未 build 过为 null） */
    public Tlas tlas() {
        return tlas;
    }

    /**
     * TLAS 的 compute 侧描述符集（set0 binding0 = 加速结构，P4-06 B3）。
     *
     * <p>TLAS 换代（容量扩张）时自动重建集并重指；调用方须在 {@link #build} 之后取，
     * 否则还没有可绑的加速结构。</p>
     */
    public DescriptorSet tlasDescriptorSet() {
        if (tlas == null) {
            throw new IllegalStateException("TLAS 尚未构建，不能取加速结构描述符集");
        }
        if (tlasSet != null && setTlas == tlas) {
            return tlasSet;
        }
        if (tlasSetLayout == null) {
            tlasSetLayout = device.createDescriptorSetLayout(new DescriptorSetLayoutDesc(List.of(
                    new DescriptorSetLayoutDesc.Binding(0, DescriptorType.ACCELERATION_STRUCTURE, 1,
                            ShaderStage.COMPUTE))));
        }
        if (tlasSet != null) {
            tlasSet.dispose();
        }
        tlasSet = device.createDescriptorSet(tlasSetLayout);
        tlasSet.update(DescriptorUpdate.accelerationStructure(0, tlas));
        setTlas = tlas;
        return tlasSet;
    }

    /** 本帧实例数 */
    public int instanceCount() {
        return instances.size();
    }

    /** 自定义索引反查对象（B3 求交结果映射用；越界返回 null） */
    public RenderObject objectAt(int customIndex) {
        if (customIndex < 0 || customIndex >= indexTable.size()) {
            return null;
        }
        return indexTable.get(customIndex);
    }

    /** 释放 TLAS 与其描述符集（Mesh 上的 BLAS 归 Mesh 自己管） */
    public void dispose() {
        if (tlas != null) {
            tlas.dispose();
            tlas = null;
        }
        if (tlasSet != null) {
            tlasSet.dispose();
            tlasSet = null;
        }
        if (tlasSetLayout != null) {
            tlasSetLayout.dispose();
            tlasSetLayout = null;
        }
        setTlas = null;
        slots.clear();
        instances.clear();
        indexTable.clear();
    }
}
