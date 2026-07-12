package dev.glade.client.pathing;

/** Per-request path options. A node count of zero selects automatic density. */
public record PathingOptions(int nodeCount) {
    public static final PathingOptions DEFAULT = new PathingOptions(0);

    public PathingOptions() { this(0); }

    public PathingOptions {
        if (nodeCount < 0) throw new IllegalArgumentException("nodeCount must be >= 0");
    }
}
