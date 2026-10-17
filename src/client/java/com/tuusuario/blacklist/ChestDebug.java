package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Herramienta temporal: con /blacklistmod debug on, cada vez que abres un cofre o menú
 * guarda en config/blacklist-debug.txt el título y el contenido de cada slot
 * (id, cantidad, textos del tooltip y datos internos). Sirve para enseñarme cómo se ven
 * los cofres de dungeons y poder escribir el profit tracker sin adivinar.
 */
public final class ChestDebug {

    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("blacklist-debug.txt");

    private static final int WAIT_TICKS = 20; // espera 1 s a que el servidor llene el menú

    private static boolean enabled = false;
    private static Screen lastScreen;
    private static int ticks;

    private ChestDebug() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        lastScreen = null;
    }

    public static Path file() {
        return FILE;
    }

    /** Se llama cada tick del cliente. */
    public static void tick(Minecraft mc) {
        if (!enabled) return;

        Screen screen = mc.screen;
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            lastScreen = null;
            return;
        }

        if (screen != lastScreen) {
            lastScreen = screen;
            ticks = 0;
        }

        ticks++;
        if (ticks == WAIT_TICKS) {
            dump(mc, container);
        }
    }

    private static void dump(Minecraft mc, AbstractContainerScreen<?> screen) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("==== ").append(LocalDateTime.now()).append(" ====\n");
            sb.append("Título: ").append(screen.getTitle().getString()).append('\n');

            List<Slot> slots = screen.getMenu().slots;
            // Los últimos 36 slots son el inventario del jugador: no nos interesan
            int limit = slots.size() > 36 ? slots.size() - 36 : slots.size();

            for (int i = 0; i < limit; i++) {
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty()) continue;

                sb.append("\n[slot ").append(i).append("] ")
                        .append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .append(" x").append(stack.getCount()).append('\n');

                List<Component> tooltip = stack.getTooltipLines(
                        Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
                for (Component line : tooltip) {
                    sb.append("    | ").append(line.getString()).append('\n');
                }

                Object custom = stack.get(DataComponents.CUSTOM_DATA);
                if (custom != null) {
                    sb.append("    datos: ").append(custom).append('\n');
                }
            }
            sb.append('\n');

            Files.writeString(FILE, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "[Blacklist] Debug guardado en config/blacklist-debug.txt")
                        .withStyle(ChatFormatting.AQUA));
            }
        } catch (Exception e) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "[Blacklist] No se pudo guardar el debug: " + e.getMessage())
                        .withStyle(ChatFormatting.RED));
            }
        }
    }
}
