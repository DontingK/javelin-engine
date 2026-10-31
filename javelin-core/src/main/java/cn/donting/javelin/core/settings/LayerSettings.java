package cn.donting.javelin.core.settings;


import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * @create: 2026/6/29
 * @Version 1.0
 **/
@Reflective
public class LayerSettings implements Serializable {
    private String[] layerNames=new String[31];

    public String[] getLayerNames() {
        return layerNames;
    }

    public void setLayerNames(String[] layerNames) {
        this.layerNames = layerNames;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("layerNames", layerNames);
    }

    @Override
    public void read(InputCapsule input) {
        layerNames = input.readStringArray("layerNames");
    }
}
