package com.javelin.core.assets;

import com.javelin.core.serialize.Serializable;

/**
 * guid资源
 */
public interface Asset extends Serializable {
    String getGuid();
    long getLastModified();
}
