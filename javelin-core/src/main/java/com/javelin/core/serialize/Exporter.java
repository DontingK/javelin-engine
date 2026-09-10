package com.javelin.core.serialize;

import java.io.IOException;

public interface Exporter {
    void export(Serializable serializable) throws IOException;
}
