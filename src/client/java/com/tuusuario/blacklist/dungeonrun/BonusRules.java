package com.tuusuario.blacklist.dungeonrun;

/**
 * Puntos de bonus por fuente. Estan aca, y no repartidos por el codigo, para poder
 * corregirlos con un solo cambio (la documentacion de Hades dice que el mimic da +2;
 * vos indicaste 1, hay que confirmarlo en el juego).
 */
public final class BonusRules {

    public static int MIMIC_POINTS = 1;
    public static int PRINCE_POINTS = 1;
    public static int MURKBAT_POINTS_PER_PLAYER = 1;

    private BonusRules() {}
}
