package com.javelin.editor.appstate;

import com.javelin.core.JavelinEngine;
import com.javelin.core.appstate.AbstractAppState;
import com.javelin.core.appstate.AppStateManager;
import com.javelin.editor.JavelinEditor;

/**
 * 编辑器应用状态基类 —— 在 {@link AbstractAppState} 之上把引擎类型收窄为
 * {@link JavelinEditor}，编辑器控制状态（Gizmo、UI 状态机、视图切换等）继承本类
 * 可直接访问编辑器 API（metaManager/assetManager 等）。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public abstract class AbsEditorAppState extends AbstractAppState {

    protected JavelinEditor javelinEditor;

    @Override
    public void onAttach(AppStateManager stateManager, JavelinEngine engine) {
        super.onAttach(stateManager, engine);
        this.javelinEditor = (JavelinEditor) engine;
    }
}
