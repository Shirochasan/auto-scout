package com.autoscout.autoscout;

import com.autoscout.autoscout.utils.Config;
import com.autoscout.autoscout.commands.AutoScoutCommand;
import com.autoscout.autoscout.commands.ClearStashesCommand;
import com.autoscout.autoscout.commands.ClearPlayersCommand;
import com.autoscout.autoscout.events.PlayerDisconnectEvent;
import com.autoscout.autoscout.hud.AutoScoutHud;
import com.autoscout.autoscout.modules.AltitudeLossDetector;
import com.autoscout.autoscout.modules.NewerNewChunks;
import com.autoscout.autoscout.modules.AutoScoutModule;
import com.autoscout.autoscout.modules.StuckDetector;
import com.autoscout.autoscout.modules.AutoElytraRepair;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;

public class AutoScout extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Auto-Scout");
    public static final HudGroup HUD_GROUP = new HudGroup("Auto-Scout");


    @Override
    public void onInitialize() {
        LOG.info("Initializing Auto-Scout");

        Config.load();

        // Modules
        Modules.get().add(new AutoScoutModule());
        Modules.get().add(new StuckDetector());
        Modules.get().add(new AltitudeLossDetector());
        Modules.get().add(new NewerNewChunks());
        Modules.get().add(new AutoElytraRepair());

        // Commands
        Commands.add(new AutoScoutCommand());
        Commands.add(new ClearStashesCommand());
        Commands.add(new ClearPlayersCommand());

        // HUD
        Hud.get().register(AutoScoutHud.INFO);

        // Events
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            MeteorClient.EVENT_BUS.post(PlayerDisconnectEvent.get());
        });
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.autoscout.autoscout";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("shirochasan", "Auto-Scout");
    }
}
