package com.nexuscraft.nexusscan;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps a block to the flat RGB color its column should render as -- the same basic idea vanilla
 * in-game maps use (and what Dynmap/BlueMap's simplest renderer does): every material gets one
 * representative color, no lighting or shading. Looked up by material name (a String key) rather
 * than the Material enum constant itself, so this doesn't need every block type to exist in the
 * stub library used to compile this against outside a real Paper API -- only whatever the actual
 * scan code references directly needs a real constant.
 */
public final class BlockColorPalette {

    private static final Map<String, Integer> COLORS = new HashMap<>();
    private static final int UNKNOWN = 0xFF00FF; // loud magenta -- makes a palette gap obvious instead of silently wrong
    private static final int UNRENDERED = 0x000000; // unloaded / air-only / ungenerated column, per spec

    static {
        // Grass, dirt, and the "natural ground" family
        put("GRASS_BLOCK", 0x7CBD6B);
        put("DIRT", 0x866043);
        put("COARSE_DIRT", 0x76573A);
        put("ROOTED_DIRT", 0x8A6D4B);
        put("PODZOL", 0x6A4E32);
        put("MYCELIUM", 0x6F6266);
        put("MUD", 0x4A4139);
        put("MUDDY_MANGROVE_ROOTS", 0x5B4636);
        put("FARMLAND", 0x69492C);
        put("DIRT_PATH", 0x8A6D45);
        put("SAND", 0xDBCC90);
        put("RED_SAND", 0xA35A2E);
        put("GRAVEL", 0x88817D);
        put("CLAY", 0x9EA3AB);
        put("SNOW", 0xF7FBFB);
        put("SNOW_BLOCK", 0xF7FBFB);
        put("POWDER_SNOW", 0xF9FDFD);
        put("ICE", 0x8DB4E2);
        put("PACKED_ICE", 0x9DC1EA);
        put("BLUE_ICE", 0x7DB8E8);
        put("MOSS_BLOCK", 0x5E8A3A);

        // Stone family
        put("STONE", 0x7B7B7B);
        put("DEEPSLATE", 0x565656);
        put("COBBLESTONE", 0x828282);
        put("MOSSY_COBBLESTONE", 0x6E7B62);
        put("ANDESITE", 0x8C8C8C);
        put("DIORITE", 0xE6E6E6);
        put("GRANITE", 0x976757);
        put("CALCITE", 0xE3E8E3);
        put("TUFF", 0x6D6D61);
        put("BEDROCK", 0x565656);
        put("BLACKSTONE", 0x363136);
        put("BASALT", 0x605C61);
        put("SMOOTH_BASALT", 0x59595C);
        put("OBSIDIAN", 0x1A1224);
        put("CRYING_OBSIDIAN", 0x2C1735);
        put("MAGMA_BLOCK", 0xA6461F);
        put("NETHERRACK", 0x723232);
        put("SOUL_SAND", 0x554134);
        put("SOUL_SOIL", 0x4B3A2C);
        put("END_STONE", 0xDBDA9C);

        // Water / liquids
        put("WATER", 0x3F76E4);
        put("LAVA", 0xE0641C);
        put("KELP", 0x3C7A3A);
        put("KELP_PLANT", 0x3C7A3A);
        put("SEAGRASS", 0x3D8E39);
        put("TALL_SEAGRASS", 0x3D8E39);

        // Wood / plant family -- matched by suffix below, these are just the defaults
        put("OAK_LOG", 0x6E5636);
        put("OAK_LEAVES", 0x4C7A32);
        put("OAK_PLANKS", 0xB08752);

        // Ores and misc common surface features
        put("COAL_ORE", 0x373737);
        put("IRON_ORE", 0xC2A278);
        put("GOLD_ORE", 0xE6C34D);
        put("DIAMOND_ORE", 0x6BE7E1);
        put("EMERALD_ORE", 0x3FCB6E);
        put("REDSTONE_ORE", 0xA82E20);
        put("LAPIS_ORE", 0x2150C4);
        put("COPPER_ORE", 0xC96E4B);
        put("AMETHYST_BLOCK", 0x9878D3);
        put("BUDDING_AMETHYST", 0x9878D3);

        // Crops / farmland features
        put("WHEAT", 0xDCC661);
        put("CARROTS", 0x3F8F3A);
        put("POTATOES", 0x3F8F3A);
        put("BEETROOTS", 0x6F1E1E);
        put("SUGAR_CANE", 0x88B75B);
        put("BAMBOO", 0x8FCB57);
        put("CACTUS", 0x5C8A3C);
        put("MELON", 0x6FA83C);
        put("PUMPKIN", 0xC77A22);
        put("CARVED_PUMPKIN", 0xC77A22);

        put("LILY_PAD", 0x3C7A3A);
    }

    private BlockColorPalette() {
    }

    private static void put(String materialName, int rgb) {
        COLORS.put(materialName, rgb);
    }

    /** Loud magenta means "add this block to the palette" -- deliberately not silently wrong. */
    public static int colorFor(Material material) {
        String name = material.name();
        Integer exact = COLORS.get(name);
        if (exact != null) return exact;

        // suffix/substring families cover the hundreds of colored/wood variants without
        // hand-listing every one -- e.g. every *_WOOL, *_LOG, *_LEAVES, *_TERRACOTTA, *_CONCRETE
        if (name.endsWith("_LEAVES")) return 0x4C7A32;
        if (name.endsWith("_LOG") || name.endsWith("_WOOD")) return 0x6E5636;
        if (name.endsWith("_PLANKS")) return 0xB08752;
        if (name.contains("_ORE")) return 0x8C8C8C;
        if (name.endsWith("_WOOL") || name.endsWith("_CARPET")) return woolFamilyColor(name);
        if (name.endsWith("_CONCRETE") || name.endsWith("_CONCRETE_POWDER")) return woolFamilyColor(name);
        if (name.endsWith("_TERRACOTTA")) return 0xA5674C;
        if (name.endsWith("_STAINED_GLASS") || name.endsWith("_STAINED_GLASS_PANE")) return 0xBFE0E6;
        if (name.contains("GLASS")) return 0xE8F4F8;

        return UNKNOWN;
    }

    private static int woolFamilyColor(String name) {
        if (name.startsWith("WHITE")) return 0xE9ECEC;
        if (name.startsWith("ORANGE")) return 0xE97A2C;
        if (name.startsWith("MAGENTA")) return 0xBB51C8;
        if (name.startsWith("LIGHT_BLUE")) return 0x6BB6DB;
        if (name.startsWith("YELLOW")) return 0xE7D138;
        if (name.startsWith("LIME")) return 0x71C231;
        if (name.startsWith("PINK")) return 0xE993AC;
        if (name.startsWith("GRAY") && !name.startsWith("LIGHT_GRAY")) return 0x3F4547;
        if (name.startsWith("LIGHT_GRAY")) return 0x8F958F;
        if (name.startsWith("CYAN")) return 0x158C90;
        if (name.startsWith("PURPLE")) return 0x792AAC;
        if (name.startsWith("BLUE")) return 0x2B379C;
        if (name.startsWith("BROWN")) return 0x664D33;
        if (name.startsWith("GREEN")) return 0x556B1F;
        if (name.startsWith("RED")) return 0xA02722;
        if (name.startsWith("BLACK")) return 0x161616;
        return UNKNOWN;
    }

    public static int unrenderedColor() {
        return UNRENDERED;
    }
}
