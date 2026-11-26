package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFieldGroup;
import cn.donting.javelin.core.inspector.InsHeader;
import cn.donting.javelin.core.render.AmbientMode;
import cn.donting.javelin.core.render.texture.CubemapImage;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.scene.component.annotation.UniqueComponent;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;

/**
 * 环境设置组件（2026-11-08）：场景级环境参数的单一归属。
 *
 * <p>挂在节点上的普通组件（编辑器经层级右键菜单创建，创建后选中节点即在节点检查器
 * 编辑），额外带 {@code @UniqueComponent(scene = true)} —— 全场景至多一个实例，
 * 重复添加即抛（{@link cn.donting.javelin.core.scene.GameScene} 校验）。</p>
 *
 * <p>v0.1 只装场景级天空盒（原 {@code GameScene.skybox} 字段迁入——退出场景级散字段）。
 * 四期环境类参数（后处理/环境光/雾）随该期各批增量加字段，不预置未生效占位。消费方：
 * SceneRenderBridge 每帧读 skybox 推 RenderingSystem（无环境组件/无 skybox = 视口回退
 * 纯色清屏，正常域空状态）。</p>
 *
 * <p>v0.2（四期 P4-04 步骤一）：加环境光模式 {@link #ambientMode}——{@code OFF}（纯黑，
 * 与三期基线一致）/ {@code CONSTANT}（颜色 + 强度常量环境项）；同样由 SceneRenderBridge
 * 每帧推送（无环境组件或未激活 = OFF）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
@InsHeader(label = "ins.environment", icon = "environment")
@UniqueComponent(scene = true)
public class Environment extends Component {

    /** 场景级天空盒（多视口共享；null = 未挂，SKYBOX 视口回退纯色清屏） */
    @InsField(label = "ins.environment.skybox")
    private CubemapImage skybox;

    /**
     * 环境光模式（四期 P4-04 步骤一）：选「直接环境光」时下方内联展开
     * {@link #ambientSetting}（颜色 + 强度）；选「探针 GI」时不展开（参数在项目设置
     * 图形页的「探针 GI（DDGI）」组）。
     */
    @InsField(label = "ins.environment.ambientMode")
    @InsFieldGroup(keys = {"OFF", "CONSTANT", "DDGI"}, value = {"", "ambientSetting", ""},
            label = "ins.environment.ambientSetting")
    private AmbientMode ambientMode = AmbientMode.OFF;

    /** 常量环境光参数组（不受 @InsField 闸门：只作为 ambientMode 的条件子组内联出现） */
    private AmbientSetting ambientSetting = new AmbientSetting();

    /**
     * 光追三档（P4-06 B5/B7/B9）。百分比语义：0 = 关、100 = 全替换、中间值与旧路按比例混合。
     *
     * <p><b>为什么默认全关</b>：光追是显式开关，不做「设备支持就默认开」。默认开会在空 TLAS /
     * 资产未重导等异常路径上把主光与环境项直接乘成 0（实测全黑且与环境光模式无关），排障时
     * 也说不清「到底开没开」。检查器里就地改，值变才由 {@code SceneRenderBridge} 下传。</p>
     *
     * <p>只有「混合管线（有 G-Buffer）+ 设备启用 ray query」时才有意义；Forward 档下光追 Pass
     * 不注册，这三档恒无效。</p>
     */
    @InsField(label = "ins.environment.rtShadow")
    private int rtShadowPercent = 0;

    /** 光追漫反射 GI 混合档（B7）：0 = 环境项走常量/DDGI，100 = 光照结果全由 1-bounce GI 供给 */
    @InsField(label = "ins.environment.rtgi")
    private int rtgiPercent = 0;

    /** 光追镜面反射档（B9）：0 = 无环境镜面，100 = 全量（只对 PBR 材质生效） */
    @InsField(label = "ins.environment.rtReflection")
    private int rtReflectionPercent = 0;

    public CubemapImage getSkybox() {
        return skybox;
    }

    public void setSkybox(CubemapImage skybox) {
        this.skybox = skybox;
    }

    public AmbientMode getAmbientMode() {
        return ambientMode;
    }

    public void setAmbientMode(AmbientMode ambientMode) {
        this.ambientMode = ambientMode != null ? ambientMode : AmbientMode.OFF;
    }

    /**
     * 常量环境光参数组（颜色 + 强度；Inspector 内经 {@code @InsFieldGroup} 条件展开）
     */
    public AmbientSetting getAmbientSetting() {
        return ambientSetting;
    }

    public void setAmbientSetting(AmbientSetting ambientSetting) {
        this.ambientSetting = ambientSetting != null ? ambientSetting : new AmbientSetting();
    }

    public int getRtShadowPercent() {
        return rtShadowPercent;
    }

    public void setRtShadowPercent(int percent) {
        this.rtShadowPercent = clampPercent(percent);
    }

    public int getRtgiPercent() {
        return rtgiPercent;
    }

    public void setRtgiPercent(int percent) {
        this.rtgiPercent = clampPercent(percent);
    }

    public int getRtReflectionPercent() {
        return rtReflectionPercent;
    }

    public void setRtReflectionPercent(int percent) {
        this.rtReflectionPercent = clampPercent(percent);
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    /**
     * 序列化数据面：skybox 走框架 @guid 分流（cubemap 资产引用；enabled 在基类）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("skybox", skybox);
        output.write("ambientMode", ambientMode);
        output.write("ambientSetting", ambientSetting);
        output.write("rtShadowPercent", rtShadowPercent);
        output.write("rtgiPercent", rtgiPercent);
        output.write("rtReflectionPercent", rtReflectionPercent);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        skybox = input.readSerializable("skybox", CubemapImage.class);
        // 缺键保字段初值（旧场景无环境光字段 = 关闭，与三期基线一致）
        ambientMode = input.readEnum("ambientMode", AmbientMode.class, AmbientMode.OFF);
        AmbientSetting group = input.readSerializable("ambientSetting", AmbientSetting.class);
        if (group != null) {
            ambientSetting = group;
        }
        // 旧场景无光追键 = 全关（光追是显式开关，不做隐式默认开）
        rtShadowPercent = clampPercent(input.readInt("rtShadowPercent", 0));
        rtgiPercent = clampPercent(input.readInt("rtgiPercent", 0));
        rtReflectionPercent = clampPercent(input.readInt("rtReflectionPercent", 0));
    }
}
