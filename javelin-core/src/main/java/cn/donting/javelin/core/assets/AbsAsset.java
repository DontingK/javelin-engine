package cn.donting.javelin.core.assets;

import cn.donting.javelin.core.obj.ObjectId;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;


/**
 * guid资源
 */
public abstract class AbsAsset extends ObjectId implements Asset {
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
