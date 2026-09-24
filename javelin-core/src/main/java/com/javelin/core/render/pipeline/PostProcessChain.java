package com.javelin.core.render.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 后处理链（Engine_RenderPipeline_Design.md 4.6）：链式可插拔，Pass 细节占位（v0.1）。
 *
 * <p>链首 = 场景颜色（本视口终点 Pass 输出），链尾 = Composite 输入。
 * v0.1 恒为空链 = 直通：管线终点 Pass 直接写目标，无中间 Pass。
 * 节点接口（Bloom/Tonemap 等）在落地后处理时定义，避免提前 speculative 抽象。
 */
public final class PostProcessChain {

    /** 已注册节点名（占位：v0.1 仅维护名单，链空则直通） */
    private final List<String> nodeNames = new ArrayList<>();

    public void addLast(String nodeName) {
        if (nodeName == null || nodeName.isEmpty()) {
            throw new IllegalArgumentException("nodeName must not be empty");
        }
        nodeNames.add(nodeName);
    }

    public boolean remove(String nodeName) {
        return nodeNames.remove(nodeName);
    }

    public boolean isEmpty() {
        return nodeNames.isEmpty();
    }

    public List<String> nodeNames() {
        return Collections.unmodifiableList(nodeNames);
    }
}
