package com.tuusuario.blacklist; // <- mismo paquete que BlacklistClient

import com.tuusuario.blacklist.dungeonrun.DungeonGrid;
import com.tuusuario.blacklist.dungeonrun.DungeonMap;
import com.tuusuario.blacklist.dungeonrun.MapPlayers;
import com.tuusuario.blacklist.dungeonrun.MapRoomResolver;
import com.tuusuario.blacklist.dungeonrun.MarkerMatcher;
import com.tuusuario.blacklist.dungeonrun.PartyParser;
import com.tuusuario.blacklist.dungeonrun.RoomIndex;
import com.tuusuario.blacklist.dungeonrun.RunData;
import com.tuusuario.blacklist.dungeonrun.RunStorage;
import com.tuusuario.blacklist.dungeonrun.RunTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parte del Dungeon run que toca Minecraft. NO esta compilada contra 26.1.2: los nombres de la API
 * (PlayerInfo, getOnlinePlayers, level.players(), getUUID...) son los de los mappings de Mojang
 * que conozco; si alguno no compila, es un cambio de nombre y se arregla en una linea.
 */
public final class DungeonRunManager {

    // SUPUESTOS A VERIFICAR EN EL JUEGO (textos de Hypixel):
    private static final String START_TEXT = "I found this map when I first entered the dungeon";
    private static final String END_TEXT = "EXTRA STATS";
    private static final Pattern TEAM_SCORE = Pattern.compile("Team Score:\\s*(\\d+)");
    private static final Pattern SECRETS = Pattern.compile("Secrets Found:\\s*(\\d+)$");
    private static final Pattern FLOOR = Pattern.compile("The Catacombs \\((\\w+)\\)");

    private static final int TAB_EVERY_TICKS = 10;
    private static final int MAP_EVERY_TICKS = 20;

    private final RunTracker tracker = new RunTracker();
    private final RunStorage storage;
    private final RoomIndex roomIndex;
    private final MarkerMatcher markerMatcher = new MarkerMatcher();

    /** Jugadores reales de la party segun el tab de la dungeon (vacio = todavia no se leyo). */
    private volatile Set<String> party = Set.of();
    /** Jugadores vivos de la party en el orden del tab (el orden de los marcadores del mapa). */
    private volatile List<String> aliveOrder = List.of();
    /** Clase de cada jugador: nombre -> clase (Archer, Berserk, Healer, Mage, Tank). */
    private volatile java.util.Map<String, String> playerClasses = new java.util.HashMap<>();
    /** El mapa de la hotbar con mas salas descubiertas que se vio durante la run. */
    private DungeonMap bestMap;
    /** Celda "x,z" -> core (hash de la columna de bloques) para identificar la sala con rooms.json. */
    private final Map<String, Integer> cores = new HashMap<>();
    private final Set<String> unmapped = new TreeSet<>();
    private int lastDropped;

    /** Jugadores (en minusculas) a los que ShardChecker les encontro el murkbat. Lo escribe otro hilo. */
    private final Set<String> murkbat = ConcurrentHashMap.newKeySet();

    private RunData pending; // run terminada que todavia no se guardo
    private String floorLabel;
    private int ticks;

    public DungeonRunManager(Path runsDir) {
        this.storage = new RunStorage(runsDir);
        // config/shitterlist/rooms.json pisa al rooms.json que viene dentro del mod
        Path parent = runsDir.getParent();
        this.roomIndex = RoomIndex.load(parent == null ? null : parent.resolve("rooms.json"));
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    // -- Eventos (llamados desde BlacklistClient) -------------------------------------------

    public void tick(Minecraft mc) {
        if (!tracker.isRunning() || mc.level == null) return;
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;

        long now = nowMs();

        Map<String, double[]> positions = new LinkedHashMap<>();
        Set<String> members = party;
        int dropped = 0;
        for (Player p : mc.level.players()) {
            // Los NPC no figuran en la lista de jugadores (tab)...
            if (conn.getPlayerInfo(p.getUUID()) == null) continue;
            // ...pero los mobs de la dungeon con forma de jugador si: se descartan por UUID y por la party
            String name = p.getName().getString();
            if (!PartyParser.isRealPlayer(p.getUUID(), name, members, p == mc.player)) {
                dropped++;
                continue;
            }
            positions.put(name, new double[] { p.getX(), p.getZ() });
        }
        lastDropped = dropped;

        // Los que el cliente no ve como entidad (lejos) se siguen con los marcadores del mapa
        if (bestMap != null) {
            List<int[]> rawMarkers = DungeonMapReader.readMarkers(mc);
            List<double[]> markerWorld = new ArrayList<>();
            for (int[] m : rawMarkers) {
                double[] w = MapPlayers.toWorld(bestMap, m[0], m[1]);
                if (w != null) markerWorld.add(w);
            }
            Map<String, double[]> fromMap = markerMatcher.match(now, markerWorld, aliveOrder, positions);
            for (Map.Entry<String, double[]> e : fromMap.entrySet()) {
                if (members.isEmpty() || members.contains(e.getKey())) positions.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        tracker.sample(now, positions);

        ticks++;
        if (ticks % TAB_EVERY_TICKS == 0) readTab(conn, now);
        if (ticks % MAP_EVERY_TICKS == 0) scanMap(mc);
    }

    /** Lee el mapa de la hotbar y, de paso, calcula el core de las salas cuyo chunk esta cargado. */
    private void scanMap(Minecraft mc) {
        byte[] colors = DungeonMapReader.read(mc);
        if (colors != null) {
            DungeonMap m = DungeonMap.parse(colors);
            if (m != null && (bestMap == null || m.knownCells() >= bestMap.knownCells())) bestMap = m;
        }
        if (mc.level == null) return;
        for (int z = 0; z < DungeonGrid.CELLS; z++) {
            for (int x = 0; x < DungeonGrid.CELLS; x++) {
                String key = x + "," + z;
                if (cores.containsKey(key)) continue;
                if (bestMap != null && bestMap.roomAt(x, z) == null) continue; // celda vacia: no es sala
                Integer h = DungeonCores.hash(mc.level, x, z, unmapped);
                if (h != null) cores.put(key, h);
            }
        }
    }

    public void onGameMessage(String plain) {
        if (!tracker.isRunning() && plain.contains(START_TEXT)) {
            start();
        } else if (tracker.isRunning() && plain.contains(END_TEXT)) {
            stop();
        }
    }

    private void readTab(ClientPacketListener conn, long now) {
        List<String> lines = new ArrayList<>();
        for (PlayerInfo info : conn.getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) continue;
            String text = display.getString().trim();
            lines.add(text);

            Matcher score = TEAM_SCORE.matcher(text);
            if (score.find()) tracker.onScore(now, Integer.parseInt(score.group(1)));

            Matcher secrets = SECRETS.matcher(text);
            if (secrets.find()) tracker.setSecretsFound(Integer.parseInt(secrets.group(1)));
        }
        refreshParty(lines);
        aliveOrder = PartyParser.aliveInOrder(TabInfo.orderedLines(Minecraft.getInstance()));
    }

    private void refreshParty(List<String> tabLines) {
        Set<String> found = PartyParser.members(tabLines);
        if (!found.isEmpty()) {
            party = found;
            // Extraer clases de cada jugador
            java.util.Map<String, String> classes = new java.util.HashMap<>();
            for (String line : tabLines) {
                java.util.Map.Entry<String, String> entry = PartyParser.memberNameAndClass(line);
                if (entry != null && entry.getValue() != null) {
                    classes.put(entry.getKey(), entry.getValue());
                }
            }
            playerClasses = classes;
        }
    }

    private static String readFloor(Minecraft mc) {
        try {
            for (String line : Location.sidebar(mc)) {
                String clean = line.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", "");
                Matcher m = FLOOR.matcher(clean);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {
            // si el marcador no se puede leer, la etiqueta queda en "?"
        }
        return null;
    }

    // -- Hooks para el resto del mod ---------------------------------------------------------

    /** Lo llama checkShards (hilo del scheduler) cuando termina de consultar a un jugador. */
    public void registerMurkbat(String player, boolean has) {
        if (has) murkbat.add(player.toLowerCase());
        else murkbat.remove(player.toLowerCase());
    }

    public void markMimic() {
        if (tracker.isRunning()) tracker.markMimic(nowMs());
    }

    public void markPrince() {
        if (tracker.isRunning()) tracker.markPrince(nowMs());
    }

    // -- Acciones (comandos y GUI) -----------------------------------------------------------

    public void start() {
        pending = null;
        ticks = 0;
        party = Set.of();
        bestMap = null;
        aliveOrder = List.of();
        cores.clear();
        unmapped.clear();
        markerMatcher.reset();
        refreshParty(TabInfo.lines(Minecraft.getInstance()));
        floorLabel = readFloor(Minecraft.getInstance());
        tracker.start(nowMs());
        say("Run iniciada" + (floorLabel == null ? "." : " (" + floorLabel + ")."));
    }

    public void stop() {
        if (!tracker.isRunning()) {
            say("No hay ninguna run en curso.");
            return;
        }
        long now = nowMs();

        // Murkbat: 1 punto por cada jugador de esta run que tenga el shard
        int withShard = 0;
        for (String name : tracker.playerNames()) {
            if (murkbat.contains(name.toLowerCase())) withShard++;
        }
        tracker.setMurkbat(now, withShard);

        String label = floorLabel != null ? floorLabel : readFloor(Minecraft.getInstance());
        scanMap(Minecraft.getInstance()); // ultima lectura del mapa, con todo lo descubierto
        pending = tracker.finish(now, label, new MapRoomResolver(bestMap, roomIndex, new HashMap<>(cores)));
        pending.doors = bestMap == null ? new ArrayList<>() : bestMap.doorList();
        // Guardar las clases de los jugadores
        java.util.List<String> classes = new java.util.ArrayList<>();
        for (String name : pending.players) {
            String cls = playerClasses.get(name);
            classes.add(cls);
        }
        pending.playerClasses = classes;

        String to300 = pending.secondsTo300 == null ? "no llego a 300"
                : String.format("300 de score a los %.1fs", pending.secondsTo300);
        say(String.format("Run terminada: %.1fs, %s. Guardala con /blacklistmod run save o desde el GUI.",
                pending.totalSeconds, to300));
    }

    public void save(String label) {
        if (pending == null) {
            say("No hay una run terminada para guardar.");
            return;
        }
        if (label != null && !label.isBlank()) pending.floor = label.strip();
        try {
            String id = storage.save(pending);
            pending = null;
            say("Run guardada: " + id);
        } catch (IOException e) {
            say("No se pudo guardar la run: " + e.getMessage());
        }
    }

    public List<RunData> list() {
        return storage.list();
    }

    public boolean delete(String id) {
        try {
            return storage.delete(id);
        } catch (IOException e) {
            say("No se pudo borrar la run: " + e.getMessage());
            return false;
        }
    }

    /** /blacklistmod run debug: que ve el mod (party, mapa, cores). Sirve para ajustar lo que falle. */
    public void debug() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            say("Entra a una dungeon para usar el debug.");
            return;
        }
        ClientPacketListener conn = mc.getConnection();
        Set<String> members = PartyParser.members(TabInfo.lines(mc));
        say("Party en el tab (" + members.size() + "): " + (members.isEmpty() ? "no encontrada" : members));

        int ok = 0;
        List<String> rejected = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (conn.getPlayerInfo(p.getUUID()) == null) continue;
            String name = p.getName().getString();
            if (PartyParser.isRealPlayer(p.getUUID(), name, members, p == mc.player)) {
                ok++;
            } else if (rejected.size() < 6) {
                rejected.add(name + " (uuid v" + p.getUUID().version() + ")");
            }
        }
        say("Jugadores validos: " + ok + ". Descartados (mobs/NPC), ejemplos: " + rejected);

        byte[] colors = DungeonMapReader.read(mc);
        if (colors == null) {
            say("Mapa: no hay un mapa en el ultimo slot de la hotbar.");
        } else {
            DungeonMap m = DungeonMap.parse(colors);
            if (m == null) {
                say("Mapa: lo leo pero no encuentro la sala de entrada (verde).");
            } else {
                say("Mapa: sala de " + m.roomPx + " px, origen (" + m.startX + "," + m.startY + "), "
                        + m.cols + "x" + m.rows + " celdas, " + m.rooms.size() + " salas, "
                        + m.knownCells() + " celdas descubiertas.");
                StringBuilder sb = new StringBuilder();
                for (DungeonMap.Room r : m.rooms) {
                    sb.append(r.type).append(' ').append(r.shape).append(" @");
                    for (int[] c : r.cells) sb.append(' ').append(c[0]).append(',').append(c[1]);
                    sb.append(" | ");
                }
                say(sb.toString());
            }
        }

        List<int[]> markers = DungeonMapReader.readMarkers(mc);
        List<String> alive = PartyParser.aliveInOrder(TabInfo.orderedLines(mc));
        say("Marcadores del mapa: " + markers.size() + ", jugadores vivos en el tab: " + alive.size() + " " + alive
                + (markers.size() == alive.size() ? " (coinciden)" : " (NO coinciden: no se usan los marcadores)"));
        DungeonMap dm = bestMap;
        if (dm == null) {
            byte[] colors2 = DungeonMapReader.read(mc);
            dm = colors2 == null ? null : DungeonMap.parse(colors2);
        }
        if (dm != null && markers.size() == alive.size()) {
            for (Map.Entry<String, double[]> e : MapPlayers.assign(dm, markers, alive).entrySet()) {
                double[] w = e.getValue();
                Player real = null;
                for (Player p : mc.level.players()) if (p.getName().getString().equals(e.getKey())) real = p;
                say(String.format("  %s: mapa (%.0f, %.0f)%s", e.getKey(), w[0], w[1], real == null ? " (sin entidad)"
                        : String.format(" | real (%.0f, %.0f) | error %.0f bloques", real.getX(), real.getZ(),
                                Math.hypot(real.getX() - w[0], real.getZ() - w[1]))));
            }
        }

        int cx = DungeonGrid.cellOf(mc.player.getX());
        int cz = DungeonGrid.cellOf(mc.player.getZ());
        if (cx >= 0 && cx < DungeonGrid.CELLS && cz >= 0 && cz < DungeonGrid.CELLS) {
            Set<String> missing = new TreeSet<>();
            Integer h = DungeonCores.hash(mc.level, cx, cz, missing);
            RoomIndex.Entry hit = h == null ? null : roomIndex.byCore(h);
            say("Celda " + cx + "," + cz + ": core=" + h + " -> "
                    + (hit != null ? hit.name + " (" + hit.type + ", " + hit.secrets + " secretos)"
                            : "no esta en rooms.json (" + roomIndex.size() + " salas cargadas)"));
            if (!missing.isEmpty()) say("Bloques sin id 1.8 en LegacyBlocks: " + missing);
        } else {
            say("Estas fuera de la grilla de la dungeon.");
        }
        say("Cores calculados en esta run: " + cores.size() + ".");
    }

    public boolean hasPending() {
        return pending != null;
    }

    public boolean isRunning() {
        return tracker.isRunning();
    }

    private static void say(String msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(
                        Component.literal("[Dungeon Run] " + msg).withStyle(ChatFormatting.AQUA));
            }
        });
    }
}
