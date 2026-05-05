package com.autoscout.autoscout.commands;

import com.autoscout.autoscout.modules.AutoScoutModule;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ClearStashesCommand extends Command {
    public ClearStashesCommand() {
        super("clear-stashes", "Clears the list of found stashes.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            Modules.get().get(AutoScoutModule.class).clearReportedStashes();
            com.autoscout.autoscout.utils.Logger.log("Cleared the list of reported stashes.");
            info("Cleared the list of reported stashes.");
            return SINGLE_SUCCESS;
        });
    }
}
