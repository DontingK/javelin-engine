package com.javelin.core.scene;

import com.javelin.core.render.Renderable;
import com.javelin.core.scene.component.Camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 场景 —— 管理根节点列表，驱动组件生命周期与渲染对象收集。
 *
 * <p><strong>每帧更新</strong>（{@link #update(float)}）：
 * <ol>
 *   <li>深度优先遍历激活层级，对每个处于激活状态的组件调用 onUpdate</li>
 *   <li>帧末统一处理待销毁队列（允许在 onUpdate 中销毁任意节点）</li>
 * </ol></p>
 *
 * <p><strong>渲染对接</strong>：GameScene 不直接碰 {@link com.javelin.core.render.RenderList}，
 * 只通过 {@link #collectRenderables(List)} / {@link #collectCameras(List)} 输出实现了
 * {@link Renderable} / {@link Camera} 接口的激活组件列表，由渲染管线已有的收集机制消费。
 * 相机（{@link Camera}）与可渲染对象（{@link Renderable}）列表为增量维护缓存
 * （由 {@link Node} 在组件增删/场景挂接变化时同步注册/注销），其上再维护脏标记激活快照
 * （激活状态变化经 {@link Component} 打脏，收集时按需重建）；
 * 无变化帧收集为 O(1) 且不分配，不做整树遍历。</p>
 *
 * <p>线程模型：仅主线程操作，不做内部加锁。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class GameScene implements Scene {

    private final String name;

    /** 根节点列表 */
    private final List<Node> rootNodes = new ArrayList<>();

    /** 待销毁队列（帧末统一处理） */
    private final List<Node> pendingDestroy = new ArrayList<>();

    /** 所有 Camera 组件缓存（无论是否激活），由 Node 在组件增删/场景挂接变化时增量维护 */
    private final ArrayList<Camera> allCameras = new ArrayList<>();

    /** 所有 Renderable 组件缓存（无论是否激活），由 Node 在组件增删/场景挂接变化时增量维护 */
    private final ArrayList<Renderable> allRenderables = new ArrayList<>();

    /** 激活组件快照（cacheDirty 时重建，查询直接复用） */
    private final ArrayList<Camera> activeCameras = new ArrayList<>();

    /** 激活组件快照（cacheDirty 时重建，查询直接复用） */
    private final ArrayList<Renderable> activeRenderables = new ArrayList<>();

    /** 快照是否过期（组件增删或激活状态变化时置位） */
    private boolean cacheDirty = true;

    public GameScene(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * 获取只读根节点列表视图。
     */
    public List<Node> getRootNodes() {
        return Collections.unmodifiableList(rootNodes);
    }

    /**
     * 添加根节点（节点先脱离原父级）。
     * <p>整棵子树的场景引用同步为本场景。</p>
     */
    @Override
    public void addRoot(Node node) {
        if (node == null) {
            throw new NullPointerException("node is null");
        }
        if (rootNodes.contains(node)) {
            return;
        }
        node.transform().removeFromParent();
        rootNodes.add(node);
        node.syncScene(this);
        node.onHierarchyChanged();
    }

    /**
     * 移除根节点（节点本身及其子树保留，仅脱离场景）。
     */
    public void removeRoot(Node node) {
        if (rootNodes.remove(node)) {
            node.syncScene(null);
        }
    }

    /**
     * 销毁场景内全部节点并清空（引擎关闭时调用）。
     */
    public void dispose() {
        List<Node> snapshot = new ArrayList<>(rootNodes);
        for (Node node : snapshot) {
            node.destroyNow();
        }
        rootNodes.clear();
        pendingDestroy.clear();
    }

    /**
     * 将节点加入待销毁队列（帧末统一拆除），由 {@link Node#destroy()} 调用。
     */
    void enqueueDestroy(Node node) {
        if (!pendingDestroy.contains(node)) {
            pendingDestroy.add(node);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  每帧更新
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 每帧更新：深度优先遍历激活层级调用组件 onUpdate，帧末处理待销毁队列。
     *
     * @param deltaTime 帧间隔（秒）
     */
    @Override
    public void update(float deltaTime) {
        // 根节点快照，防止遍历中的结构性修改影响迭代
        List<Node> snapshot = new ArrayList<>(rootNodes);
        for (Node root : snapshot) {
            updateNode(root, deltaTime);
        }
        processPendingDestroy();
    }

    /**
     * 深度优先更新单个节点及其子树（跳过失活节点整棵子树）。
     */
    private void updateNode(Node node, float deltaTime) {
        if (node.isDestroyed() || !node.activeSelf()) {
            return;
        }
        // 组件快照，允许 onUpdate 中移除组件
        List<Component> componentSnapshot = new ArrayList<>(node.getComponents());
        for (Component c : componentSnapshot) {
            if (c.isActivated()) {
                c.onUpdate(deltaTime);
            }
        }
        // 子节点快照，允许 onUpdate 中增删子节点
        List<Node> childSnapshot = node.children();
        for (Node child : childSnapshot) {
            updateNode(child, deltaTime);
        }
    }

    /**
     * 帧末统一拆除待销毁节点。
     */
    private void processPendingDestroy() {
        if (pendingDestroy.isEmpty()) {
            return;
        }
        for (Node node : pendingDestroy) {
            node.destroyNow();
            rootNodes.remove(node);
        }
        pendingDestroy.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  渲染收集
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 收集处于激活状态的 {@link Renderable} 组件。
     * <p>复用脏标记快照 {@link #activeRenderables}，无变化帧零过滤零分配；
     * 激活判定与 {@link #collectCameras(List)} 一致（enabled && 节点层级激活）。</p>
     *
     * @param out 输出列表（由调用方清空/复用）
     */
    @Override
    public void collectRenderables(List<Renderable> out) {
        if (out == null) {
            return;
        }
        ensureCacheFresh();
        out.addAll(activeRenderables);
    }

    /**
     * 收集处于激活状态的 {@link Camera} 组件。
     * <p>复用脏标记快照 {@link #activeCameras}，无变化帧零过滤零分配；
     * {@code isActivated()} 已等价于"组件 enabled && 节点层级激活"，
     * 与 engine4 的 filterActive 过滤语义一致。</p>
     *
     * @param out 输出列表（由调用方清空/复用）
     */
    @Override
    public void collectCameras(List<Camera> out) {
        if (out == null) {
            return;
        }
        ensureCacheFresh();
        out.addAll(activeCameras);
    }

    /**
     * 快照过期时从增量缓存重建激活组件快照（清脏）。
     */
    private void ensureCacheFresh() {
        if (!cacheDirty) {
            return;
        }
        activeCameras.clear();
        for (Camera c : allCameras) {
            if (((Component) c).isActivated()) {
                activeCameras.add(c);
            }
        }
        activeRenderables.clear();
        for (Renderable r : allRenderables) {
            if (((Component) r).isActivated()) {
                activeRenderables.add(r);
            }
        }
        cacheDirty = false;
    }

    /**
     * 标记组件缓存快照过期，由 Node/Component 侧在组件增删、激活状态变化时调用。
     */
    void markCacheDirty() {
        cacheDirty = true;
    }

    /**
     * 注册 Camera 组件到缓存（Node 附加组件/挂入场景时调用）。
     */
    void registerCamera(Camera camera) {
        if (camera != null && !allCameras.contains(camera)) {
            allCameras.add(camera);
            cacheDirty = true;
        }
    }

    /**
     * 从缓存注销 Camera 组件（Node 移除组件/脱离场景/销毁时调用）。
     */
    void unregisterCamera(Camera camera) {
        if (allCameras.remove(camera)) {
            cacheDirty = true;
        }
    }

    /**
     * 注册 Renderable 组件到缓存（Node 附加组件/挂入场景时调用）。
     */
    void registerRenderable(Renderable renderable) {
        if (renderable != null && !allRenderables.contains(renderable)) {
            allRenderables.add(renderable);
            cacheDirty = true;
        }
    }

    /**
     * 从缓存注销 Renderable 组件（Node 移除组件/脱离场景/销毁时调用）。
     */
    void unregisterRenderable(Renderable renderable) {
        if (allRenderables.remove(renderable)) {
            cacheDirty = true;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  查询
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 按名称查找节点（遍历整棵场景树，含失活节点），结果追加到 out。
     *
     * @param name 节点名称
     * @param out  输出列表
     */
    public void findNodesByName(String name, List<Node> out) {
        if (name == null || out == null) {
            return;
        }
        for (Node root : rootNodes) {
            findNode(root, name, out);
        }
    }

    private void findNode(Node node, String name, List<Node> out) {
        if (name.equals(node.getName())) {
            out.add(node);
        }
        List<Node> childSnapshot = node.children();
        for (Node child : childSnapshot) {
            findNode(child, name, out);
        }
    }
}
