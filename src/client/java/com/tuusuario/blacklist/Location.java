package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Dónde estás dentro de SkyBlock. La zona sale de la línea "Area: ..." del Tab de Hypixel y,
 * para Catacombs / Kuudra / Dungeon Hub, también del marcador (scoreboard) de la derecha.
 * Lo usan el profit de cofres (solo en el Dungeon Hub y dungeons) y los atajos de comandos.
 *
 * Si no se detecta bien, usa /blacklistmod profit zone para ver lo que lee el mod.
 */
public final class Location {

    /** Para comprobar que Minecraft ejecuta esta versión (se muestra en /blacklistmod profit zone). */
    public static final String VERSION = "v5";

    private static final int EVERY_TICKS = 20; // 1 s

    private static volatile Zone zone = null;
    private static volatile String area = "";
    private static int ticks;

    private Location() {
    }

    /** Zona actual, o null si no se reconoce (o no hay línea Area en el Tab). */
    public static Zone current() {
        return zone;
    }

    /** Texto de la línea "Area:" del Tab tal como lo lee el mod ("" si no aparece). */
    public static String areaName() {
        return area;
    }

    /** ¿Estás en el Dungeon Hub o dentro de una dungeon? */
    public static boolean inDungeons() {
        Zone z = zone;
        return z == Zone.DUNGEON_HUB || z == Zone.CATACOMBS;
    }

    /** Al salir de Hypixel: no estás en ningún sitio. */
    public static void clear() {
        zone = null;
        area = "";
    }

    public static void tick(Minecraft mc) {
        if (++ticks % EVERY_TICKS != 0) return;

        List<String> side = sidebar(mc);
        area = tabArea(TabInfo.lines(mc));

        Zone fromSidebar = Zone.fromSidebar(side);
        zone = fromSidebar != null ? fromSidebar : Zone.fromTabArea(area);
    }

    /** Valor de la línea "Area: Dungeon Hub" del Tab ("" si no hay). */
    static String tabArea(List<String> lines) {
        for (String line : lines) {
            String l = clean(line).strip();
            if (l.regionMatches(true, 0, "area:", 0, 5)) return l.substring(5).strip();
        }
        return "";
    }

    /** Quita los códigos de color (sección + letra) que Hypixel mete sueltos en medio del texto. */
    static String clean(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 167) { // el caracter de sección
                i++; // se salta también la letra del código
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Líneas del marcador lateral, sin colores. */
    public static List<String> sidebar(Minecraft mc) {
        List<String> result = new ArrayList<>();
        if (mc.level == null) return result;

        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) return result;

        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
            Component text = PlayerTeam.formatNameForTeam(team, Component.literal(entry.owner()));
            String line = clean(text.getString()).strip();
            if (!line.isEmpty()) result.add(line);
        }
        return result;
    }
}
