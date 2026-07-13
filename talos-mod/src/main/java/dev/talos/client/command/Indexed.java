package dev.talos.client.command;

import java.util.List;

/**
 * Python-style source indexing, the convention every target-resolving command shares (and
 * the semantics the Python API will export): {@code 0} is the first/nearest match, {@code 1}
 * the next, {@code -1} the last/furthest, {@code -2} the second-furthest.
 */
public final class Indexed {
    private Indexed() {}

    /** Selects from a nearest-first ordered list; null when the index is out of range. */
    public static <T> T select(List<T> orderedNearestFirst, int index) {
        int size = orderedNearestFirst.size();
        int resolved = index < 0 ? size + index : index;
        return resolved >= 0 && resolved < size ? orderedNearestFirst.get(resolved) : null;
    }

    public static String rangeHint(int size) {
        return size == 0 ? "no matches" : "valid indices 0.." + (size - 1) + " or -1..-" + size;
    }
}
