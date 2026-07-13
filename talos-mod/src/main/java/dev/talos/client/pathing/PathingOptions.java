package dev.talos.client.pathing;

/** Per-request path options. A node count of zero selects automatic density. */
public record PathingOptions(int nodeCount, boolean allowMining) {
    public static final PathingOptions DEFAULT = new PathingOptions(0, true);
    /** Movement only — never breaks blocks (jump/swim/pillar/route around instead). Used by /talos goto. */
    public static final PathingOptions NAVIGATE_ONLY = new PathingOptions(0, false);

    public PathingOptions() { this(0, true); }
    public PathingOptions(int nodeCount) { this(nodeCount, true); }

    public PathingOptions {
        if (nodeCount < 0) throw new IllegalArgumentException("nodeCount must be >= 0");
    }
}
