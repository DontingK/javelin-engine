package cn.donting.javelin.editor.asset.reload;

import cn.donting.javelin.core.obj.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 编辑器侧对象注册表：经 {@link ObjectId#setRegistrar} 装入，收拢所有存活的 ObjectId
 * 实例（Node/Component/Asset 及持 Asset 引用的数据类），供资产热重载做全量扫描——
 * 覆盖范围不再依赖 GameScene 可达性。
 * 弱引用不阻 GC；清理挂在 snapshot 入口（旧 core ObjectCache 因 update() 无人调用而退役，教训在案）。
 * 仅编辑器进程安装，不进 AOT 镜像。
 */
public final class EditorObjectRegistry implements Consumer<ObjectId> {

    private static final Logger log = LoggerFactory.getLogger(EditorObjectRegistry.class);

    private static final EditorObjectRegistry INSTANCE = new EditorObjectRegistry();

    private static volatile boolean installed;

    /** id → 弱引用；id 单调递增不复用，可安全作键 */
    private final ConcurrentHashMap<Integer, Ref> refs = new ConcurrentHashMap<>();
    private final ReferenceQueue<ObjectId> refQueue = new ReferenceQueue<>();

    private EditorObjectRegistry() {
    }

    /** 装上注册表（编辑器启动调用，幂等）；同时注册 JVM 关闭钩子负责卸下。 */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        ObjectId.setRegistrar(INSTANCE);
        Runtime.getRuntime().addShutdownHook(
                new Thread(EditorObjectRegistry::uninstall, "editor-object-registry"));
        log.info("编辑器对象注册表已安装");
    }

    /** 卸下注册表并清空（关闭钩子/退出调用）。 */
    public static void uninstall() {
        if (!installed) {
            return;
        }
        installed = false;
        ObjectId.setRegistrar(null);
        INSTANCE.refs.clear();
        log.info("编辑器对象注册表已卸下");
    }

    /** 存活对象快照：先清理已死条目再逐个解引用；并发构造的对象本轮不进（下轮可见）。 */
    public static List<ObjectId> snapshot() {
        return INSTANCE.snapshotInternal();
    }

    /** 当前条目数（含已死未清理），供诊断。 */
    public static int size() {
        return INSTANCE.refs.size();
    }

    /** 构造回调（任意线程）：登记弱引用，不持强引用。 */
    @Override
    public void accept(ObjectId objectId) {
        if (objectId == null) {
            return;
        }
        refs.put(objectId.id, new Ref(objectId, refQueue));
    }

    private List<ObjectId> snapshotInternal() {
        purge();
        ArrayList<ObjectId> out = new ArrayList<>(refs.size());
        for (Ref ref : refs.values()) {
            ObjectId object = ref.get();
            if (object != null) {
                out.add(object);
            }
        }
        return out;
    }

    /** 回收已 GC 条目（snapshot 入口调用，保证清理节奏不依赖外部）。 */
    private void purge() {
        Reference<? extends ObjectId> poll;
        while ((poll = refQueue.poll()) != null) {
            if (poll instanceof Ref ref) {
                refs.remove(ref.id);
            }
        }
    }

    /** 弱引用携带 id，GC 后据此摘除条目。 */
    private static final class Ref extends WeakReference<ObjectId> {

        private final int id;

        Ref(ObjectId referent, ReferenceQueue<ObjectId> queue) {
            super(referent, queue);
            this.id = referent.id;
        }
    }
}
