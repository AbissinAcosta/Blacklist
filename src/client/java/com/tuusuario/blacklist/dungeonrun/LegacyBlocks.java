package com.tuusuario.blacklist.dungeonrun;

import java.util.HashMap;
import java.util.Map;

/**
 * Traduce el nombre de un bloque moderno ("stone_bricks") al id numerico que tenia en Minecraft 1.8 (98).
 *
 * Por que existe: los "cores" de rooms.json son el hash de un texto armado con los ids 1.8 de la columna
 * de bloques en el centro de la sala. Hypixel manda ese mundo a un cliente moderno, asi que hay que
 * volver a los ids viejos para que el hash coincida.
 *
 * No depende de Minecraft. Si un bloque no esta en la tabla devuelve -1; /blacklistmod run debug lista
 * los que faltaron para poder completarla.
 */
public final class LegacyBlocks {

    private static final Map<String, Integer> EXACT = new HashMap<>();

    private static void put(int id, String... names) {
        for (String n : names) EXACT.put(n, id);
    }

    static {
        put(0, "air", "cave_air", "void_air");
        put(1, "stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite");
        put(2, "grass_block");
        put(3, "dirt", "coarse_dirt", "podzol");
        put(4, "cobblestone");
        put(6, "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "acacia_sapling", "dark_oak_sapling");
        put(7, "bedrock");
        put(8, "flowing_water");
        put(9, "water");
        put(10, "flowing_lava");
        put(11, "lava");
        put(12, "sand", "red_sand");
        put(13, "gravel");
        put(14, "gold_ore");
        put(15, "iron_ore");
        put(16, "coal_ore");
        put(19, "sponge", "wet_sponge");
        put(20, "glass");
        put(21, "lapis_ore");
        put(22, "lapis_block");
        put(23, "dispenser");
        put(24, "sandstone", "chiseled_sandstone", "cut_sandstone", "smooth_sandstone");
        put(25, "note_block");
        put(27, "powered_rail");
        put(28, "detector_rail");
        put(29, "sticky_piston");
        put(30, "cobweb");
        put(31, "short_grass", "grass", "fern", "tall_grass_bottom");
        put(32, "dead_bush");
        put(33, "piston");
        put(34, "piston_head");
        put(36, "moving_piston");
        put(37, "dandelion");
        put(38, "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip",
                "pink_tulip", "oxeye_daisy");
        put(39, "brown_mushroom");
        put(40, "red_mushroom");
        put(41, "gold_block");
        put(42, "iron_block");
        put(45, "bricks");
        put(46, "tnt");
        put(47, "bookshelf");
        put(48, "mossy_cobblestone");
        put(49, "obsidian");
        put(50, "torch", "wall_torch");
        put(51, "fire");
        put(52, "spawner");
        put(53, "oak_stairs");
        put(54, "chest");
        put(55, "redstone_wire");
        put(56, "diamond_ore");
        put(57, "diamond_block");
        put(58, "crafting_table");
        put(59, "wheat");
        put(60, "farmland");
        put(63, "oak_sign");
        put(65, "ladder");
        put(66, "rail");
        put(67, "cobblestone_stairs");
        put(68, "oak_wall_sign");
        put(69, "lever");
        put(70, "stone_pressure_plate");
        put(71, "iron_door");
        put(72, "oak_pressure_plate");
        put(77, "stone_button");
        put(78, "snow");
        put(79, "ice");
        put(80, "snow_block");
        put(81, "cactus");
        put(82, "clay");
        put(83, "sugar_cane");
        put(84, "jukebox");
        put(85, "oak_fence");
        put(86, "carved_pumpkin", "pumpkin");
        put(87, "netherrack");
        put(88, "soul_sand");
        put(89, "glowstone");
        put(90, "nether_portal");
        put(91, "jack_o_lantern");
        put(92, "cake");
        put(96, "oak_trapdoor");
        put(97, "infested_stone", "infested_cobblestone", "infested_stone_bricks", "infested_mossy_stone_bricks",
                "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks");
        put(98, "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks");
        put(99, "brown_mushroom_block", "mushroom_stem");
        put(100, "red_mushroom_block");
        put(101, "iron_bars");
        put(102, "glass_pane");
        put(103, "melon");
        put(104, "pumpkin_stem", "attached_pumpkin_stem");
        put(105, "melon_stem", "attached_melon_stem");
        put(106, "vine");
        put(107, "oak_fence_gate");
        put(108, "brick_stairs");
        put(109, "stone_brick_stairs");
        put(110, "mycelium");
        put(111, "lily_pad");
        put(112, "nether_bricks");
        put(113, "nether_brick_fence");
        put(114, "nether_brick_stairs");
        put(115, "nether_wart");
        put(116, "enchanting_table");
        put(117, "brewing_stand");
        put(118, "cauldron", "water_cauldron", "lava_cauldron");
        put(119, "end_portal");
        put(120, "end_portal_frame");
        put(121, "end_stone");
        put(122, "dragon_egg");
        put(127, "cocoa");
        put(128, "sandstone_stairs");
        put(129, "emerald_ore");
        put(130, "ender_chest");
        put(131, "tripwire_hook");
        put(132, "tripwire");
        put(133, "emerald_block");
        put(134, "spruce_stairs");
        put(135, "birch_stairs");
        put(136, "jungle_stairs");
        put(137, "command_block");
        put(138, "beacon");
        put(139, "cobblestone_wall", "mossy_cobblestone_wall");
        put(140, "flower_pot");
        put(141, "carrots");
        put(142, "potatoes");
        put(143, "oak_button");
        put(145, "anvil", "chipped_anvil", "damaged_anvil");
        put(146, "trapped_chest");
        put(147, "light_weighted_pressure_plate");
        put(148, "heavy_weighted_pressure_plate");
        put(149, "comparator");
        put(151, "daylight_detector");
        put(152, "redstone_block");
        put(153, "nether_quartz_ore");
        put(154, "hopper");
        put(155, "quartz_block", "chiseled_quartz_block", "quartz_pillar", "smooth_quartz");
        put(156, "quartz_stairs");
        put(157, "activator_rail");
        put(158, "dropper");
        put(163, "acacia_stairs");
        put(164, "dark_oak_stairs");
        put(165, "slime_block");
        put(166, "barrier");
        put(167, "iron_trapdoor");
        put(168, "prismarine", "prismarine_bricks", "dark_prismarine");
        put(169, "sea_lantern");
        put(170, "hay_block");
        put(172, "terracotta");
        put(173, "coal_block");
        put(174, "packed_ice");
        put(175, "sunflower", "lilac", "rose_bush", "peony", "tall_grass", "large_fern");
        put(179, "red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone", "smooth_red_sandstone");
        put(180, "red_sandstone_stairs");
        put(183, "spruce_fence_gate");
        put(184, "birch_fence_gate");
        put(185, "jungle_fence_gate");
        put(186, "dark_oak_fence_gate");
        put(187, "acacia_fence_gate");
        put(188, "spruce_fence");
        put(189, "birch_fence");
        put(190, "jungle_fence");
        put(191, "dark_oak_fence");
        put(192, "acacia_fence");
        put(193, "spruce_door");
        put(194, "birch_door");
        put(195, "jungle_door");
        put(196, "acacia_door");
        put(197, "dark_oak_door");
        put(64, "oak_door");
        put(26, "red_bed");
    }

    private LegacyBlocks() {}

    /**
     * @param lit        el bloque tiene la propiedad "lit" encendida (horno, mena de redstone, lampara, antorcha)
     * @param doubleSlab es una losa doble (en 1.8 era un bloque distinto)
     * @return id 1.8, o -1 si el bloque no esta en la tabla
     */
    public static int idOf(String name, boolean lit, boolean doubleSlab) {
        // Familias por color / material
        if (name.endsWith("_wool")) return 35;
        if (name.endsWith("_stained_glass_pane")) return 160;
        if (name.endsWith("_stained_glass")) return 95;
        if (name.endsWith("_terracotta") && !name.contains("glazed")) return 159;
        if (name.endsWith("_carpet")) return 171;
        if (name.endsWith("_planks")) return 5;
        if (name.endsWith("_bed")) return 26;
        if (name.endsWith("_banner")) return name.contains("wall") ? 177 : 176;
        if (name.endsWith("_head") || name.endsWith("_skull")) return 144;
        if (name.endsWith("_wall_sign")) return 68;
        if (name.endsWith("_sign")) return 63;
        if (name.endsWith("_leaves")) {
            return name.startsWith("acacia") || name.startsWith("dark_oak") ? 161 : 18;
        }
        if (name.endsWith("_log") || name.endsWith("_wood")) {
            return name.contains("acacia") || name.contains("dark_oak") ? 162 : 17;
        }
        if (name.endsWith("_slab")) return slab(name, doubleSlab);

        switch (name) {
            case "furnace": return lit ? 62 : 61;
            case "redstone_ore": return lit ? 74 : 73;
            case "redstone_lamp": return lit ? 124 : 123;
            case "redstone_torch":
            case "redstone_wall_torch": return lit ? 76 : 75;
            case "repeater": return lit ? 94 : 93;
            default: break;
        }

        Integer id = EXACT.get(name);
        return id == null ? -1 : id;
    }

    private static int slab(String name, boolean dbl) {
        if (name.startsWith("red_sandstone")) return dbl ? 181 : 182;
        boolean wooden = name.startsWith("oak") || name.startsWith("spruce") || name.startsWith("birch")
                || name.startsWith("jungle") || name.startsWith("acacia") || name.startsWith("dark_oak");
        if (wooden) return dbl ? 125 : 126;
        return dbl ? 43 : 44;
    }
}
