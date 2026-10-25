package com.javelin.editor.swing.inspector;

import com.javelin.core.assets.AssetManager;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.MaterialProperty;
import com.javelin.core.render.material.MaterialPropertyType;
import com.javelin.core.render.material.MaterialTextureEntry;
import com.javelin.core.render.material.MaterialValueEntry;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.material.SurfaceType;
import com.javelin.core.render.texture.Texture2D;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.inspector.InspectorFieldVo;
import com.javelin.editor.serializable.TextImporter;
import com.javelin.editor.serializable.TextExporter;
import com.javelin.editor.swing.SvgIcon;
import com.javelin.editor.swing.inspector.widget.AssetInput;
import com.javelin.editor.swing.inspector.widget.CollapsiblePanel;
import com.javelin.editor.swing.inspector.widget.ColorField;
import com.javelin.editor.swing.inspector.widget.InsFormat;
import com.javelin.editor.swing.inspector.widget.JeNumberInput;
import com.javelin.editor.swing.inspector.widget.JeSelect;
import com.javelin.editor.swing.inspector.widget.VectorRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 材质检查器内容件（.mat 键）：TextImporter 后台线程读 .mat 文本源
 * （MaterialImporter 同款解析链）、EDT 组装。两段分组（「材质」固定不可折叠、
 * 参数可折叠——贴图行内联参数组）+ 两列字段网格（label 列自适应、控件列填满，节点检查器
 * 同口径）。**可编辑**（v0.8 增量，2026-10-25）：Shader 行 AssetInput 换绑
 * （setShader 重置参数默认值 + 即时写回重导）；参数行类型感知控件——COLOR=
 * ColorField、VECTOR=VectorRow 四段、FLOAT/RANGE=JeNumberInput（RANGE 按
 * 声明区间硬钳）、面型=JeSelect；提交=写内存实例 + **防抖持久化**（键入即提交
 * ×写盘重导的合并阀，换选/摘除前 flushPending 兜底防丢尾改——Unity 资产即时
 * 保存口径）。编辑载体=缓存共享实例优先（loadGuid，场景同一对象——数值/面型
 * 编辑即时联动渲染；shader 换绑例外走盘面重解析，见 onShaderCommit）。贴图行
 * AssetInput 换装 Texture2D（Unity 口径：与其他参数同行内联不分组；None=占位
 * 贴图合法；materialSet 按材质缓存烤死贴图绑定，live 换绑不重绑——生效=场景
 * 重载/热重载批次）。
 *
 * <p>线程契约：onSelection（EDT）起后台加载线程，代际守卫（点击前进即 +1，
 * 晚到的旧结果丢弃）；解析失败显示错误态不静默。</p>
 *
 * @author javelin
 * @create 2026/10/21
 */
public class MaterialInspectorContent extends JComponent implements InspectorContent {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialInspectorContent.class);

    private static final String EMPTY_TEXT = "未选中材质";
    private static final String LOADING_TEXT = "加载中…";
    /** 未赋贴图槽显示（渲染侧按占位贴图约定解析） */
    private static final String PLACEHOLDER_TEXT = "无（占位贴图）";
    /** 值编辑持久化防抖间隔（毫秒——键入即提交的写盘合并阀） */
    private static final int FLUSH_DELAY_MS = 500;

    private final JLabel statusLabel = new JLabel(EMPTY_TEXT, SwingConstants.CENTER);
    private JScrollPane scrollPane;
    /** 当前展示文件（同文件重复 onSelection 跳过；代际守卫基准） */
    private File currentFile;
    /** 加载代际（选中前进即 +1，晚到的旧结果丢弃） */
    private int generation;
    /** 当前编辑载体（commit 写参数/换绑 shader 的宿主实例） */
    private Material material;
    /** Shader 行输入（None 拒绝等回滚场景用） */
    private AssetInput shaderInput;
    /** 值编辑持久化防抖定时器（单发 restart 合并；懒建） */
    private Timer flushTimer;

    public MaterialInspectorContent() {
        setLayout(new BorderLayout(0, 0));
        Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            statusLabel.setForeground(dim);
        }
        add(statusLabel, BorderLayout.CENTER);
    }

    @Override
    public JComponent component() {
        return this;
    }

    @Override
    public void onSelection(InspectorSelection selection) {
        File file = selection.file();
        if (file == null || file.equals(currentFile)) {
            return;
        }
        flushPending();
        reload(file);
    }

    @Override
    public void onDetach() {
        flushPending();
    }

    /** 加载/重载入口：代际前进 + 加载态 + 后台线程读源（EDT 调用） */
    private void reload(File file) {
        currentFile = file;
        int gen = ++generation;
        showStatus(LOADING_TEXT);
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过材质检查器加载");
            return;
        }
        AssetManager assetManager = editor.getAssetManager();
        Thread loader = new Thread(() -> load(file, assetManager, gen), "material-inspector");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * 后台线程加载：.mat 文本源解析 → 缓存查共享实例（loadGuid）命中且 shader 与
     * 盘面一致即作为编辑载体——场景 MeshRenderer 持同一对象，数值/面型编辑即时
     * 联动渲染（RenderingSystem.materialSet 每帧重打包、收集期逐帧分桶）；缓存
     * 未命中由 Library 产物加载并注册，产物缺失/损坏或 live 实例与盘面 shader
     * 分歧（换绑后）回退文本解析实例（盘面权威，编辑落盘重导、联动待热重载批次）。
     */
    private void load(File file, AssetManager assetManager, int gen) {
        Material material;
        try {
            Material parsed = new TextImporter(file, assetManager).importData(Material.class);
            material = sharedInstanceOf(parsed, assetManager);
            if (material == null) {
                material = parsed;
            }
        } catch (Exception e) {
            LOGGER.warn("材质文件解析失败: {}", file.getName(), e);
            SwingUtilities.invokeLater(() -> {
                if (gen == generation) {
                    showStatus("材质解析失败: " + e.getMessage());
                }
            });
            return;
        }
        Material loaded = material;
        SwingUtilities.invokeLater(() -> {
            if (gen == generation) {
                build(loaded, file);
            }
        });
    }

    /**
     * 缓存优先取共享实例（guid → loadGuid）：命中且 shader 与盘面一致（live 实例
     * 未与盘面分歧——换绑后、外部改盘后）才作为编辑载体，否则走文本兜底防旧实例
     * 写盘撤销换绑；加载失败同样回 null。
     */
    private static Material sharedInstanceOf(Material parsed, AssetManager assetManager) {
        String guid = parsed.getGuid();
        if (guid == null || guid.isBlank()) {
            return null;
        }
        Material shared;
        try {
            shared = assetManager.loadGuid(guid, Material.class);
        } catch (Exception e) {
            LOGGER.warn("材质共享实例加载失败，回退文本源实例: guid={}", guid, e);
            return null;
        }
        if (shared != null && !sameShaderGuid(parsed, shared)) {
            LOGGER.info("live 材质实例与盘面 shader 分歧，走文本源实例: guid={}", guid);
            return null;
        }
        return shared;
    }

    /** 两实例 shader guid 一致判定（null-safe） */
    private static boolean sameShaderGuid(Material a, Material b) {
        String ga = a.getShader() != null ? a.getShader().getGuid() : null;
        String gb = b.getShader() != null ? b.getShader().getGuid() : null;
        return ga == null ? gb == null : ga.equals(gb);
    }

    /** 组装展示（EDT）：材质信息（固定分组）/ 参数（可折叠——贴图行内联 Unity 口径） */
    private void build(Material material, File file) {
        this.material = material;
        InsScrollablePanel content = new InsScrollablePanel();
        content.setLayout(new BoxLayout(content, BoxLayout.PAGE_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(infoSection(material, file));
        if (hasParamRows(material)) {
            content.add(Box.createVerticalStrut(2));
            content.add(section("参数", null, rowsGrid(material)));
        }
        mount(content);
    }

    /** 参数分组行存在判定：shader 声明任一属性即有行；残档回退条目非空 */
    private static boolean hasParamRows(Material material) {
        Shader shader = material.getShader();
        if (shader == null) {
            return !material.getValues().isEmpty() || !material.getTextures().isEmpty();
        }
        return !shader.getProperties().isEmpty();
    }

    /** 材质信息分组（固定不可折叠——Unity 材质头信息口径）：文件（暗）/ Shader / 面型 / GUID（暗） */
    private JComponent infoSection(Material material, File file) {
        FieldGrid grid = new FieldGrid();
        grid.addDim("文件", file.getName());
        grid.add("Shader", shaderInput());
        grid.add("面型", surfaceControl(material));
        if (material.getGuid() != null && !material.getGuid().isBlank()) {
            grid.addDim("GUID", material.getGuid());
        }
        CollapsiblePanel section = new CollapsiblePanel("材质", null, titleIcon("material"), grid);
        section.setCollapsible(false);
        return section;
    }

    /**
     * 参数分组：类型感知可编辑控件。**行单源=shader Properties 声明序**（Unity
     * 口径：贴图槽与其他参数同行内联不分组、模板增删属性面板即时跟随——旧材质
     * 条目脱节不遮行；条目缺值回退属性默认值）；TEXTURE_2D 行=AssetInput 换装
     * Texture2D。shader 不可得回退条目迭代（残档兜底，贴图行只读）。
     */
    private JComponent rowsGrid(Material material) {
        FieldGrid grid = new FieldGrid();
        Shader shader = material.getShader();
        if (shader != null) {
            for (MaterialProperty property : shader.getProperties()) {
                String label = displayName(material, property.getName());
                if (property.getType() == MaterialPropertyType.TEXTURE_2D) {
                    grid.add(label, textureInput(material, property.getName()));
                } else {
                    MaterialValueEntry entry = findValueEntry(material, property.getName());
                    float[] data = entry != null ? entry.getData() : property.getDefaultValues();
                    grid.add(label, valueControl(material, property.getName(), data));
                }
            }
            return grid;
        }
        for (MaterialValueEntry entry : material.getValues()) {
            grid.add(entry.getName(), valueControl(material, entry.getName(), entry.getData()));
        }
        for (MaterialTextureEntry entry : material.getTextures()) {
            if (entry.getTexture() != null) {
                grid.add(entry.getName(), new JLabel(textureName(entry.getTexture())));
            } else {
                grid.addDim(entry.getName(), PLACEHOLDER_TEXT);
            }
        }
        return grid;
    }

    /** 贴图行输入（AssetInput 选择窗/拖放；当前值=Texture2D guid，None=占位贴图） */
    private JComponent textureInput(Material material, String name) {
        Texture2D texture = material.getTexture(name);
        String guid = texture != null ? texture.getGuid() : null;
        // 初值必传（AssetInput 无初值构造恒显 None——v0.10 GUID_ASSET 回显漏传同坑）
        AssetInput input = new AssetInput(Texture2D.class, newGuid -> onTextureCommit(name, newGuid));
        input.setValue(guid);
        return input;
    }

    /**
     * 贴图槽换绑提交（EDT，即时持久化——单发动作不走防抖，Shader 行同款）：
     * None 清空合法（渲染侧占位贴图）；guid 经 loadGuid 解 Texture2D 实例校验
     * 存在；空串=内存创建语义对贴图槽无意义拒绝。写内存实例（材质无冻结语义；
     * materialSet 按材质缓存烤死贴图绑定，live 换绑不重绑——生效=场景重载/
     * 热重载批次）→ TextExporter 即时写回 → submitImport 重导；拒绝/失败路径
     * =整面板重刷（build/reload）回滚显示。
     */
    private void onTextureCommit(String name, String newGuid) {
        Material material = this.material;
        File file = this.currentFile;
        if (material == null || file == null) {
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        Texture2D texture = null;
        if (newGuid != null) {
            if (newGuid.isBlank()) {
                LOGGER.warn("贴图槽不支持内存创建语义，拒绝空串提交: {}", name);
                build(material, file);
                return;
            }
            if (editor == null) {
                build(material, file);
                return;
            }
            texture = editor.getAssetManager().loadGuid(newGuid, Texture2D.class);
            if (texture == null) {
                LOGGER.warn("纹理加载失败（Library 缺产物？），拒绝换绑: {} guid={}", name, newGuid);
                build(material, file);
                return;
            }
        }
        // 防抖尾改先落盘：换绑从盘面最新态出发
        flushPending();
        try {
            material.setTexture(name, texture);
            new TextExporter(file).export(material);
        } catch (Exception e) {
            LOGGER.error("材质写回失败，从盘面重载: {}", file, e);
            reload(file);
            return;
        }
        if (editor != null) {
            editor.getAssetImportPipeline().submitImport(file);
        }
    }

    /** 数值条目按名查找（面板侧行值回备用；材质 API 无按名取条目口） */
    private static MaterialValueEntry findValueEntry(Material material, String name) {
        for (MaterialValueEntry entry : material.getValues()) {
            if (entry.getName().equals(name)) {
                return entry;
            }
        }
        return null;
    }

    /** 行 label = shader Properties 显示名（Unity 口径）；shader 缺失/属性失配回退绑定名 */
    private static String displayName(Material material, String name) {
        Shader shader = material.getShader();
        MaterialProperty property = shader != null ? shader.findProperty(name) : null;
        if (property != null && property.getDisplayName() != null && !property.getDisplayName().isBlank()) {
            return property.getDisplayName();
        }
        return name;
    }

    /** Shader 行输入（拖放/选择窗换绑；当前值=shader guid） */
    private AssetInput shaderInput() {
        AssetInput input = new AssetInput(Shader.class, this::onShaderCommit);
        Shader shader = material != null ? material.getShader() : null;
        input.setValue(shader != null ? shader.getGuid() : null);
        this.shaderInput = input;
        return input;
    }

    /** 面型下拉（OPAQUE/TRANSPARENT——透明分桶裁决源） */
    private JComponent surfaceControl(Material material) {
        List<InspectorFieldVo.Option> options = new ArrayList<>(2);
        options.add(enumOption("OPAQUE", "不透明"));
        options.add(enumOption("TRANSPARENT", "透明"));
        return new JeSelect(options, String.valueOf(material.getSurfaceType()), this::applySurface);
    }

    /** 参数值控件（类型感知，可编辑）：COLOR=ColorField、VECTOR=VectorRow 四段、FLOAT/RANGE=JeNumberInput */
    private JComponent valueControl(Material material, String name, float[] data) {
        Shader shader = material.getShader();
        MaterialProperty property = shader != null ? shader.findProperty(name) : null;
        MaterialPropertyType type = property != null ? property.getType() : null;
        String wire = data != null ? InsFormat.join(data) : null;

        if (type == MaterialPropertyType.COLOR && data != null && data.length >= 4) {
            return new ColorField(wire, w -> applyValue(name, w, true));
        }
        if (type == MaterialPropertyType.VECTOR && data != null && data.length >= 4) {
            return new VectorRow(new String[]{"X", "Y", "Z", "W"}, wire, false, null,
                    w -> applyValue(name, w, true));
        }
        if ((type == MaterialPropertyType.FLOAT || type == MaterialPropertyType.RANGE)
                && data != null && data.length >= 1) {
            return new JeNumberInput(InsFormat.formatFloat(data[0]), false, rangeOf(property),
                    w -> applyValue(name, w, false));
        }
        // 类型/数据失配兜底：只读文本（编辑通道按 shader 声明裁决，不瞎猜）
        return new JLabel(wire != null ? wire : "—");
    }

    /** RANGE 声明区间硬钳（FLOAT 无约束 null） */
    private static InspectorFieldVo.Range rangeOf(MaterialProperty property) {
        if (property == null || property.getType() != MaterialPropertyType.RANGE) {
            return null;
        }
        InspectorFieldVo.Range range = new InspectorFieldVo.Range();
        range.min = property.getMin();
        range.max = property.getMax();
        return range;
    }

    /** 参数提交（EDT，控件键入即提交）：写内存实例 + 防抖持久化 */
    private void applyValue(String name, String wire, boolean vec4) {
        Material material = this.material;
        if (material == null || wire == null) {
            return;
        }
        try {
            if (vec4) {
                float[] parts = InsFormat.split(wire, 4);
                if (parts == null) {
                    return;
                }
                material.setColor(name, parts[0], parts[1], parts[2], parts[3]);
            } else {
                material.setFloat(name, Float.parseFloat(wire.trim()));
            }
        } catch (Exception e) {
            LOGGER.warn("材质参数写入失败: {} = {}", name, wire, e);
            return;
        }
        scheduleFlush();
    }

    /** 面型提交（透明分桶裁决；收集期逐帧读面型，桶迁移即时生效） */
    private void applySurface(String wire) {
        Material material = this.material;
        if (material == null || wire == null) {
            return;
        }
        try {
            material.setSurfaceType(SurfaceType.valueOf(wire));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("面型值非法: {}", wire);
            return;
        }
        scheduleFlush();
    }

    /**
     * Shader 换绑提交（EDT，即时持久化——单发动作不走防抖）：材质必绑 Shader
     * （Unity 口径，None 拒绝）；换绑不落共享实例（渲染侧 MaterialResources 按
     * shader 首绑烤死，live 换绑 UBO 尺寸/贴图绑定数错配）——文本重解析实例上
     * setShader（core 语义重置参数默认值）→ TextExporter 即时写回 → submitImport
     * 重导 → 面板切到该实例（场景实例指针随场景重载/热重载批次换）。
     */
    private void onShaderCommit(String newGuid) {
        Material material = this.material;
        File file = this.currentFile;
        String oldGuid = material != null && material.getShader() != null
                ? material.getShader().getGuid() : null;
        if (material == null || file == null) {
            return;
        }
        if (newGuid == null || newGuid.isBlank()) {
            LOGGER.warn("材质必须绑定 Shader，拒绝清空提交");
            shaderInput.setValue(oldGuid);
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        Shader shader = editor != null ? editor.getAssetManager().loadGuid(newGuid, Shader.class) : null;
        if (shader == null) {
            LOGGER.warn("Shader 加载失败（Library 缺产物？），拒绝换绑: guid={}", newGuid);
            shaderInput.setValue(oldGuid);
            return;
        }
        // 防抖尾改先落盘：换绑从盘面最新态出发（解析失败连同下方写回一并走重载兜底）
        flushPending();
        Material rebound;
        try {
            rebound = new TextImporter(file, editor.getAssetManager()).importData(Material.class);
            rebound.setShader(shader);
            new TextExporter(file).export(rebound);
        } catch (Exception e) {
            // 写回失败=盘面即权威，内存态作废直接从盘面重载（显示随 build 自正）
            LOGGER.error("材质写回失败，从盘面重载: {}", file, e);
            reload(file);
            return;
        }
        editor.getAssetImportPipeline().submitImport(file);
        build(rebound, file);
    }

    // ------------------------------------------------------------------ 持久化

    /** 防抖触发（键入即提交的高频合并；单发定时器 restart 滚动续期） */
    private void scheduleFlush() {
        if (flushTimer == null) {
            flushTimer = new Timer(FLUSH_DELAY_MS, e -> flushNow());
            flushTimer.setRepeats(false);
        }
        flushTimer.restart();
    }

    /** 挂起写盘落定（换选/摘除前调用——防丢尾改） */
    private void flushPending() {
        if (flushTimer != null && flushTimer.isRunning()) {
            flushTimer.stop();
            flushNow();
        }
    }

    /** 写盘 + 重导（EDT 纯 IO 小体量先例；失败=盘面即权威从盘面重载） */
    private void flushNow() {
        Material material = this.material;
        File file = this.currentFile;
        if (material == null || file == null) {
            return;
        }
        try {
            new TextExporter(file).export(material);
        } catch (Exception e) {
            LOGGER.error("材质写回失败，从盘面重载: {}", file, e);
            reload(file);
            return;
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor != null) {
            editor.getAssetImportPipeline().submitImport(file);
        }
    }

    // ------------------------------------------------------------------ 布局件

    /** 贴图显示名（guid 反查源路径尾名，未命中退 guid 原串） */
    private static String textureName(Texture2D texture) {
        String guid = texture.getGuid();
        if (guid == null || guid.isBlank()) {
            return "（内存贴图）";
        }
        JavelinEditor editor = JavelinEditor.getInstance();
        String path = editor != null ? editor.getAssetManager().pathOfGuid(guid) : null;
        if (path == null || path.isBlank()) {
            return guid;
        }
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    /** 可折叠分组（CollapsiblePanel 灰头/亮内容；图标资源缺失不占位） */
    private static JComponent section(String title, String iconName, JComponent grid) {
        return new CollapsiblePanel(title, null, titleIcon(iconName), grid);
    }

    /** 分组头图标（16px，缺资源不占位——菜单域同口径） */
    private static Icon titleIcon(String name) {
        if (name == null) {
            return null;
        }
        SvgIcon icon = SvgIcon.of(name, 16);
        return icon.isAvailable() ? icon : null;
    }

    /** 枚举选项 VO（value=回写值、label=显示名） */
    private static InspectorFieldVo.Option enumOption(String value, String label) {
        InspectorFieldVo.Option option = new InspectorFieldVo.Option();
        option.value = value;
        option.label = label;
        return option;
    }

    /**
     * 两列字段行网格（label 列左对齐、列宽自适应组内最长；控件列填满——节点检查器
     * ComponentSection 同口径；行距 2px、label/控件列 4px 间隔例外口径）。
     */
    private static final class FieldGrid extends JPanel {

        private int row;

        private FieldGrid() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        }

        /** 常规行：label + 控件 */
        private void add(String label, JComponent control) {
            place(new JLabel(label), control, false);
        }

        /** 暗色行（诊断/占位：label 与值整体暗色） */
        private void addDim(String label, String value) {
            place(new JLabel(label), new JLabel(value), true);
        }

        private void place(JLabel label, JComponent control, boolean dim) {
            if (dim) {
                Color dimColor = UIManager.getColor("Label.disabledForeground");
                if (dimColor != null) {
                    label.setForeground(dimColor);
                    control.setForeground(dimColor);
                }
            }
            GridBagConstraints labelGbc = new GridBagConstraints();
            labelGbc.gridx = 0;
            labelGbc.gridy = row;
            labelGbc.anchor = GridBagConstraints.WEST;
            labelGbc.insets = new Insets(row == 0 ? 0 : 2, 0, 0, 4);
            add(label, labelGbc);

            GridBagConstraints controlGbc = new GridBagConstraints();
            controlGbc.gridx = 1;
            controlGbc.gridy = row;
            controlGbc.fill = GridBagConstraints.HORIZONTAL;
            controlGbc.weightx = 1;
            controlGbc.insets = new Insets(row == 0 ? 0 : 2, 0, 0, 0);
            add(control, controlGbc);
            row++;
        }
    }

    // ------------------------------------------------------------------ 壳件

    /** 挂载内容（替换状态文案） */
    private void mount(JComponent content) {
        if (scrollPane == null) {
            remove(statusLabel);
            scrollPane = new JScrollPane(content);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            add(scrollPane, BorderLayout.CENTER);
        } else {
            scrollPane.setViewportView(content);
        }
        revalidate();
        repaint();
    }

    /** 状态文案（加载中/解析失败/空态） */
    private void showStatus(String text) {
        if (scrollPane != null) {
            remove(scrollPane);
            scrollPane = null;
            add(statusLabel, BorderLayout.CENTER);
            revalidate();
            repaint();
        }
        statusLabel.setText(text);
    }
}
