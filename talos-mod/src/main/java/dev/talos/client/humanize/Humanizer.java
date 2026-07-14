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

    /** Enable/disable session-arc fatigue. Enabling restarts the arc from a fresh session. */
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

    /** The un-modulated base (what /talos script profile switches); ignores Human mode. */
    public HumanizationProfile baseProfile() { return baseProfile; }

    public void setDefaultProfile(HumanizationProfile profile) {
        baseProfile = Objects.requireNonNull(profile);
        modulatedCacheStampMs = 0;
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
