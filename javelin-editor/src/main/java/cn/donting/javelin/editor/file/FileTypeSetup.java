package cn.donting.javelin.editor.file;

import cn.donting.javelin.editor.plugin.PluginContext;

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
 * .compute 随三期批次①注册（2026-10-31，计算着色器独立资产类型，纯图标）；
 * .vert/.frag 随三期批次④-A 注册（2026-11-01，原生 GLSL 着色器资产 SpvShader，
 * 纯图标——引擎覆盖制 Pass 装载链，shadow.spv 内嵌路线同日推翻）；
 * .hdr/.pano 随三期批次⑤步骤三注册（2026-11-03，cubemap 天空盒资产
 * CubemapImage，纯图标——equirect 导入转六面）；
 * .chars 随 DebugText 内置资源迁移注册（2026-11-07，点阵字库字符集源 →
 * BitmapFont 资产，纯图标——字库烘焙链，旧 tool CLI bin 路线同批清退）；
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
        context.registerFileType(new IconFileType("compute", ".compute"));
        context.registerFileType(new IconFileType("glsl", ".vert", ".frag"));
        context.registerFileType(new IconFileType("image", ".png", ".jpg", ".jpeg"));
        context.registerFileType(new IconFileType("texture", ".texture"));
        context.registerFileType(new IconFileType("cubemap", ".hdr", ".pano"));
        context.registerFileType(new IconFileType("chars", ".chars"));
    }
}
