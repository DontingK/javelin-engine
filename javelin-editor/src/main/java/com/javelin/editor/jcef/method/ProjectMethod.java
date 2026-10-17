package com.javelin.editor.jcef.method;


import com.javelin.editor.JavelinEditor;
import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.domain.par.CreateProjectPar;
import com.javelin.editor.jcef.domain.par.FilePathPar;
import com.javelin.editor.jcef.domain.vo.FileVo;
import com.javelin.editor.jcef.domain.vo.ProjectTreeNodeVo;
import com.javelin.editor.jcef.domain.vo.ProjectTreeVo;
import com.javelin.editor.project.GradleProject;
import com.javelin.editor.project.Project;
import com.javelin.editor.project.RecentProjectManager;
import com.javelin.editor.window.cef.ProjectLoadingFrame;
import com.javelin.editor.window.cef.ProjectFrame;
import com.javelin.editor.selection.EditorSelection;
import com.javelin.editor.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 项目相关桥接方法。
 *
 * <p>提供最近项目查询、打开项目、创建项目等能力。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/6/24
 */
@BridgeMethod("/project")
public class ProjectMethod {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectMethod.class);

    private final RecentProjectManager recentProjectManager;

    /**
     * 构造项目方法类。
     */
    public ProjectMethod() {
        this.recentProjectManager = new RecentProjectManager();
    }

    /**
     * 获取最近打开的项目列表。
     *
     * @return 最近项目文件视图列表
     */
    @BridgeMethod("/recentProjects")
    public List<FileVo> getRecentProjects() {
        List<String> recentProjects = recentProjectManager.getRecentProjects();
        return recentProjects.stream()
                .map(path -> new FileVo(new File(path)))
                .toList();
    }

    /**
     * 打开指定路径的项目。
     *
     * <p>将指定目录加入最近项目列表。</p>
     *
     * @param par 项目路径请求
     * @return 项目文件视图
     */
    @BridgeMethod("/openProject")
    public FileVo openProject(FilePathPar par) throws Exception {
        File projectDir = new File(par.path);
        SwingUtilities.invokeAndWait((Runnable) () -> ProjectFrame.getInstance().dispose());

        ProjectLoadingFrame projectLoadingFrame = new ProjectLoadingFrame(projectDir);
        projectLoadingFrame.setVisible(true);
        recentProjectManager.addProject(projectDir.getAbsolutePath());
        return new FileVo(projectDir);
    }

    /**
     * 创建新项目。
     *
     * <p>在指定父目录下创建 Gradle 项目，并加入最近项目列表。</p>
     *
     * @param par 创建项目请求
     * @return 新建项目文件视图
     */
    @BridgeMethod("/createProject")
    public FileVo createProject(CreateProjectPar par) {
        try {
            GradleProject project = GradleProject.createNewProject(
                    par.directory,
                    par.name,
                    "com.javelin",
                    par.name
            );
            recentProjectManager.addProject(project.getRootDir().getAbsolutePath());
            return new FileVo(project.getRootDir());
        } catch (IOException e) {
            throw new RuntimeException("创建项目失败: " + e.getMessage(), e);
        }
    }

    /**
     * 项目文件树拉取（项目面板展示源）：仅暴露 Assets 与 src 两根。
     *
     * <p>桥线程纯文件系统遍历（不涉引擎状态，无需主线程任务队列）；
     * 编辑器实例未就绪（启动竞态）走 ready=false 不走异常，前端节流重试
     * 兜底——对齐 /scene/tree 口径。隐藏项（. 开头）与伴生 .meta 不展示
     * （Unity 口径：编辑器内部元数据不可见）。</p>
     *
     * @return 项目文件树（ready=false = 未就绪）
     */
    @BridgeMethod("/tree")
    public ProjectTreeVo tree() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return ProjectTreeVo.notReady();
        }
        Project project = editor.getProject();
        ProjectTreeVo vo = new ProjectTreeVo();
        vo.ready = true;
        vo.roots = new ArrayList<>();
        appendDir(vo.roots, project.getRootDir(), "Assets");
        appendDir(vo.roots, project.getRootDir(), "src");
        return vo;
    }

    /**
     * 项目面板选中上报（前端单击/右键触发，写 {@link EditorSelection} 单源——
     * project 域菜单命令的上下文源；volatile 直写返回即生效，右键路径前端
     * await 后再弹菜单，Swing 菜单 enabled 求值读到新选中态）。
     *
     * <p>path 为相对项目根的树节点路径（/ 分隔）；null/空 = 清除选中。边界
     * 闸门：解析结果须为项目根内已存在路径（防异常 path 拼接逃逸项目根——
     * 前端按树节点上报为常态，此处兜底），不合规记 warn 忽略不改写现选中。</p>
     *
     * @param par 路径请求
     */
    @BridgeMethod("/select")
    public void select(FilePathPar par) {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return;
        }
        String raw = par == null ? null : par.path;
        if (raw == null || raw.isBlank()) {
            EditorSelection.setSelectedPath(null);
            return;
        }
        File rootDir = editor.getProject().getRootDir();
        File file = new File(rootDir, raw.trim());
        if (!file.exists() || !FileUtil.isSubFile(rootDir, file)) {
            LOGGER.warn("项目选中上报越界或不存在，忽略: {}", raw);
            return;
        }
        EditorSelection.setSelectedPath(file);
    }

    /**
     * 根目录存在才入列（缺失根静默跳过——项目结构不完整不阻断面板其余根展示）。
     */
    private static void appendDir(List<ProjectTreeNodeVo> roots, File rootDir, String dirName) {
        File dir = new File(rootDir, dirName);
        if (!dir.isDirectory()) {
            return;
        }
        roots.add(buildNode(dir, dirName));
    }

    /**
     * File → 树节点递归（隐藏项与 .meta 过滤；目录优先 + 名称忽略大小写排序）。
     *
     * @param file          目标文件/目录
     * @param relativePath 相对项目根路径（/ 分隔，逐层累积）
     */
    private static ProjectTreeNodeVo buildNode(File file, String relativePath) {
        ProjectTreeNodeVo node = new ProjectTreeNodeVo();
        node.path = relativePath;
        node.name = file.getName();
        node.directory = file.isDirectory();
        node.children = new ArrayList<>();
        if (node.directory) {
            File[] children = file.listFiles();
            if (children != null) {
                Arrays.sort(children, ProjectMethod::compareChildren);
                for (File child : children) {
                    String name = child.getName();
                    if (name.startsWith(".") || name.endsWith(".meta")) {
                        continue;
                    }
                    node.children.add(buildNode(child, relativePath + "/" + name));
                }
            }
        }
        return node;
    }

    /**
     * 子项排序：目录在前、同层名称忽略大小写（IDEA 项目视图口径）。
     */
    private static int compareChildren(File a, File b) {
        if (a.isDirectory() != b.isDirectory()) {
            return a.isDirectory() ? -1 : 1;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
    }
}
