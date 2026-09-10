package com.javelin.rhi.api;

/**
 * 命令池。
 */
public abstract class CommandPool extends GPUResource {

    public abstract Queue queue();

    public abstract void reset();
}
