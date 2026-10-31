package cn.donting.javelin.core.serialize;

import cn.donting.javelin.ast.annotation.Reflective;

/**
 * 可序列化对象接口。
 *
 * <p>实现该接口的类可以通过序列化系统读写。</p>
 *
 * @author javelin
 * @since 1.0
 */
@Reflective
public interface Serializable {

    /**
     * 将对象序列化输出。
     *
     * @param output 输出胶囊
     */
    void write(OutputCapsule output);

    /**
     * 从输入胶囊读取数据并填充对象。
     *
     * @param input 输入胶囊
     */
    void read(InputCapsule input);
}
