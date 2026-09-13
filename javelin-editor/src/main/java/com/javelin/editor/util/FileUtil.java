package com.javelin.editor.util;


import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * 文件工具类
 *
 * @author: javelin
 * @create: 2026/4/13
 * @Version 1.0
 **/
public class FileUtil {

    /**
     * 复制文件或文件夹到目标路径
     *
     * @param src  源文件或文件夹
     * @param dest 目标文件或文件夹
     */
    public static void copy(File src, File dest) {
        if (src == null || dest == null) {
            throw new IllegalArgumentException("源文件和目标文件不能为 null");
        }
        if (!src.exists()) {
            throw new IllegalArgumentException("源文件不存在: " + src.getAbsolutePath());
        }

        Path srcPath = src.toPath();
        Path destPath = dest.toPath();

        try {
            if (src.isDirectory()) {
                // 递归复制文件夹
                if (!dest.exists()) {
                    Files.createDirectories(destPath);
                }
                try (Stream<Path> stream = Files.walk(srcPath)) {
                    stream.forEach(source -> {
                        try {
                            Path target = destPath.resolve(srcPath.relativize(source));
                            if (Files.isDirectory(source)) {
                                if (!Files.exists(target)) {
                                    Files.createDirectory(target);
                                }
                            } else {
                                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("复制文件失败: " + source + " -> " + destPath.resolve(srcPath.relativize(source)), e);
                        }
                    });
                }
            } else {
                // 复制单个文件，确保父目录存在
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) {
                    Files.createDirectories(parent.toPath());
                }
                Files.copy(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("复制文件或文件夹失败: " + src.getAbsolutePath() + " -> " + dest.getAbsolutePath(), e);
        }
    }

    /**
     * 复制目录内容到目标路径（不复制根目录本身）
     *
     * <p>例如：源目录 /a/b/ 下包含 file.txt 和 sub/，目标目录为 /x/y/，
     * 则复制后结果为 /x/y/file.txt 和 /x/y/sub/。</p>
     *
     * @param srcDir  源目录
     * @param destDir 目标目录
     */
    public static void copyContent(File srcDir, File destDir) {
        if (srcDir == null || destDir == null) {
            throw new IllegalArgumentException("源目录和目标目录不能为 null");
        }
        if (!srcDir.exists() || !srcDir.isDirectory()) {
            throw new IllegalArgumentException("源目录不存在或不是目录: " + srcDir.getAbsolutePath());
        }

        if (!destDir.exists()) {
            try {
                Files.createDirectories(destDir.toPath());
            } catch (IOException e) {
                throw new RuntimeException("创建目标目录失败: " + destDir.getAbsolutePath(), e);
            }
        }

        File[] children = srcDir.listFiles();
        if (children != null) {
            for (File child : children) {
                copy(child, new File(destDir, child.getName()));
            }
        }
    }

    /**
     * 获取文件扩展名（不包含点号）
     *
     * @param file 文件
     * @return 扩展名，如 "txt"；如果没有扩展名则返回空字符串
     */
    public static String getExtension(File file) {
        if (file == null) {
            return "";
        }
        return getExtension(file.getName());
    }
    /**
     * 获取文件名（不包含扩展名）
     *
     * @param file 文件
     * @return 文件名
     */
    public static String getFileName(File file) {
        if (file == null) {
            return "";
        }
        String fileName = file.getName();
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(0, lastDot);
    }

    /**
     * 获取文件扩展名（不包含点号）
     *
     * @param fileName 文件名
     * @return 扩展名，如 "txt"；如果没有扩展名则返回空字符串
     */
    public static String getExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot == -1 || lastDot == fileName.length() - 1 ? "" : fileName.substring(lastDot + 1);
    }

    /**
     * 删除文件或目录（支持递归删除目录）
     *
     * @param file 要删除的文件或目录
     */
    public static void delete(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    delete(child);
                }
            }
        }
        if (!file.delete()) {
            throw new RuntimeException("删除文件失败: " + file.getAbsolutePath());
        }
    }

    public static File createFileByPath(File parent, String... child) {
        File file = new File(parent, String.join(File.separator, child));
        if (!file.exists()) {
            if (!file.mkdirs()) {
                throw new RuntimeException("创建目录失败: " + file.getAbsolutePath());
            }
        }
        return file;
    }

    public static boolean isSubFile(File prent, File child) {
        try {
            return child.getCanonicalFile().getAbsolutePath().startsWith(prent.getCanonicalFile().getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void copy(InputStream inputStream, File dest) throws IOException {
        if (!dest.exists()) {
            dest.getParentFile().mkdirs();
            dest.createNewFile();
        }
        try (InputStream input = inputStream;
             OutputStream output = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new RuntimeException("复制文件失败: " + inputStream + " -> " + dest.getAbsolutePath(), e);
        }
    }

    /**
     * 获取文件相对于根目录的相对路径
     * @param root /abc/def/
     * @param file /abc/def/ghi/jkl.txt
     * @return ghi/jkl.txt
     * @return
     */
    public static String relativePath(File root, File file){
        try {
            String path = file.getCanonicalFile().getAbsolutePath().substring(root.getCanonicalFile().getAbsolutePath().length());
            if(path.startsWith("/")){
                path = path.substring(1);
            }
            if(path.startsWith("\\")){
                path = path.substring(1);
            }
            return path;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void write(File file, String content) {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        } catch (IOException e) {
            throw new RuntimeException("写入文件失败: " + file.getAbsolutePath(), e);
        }
    }
}
