package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.editor.plugin.PluginContext;

/**
 * 检查器 provider 集中显式注册（对齐菜单/面板/文件类型域先例，不做注解扫描；
 * 幂等）。接线在 EditorCorePlugin.setup——注册期早于面板创建与首次选中分发。
 *
 * <p>首批：node（节点组件清单，M4 面板逻辑平移）+ .mat（材质只读，
 * 2026-10-21）+ .shader（着色器只读诊断台，2026-10-25）+ .texture（纹理
 * 可编辑面板，2026-10-25）+ .scene（场景分组 + 环境设置组件节，2026-11-03
 * 落地、2026-11-08 改组件化渲染）。</p>
 *
 * @author javelin
 * @create 2026/10/21
 */
public final class InspectorSetup {

    private static boolean setup;

    private InspectorSetup() {
    }

    /**
     * 注册检查器 provider（幂等，经插件上下文收口）。
     *
     * @param context 插件上下文
     */
    public static void setup(PluginContext context) {
        if (setup) {
            return;
        }
        setup = true;
        context.registerInspector(new NodeInspectorProvider());
        context.registerInspector(new MaterialInspectorProvider());
        context.registerInspector(new ShaderInspectorProvider());
        context.registerInspector(new TextureInspectorProvider());
        context.registerInspector(new SceneInspectorProvider());
    }
}
