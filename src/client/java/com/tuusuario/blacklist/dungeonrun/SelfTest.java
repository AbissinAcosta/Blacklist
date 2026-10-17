package com.tuusuario.blacklist.dungeonrun;

import java.util.*;

public class SelfTest {
    static int fails = 0;
    static void check(String what, double got, double want) {
        boolean ok = Math.abs(got - want) < 0.001;
        if (!ok) fails++;
        System.out.println((ok ? "OK   " : "FAIL ") + what + " got=" + got + " want=" + want);
    }
    public static void main(String[] a) {
        // Grilla
        System.out.println("celda(-190,-190)=" + DungeonGrid.key(-190, -190) + " celda(-150,-190)=" + DungeonGrid.key(-150, -190)
            + " fuera=" + DungeonGrid.key(0, 0) + " borde=" + DungeonGrid.key(-168.01, -200));

        RunTracker t = new RunTracker();
        t.start(0);
        for (long ms = 50; ms <= 20000; ms += 50) {
            Map<String, double[]> pos = new LinkedHashMap<>();
            pos.put("A", new double[] { -190, -190 });          // celda 0,0 todo el tiempo
            if (ms <= 10000) pos.put("B", new double[] { -150, -190 }); // celda 1,0 hasta t=10s, despues desaparece
            t.sample(ms, pos);
            if (ms == 12000) t.onScore(ms, 300);
            if (ms == 5000) { t.markMimic(ms); t.markMimic(ms); t.setMurkbat(ms, 3); }
        }
        RunData r = t.finish(20000, "F7", new CellRoomResolver());

        check("A en 0,0", r.cellTimes.get("0,0").get("A"), 20.0);
        check("B en 1,0 (10s + 3s de espera)", r.cellTimes.get("1,0").get("B"), 13.0);
        check("total", r.totalSeconds, 20.0);
        check("tiempo a 300", r.secondsTo300, 12.0);
        check("bonus total (mimic 1 + murkbat 3)", r.bonusTotal(), 4.0);
        check("salas", r.rooms.size(), 2.0);
        check("jugadores", r.players.size(), 2.0);

        // Tope por lag: salto de 10s cuenta 2s
        RunTracker t2 = new RunTracker();
        t2.start(0);
        Map<String, double[]> p = new LinkedHashMap<>();
        p.put("A", new double[] { -190, -190 });
        t2.sample(10000, p);
        check("tope por lag", t2.finish(10000, null, new CellRoomResolver()).cellTimes.get("0,0").get("A"), 2.0);

        System.out.println(fails == 0 ? "TODO OK" : fails + " FALLAS");
        System.exit(fails == 0 ? 0 : 1);
    }
}
