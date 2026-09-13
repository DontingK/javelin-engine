package com.javelin.core.settings;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;
import com.javelin.core.serialize.Serializable;

/**
 * 游戏运行时设置。
 *
 * <p>存储游戏运行时的配置，如默认启动场景。</p>
 *
 * @author javelin
 * @since 1.0
 */
@Reflective
public class GameSettings implements Serializable {

    /**
     * 默认启动场景路径（相对于 Assets 目录）。
     */
    private String defaultSceneGuid;

    public GameSettings() {
    }


    public String getDefaultSceneGuid() {
        return defaultSceneGuid;
    }

    public void setDefaultSceneGuid(String defaultSceneGuid) {
        this.defaultSceneGuid = defaultSceneGuid;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("defaultSceneGuid", defaultSceneGuid);
    }

    @Override
    public void read(InputCapsule input) {
        this.defaultSceneGuid = input.readString("defaultSceneGuid");
    }
}
