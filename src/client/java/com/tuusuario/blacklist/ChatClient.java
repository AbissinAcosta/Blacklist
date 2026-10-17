package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Chat entre los usuarios del mod (global y de party). Habla con tu servidor de Render:
 *
 *  - Inicia sesión una vez con la verificación de Mojang (igual que al agregar a la blacklist) y
 *    reutiliza el token unas 2 horas.
 *  - Pregunta cada pocos segundos si hay mensajes nuevos (más seguido con el chat abierto).
 *  - Detecta tu party leyendo los mensajes de Hypixel y confirmándola con /pl (cuya salida se
 *    oculta). El servidor calcula la sala de la party con la lista de nombres.
 *
 * Comandos: /blc mensaje (global) y /blp mensaje (party), registrados en BlacklistClient.
 */
public final class ChatClient {

    public enum Room {
        GLOBAL("global", "BL"), PARTY("party", "BL-Party");

        final String api;
        final String tag;

        Room(String api, String tag) {
            this.api = api;
            this.tag = tag;
        }
    }

    /** uid = UUID (sin guiones) del emisor; avatar = hash de su foto de perfil ("" si no tiene). */
    public record ChatMessage(long id, long time, String uid, String avatar, String name, String text) {
    }

    private static final class RoomState {
        final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
        volatile long lastId = 0;
        volatile boolean loaded = false; // la primera descarga no avisa (es el historial)
        volatile int unread = 0;
        volatile String key = "";        // party: identifica la sala (cambia si cambian los miembros)
    }

    private static final int MAX_KEEP = 200;
    private static final int MAX_LEN = 200;

    // ---- Mensajes de Hypixel que indican cambios en la party ----
    private static final String RANK = "(?:\\[[^\\]]+\\] )?";
    private static final Pattern PL_SEPARATOR = Pattern.compile("^-{10,}$");
    private static final Pattern PL_HEADER = Pattern.compile("^Party Members \\(\\d+\\)$");
    private static final Pattern PL_LINE = Pattern.compile("^Party (?:Leader|Moderators|Members): (.*)$");
    private static final Pattern NOT_IN_PARTY = Pattern.compile("^You are not currently in a party\\.$");
    private static final Pattern PARTY_CHANGE = Pattern.compile("^(?:"
            + RANK + "\\w{1,16} joined the party\\.|"
            + RANK + "\\w{1,16} has left the party\\.|"
            + RANK + "\\w{1,16} has been removed from the party\\.|"
            + RANK + "\\w{1,16} was removed from your party.*|"
            + "You have joined " + RANK + "\\w{1,16}'s party!|"
            + "Party Finder > " + RANK + "\\w{1,16} joined the dungeon group!.*)$");
    private static final Pattern PARTY_GONE = Pattern.compile("^(?:"
            + "You left the party\\.|"
            + RANK + "\\w{1,16} has disbanded the party!|"
            + "The party was disbanded.*|"
            + "You have been kicked from the party by .*)$");

    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final BlacklistConfig config;
    private final String baseUrl;
    private final BooleanSupplier onHypixel;

    private final Map<Room, RoomState> rooms = new EnumMap<>(Room.class);

    // Coordinación de shards entre jugadores del mod
    private volatile ShardCoordinator shardCoordinator;

    // Sesión
    private volatile String token;
    private volatile long tokenExpiry;
    private volatile long loginRetryAt;
    private volatile String lastError = "";
    private final Object loginLock = new Object();

    // Consulta de mensajes
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile long lastPoll;
    private volatile boolean fast;      // el chat está abierto: se consulta más seguido
    private volatile boolean pollNow;
    private volatile boolean muted;

    // Conectados (usuarios del mod con sesion activa). Lo da el servidor en GET /chat/online.
    private volatile List<String> online = List.of();
    private volatile int onlineCount = 0;
    private volatile boolean onlineKnown = false;    // false = el servidor aun no respondio (o no lo soporta)
    private volatile boolean onlineUnsupported = false;
    private volatile long lastOnlinePoll;
    private static final long ONLINE_EVERY_MS = 15_000;

    // Party
    private volatile List<String> partyMembers = null; // null = sin party (o aún no se sabe)
    private final Set<String> pending = new LinkedHashSet<>();
    private volatile long headerSeen;
    private volatile long hidePlUntil;
    private volatile long lastPlSent;
    private final AtomicInteger partyGen = new AtomicInteger();
    private boolean wasOnHypixel;

    public ChatClient(HttpClient http, ScheduledExecutorService scheduler, BlacklistConfig config,
                      String baseUrl, BooleanSupplier onHypixel) {
        this.http = http;
        this.scheduler = scheduler;
        this.config = config;
        this.baseUrl = baseUrl;
        this.onHypixel = onHypixel;
        for (Room r : Room.values()) rooms.put(r, new RoomState());
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::tickPoll, 2, 1, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------ acceso (para la interfaz)

    public List<ChatMessage> messages(Room room) {
        return Collections.unmodifiableList(rooms.get(room).messages);
    }

    public int unread(Room room) {
        return rooms.get(room).unread;
    }

    public void markRead(Room room) {
        rooms.get(room).unread = 0;
    }

    // ------------------------------------------------------------ coordinación de shards

    /** Asigna el coordinador de shards (desde BlacklistClient). */
    public void setShardCoordinator(ShardCoordinator coordinator) {
        this.shardCoordinator = coordinator;
    }

    /** Envía un mensaje del protocolo de shards al chat de la party.
     * Omite las validaciones normales (party detectada, longitud) porque es tráfico interno. */
    public void sendShardProtocol(String text) {
        if (!config.chatEnabled) return;
        if (!onHypixel.getAsBoolean()) return;
        List<String> members = partyMembers;
        if (members == null) return; // sin party, no hay canal
        scheduler.execute(() -> doSend(Room.PARTY, text, members, true));
    }

    /** Miembros de tu party según el último /pl, o null si no estás en una. */
    public List<String> party() {
        return partyMembers;
    }

    /** El menu de conectados solo tiene sentido con el chat del mod activado. */
    public boolean isEnabledForOnline() {
        return config.chatEnabled;
    }

    /** Cantidad de usuarios del mod conectados, o -1 si todavia no se sabe. */
    public int onlineCount() {
        return onlineKnown ? onlineCount : -1;
    }

    /** Nombres de Minecraft de los usuarios conectados (orden alfabetico). */
    public List<String> onlineNames() {
        return online;
    }

    public boolean onlineSupported() {
        return !onlineUnsupported;
    }

    /** Pide la lista ya (por ejemplo al abrir el menu). */
    public void refreshOnline() {
        lastOnlinePoll = 0;
        pollNow();
    }

    public boolean isMuted() {
        return muted;
    }

    public String status() {
        List<String> party = partyMembers;
        long left = token == null ? 0 : Math.max(0, (tokenExpiry - System.currentTimeMillis()) / 60000);
        return "Chat " + (config.chatEnabled ? "activado" : "desactivado")
                + " | Sesión: " + (token == null ? "sin iniciar" : "activa (" + left + " min)")
                + " | Mensajes: global " + rooms.get(Room.GLOBAL).messages.size()
                + ", party " + rooms.get(Room.PARTY).messages.size()
                + " | Party: " + (party == null ? "ninguna" : String.join(", ", party))
                + (muted ? " | Estás silenciado" : "")
                + (lastError.isEmpty() ? "" : " | Último error: " + lastError);
    }

    // ------------------------------------------------------------ cada tick del juego

    /** Se llama cada tick del cliente (hilo principal). */
    public void clientTick(Minecraft mc, boolean onHypixelNow) {
        fast = mc.screen instanceof ChatScreen || mc.screen instanceof ModChatScreen;

        if (onHypixelNow && !wasOnHypixel) refreshPartySoon(7000); // al entrar a Hypixel: ¿hay party?
        if (!onHypixelNow && wasOnHypixel) clearParty();
        wasOnHypixel = onHypixelNow;

        // Si /pl se cortó a medias, se da por bueno lo leído
        if (headerSeen != 0 && System.currentTimeMillis() - headerSeen > 2500) commitPending();
    }

    // ------------------------------------------------------------ consulta periódica

    private void tickPoll() {
        try {
            if (!config.chatEnabled || !onHypixel.getAsBoolean()) return;
            long now = System.currentTimeMillis();
            if (!pollNow && now - lastPoll < (fast ? 2000 : 4000)) return;
            pollNow = false;
            pollOnce();
        } catch (Exception e) {
            lastError = "consulta: " + e.getClass().getSimpleName();
        }
    }

    private void pollNow() {
        pollNow = true;
        scheduler.execute(this::tickPoll);
    }

    private void pollOnce() throws Exception {
        if (!busy.compareAndSet(false, true)) return;
        lastPoll = System.currentTimeMillis();
        try {
            if (!ensureSession(false)) return;

            pollRoom(Room.GLOBAL, null);
            pollOnline();

            List<String> members = partyMembers;
            if (members != null) {
                pollRoom(Room.PARTY, members);
            } else {
                resetRoom(rooms.get(Room.PARTY));
            }
        } finally {
            busy.set(false);
        }
    }

    /** Conectados: se consulta cada 15 s (no hace falta mas seguido). Si el servidor no lo tiene, se deja de intentar. */
    private void pollOnline() {
        if (onlineUnsupported) return;
        long now = System.currentTimeMillis();
        if (now - lastOnlinePoll < ONLINE_EVERY_MS) return;
        lastOnlinePoll = now;
        try {
            HttpResponse<String> r = api("GET", "/chat/online", null);
            if (r.statusCode() == 404) { // servidor sin este endpoint
                onlineUnsupported = true;
                return;
            }
            if (r.statusCode() != 200) return;

            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            List<String> names = new ArrayList<>();
            if (o.has("users")) {
                for (JsonElement el : o.getAsJsonArray("users")) names.add(el.getAsString());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            online = Collections.unmodifiableList(names);
            onlineCount = o.has("count") ? o.get("count").getAsInt() : names.size();
            onlineKnown = true;
        } catch (Exception e) {
            lastError = "online: " + e.getClass().getSimpleName();
        }
    }

    private void pollRoom(Room room, List<String> members) throws Exception {
        RoomState st = rooms.get(room);

        if (room == Room.PARTY) {
            String key = roomKey(members);
            if (!key.equals(st.key)) { // otra party (o cambiaron los miembros): sala nueva
                resetRoom(st);
                st.key = key;
            }
        }

        String path = "/chat/messages?room=" + room.api
                + (members != null ? "&members=" + String.join(",", members) : "")
                + "&after=" + st.lastId;
        HttpResponse<String> r = api("GET", path, null);

        if (r.statusCode() == 401) { // la sesión caducó o el servidor se reinició
            token = null;
            return;
        }
        if (r.statusCode() != 200) {
            lastError = room.api + ": " + errorText(r);
            return;
        }

        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        muted = o.has("muted") && o.get("muted").getAsBoolean();

        if (o.has("reset") && o.get("reset").getAsBoolean()) { // el servidor se reinició
            st.messages.clear();
            st.lastId = 0;
        }

        boolean silent = !st.loaded;
        String self = Minecraft.getInstance().getUser().getName();

        JsonArray array = o.getAsJsonArray("messages");
        for (JsonElement el : array) {
            JsonObject m = el.getAsJsonObject();
            long id = m.get("id").getAsLong();
            if (id <= st.lastId) continue;

            String uid = optString(m, "uid");
            String avatar = optString(m, "avatar");
            ChatMessage msg = new ChatMessage(id, m.get("t").getAsLong(), uid, avatar,
                    m.get("name").getAsString(), m.get("text").getAsString());
            if (!avatar.isEmpty()) {
                AvatarManager.request(uid, avatar); // se va descargando para que ya este lista al dibujar
                if (m.get("name").getAsString().equalsIgnoreCase(self)) AvatarManager.noteSelf(avatar);
            }
            st.messages.add(msg);
            while (st.messages.size() > MAX_KEEP) st.messages.remove(0);
            st.lastId = id;

            if (!silent) onIncoming(room, st, msg, self);
        }
        st.loaded = true;
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private void resetRoom(RoomState st) {
        st.messages.clear();
        st.lastId = 0;
        st.loaded = false;
        st.unread = 0;
        st.key = "";
    }

    private static String roomKey(List<String> members) {
        List<String> sorted = new ArrayList<>();
        for (String n : members) sorted.add(n.toLowerCase(Locale.ROOT));
        Collections.sort(sorted);
        return String.join(",", sorted);
    }

    /** Llegó un mensaje nuevo (no del historial). */
    private void onIncoming(Room room, RoomState st, ChatMessage msg, String self) {
        boolean own = msg.name().equalsIgnoreCase(self);
        Minecraft mc = Minecraft.getInstance();

        // ¿Es un mensaje del protocolo de shards? Se oculta tanto propio como ajeno.
        ShardCoordinator coord = shardCoordinator;
        if (coord != null && room == Room.PARTY) {
            if (own) {
                // Mensaje propio del protocolo: lo oculto sin más
                if (msg.text().startsWith("__SHARD_")) return;
            } else if (coord.onSharedMessage(msg.name(), msg.text())) {
                return; // mensaje de protocolo ajeno: lo procesa el coordinador
            }
        }

        if (!own) st.unread++;

        if (config.chatToVanilla) {
            mc.execute(() -> {
                if (mc.player != null) mc.player.sendSystemMessage(format(room, msg));
            });
        }

        boolean screenOpen = mc.screen instanceof ChatScreen || mc.screen instanceof ModChatScreen;
        if (!own && !screenOpen && config.chatToastEnabled && (room == Room.GLOBAL || config.chatToastParty)) {
            // Obtener la clase del jugador del tab de la dungeon
            String className = null;
            try {
                java.util.List<String> tabLines = TabInfo.lines(mc);
                for (String line : tabLines) {
                    java.util.Map.Entry<String, String> entry = com.tuusuario.blacklist.dungeonrun.PartyParser.memberNameAndClass(line);
                    if (entry != null && entry.getKey().equalsIgnoreCase(msg.name())) {
                        className = entry.getValue();
                        break;
                    }
                }
            } catch (Exception ignored) {
                // Si no se puede leer el tab, se usa null
            }
            ChatToastHud.push(room == Room.PARTY ? "Party" : "Global", msg.uid(), msg.avatar(), msg.name(),
                    Emojis.replace(msg.text()), className);
        } else if (!own && config.chatAlerts && !config.chatToastEnabled && !screenOpen) {
            String shown = Emojis.replace(msg.text()); // los emojis cuentan como 1 carácter
            String text = shown.length() > 70 ? shown.substring(0, 70) + "..." : shown;
            BlacklistHud.addChat("[" + room.tag + "] " + msg.name() + ": " + text, room == Room.PARTY);
        }
    }

    public static Component format(Room room, ChatMessage m) {
        ChatFormatting tagColor = room == Room.PARTY ? ChatFormatting.BLUE : ChatFormatting.AQUA;
        return Component.literal("[" + room.tag + "] ").withStyle(tagColor)
                .append(Component.literal(m.name()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(": " + Emojis.replace(m.text())).withStyle(ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------ enviar

    public void send(Room room, String rawText) {
        String text = rawText == null ? "" : rawText.strip();
        if (text.isEmpty()) return;

        if (!config.chatEnabled) {
            local("El chat del mod está desactivado (/blacklistmod chat on).", ChatFormatting.RED);
            return;
        }
        if (!onHypixel.getAsBoolean()) {
            local("El chat del mod solo funciona en Hypixel.", ChatFormatting.RED);
            return;
        }
        if (text.length() > MAX_LEN) {
            local("El mensaje es muy largo (máximo " + MAX_LEN + " caracteres).", ChatFormatting.RED);
            return;
        }
        if (room == Room.PARTY && partyMembers == null) {
            local("No estás en una party (o aún no se detectó). Prueba /blacklistmod chat party.",
                    ChatFormatting.RED);
            return;
        }

        List<String> members = partyMembers;
        scheduler.execute(() -> doSend(room, text, members, true));
    }

    private void doSend(Room room, String text, List<String> members, boolean retry) {
        try {
            if (!ensureSession(true)) {
                local("No se pudo iniciar sesión en el chat del mod (" + lastError + ").", ChatFormatting.RED);
                return;
            }

            JsonObject body = new JsonObject();
            body.addProperty("room", room.api);
            body.addProperty("text", text);
            if (room == Room.PARTY) {
                JsonArray array = new JsonArray();
                for (String n : members) array.add(n);
                body.add("members", array);
            }

            HttpResponse<String> r = api("POST", "/chat/send", body.toString());

            if (r.statusCode() == 401 && retry) { // sesión caducada: se renueva y se reintenta una vez
                token = null;
                doSend(room, text, members, false);
                return;
            }
            if (r.statusCode() == 201) {
                pollNow(); // para ver tu mensaje enseguida
                return;
            }
            local("[" + room.tag + "] " + errorText(r), ChatFormatting.RED);
        } catch (Exception e) {
            local("No se pudo conectar con el servidor del chat.", ChatFormatting.RED);
        }
    }


    // ------------------------------------------------------------ fotos de perfil

    /** Tu UUID sin guiones y en minusculas (el mismo "uid" que manda el servidor). */
    public static String selfUuid() {
        return Minecraft.getInstance().getUser().getProfileId().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** Descarga la foto (PNG) de un usuario. null si no tiene o no se pudo. Llamar fuera del hilo principal. */
    public byte[] downloadAvatar(String uuid) {
        try {
            if (!ensureSession(false)) return null;
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/avatar/" + uuid))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "BlacklistMod/1.0")
                    .GET();
            String t = token;
            if (t != null) b.header("Authorization", "Bearer " + t);
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() == 401) {
                token = null;
                return null;
            }
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sube tu foto (PNG). Devuelve null si salio bien, o el texto del error. Llamar fuera del hilo principal. */
    public String uploadAvatar(byte[] png) {
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (!ensureSession(true)) return "no se pudo iniciar sesión (" + lastError + ")";

                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/avatar"))
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "BlacklistMod/1.0")
                        .header("Content-Type", "image/png")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(png));
                String t = token;
                if (t != null) b.header("Authorization", "Bearer " + t);

                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 401 && attempt == 0) { // sesión caducada: se renueva y se reintenta
                    token = null;
                    continue;
                }
                if (r.statusCode() == 200) {
                    JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                    AvatarManager.noteSelf(o.has("hash") ? o.get("hash").getAsString() : "");
                    return null;
                }
                return errorText(r);
            }
            return "sesión caducada";
        } catch (Exception e) {
            return "sin conexión con el servidor";
        }
    }

    // ------------------------------------------------------------ sesión

    private boolean ensureSession(boolean force) {
        long now = System.currentTimeMillis();
        if (token != null && now < tokenExpiry - 5 * 60_000) return true;

        synchronized (loginLock) {
            now = System.currentTimeMillis();
            if (token != null && now < tokenExpiry - 5 * 60_000) return true;
            if (!force && now < loginRetryAt) return token != null && now < tokenExpiry;
            return login();
        }
    }

    private boolean login() {
        try {
            Minecraft mc = Minecraft.getInstance();
            var user = mc.getUser();

            // 1) nonce
            HttpResponse<String> nr = rawGet("/auth/nonce");
            if (nr.statusCode() != 200) {
                fail("nonce " + nr.statusCode(), 30_000);
                return false;
            }
            String nonce = JsonParser.parseString(nr.body()).getAsJsonObject().get("nonce").getAsString();

            // 2) demostrar a Mojang que somos este jugador
            try {
                BlacklistClient.get().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), nonce);
            } catch (Exception e) {
                fail("sesión de Minecraft no válida", 120_000);
                return false;
            }

            // 3) el servidor lo comprueba con Mojang y nos da un token
            JsonObject body = new JsonObject();
            body.addProperty("username", user.getName());
            body.addProperty("nonce", nonce);

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/session"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "BlacklistMod/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (r.statusCode() != 200) {
                fail("login: " + errorText(r), 60_000);
                return false;
            }

            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            token = o.get("token").getAsString();
            tokenExpiry = System.currentTimeMillis() + o.get("expiresIn").getAsLong() * 1000;
            loginRetryAt = 0;
            lastError = "";
            return true;
        } catch (Exception e) {
            fail("sin conexión con el servidor", 30_000);
            return false;
        }
    }

    private void fail(String reason, long retryMs) {
        lastError = reason;
        loginRetryAt = System.currentTimeMillis() + retryMs;
    }

    private HttpResponse<String> rawGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60)) // Render puede estar despertando
                .header("User-Agent", "BlacklistMod/1.0")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> api(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "BlacklistMod/1.0");
        String t = token;
        if (t != null) b.header("Authorization", "Bearer " + t);
        if (json != null) {
            b.header("Content-Type", "application/json");
            b.POST(HttpRequest.BodyPublishers.ofString(json));
        } else {
            b.GET();
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String errorText(HttpResponse<String> r) {
        try {
            return JsonParser.parseString(r.body()).getAsJsonObject().get("error").getAsString();
        } catch (Exception e) {
            return "código " + r.statusCode();
        }
    }

    private void local(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("[Chat] " + text).withStyle(color));
            }
        });
    }

    // ------------------------------------------------------------ party

    /**
     * Se llama con cada mensaje del juego. Devuelve false si hay que ocultarlo
     * (la salida de /pl cuando la pidió el mod).
     */
    public boolean onMessage(String raw) {
        if (!config.chatEnabled || !onHypixel.getAsBoolean()) return true;

        String text = raw.replaceAll("\u00A7.", "").strip();
        long now = System.currentTimeMillis();
        boolean waiting = now < hidePlUntil;

        if (PL_SEPARATOR.matcher(text).matches()) {
            if (headerSeen != 0) commitPending(); // el separador de cierre de /pl
            return !waiting;
        }
        if (text.isEmpty()) return !waiting;

        if (PL_HEADER.matcher(text).matches()) {
            synchronized (pending) {
                pending.clear();
            }
            headerSeen = now;
            return !waiting;
        }

        java.util.regex.Matcher line = PL_LINE.matcher(text);
        if (line.matches() && headerSeen != 0) {
            synchronized (pending) {
                pending.addAll(names(line.group(1)));
            }
            return !waiting;
        }

        if (NOT_IN_PARTY.matcher(text).matches()) {
            clearParty();
            hidePlUntil = 0;
            return !waiting;
        }

        if (PARTY_GONE.matcher(text).matches()) {
            clearParty();
        } else if (PARTY_CHANGE.matcher(text).matches()) {
            refreshPartySoon(1200);
        }
        return true;
    }

    /** Nombres de una línea de /pl: "[MVP+] Steve ● [VIP] Alex ●" -> Steve, Alex. */
    private static List<String> names(String s) {
        String cleaned = s.replaceAll("\\[[^\\]]+\\]", " ").replace("\u25CF", " ").replace("\u2022", " ");
        List<String> out = new ArrayList<>();
        for (String token : cleaned.trim().split("\\s+")) {
            if (token.matches("\\w{1,16}")) out.add(token);
        }
        return out;
    }

    /** Da por terminada la lectura de /pl: guarda los miembros (o ninguno si quedaste solo). */
    private void commitPending() {
        List<String> members = new ArrayList<>();
        synchronized (pending) {
            for (String n : pending) {
                boolean dup = false;
                for (String m : members) if (m.equalsIgnoreCase(n)) dup = true;
                if (!dup) members.add(n);
            }
            pending.clear();
        }
        headerSeen = 0;
        hidePlUntil = 0;

        String self = Minecraft.getInstance().getUser().getName();
        boolean hasSelf = false;
        for (String m : members) if (m.equalsIgnoreCase(self)) hasSelf = true;
        if (!hasSelf && !members.isEmpty()) members.add(self);

        partyMembers = members.size() >= 2 ? List.copyOf(members) : null;
    }

    private void clearParty() {
        partyMembers = null;
        synchronized (pending) {
            pending.clear();
        }
        headerSeen = 0;
    }

    /** Pide /pl dentro de un momento (se junta con otros cambios seguidos). */
    private void refreshPartySoon(long delayMs) {
        int gen = partyGen.incrementAndGet();
        scheduler.schedule(() -> {
            if (gen == partyGen.get()) sendPl();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Vuelve a comprobar la party (también lo usa /blacklistmod chat party). */
    public void refreshParty() {
        refreshPartySoon(0);
    }

    private void sendPl() {
        if (!config.chatEnabled || !onHypixel.getAsBoolean()) return;

        long now = System.currentTimeMillis();
        if (now - lastPlSent < 3000) { // no más de un /pl cada 3 s
            refreshPartySoon(3000 - (now - lastPlSent) + 100);
            return;
        }
        lastPlSent = now;
        hidePlUntil = now + 3000; // durante este rato se oculta lo que responde Hypixel

        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.connection.sendCommand("pl");
        });
    }
}
