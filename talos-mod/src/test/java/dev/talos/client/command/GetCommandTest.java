package dev.talos.client.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.talos.client.rules.EventRuleEngine.Trigger;
import dev.talos.client.rules.EventRuleEngine;
import org.junit.jupiter.api.Test;

class GetCommandTest {
    @Test
    void catalogContainsEveryTriggerAndEntityLocation() {
        var catalog = GetCommand.catalogNames();
        for (Trigger trigger : Trigger.values()) {
            assertTrue(catalog.contains(trigger.id()), () -> "missing getter for " + trigger.id());
        }
        assertTrue(catalog.contains("server_tps"));
        assertTrue(catalog.contains("entity_location"));
    }

    @Test
    void spatialTriggerFamiliesAdvertisePointSupport() {
        for (Trigger trigger : new Trigger[] {
                Trigger.LIGHT_LEVEL, Trigger.ENTITY_TOTAL,
                Trigger.NEAREST_PLAYER_DISTANCE, Trigger.NEAREST_HOSTILE_DISTANCE,
                Trigger.NEAREST_ANIMAL_DISTANCE, Trigger.NEAREST_ITEM_DISTANCE,
                Trigger.DROPPED_ITEMS_NEAR, Trigger.XP_ORBS_NEAR, Trigger.ARROWS_NEAR,
                Trigger.SPAWN_DISTANCE, Trigger.WORLD_BORDER_DISTANCE,
                Trigger.ENTITY_COUNT, Trigger.ENTITY_NEAR, Trigger.ENTITY_GONE,
                Trigger.BLOCK_COUNT, Trigger.BLOCK_NEAR}) {
            assertTrue(EventRuleEngine.acceptsPoint(trigger),
                    () -> "missing point support for " + trigger.id());
        }
        for (Trigger trigger : new Trigger[] {Trigger.ENTITY_TOTAL,
                Trigger.NEAREST_PLAYER_DISTANCE, Trigger.NEAREST_HOSTILE_DISTANCE,
                Trigger.NEAREST_ANIMAL_DISTANCE, Trigger.NEAREST_ITEM_DISTANCE,
                Trigger.DROPPED_ITEMS_NEAR, Trigger.XP_ORBS_NEAR, Trigger.ARROWS_NEAR}) {
            assertTrue(EventRuleEngine.acceptsSpatialRadius(trigger),
                    () -> "missing spatial radius support for " + trigger.id());
        }
    }
}
