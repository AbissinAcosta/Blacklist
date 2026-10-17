package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pide a tu servidor (GET /shards/<jugador>) los atributos rekindle (shard Murkbat)
 * y reborn (shard Prince) del jugador. La clave de Hypixel vive solo en el servidor.
 */
public final class ShardChecker {

    /** Atributo vigilado: id en la API, nombre del shard, nombre del atributo y máximo (rareza más alta). */
    private record Watched(String attributeId, String shard, String attribute, int max) {
    }

    private static final Watched[] WATCHED = {
            new Watched("rekindle", "Murkbat", "Rekindle", 48),
            new Watched("reborn", "Prince", "Reborn", 32)
    };

    private ShardChecker() {
    }

    /**
     * Devuelve attributeId -> nivel (solo los que tiene), o null si algo falló.
     * Bloqueante: llamar fuera del hilo del juego.
     */
    public static Map<String, Integer> fetch(HttpClient http, String baseUrl, String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/shards/" + name))
                    .timeout(Duration.ofSeconds(60)) // Render puede estar despertando
                    .header("User-Agent", "BlacklistMod/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) return null;

            Map<String, Integer> result = new LinkedHashMap<>();
            JsonObject levels = root.getAsJsonObject("levels");
            if (levels != null) {
                for (Watched w : WATCHED) {
                    JsonElement v = levels.get(w.attributeId());
                    if (v != null && v.getAsInt() > 0) result.put(w.attributeId(), v.getAsInt());
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /** Cuántos de los shards vigilados le faltan (0 = tiene los dos, 2 = no tiene ninguno). */
    public static int missing(Map<String, Integer> levels) {
        int missing = 0;
        for (Watched w : WATCHED) {
            if (levels == null || !levels.containsKey(w.attributeId())) missing++;
        }
        return missing;
    }

    /**
     * Texto del aviso, p. ej. "Murkbat (Rekindle) no tiene, Prince (Reborn) 16/32".
     * Null si no tiene ninguno de los dos (eso lo maneja el aviso de "sin shards").
     */
    public static String describe(Map<String, Integer> levels) {
        if (levels == null || levels.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Watched w : WATCHED) {
            if (sb.length() > 0) sb.append(", ");
            Integer level = levels.get(w.attributeId());
            sb.append(w.shard()).append(" (").append(w.attribute()).append(") ");
            if (level == null) {
                sb.append("no tiene");
            } else {
                sb.append(level).append("/").append(w.max());
                if (level >= w.max()) sb.append(" MAX");
            }
        }
        return sb.toString();
    }
}
