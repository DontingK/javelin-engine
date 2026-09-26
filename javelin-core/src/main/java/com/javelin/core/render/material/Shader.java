package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 着色器模板（.shader 源码文件编译后的结构，RHI 20.2 / 21 节）。
 *
 * <p>对齐 Unity 心智：Shader 内含多个 Pass + 材质参数接口（Properties 元数据），
 * Material 是其实例。纯数据、device 无关——由离线构建工具编译二进制资产、
 * core 运行时加载（拍板 2026-09-25：着色器源码不进 core）；
 * set 2 布局 / PSO 等 RHI 资源由渲染侧懒创建。</p>
 *
 * <p>构建期（预编译 / 资产加载侧）用 {@link #builder(String)} 装配；
 * 构建完成后只读，运行期无任何修改方法。</p>
 *
 * <p>序列化（Capsule 二进制协议，Engine_Material_Design §3，2026-09-26 落地）：
 * 集合字段类型即 {@link ArrayList}——write/read 直通引用，零包装零拷贝；
 * 反序列化约束（BinaryImporter 按 id 升序统一 populate，根对象先于嵌套对象）：
 * {@code read()} 只回写字段引用，禁止解引用嵌套元素字段做派生结构。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Shader extends AbsAsset {

    // 非 final：序列化反序列化需回写（红线 9）；只读靠 API 面保证——
    // 无公开修改方法，构建只能走 Builder
    private String shaderName;
    private ArrayList<MaterialProperty> properties = new ArrayList<>();
    /** Pass 声明序存储（序列化直通）；按名查找用线性扫描（Pass 数量为个位数） */
    private ArrayList<ShaderPass> passes = new ArrayList<>();

    private Shader(String shaderName, ArrayList<MaterialProperty> properties, ArrayList<ShaderPass> passes) {
        this.shaderName = shaderName;
        this.properties = properties;
        this.passes = passes;
    }

    public Shader() {
    }

    /**
     * 序列化写出：guid/lastModified 由 AbsAsset 写，本类补 shaderName/properties/passes
     * （passes 有序 List——声明序即布局与查找序）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("shaderName", shaderName);
        output.write("properties", properties);
        output.write("passes", passes);
    }

    /**
     * 序列化读入：只回写字段引用，不经由嵌套元素派生任何结构（populate 顺序约束）。
     */
    @Override
    public void read(InputCapsule input) {
        super.read(input);
        shaderName = input.readString("shaderName");
        ArrayList<MaterialProperty> props = input.readArrayList("properties", MaterialProperty.class);
        properties = props != null ? props : new ArrayList<>();
        ArrayList<ShaderPass> passList = input.readArrayList("passes", ShaderPass.class);
        passes = passList != null ? passList : new ArrayList<>();
    }

    /**
     * 创建构建器（离线编译产物装配 / 资产加载器用）。
     */
    public static Builder builder(String shaderName) {
        return new Builder(shaderName);
    }

    /**
     * 着色器名（.shader 顶层 {@code Shader "name"} 的 name）。
     */
    public String getShaderName() {
        return shaderName;
    }

    /**
     * 材质参数接口（Properties 元数据，只读，声明顺序即 set 2 布局顺序）。
     */
    public List<MaterialProperty> getProperties() {
        return properties;
    }

    /**
     * 按名查找材质属性，没有返回 null。
     */
    public MaterialProperty findProperty(String name) {
        for (MaterialProperty property : properties) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    /**
     * 全部 Pass（按声明顺序，只读）。
     */
    public List<ShaderPass> getPasses() {
        return List.copyOf(passes);
    }

    /**
     * 按渲染阶段名取 Pass，没有返回 null。
     */
    public ShaderPass pass(String passName) {
        for (ShaderPass pass : passes) {
            if (pass.getName().equals(passName)) {
                return pass;
            }
        }
        return null;
    }

    /**
     * v0.1 前向渲染取 Pass（Engine_RenderPipeline_Design.md 4.4 Pass 名分类）：
     * 名 "Forward" → 第一个非混合 pass → 第一个 pass。混合态决定物体进
     * 不透明/透明桶（渲染队列由引擎决定，RHI 21.1）。
     */
    public ShaderPass forwardPass() {
        ShaderPass named = pass("Forward");
        if (named != null) {
            return named;
        }
        for (ShaderPass p : passes) {
            if (!p.getState().isBlendEnabled()) {
                return p;
            }
        }
        if (passes.isEmpty()) {
            throw new IllegalStateException("Shader[" + shaderName + "] 无任何 Pass");
        }
        return passes.get(0);
    }

    /**
     * Shader 构建器（仅预编译 / 资产加载侧使用；build 后 Shader 只读）。
     */
    public static final class Builder {

        private final String shaderName;
        private final ArrayList<MaterialProperty> properties = new ArrayList<>();
        private final ArrayList<ShaderPass> passes = new ArrayList<>();
        private final Set<String> passNames = new HashSet<>();

        private Builder(String shaderName) {
            if (shaderName == null || shaderName.isBlank()) {
                throw new IllegalArgumentException("着色器名不能为空");
            }
            this.shaderName = shaderName;
        }

        /**
         * 声明材质属性（保持声明顺序，即 set 2 布局顺序）。
         */
        public Builder property(MaterialProperty property) {
            properties.add(property);
            return this;
        }

        /**
         * 添加 Pass（块名即渲染阶段名，重复即抛异常）。
         */
        public Builder pass(ShaderPass pass) {
            if (!passNames.add(pass.getName())) {
                throw new IllegalArgumentException("重复 Pass: " + pass.getName());
            }
            passes.add(pass);
            return this;
        }

        /**
         * 构建 Shader 实例（字段即 ArrayList，供序列化直通引用）。
         */
        public Shader build() {
            return new Shader(shaderName, properties, passes);
        }
    }
}
