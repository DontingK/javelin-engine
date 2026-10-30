package com.javelin.core.render.material;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.assets.AbsAsset;
import com.javelin.core.render.texture.Texture2D;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/**
 * 材质实例（Shader 的实例，RHI 20.2 节）：参数值 + 贴图槽。
 *
 * <p>纯数据、device 无关——set 2 布局 / material UBO / 描述符集由渲染侧
 * 懒创建（拍板 2026-09-25）。参数写入经 {@code findProperty} 校验名称与类型；
 * 未赋值的贴图槽由渲染侧按占位贴图约定（white / bump / black）解析。</p>
 *
 * <p>序列化（Capsule 协议，2026-10-04 落地）：Capsule 无 Map 通道——keyed
 * 参数/贴图槽存储重构为条目节点列表（{@link MaterialValueEntry} /
 * {@link MaterialTextureEntry}，name 唯一、set 同名替换新名追加、查找线性扫
 * ——属性数为个位数，同 Shader.findProperty/Mesh.findAttribute 口径；存储形态
 * = 序列化形态，同 Mesh 拍板零转换，红线 10 集合字段用序列化实体类型）。
 * write/read 字段直通、read 只回写引用（红线 10 populate 顺序约束）；shader
 * 为 Asset 引用走框架 guid 分流（guid 非空 → GUID 引用经 AssetManager 解回，
 * 编辑器主路径；guid 空 → 图内嵌、Shader 数据面完整兜底——文本通道语义，
 * 二进制侧 null guid 资产引用丢失为既有语义，见 Engine_Scene_Design §9.3）。
 * 内嵌材质（.scene 文本通道）全数据面往返即闭环于此；.mat 资产格式（材质
 * guid 化共享、Inspector 编辑载体）仍待。字段非 final（红线 9）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class Material extends AbsAsset {

    private Shader shader;
    /** 面型（透明性裁决，URP Surface Type 口径，拍板 2026-10-17 方案 B）：
     * 默认 OPAQUE；不随 setShader 重置——分桶/PSO 覆盖随材质资产走，不随模板走 */
    private SurfaceType surfaceType = SurfaceType.OPAQUE;

    // 非 final：序列化反序列化需回写（AGENTS.md 红线 9）
    /** 数值参数条目（COLOR/VECTOR 存 4 元，FLOAT/RANGE 存 1 元），key = 属性名 */
    private ArrayList<MaterialValueEntry> values = new ArrayList<>();
    /** 贴图槽条目（引擎纹理资产；texture null 表示用占位贴图） */
    private ArrayList<MaterialTextureEntry> textures = new ArrayList<>();
    /** 贴图槽版本号（setTexture/setShader 自增；渲染侧比对自动重建描述符集，不入序列化） */
    private int textureRevision;

    public Material() {
    }

    public Material(Shader shader) {
        setShader(shader);
    }

    public Shader getShader() {
        return shader;
    }

    /**
     * 绑定模板（Unity 口径）：任何换 shader 都按<b>同名同类型</b>保留原值、新属性取新 shader
     * 默认值、旧有而新无（含同名但类型变了）的属性丢弃——同 guid 换代（热重载）与换绑一视同仁。
     * 首次绑定无值可留，等于全填默认。
     */
    public void setShader(Shader shader) {
        if (shader == this.shader) {
            return;
        }
        this.shader = shader;
        textureRevision++;   // 贴图槽随新 shader 重排（删/补），渲染侧需重建描述符集
        if (shader == null) {
            values.clear();
            textures.clear();
            return;
        }
        // 只保留新 shader 里同名同类型的条目：名字没了、或名字还在但类型变了（值↔贴图）都丢弃
        values.removeIf(entry -> {
            MaterialProperty p = shader.findProperty(entry.getName());
            return p == null || p.getDefaultValues() == null;   // 无此名，或同名是贴图属性
        });
        textures.removeIf(entry -> {
            MaterialProperty p = shader.findProperty(entry.getName());
            return p == null || p.getDefaultValues() != null;   // 无此名，或同名是数值属性
        });
        // 补新属性默认值（首次绑定列表本就为空，等于全填）
        for (MaterialProperty property : shader.getProperties()) {
            String name = property.getName();
            if (property.getDefaultValues() != null) {
                if (findValue(name) == null) {
                    values.add(new MaterialValueEntry(name, property.getDefaultValues().clone()));
                }
            } else if (findTexture(name) == null) {
                textures.add(new MaterialTextureEntry(name, null));
            }
        }
    }

    /**
     * 面型读取（分桶与 PSO 覆盖的裁决源，2026-10-17 方案 B）。
     */
    public SurfaceType getSurfaceType() {
        return surfaceType;
    }

    /**
     * 面型写入：TRANSPARENT → 透明桶（由远及近）+ Blend SrcAlpha OneMinusSrcAlpha /
     * ZWrite Off（PSO 侧覆盖，Cull/ZTest/ColorMask 保留 Shader 声明）。
     */
    public Material setSurfaceType(SurfaceType surfaceType) {
        if (surfaceType == null) {
            throw new IllegalArgumentException("surfaceType 不能为 null");
        }
        this.surfaceType = surfaceType;
        return this;
    }

    /**
     * 数值参数条目只读视图（检查器等展示侧用；变更走 setFloat/setColor）。
     */
    public List<MaterialValueEntry> getValues() {
        return Collections.unmodifiableList(values);
    }

    /**
     * 贴图槽条目只读视图（检查器等展示侧用；变更走 setTexture）。
     */
    public List<MaterialTextureEntry> getTextures() {
        return Collections.unmodifiableList(textures);
    }

    /**
     * 贴图槽版本号（渲染侧每帧比对；setTexture/setShader 时自增，不一致即重建描述符集）。
     */
    public int getTextureRevision() {
        return textureRevision;
    }

    /**
     * 序列化写出：guid/lastModified 由 AbsAsset 写；surfaceType/shader/values/textures 直通
     * （红线 10——集合字段直传条目列表本体，零包装）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("surfaceType", surfaceType);
        output.write("shader", shader);
        output.write("values", values);
        output.write("textures", textures);
    }

    /**
     * 序列化读入：只回写字段引用，不解引用嵌套元素做派生（populate 顺序约束）。
     * 字段缺失（Material 序列化落地前的旧场景：材质只内嵌 guid/lastModified）回退
     * null shader + 空列表——残数据按文件损坏口径在渲染侧 RenderObject 显式
     * 报错（用户拍板 2026-10-04：不静默跳过）。
     */
    @Override
    public void read(InputCapsule input) {
        super.read(input);
        // 旧场景（surfaceType 落地前）无此字段：回退默认 OPAQUE
        SurfaceType st = input.readEnum("surfaceType", SurfaceType.class);
        if (st != null) {
            surfaceType = st;
        }
        shader = input.readSerializable("shader", Shader.class);
        ArrayList<MaterialValueEntry> valueEntries =
                input.readArrayList("values", MaterialValueEntry.class);
        values = valueEntries != null ? valueEntries : new ArrayList<>();
        ArrayList<MaterialTextureEntry> textureEntries =
                input.readArrayList("textures", MaterialTextureEntry.class);
        textures = textureEntries != null ? textureEntries : new ArrayList<>();
    }

    /**
     * Float / Range 参数写入。
     */
    public Material setFloat(String name, float value) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.FLOAT, MaterialPropertyType.RANGE);
        putValue(name, new float[]{value});
        return this;
    }

    /**
     * Float / Range 参数读取。
     */
    public float getFloat(String name) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.FLOAT, MaterialPropertyType.RANGE);
        return findValue(name).getData()[0];
    }

    /**
     * Color / Vector 参数写入（rgba 四元）。
     */
    public Material setColor(String name, float r, float g, float b, float a) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.COLOR, MaterialPropertyType.VECTOR);
        putValue(name, new float[]{r, g, b, a});
        return this;
    }

    /**
     * Color / Vector 参数读取（返回拷贝，修改不影响材质）。
     */
    public float[] getColor(String name) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.COLOR, MaterialPropertyType.VECTOR);
        return findValue(name).getData().clone();
    }

    /**
     * 2D 贴图槽写入（null = 渲染侧按占位贴图约定解析）。
     */
    public Material setTexture(String name, Texture2D texture) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.TEXTURE_2D);
        putTexture(name, texture);
        return this;
    }

    /**
     * 2D 贴图槽读取（null = 未赋值，用占位贴图）。
     */
    public Texture2D getTexture(String name) {
        MaterialProperty property = requireProperty(name);
        requireType(property, MaterialPropertyType.TEXTURE_2D);
        MaterialTextureEntry entry = findTexture(name);
        return entry != null ? entry.getTexture() : null;
    }

    // ═══ 条目存取（同名替换新名追加，同 Mesh.setAttribute 口径） ═══

    private void putValue(String name, float[] data) {
        MaterialValueEntry node = new MaterialValueEntry(name, data);
        int existing = indexOfValue(name);
        if (existing >= 0) {
            values.set(existing, node);
        } else {
            values.add(node);
        }
    }

    private void putTexture(String name, Texture2D texture) {
        MaterialTextureEntry node = new MaterialTextureEntry(name, texture);
        int existing = indexOfTexture(name);
        if (existing >= 0) {
            textures.set(existing, node);
        } else {
            textures.add(node);
        }
        textureRevision++;
    }

    private MaterialValueEntry findValue(String name) {
        for (MaterialValueEntry entry : values) {
            if (entry.getName().equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private MaterialTextureEntry findTexture(String name) {
        for (MaterialTextureEntry entry : textures) {
            if (entry.getName().equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private int indexOfValue(String name) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfTexture(String name) {
        for (int i = 0; i < textures.size(); i++) {
            if (textures.get(i).getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    // ═══ 参数校验 ═══

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
