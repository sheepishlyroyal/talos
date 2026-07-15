package dev.talos.client.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.talos.client.rules.EventRuleEngine.Trigger;
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
}
