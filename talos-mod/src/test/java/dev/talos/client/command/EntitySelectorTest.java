package dev.talos.client.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
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
        String[] error = new String[1];
        EntitySelector matching = EntitySelector.parse(
                "@e[type=cow,tag=ready,name=\"Dinner Bone\"]", error);
        assertNotNull(matching, error[0]);
        assertTrue(matching.matchesFilters(EntityTypes.COW, Set.of("ready"), "Dinner Bone"));

        EntitySelector excluded = EntitySelector.parse(
                "@e[type=!pig,tag=!hostile,name=!Alex]", error);
        assertNotNull(excluded, error[0]);
        assertTrue(excluded.matchesFilters(EntityTypes.COW, Set.of("ready"), "Dinner Bone"));

        EntitySelector wrongTag = EntitySelector.parse("@e[tag=missing]", error);
        assertNotNull(wrongTag, error[0]);
        assertFalse(wrongTag.matchesFilters(EntityTypes.COW, Set.of("ready"), "Dinner Bone"));
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

}
