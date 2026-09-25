package com.facebook.pando;

import java.util.List;

/**
 * Stand-in for the Pando base class: the JSON view depends on the flag pair,
 * as on Threads 445, and list accessors return immutable lists.
 */
public class TreeJNI {
    private final String[] jsonByFlags;
    public List<Object> rows;

    public TreeJNI(String[] jsonByFlags) {
        this.jsonByFlags = jsonByFlags;
    }

    public String asJSONNative(boolean first, boolean second) {
        return jsonByFlags[(first ? 1 : 0) | (second ? 2 : 0)];
    }

    public List<Object> getRows() {
        return rows;
    }

    public native List<Object> nativeRows();

    public String scalar() {
        return "x";
    }
}
