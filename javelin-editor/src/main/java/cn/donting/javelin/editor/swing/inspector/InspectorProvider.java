package cn.donting.javelin.editor.swing.inspector;

import cn.donting.javelin.editor.plugin.PluginContext;

import java.util.List;

/**
 * 检查器 provider：一类选中目标一个实现（对齐 MenuCommand「类实现接口」先例），
 * 注册进 {@link InspectorRegistry}（经 {@link PluginContext}
 * 收口）。create 于 EDT 调用，后续生命周期契约见 {@link InspectorContent}。
 *
 * @author javelin
 * @create 2026/10/21
 */
public interface InspectorProvider {

    /** 节点选中接管键（node 分轨，非扩展名形态） */
    String NODE_KEY = "node";

    /**
     * 接管键：Node 选中 = {@link #NODE_KEY}；文件选中 = 扩展名（含点号小写，
     * 对齐 FileTypeRegistry 口径，如 {@code [".mat"]}）。
     */
    List<String> keys();

    /**
     * 创建内容件（EDT 调用；重 IO 由内容件自管线程）。
     */
    InspectorContent create();
}
