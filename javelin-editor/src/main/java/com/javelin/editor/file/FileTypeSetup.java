package com.javelin.editor.file;

import com.javelin.editor.plugin.PluginContext;

/**
 * 文件类型集中显式注册（对齐菜单/面板域先例，不做注解扫描；幂等）。接线在
 * EditorCorePlugin.setup（插件装载期，早于面板首帧绘制与菜单条安装——图标
 * 裁决即查注册表，project.open 命令 enabled 求值也查表）。
 *
 * <p>首批注册（2026-10-18 拍板）：.scene（图标 + 打开器）、.java/.json
 * （纯图标）；.mat 随材质导入管线批次注册（2026-10-19，纯图标——材质编辑
 * 面板后置，双击暂无动作）；.shader 随创建命令批次注册（2026-10-25，纯图标）；
 * .png/.jpg/.jpeg 随贴图导入管线批次注册（2026-10-25，纯图标）；
 * .texture 随创建纹理菜单批次注册（2026-10-25，纯图标——TextureImporter 自动扫描注册）；
 * .smesh 等要用时再补注册。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public final class FileTypeSetup {

    private static boolean setup;

    private FileTypeSetup() {
    }

    /**
     * 注册文件类型（幂等，经插件上下文收口）。
     *
     * @param context 插件上下文
     */
    public static void setup(PluginContext context) {
        if (setup) {
            return;
        }
        setup = true;
        context.registerFileType(new SceneFileType());
        context.registerFileType(new IconFileType("class", ".java"));
        context.registerFileType(new IconFileType("json", ".json"));
        context.registerFileType(new IconFileType("material", ".mat"));
        context.registerFileType(new IconFileType("shader", ".shader"));
        context.registerFileType(new IconFileType("image", ".png", ".jpg", ".jpeg"));
        context.registerFileType(new IconFileType("texture", ".texture"));
    }
}
