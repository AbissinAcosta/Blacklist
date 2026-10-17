package com.tuusuario.blacklist.dungeonrun;

import java.util.List;
import java.util.Map;

/**
 * Convierte el tiempo por celda en salas. Hoy existe solo la version "una celda = una sala".
 * Aca se enchufa despues la deteccion real de salas (nombre, forma, celdas fusionadas).
 */
public interface RoomResolver {

    List<RunData.RoomEntry> resolve(Map<String, Map<String, Double>> cellTimes);
}
