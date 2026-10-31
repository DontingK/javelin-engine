package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsHeader;

/**
 * 平行光（Engine_Lighting_Design §4 灯族子类）：零附加字段——方向 = 节点朝向
 * （局部 +Z 世界像），全体不进 cluster、全局读 UBO 方向光数组。
 *
 * <p>主光 = 最强激活平行光（引擎自动裁决，Scene.getSun）；ShadowPass 只服务
 * 主光。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
@InsHeader(label = "ins.light.directional", icon = "light-directional")
public class DirectionalLight extends Light {

    @Override
    public LightType getType() {
        return LightType.DIRECTIONAL;
    }
}
