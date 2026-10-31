package cn.donting.javelin.core.serialize;

/**
 * 序列化导入器接口。
 *
 * @author javelin
 * @since 1.0
 */
public interface Importer {

    /**
     * 从数据源导入对象。
     *
     * <p>根对象按 {@code tCall} 指定的类型实例化（文件中记录的根类名仅作参考），
     * 因此可用新类读取旧文件实现字段演进迁移；文件中不存在的字段保持类型默认值。
     * 嵌套对象仍按文件记录的类名实例化。</p>
     *
     * @param tCall 根对象的期望类型
     * @param <T>   类型参数
     * @return 导入的对象
     * @throws Exception 导入失败（IO 错误、未注册的类等）
     */
    <T extends Serializable> T importData(Class<T> tCall) throws Exception;
}
