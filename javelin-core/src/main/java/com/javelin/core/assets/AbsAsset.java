package com.javelin.core.assets;

import com.javelin.core.serialize.InputCapsule;
import com.javelin.core.serialize.OutputCapsule;


/**
 * guid资源
 */
public class AbsAsset implements Asset {
    private String guid;
    private long lastModified;

    @Override
    public void write(OutputCapsule output) {
        output.write("guid", guid);
        output.write("lastModified", lastModified);
    }

    @Override
    public void read(InputCapsule input) {
        guid = input.readString("guid");
        lastModified = input.readLong("lastModified");
    }

    public void setGuid(String guid) {
        this.guid = guid;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    @Override
    public long getLastModified() {
        return lastModified;
    }

    @Override
    public String getGuid() {
        return guid;
    }
}
