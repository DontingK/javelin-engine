package cn.donting.javelin.core.obj;


import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 对象唯一标识基类
 * 
 * <p>为引擎中的所有对象提供全局唯一的整数ID。使用原子整数生成器保证线程安全。</p>
 * 
 * <p>所有需要唯一标识的类应继承此类：</p>
 * <pre>
 * public class MyObject extends ObjectId {
 *     // 自动获得唯一ID
 * }
 * </pre>
 * 
 * @author: javelin
 * @create: 2026/3/27
 * @Version 1.0
 **/
public abstract class ObjectId {

    /**
     * 全局ID生成器，使用原子整数保证线程安全
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(0);

    /**
     * 对象构造回调，默认 null = 未启用（构造零额外开销）。
     * 编辑器侧注册表启动时装上、退出时卸下；引擎运行时恒为 null。
     */
    private static volatile Consumer<ObjectId> registrar;

    /**
     * 对象的唯一标识符，创建时自动分配
     */
    public final int id;

    /**
     * 构造对象并分配唯一ID，随后通知已安装的构造回调（无则跳过）。
     */
    public ObjectId() {
        id = ID_GENERATOR.incrementAndGet();
        Consumer<ObjectId> r = registrar;
        if (r != null) {
            r.accept(this);
        }
    }

    /**
     * 安装或卸载对象构造回调；传 null 卸载。回调在构造路径上同步执行，须无阻塞、不抛。
     */
    public static void setRegistrar(Consumer<ObjectId> registrar) {
        ObjectId.registrar = registrar;
    }

    /**
     * 获取当前已分配的最大ID值
     * @return 当前最大ID
     */
    public static int getCurrentMaxId() {
        return ID_GENERATOR.get();
    }

    public int getId() {
        return id;
    }

}
