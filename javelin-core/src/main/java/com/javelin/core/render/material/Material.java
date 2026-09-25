package com.javelin.core.render.material;

import com.javelin.core.assets.AbsAsset;

public class Material extends AbsAsset {
    private Shader shader;

    public Material(Shader shader) {
        this.shader = shader;
    }

    public Material(){

    }
}
