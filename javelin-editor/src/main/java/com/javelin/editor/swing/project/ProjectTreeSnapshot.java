package com.javelin.editor.swing.project;

import com.javelin.editor.project.Project;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 项目文件树快照（{@link ProjectPanel} 的数据通道——原 {@code /project/tree} 桥逻辑的
 * Swing 直调形态，Editor_Swing_Design §7.2「桥退役、Java 直调」）。
 *
 * <p><b>纯 IO，无引擎状态</b>——不走 submitTask 帧队列（区别于 SceneTreeSnapshot），
 * 消费方在后台线程组装、EDT 应用（§7.3：纯 IO 可后台线程）。仅暴露 Assets 与 src
 * 两根（项目结构不完整的根静默缺席）；隐藏项（. 开头）与伴生 .meta 不展示（Unity
 * 口径：编辑器内部元数据不可见）；子项目录优先 + 名称忽略大小写序（IDEA 项目视图
 * 口径）。Node 直持 {@link File}——面板选中经
 * {@code EditorSelection.setSelectedPath} 直写，原桥「相对路径上报 + 解析闸门」环节
 * 随直调消亡（节点由 rootDir 遍历产出，天然不越项目根）。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public final class ProjectTreeSnapshot {

    /** 未就绪快照（project 为 null：防御分支——面板构造晚于引擎装配，理论不可达） */
    private static final ProjectTreeSnapshot NOT_READY =
            new ProjectTreeSnapshot(false, new ArrayList<>());

    private final boolean ready;
    private final ArrayList<Node> roots;

    private ProjectTreeSnapshot(boolean ready, ArrayList<Node> roots) {
        this.ready = ready;
        this.roots = roots;
    }

    /**
     * 组装快照（<b>后台线程执行</b>——目录全量遍历纯 IO，勿在 EDT 直接调）。
     *
     * @param project 当前项目（rootDir 遍历源）
     * @return 项目文件树快照；project 为 null 时 ready=false
     */
    public static ProjectTreeSnapshot build(Project project) {
        if (project == null) {
            return NOT_READY;
        }
        ArrayList<Node> roots = new ArrayList<>();
        appendDir(roots, project.getRootDir(), "Assets");
        appendDir(roots, project.getRootDir(), "src");
        return new ProjectTreeSnapshot(true, roots);
    }

    /** 根目录存在才入列（缺失根静默跳过——项目结构不完整不阻断其余根展示） */
    private static void appendDir(List<Node> roots, File rootDir, String dirName) {
        File dir = new File(rootDir, dirName);
        if (!dir.isDirectory()) {
            return;
        }
        roots.add(buildNode(dir, dirName));
    }

    /** File → 树节点递归（隐藏项与伴生 .meta 过滤） */
    private static Node buildNode(File file, String relativePath) {
        ArrayList<Node> children = new ArrayList<>();
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                Arrays.sort(files, ProjectTreeSnapshot::compareChildren);
                for (File child : files) {
                    String name = child.getName();
                    if (name.startsWith(".") || name.endsWith(".meta")) {
                        continue;
                    }
                    children.add(buildNode(child, relativePath + "/" + name));
                }
            }
        }
        return new Node(relativePath, file.getName(), file.isDirectory(), file, children);
    }

    /** 子项排序：目录在前、同层名称忽略大小写（IDEA 项目视图口径） */
    private static int compareChildren(File a, File b) {
        if (a.isDirectory() != b.isDirectory()) {
            return a.isDirectory() ? -1 : 1;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName());
    }

    public boolean isReady() {
        return ready;
    }

    /** 根节点（Assets、src；不可变列表视图，面板侧只读） */
    public List<Node> getRoots() {
        return Collections.unmodifiableList(roots);
    }

    /**
     * 树节点快照：相对路径（/ 分隔，面包屑切段与左树行索引键）、显示名、目录标记、
     * File 本体（选中上报 {@code EditorSelection.setSelectedPath} 直写用）、子节点
     * （目录优先 + 名称序，文件为空列表）。纯值对象随宿主重建，无序列化协议。
     */
    public static final class Node {

        private final String relativePath;
        private final String name;
        private final boolean directory;
        private final File file;
        private final ArrayList<Node> children;

        Node(String relativePath, String name, boolean directory, File file,
             ArrayList<Node> children) {
            this.relativePath = relativePath;
            this.name = name;
            this.directory = directory;
            this.file = file;
            this.children = children;
        }

        /** 相对项目根路径（/ 分隔，根节点为单段如 "Assets"） */
        public String getRelativePath() {
            return relativePath;
        }

        public String getName() {
            return name;
        }

        public boolean isDirectory() {
            return directory;
        }

        /** 节点对应文件/目录本体 */
        public File getFile() {
            return file;
        }

        /** 直接子节点（不可变视图；含文件——左树投影在面板侧过滤） */
        public List<Node> getChildren() {
            return Collections.unmodifiableList(children);
        }
    }
}
