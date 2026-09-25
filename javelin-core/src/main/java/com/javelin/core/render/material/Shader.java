package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Shader extends AbsAsset {

    // 非 final：序列化反序列化需回写（write/read 在 AbsAsset 上扩展，格式 P2 待定）；
    // 只读靠 API 面保证——无公开修改方法，构建只能走 Builder
    private String shaderName;
    private List<MaterialProperty> properties;
    private Map<String, ShaderPass> passes;

    private Shader(String shaderName, List<MaterialProperty> properties, Map<String, ShaderPass> passes) {
        this.shaderName = shaderName;
        this.properties = properties;
        this.passes = passes;
    }
    public Shader(){

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
     * 材质参数接口（Properties 元数据，只读）。
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
        return List.copyOf(passes.values());
    }

    /**
     * 按渲染阶段名取 Pass，没有返回 null。
     */
    public ShaderPass pass(String passName) {
        return passes.get(passName);
    }

    /**
     * Shader 构建器（仅预编译 / 资产加载侧使用；build 后 Shader 只读）。
     */
    public static final class Builder {

        private final String shaderName;
        private final List<MaterialProperty> properties = new ArrayList<>();
        private final Map<String, ShaderPass> passes = new LinkedHashMap<>();

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
            if (passes.putIfAbsent(pass.getName(), pass) != null) {
                throw new IllegalArgumentException("重复 Pass: " + pass.getName());
            }
            return this;
        }

        /**
         * 构建不可变 Shader 实例。
         */
        public Shader build() {
            return new Shader(shaderName,
                    Collections.unmodifiableList(new ArrayList<>(properties)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(passes)));
        }
    }
}
