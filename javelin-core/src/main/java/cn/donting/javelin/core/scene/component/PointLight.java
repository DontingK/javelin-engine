package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsHeader;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;

/**
 * 点光源（Engine_Lighting_Design §4 灯族子类）：各向同性，位置 = 节点世界位置；
 * 物理 inverse-square 衰减到 range 平滑归零；按 range 包围球视锥剔除后进
 * lightList SSBO。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
@InsHeader(label = "ins.light.point", icon = "light-point")
public class PointLight extends Light {

    /** 衰减半径（世界单位） */
    @InsField(label = "ins.light.range")
    private float range = 10.0f;

    @Override
    public LightType getType() {
        return LightType.POINT;
    }

    public float getRange() {
        return range;
    }

    public void setRange(float range) {
        this.range = range;
    }

    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("range", range);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        range = input.readFloat("range");
    }
}
