package com.tuusuario.blacklist.dungeonrun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logica de la run en curso. No depende de Minecraft: recibe posiciones y tiempos y los acumula.
 * Los tiempos (nowMs) deben venir de un reloj monotono, por ejemplo System.nanoTime() / 1_000_000.
 */
public final class RunTracker {

    /** Si un jugador desaparece (sale del render distance), se le sigue contando su ultima celda este tiempo. */
    public static final long HOLD_MS = 3000;
    /** Tope por muestra, para que un lag spike o una pausa no cuente como tiempo en sala. */
    public static final long MAX_STEP_MS = 2000;

    private static final class PlayerState {
        String cell;
        long lastSeenMs;
    }

    private final Map<String, PlayerState> states = new LinkedHashMap<>();
    private final Map<String, Map<String, Double>> cellTimes = new LinkedHashMap<>();
    private final List<RunData.BonusEntry> bonus = new ArrayList<>();

    /** Un punto nuevo del recorrido solo se guarda si el jugador se movio al menos esto (en celdas, ~2.5 bloques). */
    private static final double PATH_MIN_STEP = 0.08;
    private static final int PATH_MAX_POINTS = 6000;
    private final Map<String, List<float[]>> paths = new LinkedHashMap<>();

    private boolean running;
    private long startMs;
    private long lastMs;
    private Double secondsTo300;
    private Integer secretsFound;

    public boolean isRunning() {
        return running;
    }

    /** Jugadores vistos hasta ahora en esta run. */
    public List<String> playerNames() {
        return new ArrayList<>(states.keySet());
    }

    public void start(long nowMs) {
        states.clear();
        cellTimes.clear();
        paths.clear();
        bonus.clear();
        secondsTo300 = null;
        secretsFound = null;
        running = true;
        startMs = nowMs;
        lastMs = nowMs;
    }

    /** Posiciones actuales: nombre del jugador -> {x, z}. Llamar en cada tick. */
    public void sample(long nowMs, Map<String, double[]> positions) {
        if (!running) return;
        long dt = Math.min(nowMs - lastMs, MAX_STEP_MS);
        lastMs = nowMs;

        for (Map.Entry<String, double[]> e : positions.entrySet()) {
            PlayerState st = states.computeIfAbsent(e.getKey(), k -> new PlayerState());
            st.cell = DungeonGrid.key(e.getValue()[0], e.getValue()[1]);
            st.lastSeenMs = nowMs;
            credit(st.cell, e.getKey(), dt);
            recordPath(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }

        for (Map.Entry<String, PlayerState> e : states.entrySet()) {
            if (positions.containsKey(e.getKey())) continue;
            PlayerState st = e.getValue();
            if (nowMs - st.lastSeenMs <= HOLD_MS) credit(st.cell, e.getKey(), dt);
        }
    }

    private void recordPath(String player, double worldX, double worldZ) {
        double fx = DungeonGrid.frac(worldX);
        double fz = DungeonGrid.frac(worldZ);
        if (fx < 0 || fz < 0 || fx >= DungeonGrid.CELLS || fz >= DungeonGrid.CELLS) return;
        List<float[]> list = paths.computeIfAbsent(player, k -> new ArrayList<>());
        if (list.size() >= PATH_MAX_POINTS) return;
        if (!list.isEmpty()) {
            float[] last = list.get(list.size() - 1);
            double dx = fx - last[0];
            double dz = fz - last[1];
            if (dx * dx + dz * dz < PATH_MIN_STEP * PATH_MIN_STEP) return;
        }
        list.add(new float[] { (float) fx, (float) fz });
    }

    private static String encodePath(List<float[]> pts) {
        StringBuilder sb = new StringBuilder();
        for (float[] p : pts) {
            sb.append(Math.round(p[0] * 100) / 100.0).append(',')
              .append(Math.round(p[1] * 100) / 100.0).append(';');
        }
        return sb.toString();
    }

    private void credit(String cell, String player, long dtMs) {
        if (cell == null || dtMs <= 0) return;
        cellTimes.computeIfAbsent(cell, k -> new LinkedHashMap<>())
                .merge(player, dtMs / 1000.0, Double::sum);
    }

    /** Score actual de la party; guarda el momento en que cruza 300 por primera vez. */
    public void onScore(long nowMs, int score) {
        if (running && secondsTo300 == null && score >= 300) {
            secondsTo300 = (nowMs - startMs) / 1000.0;
        }
    }

    public void setSecretsFound(int secrets) {
        secretsFound = secrets;
    }

    // -- Bonus ---------------------------------------------------------------------------

    public void markMimic(long nowMs) {
        addOnce("mimic", BonusRules.MIMIC_POINTS, nowMs, null);
    }

    public void markPrince(long nowMs) {
        addOnce("prince", BonusRules.PRINCE_POINTS, nowMs, null);
    }

    /** Cantidad de jugadores de la party que tienen el shard murkbat; reemplaza el valor anterior. */
    public void setMurkbat(long nowMs, int playersWithShard) {
        bonus.removeIf(b -> b.source.equals("murkbat"));
        if (playersWithShard > 0) {
            add("murkbat", playersWithShard * BonusRules.MURKBAT_POINTS_PER_PLAYER, nowMs,
                    playersWithShard + " jugador(es) con el shard");
        }
    }

    private void addOnce(String source, int points, long nowMs, String note) {
        for (RunData.BonusEntry b : bonus) if (b.source.equals(source)) return;
        add(source, points, nowMs, note);
    }

    private void add(String source, int points, long nowMs, String note) {
        RunData.BonusEntry b = new RunData.BonusEntry();
        b.source = source;
        b.points = points;
        b.atSeconds = running ? (nowMs - startMs) / 1000.0 : 0;
        b.note = note;
        bonus.add(b);
    }

    // -- Cierre --------------------------------------------------------------------------

    public RunData finish(long nowMs, String label, RoomResolver resolver) {
        RunData run = new RunData();
        run.floor = (label == null || label.isBlank()) ? "?" : label.strip();
        run.totalSeconds = running ? (nowMs - startMs) / 1000.0 : 0;
        run.secondsTo300 = secondsTo300;
        run.secretsFound = secretsFound;
        run.players = new ArrayList<>(states.keySet());
        for (Map.Entry<String, Map<String, Double>> e : cellTimes.entrySet()) {
            run.cellTimes.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }
        for (Map.Entry<String, List<float[]>> e : paths.entrySet()) {
            run.paths.put(e.getKey(), encodePath(e.getValue()));
        }
        run.rooms = resolver.resolve(run.cellTimes);
        run.bonus = new ArrayList<>(bonus);
        running = false;
        return run;
    }
}
