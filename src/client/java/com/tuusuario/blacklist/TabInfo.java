package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee la lista de jugadores (la de la tecla Tab) de Hypixel y saca de ahí el número de cofres
 * de dungeons sin abrir que informa el servidor. Si aparece, se guarda en ProfitHud.
 *
 * Si no lo encuentra, abre el Tab y usa /blacklistmod profit tab: guarda todas las líneas del
 * Tab en config/blacklist-tab.txt para ajustar UNCLAIMED a como las escribe el servidor.
 */
public final class TabInfo {

    // Acepta "Unclaimed Chests: 20", "Unopened Chests: 20", "Unclaimed Dungeon Chests: 20"...
    private static final Pattern UNCLAIMED =
            Pattern.compile("(?i)(?:unclaimed|unopened)\\s+(?:dungeon\\s+)?chests?\\s*:?\\s*(\\d+)");

    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("blacklist-tab.txt");

    private static final int EVERY_TICKS = 40; // cada 2 s
    private static int ticks;

    private TabInfo() {
    }

    /** Todas las líneas visibles del Tab, sin colores. */
    public static List<String> lines(Minecraft mc) {
        List<String> result = new ArrayList<>();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return result;

        for (PlayerInfo info : connection.getOnlinePlayers()) {
            Component name = info.getTabListDisplayName();
            if (name == null) continue;
            String text = name.getString().strip();
            if (!text.isEmpty()) result.add(text);
        }
        return result;
    }

    /**
     * Lineas del Tab en el orden en que Hypixel las muestra: ordenadas por el nombre (falso) de cada entrada,
     * que es lo que usa el servidor para colocarlas ("!A-a", "!B-a"...). getOnlinePlayers() no garantiza orden.
     */
    public static List<String> orderedLines(Minecraft mc) {
        List<String> result = new ArrayList<>();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return result;

        List<PlayerInfo> infos = new ArrayList<>(connection.getOnlinePlayers());
        infos.sort(java.util.Comparator.comparing(i -> i.getProfile().name(), String.CASE_INSENSITIVE_ORDER));
        for (PlayerInfo info : infos) {
            Component name = info.getTabListDisplayName();
            if (name == null) continue;
            String text = name.getString().strip();
            if (!text.isEmpty()) result.add(text);
        }
        return result;
    }

    /** Número de cofres sin abrir según el Tab, o -1 si no aparece. */
    static int parse(List<String> lines) {
        for (String line : lines) {
            Matcher m = UNCLAIMED.matcher(line);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    public static void tick(Minecraft mc, BlacklistConfig config) {
        if (!config.profitEnabled) return;
        if (++ticks % EVERY_TICKS != 0) return;

        int unopened = parse(lines(mc));
        if (unopened >= 0) ProfitHud.setUnopened(unopened);
    }

    /** Guarda las líneas del Tab en un archivo para poder revisarlas. Devuelve cuántas guardó. */
    public static int dump(Minecraft mc) {
        List<String> lines = lines(mc);
        try {
            Files.writeString(FILE, String.join("\n", lines) + "\n");
        } catch (Exception ignored) {
        }
        return lines.size();
    }
}
