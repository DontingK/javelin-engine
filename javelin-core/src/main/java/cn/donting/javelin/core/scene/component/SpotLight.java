package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsHeader;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;

/**
 * 聚光灯（Engine_Lighting_Design §4 灯族子类）：点光衰减 + 方向锥（锥内 →
 * 锥外平滑带），方向 = 节点朝向（局部 +Z 世界像）。着色分支归批次③后半，
 * 收集侧已支持（进 lightList，w=类型 1）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
@InsHeader(label = "ins.light.spot", icon = "light-spot")
public class SpotLight extends Light {

    /** 衰减半径（世界单位） */
    @InsField(label = "ins.light.range")
    private float range = 10.0f;
    /** 锥角（度；inner < outer，锥内 → 锥外平滑带） */
    @InsField(label = "ins.light.innerAngle")
    private float innerAngle = 30.0f;
    @InsField(label = "ins.light.outerAngle")
    private float outerAngle = 45.0f;

    @Override
    public LightType getType() {
        return LightType.SPOT;
    }

    public float getRange() {
        return range;
    }

    public void setRange(float range) {
        this.range = range;
    }

    public float getInnerAngle() {
        return innerAngle;
    }

    public void setInnerAngle(float innerAngle) {
        this.innerAngle = innerAngle;
    }

    public float getOuterAngle() {
        return outerAngle;
    }

    public void setOuterAngle(float outerAngle) {
        this.outerAngle = outerAngle;
    }

    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("range", range);
        output.write("innerAngle", innerAngle);
        output.write("outerAngle", outerAngle);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        range = input.readFloat("range");
        innerAngle = input.readFloat("innerAngle");
        outerAngle = input.readFloat("outerAngle");
    }
}
