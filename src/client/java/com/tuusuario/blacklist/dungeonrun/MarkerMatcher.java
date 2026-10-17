package com.tuusuario.blacklist.dungeonrun;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decide que marcador del mapa es de que jugador. No depende de Minecraft.
 *
 * Los marcadores no traen nombre, y asumir que van en el orden del tab puede estar mal (entonces un jugador
 * "hereda" el camino de otro). Por eso el orden del tab es solo una pista debil; lo que manda es:
 *  1. Los jugadores que el cliente ve como entidad ("anclas"): su posicion real fija cual marcador es suyo.
 *  2. Continuidad: un jugador que deja de verse sigue siendo el marcador mas cercano a donde estaba.
 * Para el resto se prefiere NO tener dato (un hueco en el camino) antes que atribuirselo a la persona equivocada.
 */
public final class MarkerMatcher {

    private static final double ORDER_PENALTY = 4.0;   // el orden del tab desempata, nada mas
    private static final double NEUTRAL_COST = 8.0;    // jugador nunca visto: no sabemos nada
    private static final double UNASSIGNED_COST = 25.0; // dejar un marcador sin dueno
    private static final double CAP = 60.0;            // distancias grandes no pesan mas que esto
    private static final double ORDER_AGREE_BLOCKS = 8.0;
    private static final int MAX_MARKERS = 7;

    private static final class Seen {
        double x, z;
        long ms;
    }

    private final Map<String, Seen> last = new HashMap<>();

    // Estadisticas para /blacklistmod run debug
    public int accepted;
    public int rejected;
    public boolean orderTrusted;
    public boolean orderContradicted;

    public void reset() {
        last.clear();
        accepted = 0;
        rejected = 0;
        orderTrusted = false;
        orderContradicted = false;
    }

    private void remember(String name, double x, double z, long nowMs) {
        Seen s = last.computeIfAbsent(name, k -> new Seen());
        s.x = x;
        s.z = z;
        s.ms = nowMs;
    }

    private static double dist(double ax, double az, double bx, double bz) {
        return Math.hypot(ax - bx, az - bz);
    }

    /**
     * @param markers posiciones del mundo {x, z} de cada marcador, en el orden en que llegan
     * @param alive   jugadores vivos en el orden del tab (pista)
     * @param anchors jugadores vistos como entidad este tick: nombre -> {x, z}
     * @return posiciones SOLO de los jugadores que no estan en anchors y se pudieron identificar con seguridad
     */
    public Map<String, double[]> match(long nowMs, List<double[]> markers, List<String> alive,
                                       Map<String, double[]> anchors) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : anchors.entrySet()) {
            remember(e.getKey(), e.getValue()[0], e.getValue()[1], nowMs);
        }
        if (markers.isEmpty() || markers.size() > MAX_MARKERS) return out;

        List<String> names = new ArrayList<>(alive);
        for (String a : anchors.keySet()) if (!names.contains(a)) names.add(a);
        if (names.isEmpty() || names.size() > MAX_MARKERS + 1) return out;

        // ¿Se puede confiar en el orden del tab? Solo si las anclas lo confirman y ninguna lo contradice.
        boolean sameSize = !alive.isEmpty() && alive.size() == markers.size();
        int agree = 0;
        int contradict = 0;
        if (sameSize) {
            for (int i = 0; i < alive.size(); i++) {
                double[] a = anchors.get(alive.get(i));
                if (a == null) continue;
                double[] m = markers.get(i);
                if (dist(a[0], a[1], m[0], m[1]) <= ORDER_AGREE_BLOCKS) agree++;
                else contradict++;
            }
        }
        boolean usePrior = sameSize && contradict == 0;
        orderTrusted = usePrior && agree > 0;
        orderContradicted = sameSize && contradict > 0;

        int n = names.size();
        int k = markers.size();
        double[][] cost = new double[n][k];
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            double[] anchor = anchors.get(name);
            Seen seen = last.get(name);
            int orderIdx = alive.indexOf(name);
            for (int j = 0; j < k; j++) {
                double[] m = markers.get(j);
                double c;
                if (anchor != null) c = Math.min(CAP, dist(anchor[0], anchor[1], m[0], m[1]));
                else if (seen != null) c = Math.min(CAP, dist(seen.x, seen.z, m[0], m[1]));
                else c = NEUTRAL_COST;
                if (usePrior && orderIdx != j) c += ORDER_PENALTY;
                cost[i][j] = c;
            }
        }

        // Asignacion de costo minimo (como mucho 7 marcadores y 8 nombres: se prueban todas)
        int[] best = new int[k];
        int[] cur = new int[k];
        double[] bestCost = { Double.MAX_VALUE };
        search(0, k, n, cost, new boolean[n], cur, 0, best, bestCost);

        for (int j = 0; j < k; j++) {
            int i = best[j];
            if (i < 0) continue;
            String name = names.get(i);
            if (anchors.containsKey(name)) continue; // ya se conoce su posicion real
            double[] m = markers.get(j);

            boolean ok;
            Seen seen = last.get(name);
            if (seen != null) {
                double ageSec = Math.max(0, (nowMs - seen.ms) / 1000.0);
                double allowed = Math.min(CAP, 8.0 + 10.0 * ageSec); // lo que pudo haberse movido
                ok = dist(seen.x, seen.z, m[0], m[1]) <= allowed;
            } else {
                ok = orderTrusted && alive.indexOf(name) == j;
            }
            if (ok) {
                out.put(name, m);
                remember(name, m[0], m[1], nowMs);
                accepted++;
            } else {
                rejected++;
            }
        }
        return out;
    }

    private static void search(int j, int k, int n, double[][] cost, boolean[] used, int[] cur, double acc,
                               int[] best, double[] bestCost) {
        if (acc >= bestCost[0]) return;
        if (j == k) {
            bestCost[0] = acc;
            System.arraycopy(cur, 0, best, 0, k);
            return;
        }
        for (int i = 0; i < n; i++) {
            if (used[i]) continue;
            used[i] = true;
            cur[j] = i;
            search(j + 1, k, n, cost, used, cur, acc + cost[i][j], best, bestCost);
            used[i] = false;
        }
        cur[j] = -1; // este marcador queda sin dueno
        search(j + 1, k, n, cost, used, cur, acc + UNASSIGNED_COST, best, bestCost);
    }
}
