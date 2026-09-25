package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.core.render.texture.Texture2D;

import java.util.HashMap;
import java.util.Map;

/**
 * 材质实例（Shader 的实例，RHI 20.2 节）：参数值 + 贴图槽。
 *
 * <p>纯数据、device 无关——set 2 布局 / material UBO / 描述符集由渲染侧
 * 懒创建（拍板 2026-09-25）。参数写入经 {@code findProperty} 校验名称与类型；
 * 未赋值的贴图槽由渲染侧按占位贴图约定（white / bump / black）解析。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Material extends AbsAsset {

    private Shader shader;

    // 非 final：序列化反序列化需回写（AGENTS.md 红线 9；.mat 格式 P2 待定）
    /** 数值参数（COLOR/VECTOR 存 4 元，FLOAT/RANGE 存 1 元），key = 属性名 */
    private Map<String, float[]> values = new HashMap<>();
    /** 贴图槽（引擎纹理资产，CPU 数据 + 采样参数），key = 属性名；value null 表示用占位贴图 */
    private Map<String, Texture2D> textures = new HashMap<>();

    public Material() {
    }

    public Material(Shader shader) {
        setShader(shader);
    }

    public Shader getShader() {
        return shader;
    }

    /**
     * 绑定模板并重置参数为默认值（材质实例与 Shader 一对一）。
     */
    public void setShader(Shader shader) {
        this.shader = shader;
        values.clear();
        textures.clear();
        if (shader != null) {
            for (MaterialProperty property : shader.getProperties()) {
                if (property.getDefaultValues() != null) {
                    values.put(property.getName(), property.getDefaultValues().clone());
                } else {
                    textures.put(property.getName(), null);
                }
            }
        }
    }

    /**
     * Float / Range 参数写入。
     */
    public Material setFloat(String name, float value) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.FLOAT, MaterialPropertyType.RANGE);
        values.put(name, new float[]{value});
        return this;
    }

    /**
     * Float / Range 参数读取。
     */
    public float getFloat(String name) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.FLOAT, MaterialPropertyType.RANGE);
        return values.get(name)[0];
    }

    /**
     * Color / Vector 参数写入（rgba 四元）。
     */
    public Material setColor(String name, float r, float g, float b, float a) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.COLOR, MaterialPropertyType.VECTOR);
        values.put(name, new float[]{r, g, b, a});
        return this;
    }

    /**
     * Color / Vector 参数读取（返回拷贝，修改不影响材质）。
     */
    public float[] getColor(String name) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.COLOR, MaterialPropertyType.VECTOR);
        return values.get(name).clone();
    }

    /**
     * 2D 贴图槽写入（null = 渲染侧按占位贴图约定解析）。
     */
    public Material setTexture(String name, Texture2D texture) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.TEXTURE_2D);
        textures.put(name, texture);
        return this;
    }

    /**
     * 2D 贴图槽读取（null = 未赋值，用占位贴图）。
     */
    public Texture2D getTexture(String name) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.TEXTURE_2D);
        return textures.get(name);
    }

    private MaterialProperty requireProperty(String name) {
        if (shader == null) {
            throw new IllegalStateException("材质未绑定 Shader，先调用 setShader");
        }
        MaterialProperty property = shader.findProperty(name);
        if (property == null) {
            throw new IllegalArgumentException(
                    "Shader[" + shader.getShaderName() + "] 未声明属性: " + name);
        }
        return property;
    }

    private static void requireType(MaterialProperty property, MaterialPropertyType... allowed) {
        for (MaterialPropertyType type : allowed) {
            if (property.getType() == type) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "属性 " + property.getName() + " 类型为 " + property.getType() + "，不支持该操作");
    }
}
