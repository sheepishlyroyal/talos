package dev.talos.client.humanize;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/**
 * Facade for action-scoped humanization planners.
 * This is best-effort obfuscation of automated input timing and rotation, not a
 * guarantee of undetectability.
 */
public final class Humanizer {
    private final RotationHumanizer rotation = new RotationHumanizer();
    private final TimingHumanizer timing = new TimingHumanizer();
    private final MovementHumanizer movement = new MovementHumanizer();
    private volatile HumanizationProfile selectedProfile = HumanizationProfile.NATURAL;
    private final HumanizationOverrides overrides = new HumanizationOverrides();
    /** selectedProfile with user tuning applied; recomputed on every tuning/profile change. */
    private volatile HumanizationProfile baseProfile = HumanizationProfile.NATURAL;

    // Session-arc ("Human mode"): when enabled, defaultProfile() returns the base profile
    // fatigue-modulated by the arc, so every consumer drifts over the session with no other
    // wiring. The modulated result is cached and refreshed on a throttle (defaultProfile()
    // is read every tick by the follower) so per-tick cost stays a field read.
    private final SessionArc sessionArc = new SessionArc();
    private volatile boolean humanMode;
    private volatile HumanizationProfile modulatedCache = HumanizationProfile.NATURAL;
    private volatile long modulatedCacheStampMs;

    public SessionArc sessionArc() { return sessionArc; }
    public boolean humanMode() { return humanMode; }

    /** Human mode owns eased cube aiming as well as session-arc modulation. */
    public boolean humanizedAim() { return humanMode; }

    /** Enable/disable humanized aim and session-arc fatigue. Enabling starts a fresh session. */
    public void setHumanMode(boolean enabled) {
        if (enabled && !humanMode) sessionArc.reset();
        humanMode = enabled;
        modulatedCacheStampMs = 0; // force a refresh on next read
    }

    public HumanizationProfile defaultProfile() {
        if (!humanMode) return baseProfile;
        long now = System.nanoTime() / 1_000_000L;
        if (now - modulatedCacheStampMs >= 500) {
            modulatedCache = sessionArc.modulate(baseProfile);
            modulatedCacheStampMs = now;
        }
        return modulatedCache;
    }

    /** The un-modulated base (selected profile + user tuning); ignores Human mode. */
    public HumanizationProfile baseProfile() { return baseProfile; }

    public void setDefaultProfile(HumanizationProfile profile) {
        selectedProfile = Objects.requireNonNull(profile);
        refreshTuning();
    }

    /** User tuning applied on top of the selected profile (intensity + per-knob overrides). */
    public HumanizationOverrides overrides() { return overrides; }

    /** Re-derives the tuned base profile after any overrides mutation. */
    public void refreshTuning() {
        baseProfile = overrides.apply(selectedProfile);
        modulatedCacheStampMs = 0;
    }

    public void setIntensity(double value) {
        overrides.setIntensity(value);
        refreshTuning();
    }

    /** Sets a named knob (see {@link HumanizationOverrides.Knob}); unknown names throw. */
    public void setKnob(String key, double value) {
        overrides.set(HumanizationOverrides.Knob.byKey(key), value);
        refreshTuning();
    }

    /** Restricts trajectory families from a csv ("bezier,min_jerk,linear"); blank resets. */
    public void setFamilies(String csv) {
        overrides.setFamilies(csv);
        refreshTuning();
    }

    public void resetTuning() {
        overrides.reset();
        refreshTuning();
    }
    public RotationHumanizer rotation() { return rotation; }
    public TimingHumanizer timing() { return timing; }
    public MovementHumanizer movement() { return movement; }

    public RotationHumanizer.RotationPlan lookAt(Vec3 eye, Vec3 target,
                                                  HumanizationProfile profile, long seed) {
        float[] targetAngles = RotationHumanizer.yawPitchTo(eye, target);
        return rotation.plan(0, 0, targetAngles[0], targetAngles[1], profile, new SeededRng(seed));
    }
}
