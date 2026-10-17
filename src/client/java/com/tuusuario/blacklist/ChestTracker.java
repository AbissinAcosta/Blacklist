package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conecta los cofres de dungeons con ProfitHud.
 *
 * - Cada vez que hay un menú abierto lee los cofres que muestra (los 6 de Croesus, o el cofre
 *   suelto "Open Reward Chest" dentro de la dungeon), valora su contenido con PriceService y
 *   guarda el mejor de la run en ProfitHud.
 * - Al hacer clic en un cofre para abrirlo marca la run como abierta.
 *
 * Identificar la run: un cofre visto en la dungeon y luego en Croesus tiene exactamente el mismo
 * contenido, así que se reconoce por su "huella" (ChestParser.Chest.print) y no se cuenta dos veces.
 * Los cofres Wood no se usan para reconocer (se repiten mucho entre runs distintas).
 */
public final class ChestTracker {

    private static final int FIRST_SCAN_TICKS = 10;   // espera a que el servidor llene el menú
    private static final int RESCAN_TICKS = 40;       // y vuelve a mirar cada 2 s mientras siga abierto
    private static final long SAME_RUN_GAP_MS = 3 * 60 * 1000;

    private static Screen lastScreen;
    private static int ticks;

    // Run "actual" para cofres sueltos dentro de la dungeon
    private static String dungeonKey;
    private static long dungeonKeyTime;
    private static int dungeonLevelId;

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ChestTracker() {
    }

    private record SlotChest(int slot, ChestParser.Chest chest) {
    }

    // ------------------------------------------------------------------ tick

    public static void tick(Minecraft mc, BlacklistConfig config) {
        if (!config.profitEnabled) return;

        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            lastScreen = null;
            return;
        }
        if (screen != lastScreen) {
            lastScreen = screen;
            ticks = 0;
        }

        ticks++;
        if (ticks == FIRST_SCAN_TICKS || (ticks > FIRST_SCAN_TICKS && ticks % RESCAN_TICKS == 0)) {
            process(mc, screen, -1);
        }
    }

    // ----------------------------------------------------------------- clics

    /** Se llama al hacer clic izquierdo dentro de un menú (x, y en coordenadas de la pantalla). */
    public static void onClick(Minecraft mc, AbstractContainerScreen<?> screen, double mx, double my) {
        BlacklistClient client = BlacklistClient.get();
        if (client == null || !client.getConfig().profitEnabled) return;

        List<Slot> slots = screen.getMenu().slots;
        int chestSlots = slots.size() - 36;
        if (chestSlots < 9 || chestSlots % 9 != 0) return; // no es un menú tipo cofre

        // Misma geometría que el cofre de vanilla (176 de ancho, 114 + 18 por fila de alto)
        int rows = chestSlots / 9;
        int left = (screen.width - 176) / 2;
        int top = (screen.height - (114 + rows * 18)) / 2;

        for (int i = 0; i < chestSlots; i++) {
            Slot s = slots.get(i);
            if (mx >= left + s.x && mx < left + s.x + 16 && my >= top + s.y && my < top + s.y + 16) {
                process(mc, screen, i);
                return;
            }
        }
    }

    // -------------------------------------------------------------- lectura

    private static List<SlotChest> readChests(Minecraft mc, AbstractContainerScreen<?> screen) {
        List<SlotChest> result = new ArrayList<>();
        List<Slot> slots = screen.getMenu().slots;
        int limit = slots.size() > 36 ? slots.size() - 36 : slots.size();
        String title = screen.getTitle().getString();

        for (int i = 0; i < limit; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("glass_pane")) continue;

            List<String> lines = new ArrayList<>();
            for (Component line : stack.getTooltipLines(
                    Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL)) {
                lines.add(line.getString());
            }

            ChestParser.Chest chest = ChestParser.parse(lines);
            if (chest == null) continue;

            // Dentro de la dungeon el ítem se llama "Open Reward Chest": el nombre real es el título
            if (chest.name().equals("Open Reward Chest")) {
                chest = new ChestParser.Chest(title, chest.contents(), chest.cost());
            }
            result.add(new SlotChest(i, chest));
        }
        return result;
    }

    private static String runKey(Minecraft mc, List<SlotChest> chests) {
        List<String> prints = new ArrayList<>();
        for (SlotChest sc : chests) prints.add(sc.chest().print());

        String known = ProfitHud.findRun(prints);
        if (known != null) return known;

        if (chests.size() >= 2) {
            // Croesus: los 6 cofres identifican la run
            List<String> sorted = new ArrayList<>(prints);
            java.util.Collections.sort(sorted);
            return "cr:" + UUID.nameUUIDFromBytes(String.join("|", sorted).getBytes());
        }

        // Cofre suelto dentro de la dungeon: misma run mientras sea el mismo mundo y no pase mucho tiempo
        long now = System.currentTimeMillis();
        int levelId = System.identityHashCode(mc.level);
        if (dungeonKey == null || levelId != dungeonLevelId || now - dungeonKeyTime > SAME_RUN_GAP_MS) {
            dungeonKey = "dg:" + now;
            dungeonLevelId = levelId;
        }
        dungeonKeyTime = now;
        return dungeonKey;
    }

    // ------------------------------------------------------------- valoración

    private record Valued(ChestParser.Chest chest, long value) {
        long net() {
            return value - chest.cost();
        }
    }

    private static Valued value(ChestParser.Chest chest) {
        double total = 0;
        for (String line : chest.contents()) {
            ChestParser.Entry e = ChestParser.entry(line);
            double price = PriceService.price(e.id());
            if (price < 0) {
                warnOnce(e);
                continue; // sin precio: no suma (el aviso en el chat te dice cuál falta)
            }
            total += price * e.count();
        }
        return new Valued(chest, Math.round(total));
    }

    private static void warnOnce(ChestParser.Entry e) {
        String key = e.id() == null ? e.text() : e.id();
        if (!WARNED.add(key)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "[Profit] Sin precio para \"" + e.text() + "\" (" + key + "): no se cuenta")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    // ---------------------------------------------------------------- proceso

    /** claimedSlot >= 0: el jugador acaba de hacer clic en ese slot. */
    private static void process(Minecraft mc, AbstractContainerScreen<?> screen, int claimedSlot) {
        if (mc.level == null) return;

        List<SlotChest> chests = readChests(mc, screen);
        if (chests.isEmpty()) return;

        String key = runKey(mc, chests);

        if (PriceService.ready()) {
            List<String> prints = new ArrayList<>();
            Valued best = null;
            for (SlotChest sc : chests) {
                prints.add(sc.chest().print());
                Valued v = value(sc.chest());
                if (best == null || v.net() > best.net()) best = v;
            }

            boolean changed = ProfitHud.recordChest(key, best.chest().name(), best.value(), best.chest().cost(), prints);
            if (changed && mc.player != null) {
                long net = best.net();
                mc.player.sendSystemMessage(Component.literal("[Profit] Mejor cofre de la run: "
                        + best.chest().name() + " (" + (net > 0 ? "+" : "") + ProfitHud.formatCoins(net) + ")")
                        .withStyle(net > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            }
        }

        if (claimedSlot >= 0) {
            for (SlotChest sc : chests) {
                if (sc.slot() == claimedSlot) {
                    ProfitHud.markClaimed(key);
                    break;
                }
            }
        }
    }
}
