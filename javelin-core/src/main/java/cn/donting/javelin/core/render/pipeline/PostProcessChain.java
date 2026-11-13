package cn.donting.javelin.core.render.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 后处理链（Engine_PostProcess_Design §3.4）：有序节点表，链空 = 直通。
 *
 * <p>链非空即 HDR 生效（场景 Pass 写 lightingRT，链尾节点写显示终点）；链空时零行为变化。
 * 链组成变化 = 图缓存 structureHash 正常失效（RHI 18.5）。</p>
 */
public final class PostProcessChain {

    private final List<PostProcessNode> nodes = new ArrayList<>();

    /** 尾部追加节点（由组合根 {@code PostProcessSetup} 增删；链序按节点 {@code isTerminal()} 重排） */
    public void addLast(PostProcessNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        nodes.add(node);
    }

    public boolean remove(PostProcessNode node) {
        return nodes.remove(node);
    }

    /** 按节点名移除（首个同名） */
    public boolean remove(String nodeName) {
        return nodes.removeIf(n -> n.name().equals(nodeName));
    }

    public void clear() {
        nodes.clear();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** 链内节点（只读，展开用；有序） */
    public List<PostProcessNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }
}
