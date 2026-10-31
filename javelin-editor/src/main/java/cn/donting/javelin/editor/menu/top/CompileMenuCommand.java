package cn.donting.javelin.editor.menu.top;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编译命令（构建域）：项目编译入口。引擎侧 {@code GradleProject.compile()}
 * （gradle compileJava）已备，菜单入口先禁用（2026-10-11 拍板「编译先禁用」），
 * 启用时去掉 {@link #enabled()} 覆写即接通。
 *
 * <p>置灰显示、菜单路径不可达；execute 记 warn 返回，仅防桥 /command/execute
 * 直触发（置灰语义同 UnimplementedMenuCommand，本类是「实而先禁」非「未实现」）。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/11
 */
public final class CompileMenuCommand extends TopMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompileMenuCommand.class);

    @Override
    public String id() {
        return "build.compile";
    }

    /** 恒置灰：编译能力已备，禁用是产品拍板非缺功能 */
    @Override
    public Boolean enabled() {
        return false;
    }

    @Override
    public void execute() {
        LOGGER.warn("build.compile 禁用中（菜单入口先禁用拍板），桥直触发拦截");
    }
}
