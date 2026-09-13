package com.javelin.core.serialize.binary;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 二进制流输入实现
 *
 * <p>包装 InputStream，提供基本类型的二进制读取</p>
 *
 * @author javelin
 * @since 1.0
 */
public class BinaryStreamInput implements Closeable {

    private final DataInputStream dataInput;

    public BinaryStreamInput(InputStream inputStream) {
        this.dataInput = new DataInputStream(inputStream);
    }

    /**
     * 读取 byte 值
     */
    public byte readByte() {
        try {
            return dataInput.readByte();
        } catch (IOException e) {
            throw new RuntimeException("读取 byte 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 short 值（大端序）
     */
    public short readShort() {
        try {
            return dataInput.readShort();
        } catch (IOException e) {
            throw new RuntimeException("读取 short 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 int 值（大端序）
     */
    public int readInt() {
        try {
            return dataInput.readInt();
        } catch (IOException e) {
            throw new RuntimeException("读取 int 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 long 值（大端序）
     */
    public long readLong() {
        try {
            return dataInput.readLong();
        } catch (IOException e) {
            throw new RuntimeException("读取 long 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 float 值（IEEE 754，大端序）
     */
    public float readFloat() {
        try {
            return dataInput.readFloat();
        } catch (IOException e) {
            throw new RuntimeException("读取 float 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 double 值（IEEE 754，大端序）
     */
    public double readDouble() {
        try {
            return dataInput.readDouble();
        } catch (IOException e) {
            throw new RuntimeException("读取 double 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 boolean 值
     */
    public boolean readBoolean() {
        try {
            return dataInput.readBoolean();
        } catch (IOException e) {
            throw new RuntimeException("读取 boolean 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 char 值（UTF-16，2 字节）
     */
    public char readChar() {
        try {
            return dataInput.readChar();
        } catch (IOException e) {
            throw new RuntimeException("读取 char 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 String 值（先读长度 int，再读 UTF-8 字节）
     */
    public String readString() {
        try {
            int length = dataInput.readInt();
            if (length == -1) {
                return null;
            }
            byte[] bytes = new byte[length];
            dataInput.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取 String 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 跳过指定字节数
     */
    public void skipBytes(int n) {
        try {
            dataInput.skipBytes(n);
        } catch (IOException e) {
            throw new RuntimeException("跳过字节失败: " + e.getMessage(), e);
        }
    }

    /**
     * 返回可读字节数估算
     */
    public int available() {
        try {
            return dataInput.available();
        } catch (IOException e) {
            throw new RuntimeException("获取可用字节失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        dataInput.close();
    }
}
