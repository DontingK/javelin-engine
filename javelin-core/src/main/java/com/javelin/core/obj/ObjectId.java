package com.javelin.core.obj;


import java.util.concurrent.atomic.AtomicInteger;

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
     * 对象的唯一标识符，创建时自动分配
     */
    public final int id;

    
    /**
     * 构造对象并分配唯一ID
     */
    public ObjectId() {
        id = ID_GENERATOR.incrementAndGet();
        ObjectCache.add(this);
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
