package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsHeader;
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

    public CubemapImage getSkybox() {
        return skybox;
    }

    public void setSkybox(CubemapImage skybox) {
        this.skybox = skybox;
    }

    /**
     * 序列化数据面：skybox 走框架 @guid 分流（cubemap 资产引用；enabled 在基类）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("skybox", skybox);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        skybox = input.readSerializable("skybox", CubemapImage.class);
    }
}
