package com.javelin.core.serialize.binary;

import com.javelin.core.serialize.Exporter;
import com.javelin.core.serialize.Serializable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * 二进制导出器
 *
 * <p>将 Serializable 对象导出为二进制文件，支持循环引用检测</p>
 *
 * @author javelin
 * @since 1.0
 */
public class BinaryExporter implements Exporter {

    public static final int FILE_FLAG_SERIALIZABLE = 0x0411;
    public static final int NULL_OBJECT_ID = -1;
    private static final byte TYPE_SERIALIZABLE = 1;
    private static final byte TYPE_ARRAY = 2;
    private static final byte TYPE_ARRAY_LIST = 3;

    private final File outputFile;
    private final IdentityHashMap<Object, BinaryOutput> outputMap = new IdentityHashMap<>();
    private final ArrayList<BinaryOutput> outputs = new ArrayList<>();
    private int nextId = 0;
    /** 本导出的根对象（Asset 分流用：图内根引用走对象 id 而非 GUID，见 isRoot） */
    private Serializable root;

    public BinaryExporter(File outputFile) {
        this.outputFile = outputFile;
    }

    @Override
    public void export(Serializable serializable) throws IOException {
        if (outputFile.isDirectory()) {
            throw new IllegalArgumentException("输出路径不能是目录: " + outputFile.getAbsolutePath());
        }
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // 重置状态，支持同一实例多次导出
        outputMap.clear();
        outputs.clear();
        nextId = 0;
        this.root = serializable;

        // 收集对象图
        getOutput(serializable);

        File tempFile = Files.createTempFile(parentDir.toPath(), outputFile.getName(), ".tmp").toFile();
        boolean targetCreatedByThisCall = !outputFile.exists();

        try {
            FileOutputStream fileOutputStream = new FileOutputStream(tempFile);
            try {
                BinaryStreamOutput out = new BinaryStreamOutput(fileOutputStream);

                // 文件头
                writeHead(out);
                out.write(outputs.size());

                // 对象头区
                for (BinaryOutput output : outputs) {
                    out.write(output.getId());
                    if (output instanceof BinaryOutputCapsule) {
                        out.write(TYPE_SERIALIZABLE);
                        out.write(output.getValue().getClass().getName());
                    } else if (output instanceof BinaryArrayOutput) {
                        out.write(TYPE_ARRAY);
                        BinaryArrayOutput arr = (BinaryArrayOutput) output;
                        out.write(arr.getElementClass().getName());
                        out.write(arr.getLength());
                    } else if (output instanceof BinaryArrayListOutput) {
                        out.write(TYPE_ARRAY_LIST);
                        BinaryArrayListOutput list = (BinaryArrayListOutput) output;
                        out.write(list.getElementClassName());
                        out.write(list.getSize());
                    }
                }

                // 对象数据区
                for (BinaryOutput output : outputs) {
                    out.write(output.getId());
                    output.writeTo(out);
                }

                out.flush();
                fileOutputStream.getFD().sync();
            } finally {
                // 失败路径也必须关闭流，否则 Windows 下临时文件被占用无法清理
                fileOutputStream.close();
            }

            moveTempToTarget(tempFile, outputFile);
        } catch (Exception e) {
            if (tempFile.exists()) {
                tempFile.delete();
            }
            if (targetCreatedByThisCall && outputFile.exists()) {
                outputFile.delete();
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            } else if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            } else {
                throw new IOException("导出二进制文件失败: " + outputFile.getAbsolutePath(), e);
            }
        }
    }

    /**
     * 引用值是否为本导出的根对象（实例同一性）。
     *
     * <p>供 Capsule 的 Asset 分流判定：根对象被图内字段引用时（如 Node.scene →
     * GameScene）走对象 id 引用而非 GUID——图内根引用语义 vs 外部资产 GUID 语义
     *（2026-10-02 拍板；按实例判定而非 guid 比较，规避 null guid 撞车与同 guid
     * 异实例的歧义）。读侧按落盘类型标记分流，零改动。</p>
     */
    boolean isRoot(Object value) {
        return value == root;
    }

    BinaryOutputCapsule getOutput(Serializable value) {
        if (value == null) {
            return null;
        }
        BinaryOutput existing = outputMap.get(value);
        if (existing instanceof BinaryOutputCapsule) {
            return (BinaryOutputCapsule) existing;
        }
        BinaryOutputCapsule capsule = new BinaryOutputCapsule(nextId++, value, this);
        outputMap.put(value, capsule);
        outputs.add(capsule);
        capsule.collect();
        return capsule;
    }

    BinaryArrayOutput getArrayOutput(Object array) {
        if (array == null) {
            return null;
        }
        BinaryOutput existing = outputMap.get(array);
        if (existing instanceof BinaryArrayOutput) {
            return (BinaryArrayOutput) existing;
        }
        BinaryArrayOutput arrayOutput = new BinaryArrayOutput(nextId++, array, this);
        outputMap.put(array, arrayOutput);
        outputs.add(arrayOutput);
        arrayOutput.collect();
        return arrayOutput;
    }

    BinaryArrayListOutput getArrayListOutput(ArrayList<?> list) {
        if (list == null) {
            return null;
        }
        BinaryOutput existing = outputMap.get(list);
        if (existing instanceof BinaryArrayListOutput) {
            return (BinaryArrayListOutput) existing;
        }
        BinaryArrayListOutput listOutput = new BinaryArrayListOutput(nextId++, list, this);
        outputMap.put(list, listOutput);
        outputs.add(listOutput);
        listOutput.collect();
        return listOutput;
    }

    private static void moveTempToTarget(File tempFile, File targetFile) throws IOException {
        IOException lastException = null;
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Files.move(tempFile.toPath(), targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                try {
                    Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (IOException fallbackException) {
                    lastException = fallbackException;
                }
            } catch (IOException e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new IOException("移动临时文件失败: " + targetFile.getAbsolutePath());
    }

    protected void writeHead(BinaryStreamOutput out){
        out.write(FILE_FLAG_SERIALIZABLE);
    }
}
