package com.autoscout.autoscout.modules;

import com.autoscout.autoscout.AutoScout;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
//import meteordevelopment.meteorclient.settings.SettingColor;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.fluid.FluidState;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
    Ported from:
    https://github.com/BleachDrinker420/BleachHack/blob/master/BleachHack-Fabric-1.16/src/main/java/bleach/hack/module/mods/NewChunks.java
    updated by etianl :D

    Rewritten for:
    - Minecraft 1.21.4
    - Yarn 1.21.4+build.4
    - Fabric Loader 0.15.11
*/
public class NewerNewChunks extends Module {
    public enum DetectMode {
        Normal,
        IgnoreBlockExploit,
        BlockExploitMode
    }

    private final SettingGroup specialGroup = settings.createGroup("Detection Settings");
    private final SettingGroup specialGroup2 = settings.createGroup("Detection for chunks that were generated in old versions.");
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCdata = settings.createGroup("Saved Chunk Data");
    private final SettingGroup sgcacheCdata = settings.createGroup("Cached Chunk Data");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> PaletteExploit = specialGroup.add(new BoolSetting.Builder()
        .name("PaletteExploit")
        .description("Detects new chunks by scanning the order of chunk section palettes. Highlights chunks being updated from an old version. Only works for server versions >= 1.18!")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> beingUpdatedDetector = specialGroup.add(new BoolSetting.Builder()
        .name("Detection for chunks that haven't been explored since <=1.17")
        .description("Marks chunks as their own color if they are currently being updated from old version. Requires PaletteExploit and server version >= 1.18!")
        .defaultValue(true)
        .visible(() -> PaletteExploit.get())
        .build()
    );

    private final Setting<Boolean> overworldOldChunksDetector = specialGroup2.add(new BoolSetting.Builder()
        .name("Pre 1.17 Overworld OldChunk Detector")
        .description("Marks chunks as generated in an old version if they have specific blocks above Y 0 and are in the overworld.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> netherOldChunksDetector = specialGroup2.add(new BoolSetting.Builder()
        .name("Pre 1.16 Nether OldChunk Detector")
        .description("Marks chunks as generated in an old version if they are missing blocks found in the new Nether.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> endOldChunksDetector = specialGroup2.add(new BoolSetting.Builder()
        .name("Pre 1.13 End OldChunk Detector")
        .description("Marks chunks as generated in an old version if they have the biome of minecraft:the_end.")
        .defaultValue(true)
        .build()
    );

    public final Setting<DetectMode> detectmode = sgGeneral.add(new EnumSetting.Builder<DetectMode>()
        .name("Chunk Detection Mode")
        .description("Anything other than normal is for old servers where build limits are being increased due to updates.")
        .defaultValue(DetectMode.Normal)
        .build()
    );

    public final Setting<Boolean> dynamicTrailDetection = sgGeneral.add(new BoolSetting.Builder()
        .name("dynamic-trail-detection")
        .description("Enables dynamic following of new chunk trails.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> liquidexploit = sgGeneral.add(new BoolSetting.Builder()
        .name("LiquidExploit")
        .description("Estimates newchunks based on flowing liquids.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> blockupdateexploit = sgGeneral.add(new BoolSetting.Builder()
        .name("BlockUpdateExploit")
        .description("Estimates newchunks based on block updates. THESE MAY POSSIBLY BE OLD. BlockExploitMode needed to help determine false positives.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> remove = sgcacheCdata.add(new BoolSetting.Builder()
        .name("RemoveOnModuleDisabled")
        .description("Removes the cached chunks when disabling the module.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> worldleaveremove = sgcacheCdata.add(new BoolSetting.Builder()
        .name("RemoveOnLeaveWorldOrChangeDimensions")
        .description("Removes the cached chunks when leaving the world or changing dimensions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> removerenderdist = sgcacheCdata.add(new BoolSetting.Builder()
        .name("RemoveOutsideRenderDistance")
        .description("Removes the cached chunks when they leave the defined render distance.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> save = sgCdata.add(new BoolSetting.Builder()
        .name("SaveChunkData")
        .description("Saves the cached chunks to a file.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> load = sgCdata.add(new BoolSetting.Builder()
        .name("LoadChunkData")
        .description("Loads the saved chunks from the file.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoreload = sgCdata.add(new BoolSetting.Builder()
        .name("AutoReloadChunks")
        .description("Reloads the chunks automatically from your savefiles on a delay.")
        .defaultValue(false)
        .visible(load::get)
        .build()
    );

    private final Setting<Integer> removedelay = sgCdata.add(new IntSetting.Builder()
        .name("AutoReloadDelayInSeconds")
        .description("Reloads the chunks automatically from your savefiles on a delay.")
        .sliderRange(1, 300)
        .defaultValue(60)
        .visible(() -> autoreload.get() && load.get())
        .build()
    );

    public final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("Render-Distance(Chunks)")
        .description("How many chunks from the character to render the detected chunks.")
        .defaultValue(64)
        .min(6)
        .sliderRange(6, 1024)
        .build()
    );

    public final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
        .name("render-height")
        .description("The height at which new chunks will be rendered")
        .defaultValue(0)
        .sliderRange(-112, 319)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> newChunksSideColor = sgRender.add(new ColorSetting.Builder()
        .name("new-chunks-side-color")
        .description("Color of the chunks that are completely new.")
        .defaultValue(new SettingColor(255, 0, 0, 95))
        .visible(() -> shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> tickexploitChunksSideColor = sgRender.add(new ColorSetting.Builder()
        .name("BlockExploitChunks-side-color")
        .description("MAY POSSIBLY BE OLD. Color of the chunks that have been triggered via block ticking packets")
        .defaultValue(new SettingColor(0, 0, 255, 75))
        .visible(() -> (shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both) && detectmode.get() == DetectMode.BlockExploitMode)
        .build()
    );

    private final Setting<SettingColor> oldChunksSideColor = sgRender.add(new ColorSetting.Builder()
        .name("old-chunks-side-color")
        .description("Color of the chunks that have been loaded before.")
        .defaultValue(new SettingColor(0, 255, 0, 40))
        .visible(() -> shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> beingUpdatedOldChunksSideColor = sgRender.add(new ColorSetting.Builder()
        .name("being-updated-chunks-side-color")
        .description("Color of the chunks that haven't been explored since versions <=1.17.")
        .defaultValue(new SettingColor(255, 210, 0, 60))
        .visible(() -> shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> OldGenerationOldChunksSideColor = sgRender.add(new ColorSetting.Builder()
        .name("old-version-chunks-side-color")
        .description("Color of the chunks that have been loaded before in old versions.")
        .defaultValue(new SettingColor(190, 255, 0, 40))
        .visible(() -> shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> newChunksLineColor = sgRender.add(new ColorSetting.Builder()
        .name("new-chunks-line-color")
        .description("Color of the chunks that are completely new.")
        .defaultValue(new SettingColor(255, 0, 0, 205))
        .visible(() -> shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> tickexploitChunksLineColor = sgRender.add(new ColorSetting.Builder()
        .name("BlockExploitChunks-line-color")
        .description("MAY POSSIBLY BE OLD. Color of the chunks that have been triggered via block ticking packets")
        .defaultValue(new SettingColor(0, 0, 255, 170))
        .visible(() -> (shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both) && detectmode.get() == DetectMode.BlockExploitMode)
        .build()
    );

    private final Setting<SettingColor> oldChunksLineColor = sgRender.add(new ColorSetting.Builder()
        .name("old-chunks-line-color")
        .description("Color of the chunks that have been loaded before.")
        .defaultValue(new SettingColor(0, 255, 0, 80))
        .visible(() -> shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> beingUpdatedOldChunksLineColor = sgRender.add(new ColorSetting.Builder()
        .name("being-updated-chunks-line-color")
        .description("Color of the chunks that haven't been explored since versions <=1.17.")
        .defaultValue(new SettingColor(255, 220, 0, 100))
        .visible(() -> shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> OldGenerationOldChunksLineColor = sgRender.add(new ColorSetting.Builder()
        .name("old-version-chunks-line-color")
        .description("Color of the chunks that have been loaded before in old versions.")
        .defaultValue(new SettingColor(190, 255, 0, 80))
        .visible(() -> shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private int deletewarningTicks = 666;
    private int deletewarning = 0;
    private int errticks = 0;
    private int autoreloadticks = 0;
    private int loadingticks = 0;

    private boolean worldchange = false;
    private boolean lastSaveSetting = false;

    private String serverip;
    private String world;

    private final Set<ChunkPos> newChunks = Collections.synchronizedSet(new HashSet<>());
    private final Set<ChunkPos> oldChunks = Collections.synchronizedSet(new HashSet<>());
    private final Set<ChunkPos> beingUpdatedOldChunks = Collections.synchronizedSet(new HashSet<>());
    private final Set<ChunkPos> OldGenerationOldChunks = Collections.synchronizedSet(new HashSet<>());
    private final Set<ChunkPos> tickexploitChunks = Collections.synchronizedSet(new HashSet<>());

    private static final Direction[] searchDirs = new Direction[] {
        Direction.EAST, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.UP
    };

    private static final Set<Block> ORE_BLOCKS = new HashSet<>();
    private static final Set<Block> DEEPSLATE_BLOCKS = new HashSet<>();
    private static final Set<Block> NEW_OVERWORLD_BLOCKS = new HashSet<>();
    private static final Set<Block> NEW_NETHER_BLOCKS = new HashSet<>();

    private static final Path OLD_FILE = Paths.get("OldChunkData.txt");
    private static final Path BEING_UPDATED_FILE = Paths.get("BeingUpdatedChunkData.txt");
    private static final Path OLD_GENERATION_FILE = Paths.get("OldGenerationChunkData.txt");
    private static final Path NEW_FILE = Paths.get("NewChunkData.txt");
    private static final Path BLOCK_EXPLOIT_FILE = Paths.get("BlockExploitChunkData.txt");

    static {
        ORE_BLOCKS.add(Blocks.COAL_ORE);
        ORE_BLOCKS.add(Blocks.DEEPSLATE_COAL_ORE);
        ORE_BLOCKS.add(Blocks.COPPER_ORE);
        ORE_BLOCKS.add(Blocks.DEEPSLATE_COPPER_ORE);
        ORE_BLOCKS.add(Blocks.IRON_ORE);
        ORE_BLOCKS.add(Blocks.DEEPSLATE_IRON_ORE);
        ORE_BLOCKS.add(Blocks.GOLD_ORE);
        ORE_BLOCKS.add(Blocks.DEEPSLATE_GOLD_ORE);
        ORE_BLOCKS.add(Blocks.LAPIS_ORE);
        ORE_BLOCKS.add(Blocks.DEEPSLATE_LAPIS_ORE);
        ORE_BLOCKS.add(Blocks.DIAMOND_ORE);
        ORE_BLOCKS.add(Blocks.DEEPSLATE_DIAMOND_ORE);
        ORE_BLOCKS.add(Blocks.REDSTONE_ORE);
        ORE_BLOCKS.add(Blocks.DEEPSLATE_REDSTONE_ORE);
        ORE_BLOCKS.add(Blocks.EMERALD_ORE);
        ORE_BLOCKS.add(Blocks.DEEPSLATE_EMERALD_ORE);

        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_COPPER_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_IRON_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_COAL_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_REDSTONE_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_EMERALD_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_GOLD_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_LAPIS_ORE);
        DEEPSLATE_BLOCKS.add(Blocks.DEEPSLATE_DIAMOND_ORE);

        NEW_OVERWORLD_BLOCKS.add(Blocks.DEEPSLATE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.AMETHYST_BLOCK);
        NEW_OVERWORLD_BLOCKS.add(Blocks.BUDDING_AMETHYST);
        NEW_OVERWORLD_BLOCKS.add(Blocks.AZALEA);
        NEW_OVERWORLD_BLOCKS.add(Blocks.FLOWERING_AZALEA);
        NEW_OVERWORLD_BLOCKS.add(Blocks.BIG_DRIPLEAF);
        NEW_OVERWORLD_BLOCKS.add(Blocks.BIG_DRIPLEAF_STEM);
        NEW_OVERWORLD_BLOCKS.add(Blocks.SMALL_DRIPLEAF);
        NEW_OVERWORLD_BLOCKS.add(Blocks.CAVE_VINES);
        NEW_OVERWORLD_BLOCKS.add(Blocks.CAVE_VINES_PLANT);
        NEW_OVERWORLD_BLOCKS.add(Blocks.SPORE_BLOSSOM);
        NEW_OVERWORLD_BLOCKS.add(Blocks.COPPER_ORE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.DEEPSLATE_COPPER_ORE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.DEEPSLATE_IRON_ORE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.DEEPSLATE_COAL_ORE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.DEEPSLATE_REDSTONE_ORE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.DEEPSLATE_EMERALD_ORE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.DEEPSLATE_GOLD_ORE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.DEEPSLATE_LAPIS_ORE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.DEEPSLATE_DIAMOND_ORE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.GLOW_LICHEN);
        NEW_OVERWORLD_BLOCKS.add(Blocks.RAW_COPPER_BLOCK);
        NEW_OVERWORLD_BLOCKS.add(Blocks.RAW_IRON_BLOCK);
        NEW_OVERWORLD_BLOCKS.add(Blocks.DRIPSTONE_BLOCK);
        NEW_OVERWORLD_BLOCKS.add(Blocks.MOSS_BLOCK);
        NEW_OVERWORLD_BLOCKS.add(Blocks.MOSS_CARPET);
        NEW_OVERWORLD_BLOCKS.add(Blocks.POINTED_DRIPSTONE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.SMOOTH_BASALT);
        NEW_OVERWORLD_BLOCKS.add(Blocks.TUFF);
        NEW_OVERWORLD_BLOCKS.add(Blocks.CALCITE);
        NEW_OVERWORLD_BLOCKS.add(Blocks.HANGING_ROOTS);
        NEW_OVERWORLD_BLOCKS.add(Blocks.ROOTED_DIRT);
        NEW_OVERWORLD_BLOCKS.add(Blocks.AZALEA_LEAVES);
        NEW_OVERWORLD_BLOCKS.add(Blocks.FLOWERING_AZALEA_LEAVES);
        NEW_OVERWORLD_BLOCKS.add(Blocks.POWDER_SNOW);

        NEW_NETHER_BLOCKS.add(Blocks.ANCIENT_DEBRIS);
        NEW_NETHER_BLOCKS.add(Blocks.BASALT);
        NEW_NETHER_BLOCKS.add(Blocks.BLACKSTONE);
        NEW_NETHER_BLOCKS.add(Blocks.GILDED_BLACKSTONE);
        NEW_NETHER_BLOCKS.add(Blocks.POLISHED_BLACKSTONE_BRICKS);
        NEW_NETHER_BLOCKS.add(Blocks.CRIMSON_STEM);
        NEW_NETHER_BLOCKS.add(Blocks.CRIMSON_NYLIUM);
        NEW_NETHER_BLOCKS.add(Blocks.NETHER_GOLD_ORE);
        NEW_NETHER_BLOCKS.add(Blocks.WARPED_NYLIUM);
        NEW_NETHER_BLOCKS.add(Blocks.WARPED_STEM);
        NEW_NETHER_BLOCKS.add(Blocks.TWISTING_VINES);
        NEW_NETHER_BLOCKS.add(Blocks.WEEPING_VINES);
        NEW_NETHER_BLOCKS.add(Blocks.BONE_BLOCK);
        NEW_NETHER_BLOCKS.add(Blocks.CHAIN);
        NEW_NETHER_BLOCKS.add(Blocks.OBSIDIAN);
        NEW_NETHER_BLOCKS.add(Blocks.CRYING_OBSIDIAN);
        NEW_NETHER_BLOCKS.add(Blocks.SOUL_SOIL);
        NEW_NETHER_BLOCKS.add(Blocks.SOUL_FIRE);
    }

    public NewerNewChunks() {
        super(
            AutoScout.CATEGORY,
            "NewerNewChunks",
            "Detects new chunks by scanning chunk section palettes (1.18+ only), liquid flow, and block ticking packets."
        );
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();

        WButton deletedata = table.add(theme.button("**DELETE CHUNK DATA**")).expandX().minWidth(100).widget();
        deletedata.action = () -> {
            if (deletewarning == 0) error("PRESS AGAIN WITHIN 5s TO DELETE ALL CHUNK DATA FOR THIS DIMENSION.");
            deletewarningTicks = 0;
            deletewarning++;
        };

        table.row();
        return table;
    }

    public List<ChunkPos> getOldChunks() {
        synchronized (oldChunks) {
            return new ArrayList<>(oldChunks);
        }
    }

    public List<ChunkPos> getNewChunks() {
        synchronized (newChunks) {
            return new ArrayList<>(newChunks);
        }
    }

    @Override
    public void onActivate() {
        clearChunkData();

        if (resolveStorageContext() && (save.get() || load.get())) {
            ensureDataFiles();
        }

        if (PaletteExploit.get()) {
            info("PaletteExploit enabled - make sure your server is version 1.18 or higher!");
        }

        if (load.get()) {
            loadData();
        }

        lastSaveSetting = save.get();
        autoreloadticks = 0;
        loadingticks = 0;
        worldchange = false;
        deletewarning = 0;
        deletewarningTicks = 666;
    }

    @Override
    public void onDeactivate() {
        autoreloadticks = 0;
        loadingticks = 0;
        worldchange = false;

        if (remove.get() || autoreload.get()) {
            clearChunkData();
        }

        super.onDeactivate();
    }

    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (event.screen instanceof DisconnectedScreen) {
            if (worldleaveremove.get()) clearChunkData();
        }

        if (event.screen instanceof DownloadingTerrainScreen) {
            worldchange = true;
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (worldleaveremove.get()) clearChunkData();
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (deletewarningTicks <= 100) deletewarningTicks++;
        else deletewarning = 0;

        if (deletewarning >= 2) {
            deletewarning = 0;
            clearChunkData();

            if (resolveStorageContext()) {
                try {
                    deleteIfExists(NEW_FILE);
                    deleteIfExists(OLD_FILE);
                    deleteIfExists(BEING_UPDATED_FILE);
                    deleteIfExists(OLD_GENERATION_FILE);
                    deleteIfExists(BLOCK_EXPLOIT_FILE);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            error("Chunk Data deleted for this Dimension.");
        }

        if (mc.world == null) return;

        resolveStorageContext();

        if (detectmode.get() == DetectMode.Normal && blockupdateexploit.get()) {
            if (errticks < 6) errticks++;
            if (errticks == 5) {
                error("BlockExploitMode RECOMMENDED. Required to determine false positives from the Block Exploit from the OldChunks.");
            }
        } else {
            errticks = 0;
        }

        if (load.get()) {
            if (loadingticks < 1) {
                loadData();
                loadingticks++;
            }
        } else {
            loadingticks = 0;
        }

        if (autoreload.get()) {
            autoreloadticks++;
            int delayTicks = removedelay.get() * 20;

            if (autoreloadticks == delayTicks) {
                clearChunkData();
                if (load.get()) loadData();
            } else if (autoreloadticks >= delayTicks) {
                autoreloadticks = 0;
            }
        }

        if (load.get() && worldchange) {
            if (worldleaveremove.get()) clearChunkData();
            loadData();
            worldchange = false;
        }

        if (!lastSaveSetting && save.get()) {
            ensureDataFiles();
            persistAllCachedData();
        }
        lastSaveSetting = save.get();

        if (removerenderdist.get()) {
            removeChunksOutsideRenderDistance();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null || event.renderer == null) return;

        BlockPos playerPos = new BlockPos(mc.player.getBlockX(), renderHeight.get(), mc.player.getBlockZ());
        double maxDistance = renderDistance.get() * 16.0;

        if (isVisible(newChunksLineColor.get(), newChunksSideColor.get())) {
            synchronized (newChunks) {
                for (ChunkPos c : newChunks) {
                    renderChunkBoxIfInRange(c, playerPos, maxDistance, newChunksSideColor.get(), newChunksLineColor.get(), event);
                }
            }
        }

        if (isVisible(tickexploitChunksLineColor.get(), tickexploitChunksSideColor.get())) {
            synchronized (tickexploitChunks) {
                for (ChunkPos c : tickexploitChunks) {
                    if (c == null) continue;
                    if (!playerPos.isWithinDistance(new BlockPos(c.getCenterX(), renderHeight.get(), c.getCenterZ()), maxDistance)) continue;

                    if (detectmode.get() == DetectMode.BlockExploitMode && blockupdateexploit.get()) {
                        renderChunkBox(c, tickexploitChunksSideColor.get(), tickexploitChunksLineColor.get(), event);
                    } else if (detectmode.get() == DetectMode.Normal && blockupdateexploit.get()) {
                        renderChunkBox(c, newChunksSideColor.get(), newChunksLineColor.get(), event);
                    } else {
                        renderChunkBox(c, oldChunksSideColor.get(), oldChunksLineColor.get(), event);
                    }
                }
            }
        }

        if (isVisible(oldChunksLineColor.get(), oldChunksSideColor.get())) {
            synchronized (oldChunks) {
                for (ChunkPos c : oldChunks) {
                    renderChunkBoxIfInRange(c, playerPos, maxDistance, oldChunksSideColor.get(), oldChunksLineColor.get(), event);
                }
            }
        }

        if (isVisible(beingUpdatedOldChunksLineColor.get(), beingUpdatedOldChunksSideColor.get())) {
            synchronized (beingUpdatedOldChunks) {
                for (ChunkPos c : beingUpdatedOldChunks) {
                    renderChunkBoxIfInRange(c, playerPos, maxDistance, beingUpdatedOldChunksSideColor.get(), beingUpdatedOldChunksLineColor.get(), event);
                }
            }
        }

        if (isVisible(OldGenerationOldChunksLineColor.get(), OldGenerationOldChunksSideColor.get())) {
            synchronized (OldGenerationOldChunks) {
                for (ChunkPos c : OldGenerationOldChunks) {
                    renderChunkBoxIfInRange(c, playerPos, maxDistance, OldGenerationOldChunksSideColor.get(), OldGenerationOldChunksLineColor.get(), event);
                }
            }
        }
    }

    @EventHandler
    private void onReadPacket(PacketEvent.Receive event) {
        if (mc.world == null) return;

        if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            if (liquidexploit.get()) handleChunkDeltaUpdate(packet);
            return;
        }

        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            handleBlockUpdate(packet);
            return;
        }

        if (event.packet instanceof ChunkDataS2CPacket packet) {
            handleChunkData(packet);
        }
    }

    private void handleChunkDeltaUpdate(ChunkDeltaUpdateS2CPacket packet) {
        packet.visitUpdates((pos, state) -> {
            ChunkPos chunkPos = new ChunkPos(pos);

            if (!state.getFluidState().isEmpty() && !state.getFluidState().isStill()) {
                for (Direction dir : searchDirs) {
                    try {
                        if (mc.world != null
                            && mc.world.getBlockState(pos.offset(dir)).getFluidState().isStill()
                            && !isTracked(chunkPos)) {
                            tickexploitChunks.remove(chunkPos);
                            addChunk(newChunks, NEW_FILE, chunkPos);
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void handleBlockUpdate(BlockUpdateS2CPacket packet) {
        ChunkPos chunkPos = new ChunkPos(packet.getPos());

        if (blockupdateexploit.get()) {
            try {
                if (!isTracked(chunkPos)) {
                    addChunk(tickexploitChunks, BLOCK_EXPLOIT_FILE, chunkPos);
                }
            } catch (Exception ignored) {
            }
        }

        if (!liquidexploit.get()) return;

        FluidState fluid = packet.getState().getFluidState();
        if (!fluid.isEmpty() && !fluid.isStill()) {
            for (Direction dir : searchDirs) {
                try {
                    if (mc.world != null
                        && mc.world.getBlockState(packet.getPos().offset(dir)).getFluidState().isStill()
                        && !containsInAnySetExcept(chunkPos, tickexploitChunks)) {
                        tickexploitChunks.remove(chunkPos);
                        addChunk(newChunks, NEW_FILE, chunkPos);
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handleChunkData(ChunkDataS2CPacket packet) {
        if (mc.world == null) return;

        ChunkPos chunkPos = new ChunkPos(packet.getChunkX(), packet.getChunkZ());

        if (mc.world.getChunkManager().getChunk(packet.getChunkX(), packet.getChunkZ()) != null) {
            return;
        }

        WorldChunk chunk = new WorldChunk(mc.world, chunkPos);

        try {
            ChunkData chunkData = packet.getChunkData();
            chunk.loadFromPacket(
                chunkData.getSectionsDataBuf(),
                chunkData.getHeightmap(),
                chunkData.getBlockEntities(packet.getChunkX(), packet.getChunkZ())
            );
        } catch (Exception e) {
            return;
        }

        boolean isNewChunk = false;
        boolean isOldGeneration = false;
        boolean chunkIsBeingUpdated = false;
        boolean foundAnyOre = false;
        boolean isNewOverworldGeneration = false;
        boolean isNewNetherGeneration = false;

        ChunkSection[] sections = chunk.getSectionArray();

        if (overworldOldChunksDetector.get()
            && mc.world.getRegistryKey() == World.OVERWORLD
            && chunk.getStatus().isAtLeast(ChunkStatus.FULL)
            && !chunk.isEmpty()) {

            int max = Math.min(17, sections.length);
            for (int i = 0; i < max; i++) {
                ChunkSection section = sections[i];
                if (section == null || section.isEmpty()) continue;

                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            Block block = section.getBlockState(x, y, z).getBlock();

                            if (!foundAnyOre && ORE_BLOCKS.contains(block)) foundAnyOre = true;

                            if (((y >= 5 && i == 4) || i > 4)
                                && !isNewOverworldGeneration
                                && (NEW_OVERWORLD_BLOCKS.contains(block) || DEEPSLATE_BLOCKS.contains(block))) {
                                isNewOverworldGeneration = true;
                            }
                        }
                    }
                }
            }

            if (foundAnyOre && !isNewOverworldGeneration) {
                isOldGeneration = true;
            }
        }

        if (netherOldChunksDetector.get()
            && mc.world.getRegistryKey() == World.NETHER
            && chunk.getStatus().isAtLeast(ChunkStatus.FULL)
            && !chunk.isEmpty()) {

            int max = Math.min(8, sections.length);
            for (int i = 0; i < max; i++) {
                ChunkSection section = sections[i];
                if (section == null || section.isEmpty()) continue;

                for (int x = 0; x < 16 && !isNewNetherGeneration; x++) {
                    for (int y = 0; y < 16 && !isNewNetherGeneration; y++) {
                        for (int z = 0; z < 16; z++) {
                            if (NEW_NETHER_BLOCKS.contains(section.getBlockState(x, y, z).getBlock())) {
                                isNewNetherGeneration = true;
                                break;
                            }
                        }
                    }
                }
            }

            if (!isNewNetherGeneration) {
                isOldGeneration = true;
            }
        }

        if (endOldChunksDetector.get()
            && mc.world.getRegistryKey() == World.END
            && chunk.getStatus().isAtLeast(ChunkStatus.FULL)
            && !chunk.isEmpty()) {

            try {
                boolean hasOldEndStructure = false;

                for (int x = 0; x < 16 && !hasOldEndStructure; x++) {
                    for (int z = 0; z < 16 && !hasOldEndStructure; z++) {
                        int worldX = chunkPos.getStartX() + x;
                        int worldZ = chunkPos.getStartZ() + z;
                        BlockState surfaceBlock = chunk.getBlockState(new BlockPos(worldX, 64, worldZ));
                        if (surfaceBlock.getBlock() == Blocks.END_STONE) {
                            hasOldEndStructure = true;
                        }
                    }
                }

                if (hasOldEndStructure) {
                    isOldGeneration = true;
                }
            } catch (Exception ignored) {
                isOldGeneration = true;
            }
        }

        if (PaletteExploit.get()) {
            boolean firstChunkAppearsNew = false;
            int loops = 0;
            int newChunkQuantifier = 0;
            int oldChunkQuantifier = 0;

            try {
                for (ChunkSection section : sections) {
                    if (section == null || section.isEmpty()) continue;

                    Set<Block> blocksFound = new HashSet<>();

                    for (int x = 0; x < 16; x += 4) {
                        for (int y = 0; y < 16; y += 4) {
                            for (int z = 0; z < 16; z += 4) {
                                blocksFound.add(section.getBlockState(x, y, z).getBlock());
                            }
                        }
                    }

                    int uniqueBlockTypes = blocksFound.size();

                    if (loops == 0 && blocksFound.contains(Blocks.AIR) && uniqueBlockTypes <= 3) {
                        firstChunkAppearsNew = true;
                        newChunkQuantifier++;
                    } else if (uniqueBlockTypes > 5) {
                        oldChunkQuantifier++;
                    }

                    if (loops == 4
                        && blocksFound.contains(Blocks.BEDROCK)
                        && mc.world.getRegistryKey() != World.NETHER
                        && mc.world.getRegistryKey() != World.END
                        && beingUpdatedDetector.get()) {
                        chunkIsBeingUpdated = true;
                    }

                    loops++;
                }

                if (loops > 0) {
                    if (beingUpdatedDetector.get()
                        && (mc.world.getRegistryKey() == World.NETHER || mc.world.getRegistryKey() == World.END)) {
                        double oldPercentage = ((double) oldChunkQuantifier / loops) * 100.0;
                        if (oldPercentage >= 25.0) chunkIsBeingUpdated = true;
                    } else if (mc.world.getRegistryKey() != World.NETHER && mc.world.getRegistryKey() != World.END) {
                        double percentage = ((double) newChunkQuantifier / loops) * 100.0;
                        if (percentage >= 51.0 || firstChunkAppearsNew) isNewChunk = true;
                    }
                }
            } catch (Exception ignored) {
                if (firstChunkAppearsNew) isNewChunk = true;
            }

            boolean validNewChunk = (mc.world.getRegistryKey() == World.END) ? isNewChunk : !isOldGeneration;

            if (isNewChunk && !chunkIsBeingUpdated && validNewChunk) {
                if (!isTracked(chunkPos)) {
                    addChunk(newChunks, NEW_FILE, chunkPos);
                }
                return;
            }

            if (!isNewChunk && !chunkIsBeingUpdated && isOldGeneration) {
                if (!isTracked(chunkPos)) {
                    addChunk(OldGenerationOldChunks, OLD_GENERATION_FILE, chunkPos);
                }
                return;
            }

            if (chunkIsBeingUpdated) {
                if (!isTracked(chunkPos)) {
                    addChunk(beingUpdatedOldChunks, BEING_UPDATED_FILE, chunkPos);
                }
                return;
            }

            if (!isNewChunk) {
                if (!isTracked(chunkPos)) {
                    addChunk(oldChunks, OLD_FILE, chunkPos);
                }
                return;
            }
        }

        if (liquidexploit.get()) {
            for (int x = 0; x < 16; x++) {
                for (int y = mc.world.getBottomY(); y <= mc.world.getTopYInclusive(); y++) {
                    for (int z = 0; z < 16; z++) {
                        try {
                            FluidState fluid = chunk.getFluidState(x, y, z);
                            if (!fluid.isEmpty() && !fluid.isStill() && !isTracked(chunkPos)) {
                                addChunk(oldChunks, OLD_FILE, chunkPos);
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }

    private void clearChunkData() {
        newChunks.clear();
        oldChunks.clear();
        beingUpdatedOldChunks.clear();
        OldGenerationOldChunks.clear();
        tickexploitChunks.clear();
    }

    private boolean resolveStorageContext() {
        if (mc.world == null) return false;

        world = mc.world.getRegistryKey().getValue().toString().replace(':', '_');

        if (mc.isInSingleplayer()) {
            if (mc.getServer() != null) {
                String[] array = mc.getServer().getSavePath(WorldSavePath.ROOT).toString().replace(':', '_').split("/|\\\\");
                serverip = array.length >= 2 ? array[array.length - 2] : "singleplayer";
            } else {
                serverip = "singleplayer";
            }
        } else {
            if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
                serverip = mc.getCurrentServerEntry().address.replace(':', '_');
            } else {
                serverip = "multiplayer";
            }
        }

        return true;
    }

    private Path getBaseDir() {
        if (!resolveStorageContext()) return null;
        return Paths.get("AutoScout", "NewChunks", serverip, world);
    }

    private void ensureDataFiles() {
        Path baseDir = getBaseDir();
        if (baseDir == null) return;

        try {
            Files.createDirectories(baseDir);
            ensureFile(baseDir.resolve(OLD_FILE));
            ensureFile(baseDir.resolve(BEING_UPDATED_FILE));
            ensureFile(baseDir.resolve(OLD_GENERATION_FILE));
            ensureFile(baseDir.resolve(NEW_FILE));
            ensureFile(baseDir.resolve(BLOCK_EXPLOIT_FILE));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void ensureFile(Path path) throws IOException {
        if (Files.notExists(path)) Files.createFile(path);
    }

    private void deleteIfExists(Path relativeFile) throws IOException {
        Path baseDir = getBaseDir();
        if (baseDir == null) return;
        Files.deleteIfExists(baseDir.resolve(relativeFile));
    }

    private boolean isTracked(ChunkPos pos) {
        return newChunks.contains(pos)
            || oldChunks.contains(pos)
            || beingUpdatedOldChunks.contains(pos)
            || OldGenerationOldChunks.contains(pos)
            || tickexploitChunks.contains(pos);
    }

    private boolean containsInAnySetExcept(ChunkPos pos, Set<ChunkPos> excluded) {
        return (excluded != newChunks && newChunks.contains(pos))
            || (excluded != oldChunks && oldChunks.contains(pos))
            || (excluded != beingUpdatedOldChunks && beingUpdatedOldChunks.contains(pos))
            || (excluded != OldGenerationOldChunks && OldGenerationOldChunks.contains(pos))
            || (excluded != tickexploitChunks && tickexploitChunks.contains(pos));
    }

    private void addChunk(Set<ChunkPos> target, Path saveFile, ChunkPos pos) {
        if (pos == null) return;
        if (isTracked(pos)) return;

        target.add(pos);

        if (save.get()) {
            saveData(saveFile, pos);
        }
    }

    private void loadData() {
        if (!load.get()) return;

        ensureDataFiles();

        loadChunkData(BLOCK_EXPLOIT_FILE, tickexploitChunks);
        loadChunkData(OLD_FILE, oldChunks);
        loadChunkData(NEW_FILE, newChunks);
        loadChunkData(BEING_UPDATED_FILE, beingUpdatedOldChunks);
        loadChunkData(OLD_GENERATION_FILE, OldGenerationOldChunks);
    }

    private void loadChunkData(Path relativeFile, Set<ChunkPos> chunkSet) {
        Path baseDir = getBaseDir();
        if (baseDir == null) return;

        Path filePath = baseDir.resolve(relativeFile);
        if (Files.notExists(filePath)) return;

        try {
            List<String> allLines = Files.readAllLines(filePath, StandardCharsets.UTF_8);

            for (String line : allLines) {
                if (line == null || line.isEmpty()) continue;

                String[] array = line.split(", ");
                if (array.length != 2) continue;

                int x = Integer.parseInt(array[0].replace("[", "").replace("]", ""));
                int z = Integer.parseInt(array[1].replace("[", "").replace("]", ""));
                ChunkPos chunkPos = new ChunkPos(x, z);

                if (!isTracked(chunkPos)) {
                    chunkSet.add(chunkPos);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveData(Path relativeFile, ChunkPos chunkPos) {
        Path baseDir = getBaseDir();
        if (baseDir == null) return;

        try {
            Files.createDirectories(baseDir);

            Path filePath = baseDir.resolve(relativeFile);
            String data = chunkPos.toString() + System.lineSeparator();

            Files.write(
                filePath,
                data.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void persistAllCachedData() {
        synchronized (newChunks) {
            for (ChunkPos chunk : newChunks) saveData(NEW_FILE, chunk);
        }
        synchronized (OldGenerationOldChunks) {
            for (ChunkPos chunk : OldGenerationOldChunks) saveData(OLD_GENERATION_FILE, chunk);
        }
        synchronized (beingUpdatedOldChunks) {
            for (ChunkPos chunk : beingUpdatedOldChunks) saveData(BEING_UPDATED_FILE, chunk);
        }
        synchronized (oldChunks) {
            for (ChunkPos chunk : oldChunks) saveData(OLD_FILE, chunk);
        }
        synchronized (tickexploitChunks) {
            for (ChunkPos chunk : tickexploitChunks) saveData(BLOCK_EXPLOIT_FILE, chunk);
        }
    }

    private void removeChunksOutsideRenderDistance() {
        if (mc.player == null) return;

        BlockPos playerPos = new BlockPos(mc.player.getBlockX(), renderHeight.get(), mc.player.getBlockZ());
        double renderDistanceBlocks = renderDistance.get() * 16.0;

        removeChunksOutsideRenderDistance(newChunks, playerPos, renderDistanceBlocks);
        removeChunksOutsideRenderDistance(oldChunks, playerPos, renderDistanceBlocks);
        removeChunksOutsideRenderDistance(beingUpdatedOldChunks, playerPos, renderDistanceBlocks);
        removeChunksOutsideRenderDistance(OldGenerationOldChunks, playerPos, renderDistanceBlocks);
        removeChunksOutsideRenderDistance(tickexploitChunks, playerPos, renderDistanceBlocks);
    }

    private void removeChunksOutsideRenderDistance(Set<ChunkPos> chunkSet, BlockPos playerPos, double renderDistanceBlocks) {
        chunkSet.removeIf(c -> !playerPos.isWithinDistance(new BlockPos(c.getCenterX(), renderHeight.get(), c.getCenterZ()), renderDistanceBlocks));
    }

    private boolean isVisible(Color line, Color side) {
        return line.a > 5 || side.a > 5;
    }

    private void renderChunkBoxIfInRange(ChunkPos c, BlockPos playerPos, double maxDistance, Color side, Color line, Render3DEvent event) {
        if (c == null) return;
        if (!playerPos.isWithinDistance(new BlockPos(c.getCenterX(), renderHeight.get(), c.getCenterZ()), maxDistance)) return;
        renderChunkBox(c, side, line, event);
    }

    private void renderChunkBox(ChunkPos c, Color side, Color line, Render3DEvent event) {
        try {
            BlockPos start = c.getStartPos();
            double y = renderHeight.get();

            Box box = new Box(
                start.getX(),
                y,
                start.getZ(),
                start.getX() + 16.0,
                y,
                start.getZ() + 16.0
            );

            event.renderer.box(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                side, line, shapeMode.get(), 0
            );
        } catch (Exception ignored) {
        }
    }
}
