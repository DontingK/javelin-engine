package cn.donting.javelin.core.assets;

import cn.donting.javelin.core.serialize.Serializable;

/**
 * guid资源
 */
public interface Asset extends Serializable {
    String getGuid();
    long getLastModified();
}
