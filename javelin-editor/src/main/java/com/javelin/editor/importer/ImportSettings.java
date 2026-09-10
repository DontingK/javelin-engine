package com.javelin.editor.importer;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.serialize.Serializable;

/**
 * 资源导入设置基类
 *
 * <p>定义资源导入的通用配置，具体资源类型继承此类实现特定设置</p>
 *
 * @author: javelin
 * @create: 2026/4/8
 * @Version 1.0
 */
@Reflective
public abstract class ImportSettings implements Serializable {

    /**
     * 获取设置类型
     *
     * @return 设置类型名称
     */
    public abstract String getType();

    /**
     * 验证设置有效性
     *
     * @return 如果设置有效返回 true
     */
    public abstract boolean validate();
}
