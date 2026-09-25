package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.render.Renderable;
import com.javelin.core.scene.Component;

/**
 * 网格渲染组件（合并设计，无 MeshFilter）。
 *
 * <p>几何共享靠资产引用（同一 Mesh 对象被多个 MeshRenderer 复用），
 * 渲染差异靠材质实例（每 renderer 独立），见场景设计文档 5.1 节。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
@Reflective
public class MeshRenderer extends Component implements Renderable {

    private boolean castShadow = true;
    private boolean receiveShadow = true;

    // Mesh / Material 字段在资产管线接入后补上（Engine_Assets_Design + RHI 20 节）

    public boolean isCastShadow() {
        return castShadow;
    }

    public void setCastShadow(boolean castShadow) {
        this.castShadow = castShadow;
    }

    public boolean isReceiveShadow() {
        return receiveShadow;
    }

    public void setReceiveShadow(boolean receiveShadow) {
        this.receiveShadow = receiveShadow;
    }
}
