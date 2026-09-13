package com.javelin.core.serialize.binary;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 二进制流输出实现
 *
 * <p>包装 OutputStream，提供基本类型的二进制写入</p>
 *
 * @author javelin
 * @since 1.0
 */
public class BinaryStreamOutput implements Closeable {

    private final DataOutputStream dataOutput;

    public BinaryStreamOutput(OutputStream outputStream) {
        this.dataOutput = new DataOutputStream(outputStream);
    }

    /**
     * 写入 byte 值
     */
    public void write(byte value) {
        try {
            dataOutput.writeByte(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 byte 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入 short 值（大端序）
     */
    public void write(short value) {
        try {
            dataOutput.writeShort(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 short 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入 int 值（大端序）
     */
    public void write(int value) {
        try {
            dataOutput.writeInt(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 int 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入 long 值（大端序）
     */
    public void write(long value) {
        try {
            dataOutput.writeLong(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 long 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入 float 值（IEEE 754，大端序）
     */
    public void write(float value) {
        try {
            dataOutput.writeFloat(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 float 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入 double 值（IEEE 754，大端序）
     */
    public void write(double value) {
        try {
            dataOutput.writeDouble(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 double 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入 boolean 值（1 字节，true = 1，false = 0）
     */
    public void write(boolean value) {
        try {
            dataOutput.writeBoolean(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 boolean 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入 char 值（UTF-16，2 字节）
     */
    public void write(char value) {
        try {
            dataOutput.writeChar(value);
        } catch (IOException e) {
            throw new RuntimeException("写入 char 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入 String 值（先写长度 int，再写 UTF-8 字节）
     */
    public void write(String value) {
        try {
            if (value == null) {
                dataOutput.writeInt(-1);
                return;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            dataOutput.writeInt(bytes.length);
            dataOutput.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException("写入 String 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写入原始字节数组
     */
    public void write(byte[] bytes) {
        try {
            dataOutput.write(bytes);
        } catch (IOException e) {
            throw new RuntimeException("写入 byte[] 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 刷新缓冲区
     */
    public void flush() {
        try {
            dataOutput.flush();
        } catch (IOException e) {
            throw new RuntimeException("刷新失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取已写入的字节数
     */
    public int size() {
        return dataOutput.size();
    }

    @Override
    public void close() throws IOException {
        dataOutput.close();
    }
}
