package dev.talos.client.pathing.sim;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;

/** Immutable snapshot of the live movement modifiers used by planner rollouts. */
public record MovementProfile(
        double movementSpeed,
        double jumpVelocity,
        double jumpBoost,
        double gravity,
        double waterHorizontalDrag,
        double lavaDrag,
        int soulSpeedLevel,
        boolean slowFalling,
        int levitationLevel,
        double sneakSlowFactor,
        double stepHeight) {

    private static final double BASE_MOVEMENT_SPEED = 0.1;
    private static final double BASE_JUMP_VELOCITY = 0.42;
    private static final double BASE_GRAVITY = 0.08;
    private static final double BASE_WATER_DRAG = 0.8;
    private static final double BASE_LAVA_DRAG = 0.5;

    public MovementProfile {
        if (movementSpeed < 0.0 || jumpVelocity < 0.0 || jumpBoost < 0.0 || gravity < 0.0
                || waterHorizontalDrag < 0.0 || waterHorizontalDrag > 1.0
                || lavaDrag < 0.0 || lavaDrag > 1.0 || soulSpeedLevel < 0
                || levitationLevel < 0 || sneakSlowFactor < 0.0 || stepHeight < 0.0) {
            throw new IllegalArgumentException("invalid movement profile value");
        }
    }

    /** Captures all entity-dependent state once; simulation itself never touches the entity. */
    public static MovementProfile capture(LivingEntity player) {
        if (player == null) return vanilla();

        double movementSpeed = attribute(player, EntityAttributes.MOVEMENT_SPEED,
                BASE_MOVEMENT_SPEED);
        // Vanilla setSprinting() installs a MULTIPLY_TOTAL 0.3 modifier on MOVEMENT_SPEED,
        // so a mid-sprint capture already contains the sprint boost. The simulator applies
        // its own x1.3 per sprinting input; keeping both made every sprint rollout ~30%
        // too fast (predictions ran ahead of the real player all run long).
        if (player.isSprinting()) movementSpeed /= 1.3;
        // Players expose JUMP_STRENGTH in 1.21.11. Jump Boost is additive AFTER the block
        // jump multiplier (vanilla: base * multiplier + boost), so it is carried separately.
        double jumpVelocity = attribute(player, EntityAttributes.JUMP_STRENGTH,
                BASE_JUMP_VELOCITY);
        StatusEffectInstance jumpBoostEffect = player.getStatusEffect(StatusEffects.JUMP_BOOST);
        double jumpBoost = jumpBoostEffect == null ? 0.0
                : 0.1 * (jumpBoostEffect.getAmplifier() + 1);

        double gravity = attribute(player, EntityAttributes.GRAVITY, BASE_GRAVITY);
        boolean slowFalling = player.getStatusEffect(StatusEffects.SLOW_FALLING) != null;
        StatusEffectInstance levitation = player.getStatusEffect(StatusEffects.LEVITATION);
        int levitationLevel = levitation == null ? 0 : levitation.getAmplifier() + 1;

        int depthStrider = equipmentLevel(player, Enchantments.DEPTH_STRIDER);
        int soulSpeed = equipmentLevel(player, Enchantments.SOUL_SPEED);
        double depthFraction = Math.min(depthStrider, 3) / 3.0;
        double waterDrag = BASE_WATER_DRAG + (1.0 - BASE_WATER_DRAG) * depthFraction;
        double waterEfficiency = attribute(player, EntityAttributes.WATER_MOVEMENT_EFFICIENCY, 0.0);
        waterDrag += (1.0 - waterDrag) * clamp01(waterEfficiency);
        if (player.getStatusEffect(StatusEffects.DOLPHINS_GRACE) != null) {
            waterDrag = Math.max(waterDrag, 0.96);
        }

        double sneakFactor = attribute(player, EntityAttributes.SNEAKING_SPEED, 1.0);
        double stepHeight = attribute(player, EntityAttributes.STEP_HEIGHT, 0.6);
        return new MovementProfile(movementSpeed, jumpVelocity, jumpBoost, gravity, waterDrag,
                BASE_LAVA_DRAG, soulSpeed, slowFalling, levitationLevel,
                sneakFactor, stepHeight);
    }

    /** Baseline profile for tests and callers without a live player. */
    public static MovementProfile vanilla() {
        return new MovementProfile(BASE_MOVEMENT_SPEED, BASE_JUMP_VELOCITY, 0.0, BASE_GRAVITY,
                BASE_WATER_DRAG, BASE_LAVA_DRAG, 0, false, 0, 1.0, 0.6);
    }

    private static double attribute(LivingEntity player,
            RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attribute,
            double fallback) {
        return player.getAttributes().hasAttribute(attribute)
                ? player.getAttributeValue(attribute) : fallback;
    }

    private static int equipmentLevel(LivingEntity player,
            net.minecraft.registry.RegistryKey<Enchantment> enchantment) {
        try {
            RegistryEntry<Enchantment> entry = player.getRegistryManager()
                    .getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(enchantment);
            return EnchantmentHelper.getEquipmentLevel(entry, player);
        } catch (IllegalStateException exception) {
            // A partially initialized client registry should not disable the whole simulator.
            return 0;
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
