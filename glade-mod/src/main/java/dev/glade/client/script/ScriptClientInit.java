package dev.glade.client.script;

import dev.glade.client.command.ScriptCommand;
import net.fabricmc.api.ClientModInitializer;

/**
 * Secondary client entrypoint (see {@code fabric.mod.json}) that wires up the in-game
 * Python editor's {@code /glade script editor} command. Split out from
 * {@code dev.glade.client.GladeClientMod} so this feature's registration doesn't need
 * to touch that file or {@code GladeCommands}.
 */
public final class ScriptClientInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ScriptCommand.register();
    }
}
