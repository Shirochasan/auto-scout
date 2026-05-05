package com.autoscout.autoscout.commands;

import com.autoscout.autoscout.modules.AutoScoutModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ClearPlayersCommand extends Command {
    public ClearPlayersCommand() {
        super("clear-players", "Clears the list of reported players.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            Modules.get().get(AutoScoutModule.class).clearReportedPlayers();
            com.autoscout.autoscout.utils.Logger.log("Cleared the list of reported players.");
            info("Cleared the list of reported players.");
            return SINGLE_SUCCESS;
        });
    }
}
