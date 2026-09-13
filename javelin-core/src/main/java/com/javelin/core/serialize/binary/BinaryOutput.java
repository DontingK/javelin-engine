package com.javelin.core.serialize.binary;

/**
 * 二进制输出内部接口，统一 {@link BinaryOutputCapsule} 和 {@link BinaryArrayOutput}。
 *
 * @author javelin
 * @since 1.0
 */
interface BinaryOutput {

    /**
     * 获取序列化对象 ID。
     */
    int getId();

    /**
     * 收集阶段：遍历对象图，触发子对象注册。
     */
    void collect();

    /**
     * 写入阶段：将缓冲数据写出到二进制流。
     */
    void writeTo(BinaryStreamOutput out);

    /**
     * 获取原始对象。
     */
    Object getValue();
}
