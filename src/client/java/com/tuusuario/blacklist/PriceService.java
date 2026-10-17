package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Precios de SkyBlock en memoria: lowest BIN de subastas + Bazaar.
 *
 * - Lowest BIN: API pública "lowestbins" (JSON simple id -> precio). Si la primera dirección
 *   no responde se prueba la segunda. Si algún día dejan de funcionar, cambia BIN_URLS.
 * - Bazaar: API oficial de Hypixel (no necesita clave). Se usa el precio de venta instantánea
 *   (sellPrice), que es lo que realmente te darían por vender; el Bazaar pisa al BIN.
 */
public final class PriceService {

    private static final String[] BIN_URLS = {
            "https://lb.tricked.dev/lowestbins",
            "https://lb.tricked.pro/lowestbins"
    };
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";

    private static volatile Map<String, Double> prices = Map.of();
    private static volatile long lastUpdate = 0;

    private PriceService() {
    }

    public static boolean ready() {
        return !prices.isEmpty();
    }

    public static int size() {
        return prices.size();
    }

    public static long lastUpdate() {
        return lastUpdate;
    }

    /** Precio del ítem, o -1 si no hay dato. */
    public static double price(String id) {
        if (id == null) return -1;
        Double p = prices.get(id);
        return p == null ? -1 : p;
    }

    /** Bloqueante: llamar fuera del hilo del juego. Devuelve true si se actualizó algo. */
    public static boolean refresh(HttpClient http) {
        Map<String, Double> fresh = new HashMap<>();

        for (String url : BIN_URLS) {
            if (loadBins(http, url, fresh)) break;
        }
        loadBazaar(http, fresh);

        if (fresh.isEmpty()) return false; // todo falló: se conservan los precios anteriores
        prices = fresh;
        lastUpdate = System.currentTimeMillis();
        return true;
    }

    private static boolean loadBins(HttpClient http, String url, Map<String, Double> out) {
        try {
            String body = get(http, url);
            if (body == null) return false;
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    double price = v.getAsDouble();
                    if (price > 0) out.put(e.getKey(), price);
                }
            }
            return !out.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static void loadBazaar(HttpClient http, Map<String, Double> out) {
        try {
            String body = get(http, BAZAAR_URL);
            if (body == null) return;
            JsonObject products = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("products");
            if (products == null) return;
            for (Map.Entry<String, JsonElement> e : products.entrySet()) {
                JsonObject quick = e.getValue().getAsJsonObject().getAsJsonObject("quick_status");
                if (quick == null) continue;
                double sell = quick.get("sellPrice").getAsDouble();
                if (sell <= 0) sell = quick.get("buyPrice").getAsDouble();
                if (sell > 0) out.put(e.getKey(), sell);
            }
        } catch (Exception ignored) {
            // sin Bazaar: queda lo que haya del BIN
        }
    }

    private static String get(HttpClient http, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("User-Agent", "BlacklistMod/1.0")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? response.body() : null;
    }
}
