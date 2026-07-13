package dev.talos.client.script;

import dev.talos.client.command.ScriptCommand;
import net.fabricmc.api.ClientModInitializer;

/**
 * Secondary client entrypoint (see {@code fabric.mod.json}) that wires up the in-game
 * Python editor's {@code /talos script editor} command. Split out from
 * {@code dev.talos.client.TalosClientMod} so this feature's registration doesn't need
 * to touch that file or {@code TalosCommands}.
 */
public final class ScriptClientInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ScriptCommand.register();
    }
}
