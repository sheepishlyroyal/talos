package dev.talos.client.humanize;

import net.minecraft.util.math.Vec3d;

import java.util.Objects;

/**
 * Facade for action-scoped humanization planners.
 * This is best-effort obfuscation of automated input timing and rotation, not a
 * guarantee of undetectability.
 */
public final class Humanizer {
    private final RotationHumanizer rotation = new RotationHumanizer();
    private final TimingHumanizer timing = new TimingHumanizer();
    private final MovementHumanizer movement = new MovementHumanizer();
    private volatile HumanizationProfile defaultProfile = HumanizationProfile.NATURAL;

    public HumanizationProfile defaultProfile() { return defaultProfile; }
    public void setDefaultProfile(HumanizationProfile profile) { defaultProfile = Objects.requireNonNull(profile); }
    public RotationHumanizer rotation() { return rotation; }
    public TimingHumanizer timing() { return timing; }
    public MovementHumanizer movement() { return movement; }

    public RotationHumanizer.RotationPlan lookAt(Vec3d eye, Vec3d target,
                                                  HumanizationProfile profile, long seed) {
        float[] targetAngles = RotationHumanizer.yawPitchTo(eye, target);
        return rotation.plan(0, 0, targetAngles[0], targetAngles[1], profile, new SeededRng(seed));
    }
}
