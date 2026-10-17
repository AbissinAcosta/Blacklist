package com.tuusuario.blacklist;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Coordina las consultas de shards entre los jugadores de la party que usan el mod.
 *
 * Cuando alguien entra a la party, cada copia del mod envía una petición al chat de la party.
 * El primero en llegar hace la consulta a la API y comparte el resultado. Los demás reciben
 * el resultado y saltan su propia petición, ahorrando llamadas a la API de Hypixel.
 *
 * Protocolo (mensajes ocultos en el chat de la party):
 *   __SHARD_REQ:&lt;nombre&gt;  — pido que se consulte a un jugador
 *   __SHARD_RES:&lt;nombre&gt;:rekindle=48,reborn=32  — resultado de la consulta
 */
public final class ShardCoordinator {

    /** Cuánto tiempo (ms) espero a que otro responda antes de hacer yo la petición. */
    private static final long RESPONSE_WAIT_MS = 2_000;

    /** Cuánto tiempo (ms) se guarda un resultado en caché local. */
    private static final long CACHE_TTL_MS = 5 * 60_000; // 5 minutos

    /** Prefijo que identifica los mensajes del protocolo. */
    private static final String REQ_PREFIX = "__SHARD_REQ:";
    private static final String RES_PREFIX = "__SHARD_RES:";

    private final HttpClient http;
    private final String baseUrl;
    private final ScheduledExecutorService scheduler;
    private final BooleanSupplier chatEnabled;
    private final Consumer<String> sendParty;

    // Resultados en caché: nombre (minúsculas) -> (niveles, timestamp)
    private static final class CacheEntry {
        final Map<String, Integer> levels;
        final long ts;
        CacheEntry(Map<String, Integer> levels, long ts) {
            this.levels = levels;
            this.ts = ts;
        }
    }
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    // Peticiones en curso: nombre -> flag que indica si ya está en progreso
    private final Map<String, AtomicBoolean> inProgress = new ConcurrentHashMap<>();

    // Callbacks pendientes por nombre: se llaman cuando llega el resultado
    private final Map<String, java.util.List<Consumer<Map<String, Integer>>>> pendingCallbacks
            = new ConcurrentHashMap<>();

    public ShardCoordinator(HttpClient http, String baseUrl, ScheduledExecutorService scheduler,
                            BooleanSupplier chatEnabled, Consumer<String> sendParty) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.scheduler = scheduler;
        this.chatEnabled = chatEnabled;
        this.sendParty = sendParty;
    }

    /**
     * Obtiene los shards de un jugador, coordinando con otros jugadores del mod.
     * Si hay un resultado reciente en caché, se usa directamente.
     * Si el chat del mod está activo, se envía una petición y se espera a que otro responda;
     * si nadie responde a tiempo, se hace la consulta y se comparte el resultado.
     *
     * El callback se llama con el mapa de niveles (puede ser null si falló).
     */
    public void check(String name, Consumer<Map<String, Integer>> callback) {
        String key = name.toLowerCase();

        // 1. ¿Hay un resultado fresco en caché?
        CacheEntry entry = cache.get(key);
        if (entry != null && System.currentTimeMillis() - entry.ts < CACHE_TTL_MS) {
            callback.accept(entry.levels);
            return;
        }

        // 2. Registrar el callback para cuando llegue el resultado
        pendingCallbacks.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(callback);

        // 3. ¿Ya hay una petición en curso para este jugador?
        AtomicBoolean progress = inProgress.computeIfAbsent(key, k -> new AtomicBoolean(false));
        if (progress.get()) {
            // Ya hay alguien trabajando en esto; el callback se llamará cuando llegue el resultado
            return;
        }

        // 4. Intentar ser el que hace la petición
        if (!progress.compareAndSet(false, true)) {
            // Otro hilo ganó; el callback ya está registrado
            return;
        }

        // 5. ¿Chat activo? Si sí, envío petición y espero; si no, consulto directo
        if (chatEnabled.getAsBoolean()) {
            // Envío la petición al chat de la party
            sendParty.accept(REQ_PREFIX + name);

            // Espero un momento a que otro responda
            scheduler.schedule(() -> {
                // Si todavía no hay resultado en caché, hago yo la consulta
                CacheEntry e = cache.get(key);
                if (e == null || System.currentTimeMillis() - e.ts >= CACHE_TTL_MS) {
                    fetchAndShare(name);
                } else {
                    // Alguien ya puso el resultado en caché; llamo a los callbacks
                    callCallbacks(key, e.levels);
                }
                inProgress.remove(key);
            }, RESPONSE_WAIT_MS, TimeUnit.MILLISECONDS);
        } else {
            // Sin chat: consulta directa
            scheduler.execute(() -> fetchAndShare(name));
        }
    }

    /**
     * Se llama cuando llega un mensaje del chat de la party.
     * Devuelve true si el mensaje es parte del protocolo y se debe ocultar del chat visible.
     */
    public boolean onSharedMessage(String sender, String text) {
        if (text.startsWith(RES_PREFIX)) {
            // Formato: __SHARD_RES:&lt;nombre&gt;:rekindle=48,reborn=32
            int nameEnd = text.indexOf(':', RES_PREFIX.length());
            if (nameEnd < 0) return true; // mal formado, lo oculto

            String playerName = text.substring(RES_PREFIX.length(), nameEnd);
            String data = text.substring(nameEnd + 1);
            String key = playerName.toLowerCase();

            Map<String, Integer> levels = parseLevels(data);
            if (levels != null) {
                long now = System.currentTimeMillis();
                cache.put(key, new CacheEntry(levels, now));
                callCallbacks(key, levels);
            }
            inProgress.remove(key);
            return true; // ocultar
        }
        if (text.startsWith(REQ_PREFIX)) {
            // Petición de otro jugador: respondo si no estoy ya pidiendo al mismo
            String requested = text.substring(REQ_PREFIX.length());
            String key = requested.toLowerCase();

            AtomicBoolean progress = inProgress.computeIfAbsent(key, k -> new AtomicBoolean(false));
            if (!progress.get()) {
                // Soy el primero en ver esta petición: hago la consulta
                scheduler.execute(() -> fetchAndShare(requested));
            }
            return true; // ocultar
        }
        return false;
    }

    private void fetchAndShare(String name) {
        String key = name.toLowerCase();
        Map<String, Integer> levels = ShardChecker.fetch(http, baseUrl, name);

        if (levels != null) {
            long now = System.currentTimeMillis();
            cache.put(key, new CacheEntry(levels, now));

            // Comparto el resultado con la party
            if (chatEnabled.getAsBoolean()) {
                String shared = RES_PREFIX + name + ":" + toDataString(levels);
                sendParty.accept(shared);
            }
        }
        // Llamo a los callbacks pendientes
        callCallbacks(key, levels);
        inProgress.remove(key);
    }

    private void callCallbacks(String key, Map<String, Integer> levels) {
        java.util.List<Consumer<Map<String, Integer>>> callbacks = pendingCallbacks.remove(key);
        if (callbacks != null) {
            for (Consumer<Map<String, Integer>> cb : callbacks) {
                cb.accept(levels);
            }
        }
    }

    private static Map<String, Integer> parseLevels(String data) {
        try {
            Map<String, Integer> result = new LinkedHashMap<>();
            String[] pairs = data.split(",");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).trim();
                    int v = Integer.parseInt(pair.substring(eq + 1).trim());
                    if (v > 0) result.put(k, v);
                }
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    private static String toDataString(Map<String, Integer> levels) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            if (!first) sb.append(",");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }
}
