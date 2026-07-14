package dev.talos.client.humanize;

/**
 * Session-arc humanization ("Human mode"): a wall-clock fatigue model that modulates the
 * active {@link HumanizationProfile} over the length of a session, plus idle micro-breaks.
 *
 * <p>The stationary profiles (Raw/Natural/Paranoid) draw from fixed distributions with
 * constant parameters — which is itself a fingerprint a server can find over hours, because
 * a real human's parameters DRIFT: reactions lengthen with fatigue, aim loosens, timing
 * variance widens, and people take breaks. This layer applies that drift on top of whatever
 * base profile is active, so the input stream stops being statistically stationary.</p>
 *
 * <p>Honest scope: this raises the cost of long-session statistical detection; it is not a
 * guarantee of undetectability, and automation may still violate a server's rules. It does
 * NOT make wrong-target "mistakes" (attacking the wrong mob, misclicking a real slot) — the
 * modelled mistakes are motor-level (overshoot, hesitation), never semantic.</p>
 */
public final class SessionArc {
    /** Active minutes at which fatigue reaches its sustained plateau. */
    private static final double RAMP_MINUTES = 150.0;
    /** Plateau fatigue after the ramp (never a full 1.0 — people don't degrade to useless). */
    private static final double PLATEAU = 0.85;
    /** A slow ~37-minute "attention" wave riding on the ramp, so fatigue isn't monotone. */
    private static final double WAVE_PERIOD_MINUTES = 37.0;
    private static final double WAVE_AMPLITUDE = 0.12;

    // Micro-break model: rare, short pauses that get more frequent as fatigue rises.
    private static final double BREAK_BASE_CHANCE_PER_SEC = 0.0015; // ~1 per 11 min when fresh
    private static final long BREAK_MIN_MS = 900;
    private static final long BREAK_MAX_MS = 6500;

    private final SeededRng rng = new SeededRng(System.nanoTime());
    private long sessionStartNanos = System.nanoTime();
    private long activeNanos;          // time actually spent working (breaks don't age you the same)
    private long lastAdvanceNanos = System.nanoTime();

    private long breakUntilMs;         // wall-clock ms; > now means we're mid-break
    private long lastBreakRollMs;

    /** Restart the arc — call when a session begins (world join) or on explicit reset. */
    public void reset() {
        sessionStartNanos = System.nanoTime();
        activeNanos = 0;
        lastAdvanceNanos = sessionStartNanos;
        breakUntilMs = 0;
    }

    /**
     * Advance the arc one tick. {@code working} is true when the bot is actively doing
     * something (pathing, an action) — idle standing still ages fatigue at a reduced rate,
     * the way sitting at a screen doing nothing is less tiring than playing.
     */
    public void tick(boolean working) {
        long now = System.nanoTime();
        long delta = now - lastAdvanceNanos;
        lastAdvanceNanos = now;
        if (delta < 0) delta = 0;
        activeNanos += working ? delta : delta / 3;
        maybeStartBreak(working);
    }

    /** Current fatigue in [0, 1]: a saturating ramp plus a slow attention wave. */
    public double fatigue() {
        double minutes = activeNanos / 60_000_000_000.0;
        double ramp = PLATEAU * (1.0 - Math.exp(-minutes / (RAMP_MINUTES / 3.0)));
        double wave = WAVE_AMPLITUDE * Math.sin(minutes / WAVE_PERIOD_MINUTES * 2.0 * Math.PI);
        return Math.clamp(ramp + wave, 0.0, 1.0);
    }

    /** Active session length in minutes (for HUD/telemetry). */
    public double activeMinutes() {
        return activeNanos / 60_000_000_000.0;
    }

    /** True while a micro-break is in progress: callers should idle (hold no keys, no actions). */
    public boolean onBreak() {
        return nowMs() < breakUntilMs;
    }

    /** Milliseconds left in the current break, or 0 if not on one. */
    public long breakRemainingMs() {
        long remaining = breakUntilMs - nowMs();
        return Math.max(0, remaining);
    }

    private void maybeStartBreak(boolean working) {
        long now = nowMs();
        if (now < breakUntilMs) return;              // already resting
        if (now - lastBreakRollMs < 1000) return;    // roll about once a second
        lastBreakRollMs = now;
        if (!working) return;                        // breaks interrupt work, not idling
        double chance = BREAK_BASE_CHANCE_PER_SEC * (1.0 + 3.0 * fatigue());
        if (rng.nextDouble() < chance) {
            long span = BREAK_MIN_MS + (long) (rng.nextDouble() * (BREAK_MAX_MS - BREAK_MIN_MS));
            // Fatigue lengthens breaks; a tired player lingers.
            breakUntilMs = now + (long) (span * (1.0 + fatigue()));
        }
    }

    /**
     * Fatigue-modulate a base profile. Reactions slow and spread, aim loosens and overshoots
     * more, and the walk wobbles wider as the session wears on. RAW ("no humanization") is
     * returned untouched — Human mode on RAW is a contradiction, so it stays a clean identity.
     */
    public HumanizationProfile modulate(HumanizationProfile base) {
        if (base == HumanizationProfile.RAW) return base;
        double f = fatigue();
        var speed = base.rotationSpeedDegPerTick();
        var over = base.overshootMagnitudeDeg();
        return new HumanizationProfile(
                base.name() + "+human",
                base.reactionMedianMs() * (1.0 + 0.55 * f),      // slower reactions
                base.reactionSigma() * (1.0 + 0.65 * f),         // wider spread
                // Tired hands turn a touch slower AND less consistently: widen the range.
                new HumanizationProfile.Range(
                        Math.max(1.0, speed.min() * (1.0 - 0.30 * f)),
                        speed.max() * (1.0 - 0.12 * f)),
                Math.max(1.0, base.maxAngularAccelDegPerTick2() * (1.0 - 0.20 * f)),
                Math.clamp(base.overshootProbability() * (1.0 + 1.3 * f), 0.0, 0.95),
                new HumanizationProfile.Range(over.min(), over.max() * (1.0 + 0.6 * f)),
                base.timingJitterPhi(),
                base.pathDeviationStdev() * (1.0 + 0.7 * f),
                base.allowedTrajectoryFamilies(),
                base.alwaysVisibilityChecked());
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
