package dev.talos.client.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EntitySelectorTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everySelectorIdentityAcceptsBracketFilters() {
        for (String identity : List.of("e", "a", "p", "r", "n", "s")) {
            String[] error = new String[1];
            EntitySelector selector = EntitySelector.parse(
                    "@" + identity + "[tag=ready,name=!Ignored,distance=..20]", error);
            assertNotNull(selector, () -> "@" + identity + " failed: " + error[0]);
        }
    }

    @Test
    void tagTypeAndQuotedNameFiltersCompose() {
        Entity cow = new TestEntity();
        cow.addTag("ready");
        cow.setCustomName(Component.literal("Dinner Bone"));

        String[] error = new String[1];
        EntitySelector matching = EntitySelector.parse(
                "@e[type=cow,tag=ready,name=\"Dinner Bone\"]", error);
        assertNotNull(matching, error[0]);
        assertTrue(matching.matchesFilters(cow));

        EntitySelector excluded = EntitySelector.parse(
                "@e[type=!pig,tag=!hostile,name=!Alex]", error);
        assertNotNull(excluded, error[0]);
        assertTrue(excluded.matchesFilters(cow));

        EntitySelector wrongTag = EntitySelector.parse("@e[tag=missing]", error);
        assertNotNull(wrongTag, error[0]);
        assertFalse(wrongTag.matchesFilters(cow));
    }

    @Test
    void invalidLimitsAndSortsFailClearly() {
        String[] error = new String[1];
        assertNull(EntitySelector.parse("@e[limit=0]", error));
        assertTrue(error[0].contains("at least 1"));
        assertNull(EntitySelector.parse("@e[sort=sideways]", error));
        assertTrue(error[0].contains("sort"));
    }

    @Test
    void nearestAndRandomAliasesHaveTheirOwnKinds() {
        String[] error = new String[1];
        assertEquals(EntitySelector.Kind.ENTITY_NEAREST,
                EntitySelector.parse("@n", error).kind());
        assertEquals(EntitySelector.Kind.PLAYER_RANDOM,
                EntitySelector.parse("@r", error).kind());
    }

    private static final class TestEntity extends Entity {
        TestEntity() {
            super(EntityType.COW, null);
        }

        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
        @Override public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
            return false;
        }
        @Override protected void readAdditionalSaveData(ValueInput input) { }
        @Override protected void addAdditionalSaveData(ValueOutput output) { }
    }
}
