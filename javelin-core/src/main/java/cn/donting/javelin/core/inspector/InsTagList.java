package cn.donting.javelin.core.inspector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inspector 字符串列表字段按「标签清单」展示：名字只读、逐行可删、底部按钮新增，不支持改名。
 *
 * <p>Tag 以名字为身份（改名等于另一个标签，已引用节点不会跟着变），故设置面板只允许增删。
 * 未标记的字符串列表仍按普通可编辑行（{@code STRING_LIST}）展示。编辑器侧运行期反射读取。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InsTagList {
}
