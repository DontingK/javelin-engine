package com.javelin.editor.jcef.method;


import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.domain.par.CreateProjectPar;
import com.javelin.editor.jcef.domain.par.FilePathPar;
import com.javelin.editor.jcef.domain.vo.FileVo;
import com.javelin.editor.project.GradleProject;
import com.javelin.editor.project.RecentProjectManager;
import com.javelin.editor.window.cef.ProjectLoadingFrame;
import com.javelin.editor.window.cef.ProjectFrame;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
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
}


