package com.javelin.core.scene.component;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.scene.Component;


@Reflective
public class Camera extends Component {

    /**
     * 投影类型
     */
    public enum ProjectionType {
        PERSPECTIVE,
        ORTHOGRAPHIC
    }

}
