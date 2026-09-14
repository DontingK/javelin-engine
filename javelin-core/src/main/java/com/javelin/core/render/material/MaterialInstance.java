package com.javelin.core.render.material;

import com.javelin.rhi.api.*;
import com.javelin.rhi.desc.*;
import com.javelin.rhi.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 运行时材质实例 —— Material 的运行时 GPU 形态。
 *
 * <p>每个 {@link com.javelin.core.scene.component.MeshRenderer} 持有独立的
 * MaterialInstance（即使共享同一个 Material 资产），因为每个实例可以有不同
 * 的参数值（如不同颜色）。</p>
 *
 * <p>负责管理：
 * <ul>
 *   <li>参数值的 GPU 资源（UBO、纹理）</li>
 *   <li>DescriptorSet 的分配和更新</li>
 * </ul>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class MaterialInstance {

    private static final Logger log = LoggerFactory.getLogger(MaterialInstance.class);

    /** 源材质资产（只读模板） */
    private final Material material;

    /** 运行时参数值（覆盖 material 的默认值） */
    private final Map<String, Object> runtimeOverrides = new HashMap<>();

    /** UBO 缓冲区（存储材质参数） */
    private Buffer uboBuffer;

    /** 描述符集（简化版先单 Pass） */
    private DescriptorSet descriptorSet;

    /** UBO 数据是否脏（需要重新上传） */
    private boolean uboDirty = true;

    /** UBO 数据大小（字节） */
    private int uboSize = 256; // 默认 256 bytes，后续按 Shader 实际计算

    /** 描述符池（用于释放时归还） */
    private DescriptorPool descriptorPool;

    // ═══════════════════════════════════════════════════════════════════════
    //  构造
    // ═══════════════════════════════════════════════════════════════════════

    public MaterialInstance(Material material) {
        this.material = material;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  参数读写
    // ═══════════════════════════════════════════════════════════════════════

    public void setFloat(String name, float value) {
        runtimeOverrides.put(name, value);
        uboDirty = true;
    }

    public void setVector4(String name, float[] value) {
        runtimeOverrides.put(name, value);
        uboDirty = true;
    }

    public void setTexture(String name, com.javelin.rhi.api.Texture texture) {
        runtimeOverrides.put(name, texture);
        uboDirty = true;
    }

    public Object getProperty(String name) {
        Object runtime = runtimeOverrides.get(name);
        if (runtime != null) return runtime;
        return material != null ? material.getProperty(name) : null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GPU 资源构建
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 创建 UBO 缓冲区和 DescriptorSet。
     *
     * @param device       逻辑设备
     * @param pool         描述符池
     * @param pipelineLayout 管线布局（含 DescriptorSetLayout）
     */
    public void build(Device device, DescriptorPool pool, PipelineLayout pipelineLayout) {
        this.descriptorPool = pool;

        // 创建 UBO 缓冲区
        if (uboBuffer == null) {
            uboBuffer = device.createBuffer(new BufferDesc(
                uboSize,
                BufferUsage.UNIFORM_BUFFER,
                MemoryUsage.CPU_TO_GPU
            ));
        }

        // 分配 DescriptorSet
        if (descriptorSet == null && pool != null) {
            DescriptorSetLayout[] layouts = pipelineLayout.setLayouts();
            if (layouts.length > 0) {
                descriptorSet = pool.allocateSet(layouts[0]);
            }
        }

        updateDescriptorSet();
    }

    /**
     * 更新 DescriptorSet 绑定。
     */
    public void updateDescriptorSet() {
        if (descriptorSet == null || uboBuffer == null) return;

        // 绑定 UBO
        descriptorSet.writeBuffer(
            0, // binding
            uboBuffer,
            0,
            uboSize
        );

        // TODO: 绑定纹理（按 ShaderPropertyType.TEXTURE）
    }

    /**
     * 上传 UBO 数据（每帧调用）。
     */
    public void uploadUboData() {
        if (!uboDirty || uboBuffer == null) return;

        // TODO: 按 Shader 的 UBO 布局序列化数据
        // 简化版：直接 map 写数据
        ByteBuffer mapped = uboBuffer.map();
        try {
            // 按 propertyOverrides 顺序写入
            // 实际应按 Shader 的 UBO 布局精确计算偏移
            for (Map.Entry<String, Object> entry : runtimeOverrides.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Float f) {
                    mapped.putFloat(f);
                } else if (value instanceof float[] arr) {
                    for (float v : arr) {
                        mapped.putFloat(v);
                    }
                }
                // TODO: 其他类型
            }
        } finally {
            uboBuffer.unmap();
        }

        uboDirty = false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  访问器
    // ═══════════════════════════════════════════════════════════════════════

    public Material getMaterial() {
        return material;
    }

    public DescriptorSet getDescriptorSet() {
        return descriptorSet;
    }

    public Buffer getUboBuffer() {
        return uboBuffer;
    }

    /**
     * 释放 GPU 资源。
     */
    public void dispose() {
        // 归还 DescriptorSet 到池中
        if (descriptorSet != null && descriptorPool != null) {
            descriptorPool.freeSets(new DescriptorSet[]{descriptorSet});
            descriptorSet = null;
        }
        if (uboBuffer != null) {
            uboBuffer.dispose();
            uboBuffer = null;
        }
    }
}
