package com.javelin.editor.importer.mesh;

import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.rhi.types.PrimitiveTopology;
import com.javelin.editor.importer.AssetImporter;
import com.javelin.editor.importer.ImportSettings;
import com.javelin.editor.importer.ImportTask;
import com.javelin.editor.meta.GuidHeaderManager;
import com.javelin.editor.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * 简单网格导入器
 *
 * <p>解析自定义文本格式（.smesh），支持 [POSITION]、[UV]、[INDEX]、[NORMAL]、
 * [TOPOLOGY] 段，生成 {@link Mesh}（[TOPOLOGY] 值 = PrimitiveTopology 枚举名，
 * 如 TRIANGLE_LIST，缺省同默认）。</p>
 *
 * <p><b>格式示例：</b></p>
 * <pre>
 * [POSITION]
 * 0,0,0
 * 1,0,0
 * 0,1,0
 * [UV]
 * 0,0
 * 1,0
 * 0,1
 * [INDEX]
 * 0,1,2
 * [NORMAL]
 * 0,0,1
 * 0,0,1
 * 0,0,1
 * [TOPOLOGY]
 * TRIANGLE_LIST
 * </pre>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class SimpleMeshImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleMeshImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".smesh");

    /** 源码文件头 GUID 读取（自包含真值源优先于 meta） */
    private final GuidHeaderManager guidHeaderManager = new GuidHeaderManager();

    @Override
    public String getName() {
        return "SimpleMeshImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new SimpleMeshImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();

        try (BufferedReader reader = Files.newBufferedReader(sourceFile.toPath())) {
            // 逐段收集数据，无装箱
            FloatArray positions = new FloatArray();
            FloatArray normals = new FloatArray();
            FloatArray uvs = new FloatArray();
            IntArray indices = new IntArray();

            String currentSection = "NONE";
            String topologyName = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // 段切换：读到 [SECTION_NAME]
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).toUpperCase();
                    continue;
                }

                // 根据当前段解析数据行
                switch (currentSection) {
                    case "POSITION" -> parseFloatLine(line, positions, 3);
                    case "NORMAL" -> parseFloatLine(line, normals, 3);
                    case "UV" -> parseFloatLine(line, uvs, 2);
                    case "INDEX" -> parseIntLine(line, indices);
                    case "TOPOLOGY" -> topologyName = line.toUpperCase();
                    default -> {
                        // 未识别段，忽略
                    }
                }
            }

            // 校验必需段
            if (positions.size == 0) {
                task.setErrorMessage("缺少 [POSITION] 数据或数据为空");
                return false;
            }

            int vertexCount = positions.size / 3;

            // 校验数量一致性
            if (normals.size > 0 && normals.size / 3 != vertexCount) {
                task.setErrorMessage(
                        "法线数量与顶点数量不匹配：顶点=" + vertexCount + "，法线=" + (normals.size / 3));
                return false;
            }
            if (uvs.size > 0 && uvs.size / 2 != vertexCount) {
                task.setErrorMessage(
                        "UV 数量与顶点数量不匹配：顶点=" + vertexCount + "，UV=" + (uvs.size / 2));
                return false;
            }


            // 装配 Mesh（给哪些段就有哪些属性，数据驱动——Mesh 2.2）
            Mesh mesh = new Mesh();
            mesh.setPositions(positions.toArray());
            if (normals.size > 0) {
                mesh.setNormals(normals.toArray());
            }
            if (uvs.size > 0) {
                mesh.setUVs(uvs.toArray());
            }
            if (indices.size > 0) {
                mesh.setIndices(indices.toArray());
            }
            if (topologyName != null) {
                // 拓扑值 = PrimitiveTopology 枚举名（TRIANGLE_LIST / TRIANGLE_STRIP / ...）
                try {
                    mesh.setTopology(PrimitiveTopology.valueOf(topologyName));
                } catch (IllegalArgumentException e) {
                    task.setErrorMessage("TOPOLOGY 取值非法: " + topologyName
                            + "（应为 PrimitiveTopology 枚举名，如 TRIANGLE_LIST）");
                    return false;
                }
            }

            // 落盘：guid 优先源文件头（.smesh 自包含），缺失才回退 meta；
            // 产物路径与解析出的 guid 同源（文件名/内容/index 三者一致）
            String guid = guidHeaderManager.extractGuid(sourceFile);
            if (guid == null || guid.isEmpty()) {
                guid = task.getMeta().getGuid();
            }
            // 产物自描述 guid（2026-10-04 拍板，与 ShaderImporter 同款）：Mesh.write
            // 调 super 内嵌——加载实例自带 guid，字段引用序列化走对象 guid 分流
            mesh.setGuid(guid);
            File libraryDir = project.getLibrary();
            if (!libraryDir.exists()) {
                libraryDir.mkdirs();
            }
            File outputFile = new File(libraryDir, guid + EXTENSION);
            new BinaryExporter(outputFile).export(mesh);

            LOGGER.info("简单网格导入成功: {} -> {} (顶点数={})",
                    sourceFile.getName(), outputFile.getName(), vertexCount);
            return true;

        } catch (IOException e) {
            LOGGER.error("读取简单网格文件失败: {}", sourceFile.getName(), e);
            task.setErrorMessage("读取文件失败: " + e.getMessage());
            return false;
        } catch (NumberFormatException e) {
            LOGGER.error("解析简单网格数据失败: {}", sourceFile.getName(), e);
            task.setErrorMessage("数据格式错误: " + e.getMessage());
            return false;
        }
    }

    /**
     * 解析一行 float 数据，无 split 分配
     *
     * @param line          数据行
     * @param target        目标数组
     * @param expectedCount 期望的 float 数量
     */
    private void parseFloatLine(String line, FloatArray target, int expectedCount) {
        int last = 0;
        int count = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == ',') {
                String val = line.substring(last, i).trim();
                if (!val.isEmpty()) {
                    target.add(Float.parseFloat(val));
                    count++;
                }
                last = i + 1;
            }
        }
        if (count != expectedCount) {
            throw new NumberFormatException(
                    "行数据格式错误，期望 " + expectedCount + " 个值，实际 " + count + " 个：" + line);
        }
    }

    /**
     * 解析一行 int 数据，无 split 分配
     *
     * @param line   数据行
     * @param target 目标数组
     */
    private void parseIntLine(String line, IntArray target) {
        int last = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == ',') {
                String val = line.substring(last, i).trim();
                if (!val.isEmpty()) {
                    target.add(Integer.parseInt(val));
                }
                last = i + 1;
            }
        }
    }

    /**
     * float 动态数组（无装箱）
     */
    private static class FloatArray {
        float[] data = new float[1024];
        int size = 0;

        void add(float v) {
            if (size >= data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    /**
     * int 动态数组（无装箱）
     */
    private static class IntArray {
        int[] data = new int[1024];
        int size = 0;

        void add(int v) {
            if (size >= data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
