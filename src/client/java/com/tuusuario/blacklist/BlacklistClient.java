package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BlacklistClient implements ClientModInitializer {

    // Nombre de la carpeta dentro de assets/ (tiene que coincidir con el id de tu fabric.mod.json)
    private static final String MOD_ID = "shitterlist";

    static final String BASE_URL = "https://burritos-g7ip.onrender.com";
    private static final String API_URL = BASE_URL + "/blacklist";

    // "[MVP+] Steve joined the party."  o  "Steve joined the party."
    private static final Pattern JOIN =
            Pattern.compile("^(?:\\[[^\\]]+\\] )?(\\w{1,16}) joined the party\\.$");

    // "Party Finder > [MVP+] Steve joined the dungeon group! (Berserk Level 30)"
    //  o "Party Finder > Steve joined the dungeon group! (Berserk Level 30)"
    private static final Pattern FINDER =
            Pattern.compile("^Party Finder > (?:\\[[^\\]]+\\] )?(\\w{1,16}) joined the dungeon group!.*$");

    // Acceso global para la pantalla del mod (BlacklistScreen)
    private static BlacklistClient instance;

    public static BlacklistClient get() {
        return instance;
    }

    private BlacklistConfig config;
    private ChatClient chat; // chat entre usuarios del mod (/blc y /blp)
    private DungeonRunManager dungeonRun; // Dungeon run: tiempo por sala, score y runs guardadas
    private ShardCoordinator shardCoordinator; // coordina consultas de shards entre jugadores del mod

    // Tarea para ejecutar en el siguiente tick (abrir pantallas desde comandos)
    private volatile Runnable pendingOpen;

    // jugador (minúsculas) -> razón
    private volatile Map<String, String> blacklist = Collections.emptyMap();
    private volatile long lastRefresh = 0; // 0 = nunca se pudo descargar

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, r -> {
                Thread t = new Thread(r, "blacklist-mod");
                t.setDaemon(true);
                return t;
            });

    // ---------- Acceso para la pantalla ----------
    public BlacklistConfig getConfig() {
        return config;
    }

    public ChatClient chat() {
        return chat;
    }

    public DungeonRunManager dungeonRuns() {
        return dungeonRun;
    }

    public Map<String, String> getBlacklist() {
        return blacklist;
    }

    public long getLastRefresh() {
        return lastRefresh;
    }

    public void refreshAsync() {
        scheduler.execute(this::refreshBlacklist);
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        config = BlacklistConfig.load();
        dungeonRun = new DungeonRunManager(
                FabricLoader.getInstance().getConfigDir().resolve("shitterlist").resolve("dungeon-runs"));

        // Busca una versión nueva del mod (el botón está en Inicio)
        Updater.checkAsync(BASE_URL);

        scheduler.scheduleAtFixedRate(this::refreshBlacklist, 0, 5, TimeUnit.MINUTES);

        // Precios (BIN + Bazaar) para el profit de cofres
        scheduler.scheduleAtFixedRate(() -> {
            if (config.profitEnabled) PriceService.refresh(http);
        }, 0, 5, TimeUnit.MINUTES);

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                String text = message.getString();
                if (onHypixel(Minecraft.getInstance())) dungeonRun.onGameMessage(text);
                onGameMessage(text);
            }
        });

        // ---- Chat del mod ----
        chat = new ChatClient(http, scheduler, config, BASE_URL, () -> onHypixel(Minecraft.getInstance()));
        chat.start();

        // ---- Coordinación de shards ----
        shardCoordinator = new ShardCoordinator(http, BASE_URL, scheduler,
                () -> config.chatEnabled,
                text -> chat.sendShardProtocol(text));
        chat.setShardCoordinator(shardCoordinator);

        // Oculta la salida de /pl cuando la pidió el mod (false = el mensaje no se muestra)
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
                overlay || chat.onMessage(message.getString()));

        // Mientras el chat del mod está abierto, el chat de verdad no se dibuja
        HudElementRegistry.replaceElement(
                VanillaHudElements.CHAT,
                original -> (graphics, delta) -> {
                    Minecraft mc = Minecraft.getInstance();
                    boolean modChatShown = mc.screen instanceof ModChatScreen
                            || (onHypixel(mc) && ModChatHud.active(config, mc));
                    if (!modChatShown) {
                        original.extractRenderState(graphics, delta);
                    }
                });

        // Chat del mod siempre visible (si se activa en la pestaña Chat), aunque no tengas abierto el chat
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("blacklist", "mod_chat"),
                (graphics, delta) -> {
                    if (onHypixel(Minecraft.getInstance())) ModChatHud.render(graphics, config);
                });

        // HUD (se dibuja justo antes del chat)
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("blacklist", "status"),
                (graphics, delta) -> BlacklistHud.render(
                        graphics, delta, config, blacklist.size(), lastRefresh));

        // Aviso estilo Discord de los mensajes del chat del mod
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("blacklist", "chat_toast"),
                (graphics, delta) -> ChatToastHud.render(graphics, config));

        // Tracker de eventos (solo en Hypixel)
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("blacklist", "events"),
                (graphics, delta) -> {
                    if (onHypixel(Minecraft.getInstance())) EventsHud.render(graphics, config);
                });

        // Profit de cofres de dungeons (solo en Hypixel)
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("blacklist", "profit"),
                (graphics, delta) -> {
                    // Solo en el Dungeon Hub o dentro de una dungeon
                    if (onHypixel(Minecraft.getInstance()) && Location.inDungeons()) {
                        ProfitHud.render(graphics, config);
                    }
                });

        // Botón en el chat que abre la pantalla del mod (se mueve con /blacklistmod gui)
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            // Cofres de dungeons: un clic izquierdo en un cofre lo marca como abierto
            if (screen instanceof AbstractContainerScreen<?> container) {
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                    if (event.button() == 0 && onHypixel(client)) {
                        ChestTracker.onClick(client, container, event.x(), event.y());
                    }
                    return true; // nunca bloqueamos el clic
                });
                return;
            }

            if (!(screen instanceof ChatScreen)) return;

            WidgetSprites sprites = new WidgetSprites(
                    Identifier.fromNamespaceAndPath(MOD_ID, "img"),
                    Identifier.fromNamespaceAndPath(MOD_ID, "img2")
            );

            ImageButton button = new ImageButton(
                    config.buttonX(width), config.buttonY(height),
                    config.buttonSize, config.buttonSize,
                    sprites,
                    b -> client.setScreen(new BlacklistScreen(screen)),
                    Component.literal("Blacklist")
            );

            Screens.getWidgets(screen).add(button);

            // Pestañas Chat | Global | Party encima del chat normal
            if (config.chatEnabled) {
                for (Button tab : ModChatScreen.tabs(client, height, chat, null)) {
                    Screens.getWidgets(screen).add(tab);
                }
            }
        });

        // Si abrimos una pantalla justo al ejecutar un comando, el chat la cierra al terminar;
        // por eso se abre un tick después.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            chat.clientTick(client, onHypixel(client));
            Updater.tickNotice(client);
            redirectToModChat(client);
            tickChatCycle(client);
            CommandKeys.tick(client, config);
            ChestDebug.tick(client);
            if (onHypixel(client)) {
                Location.tick(client);
                dungeonRun.tick(client);
                ChestTracker.tick(client, config);
                TabInfo.tick(client, config);
            } else {
                Location.clear();
            }
            Runnable task = pendingOpen;
            if (task != null) {
                pendingOpen = null;
                task.run();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                registerChatCommands(dispatcher));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("blacklistmod")
                        .executes(this::showStatus)
                        .then(ClientCommands.literal("off")
                                .executes(ctx -> setMode(ctx, BlacklistConfig.Mode.OFF)))
                        .then(ClientCommands.literal("alert")
                                .executes(ctx -> setMode(ctx, BlacklistConfig.Mode.ALERT)))
                        .then(ClientCommands.literal("kick")
                                .executes(ctx -> setMode(ctx, BlacklistConfig.Mode.KICK)))
                        .then(ClientCommands.literal("refresh")
                                .executes(this::forceRefresh))
                        .then(ClientCommands.literal("gui")
                                .executes(this::openEditor)
                                .then(ClientCommands.literal("reset")
                                        .executes(this::resetGui)))
                        .then(ClientCommands.literal("chat")
                                .executes(this::chatStatus)
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> setChat(ctx, true)))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> setChat(ctx, false)))
                                .then(ClientCommands.literal("party")
                                        .executes(this::refreshParty)))
                        .then(ClientCommands.literal("shards")
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> setShards(ctx, true)))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> setShards(ctx, false))))
                        .then(ClientCommands.literal("events")
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> setEvents(ctx, true)))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> setEvents(ctx, false)))
                                .then(ClientCommands.literal("pos")
                                        .then(ClientCommands.argument("x", IntegerArgumentType.integer(0, 4000))
                                                .then(ClientCommands.argument("y", IntegerArgumentType.integer(0, 4000))
                                                        .executes(this::setEventsPos)))))
                        .then(ClientCommands.literal("profit")
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> setProfit(ctx, true)))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> setProfit(ctx, false)))
                                .then(ClientCommands.literal("reset")
                                        .executes(this::resetProfit))
                                .then(ClientCommands.literal("prices")
                                        .executes(this::refreshPrices))
                                .then(ClientCommands.literal("tab")
                                        .executes(this::dumpTab))
                                .then(ClientCommands.literal("zone")
                                        .executes(this::showZone)))
                        .then(ClientCommands.literal("debug")
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> setDebug(ctx, true)))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> setDebug(ctx, false))))
                        .then(ClientCommands.literal("run")
                                .then(ClientCommands.literal("start")
                                        .executes(ctx -> { dungeonRun.start(); return 1; }))
                                .then(ClientCommands.literal("stop")
                                        .executes(ctx -> { dungeonRun.stop(); return 1; }))
                                .then(ClientCommands.literal("save")
                                        .executes(ctx -> { dungeonRun.save(null); return 1; })
                                        .then(ClientCommands.argument("etiqueta", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    dungeonRun.save(StringArgumentType.getString(ctx, "etiqueta"));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("debug")
                                        .executes(ctx -> { dungeonRun.debug(); return 1; }))
                                .then(ClientCommands.literal("mimic")
                                        .executes(ctx -> { dungeonRun.markMimic(); return 1; }))
                                .then(ClientCommands.literal("prince")
                                        .executes(ctx -> { dungeonRun.markPrince(); return 1; })))
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("jugador", StringArgumentType.word())
                                        .then(ClientCommands.argument("razon", StringArgumentType.greedyString())
                                                .executes(this::addPlayer))))
                        .then(ClientCommands.literal("hud")
                                .then(ClientCommands.literal("on")
                                        .executes(ctx -> setHud(ctx, true)))
                                .then(ClientCommands.literal("off")
                                        .executes(ctx -> setHud(ctx, false)))
                                .then(ClientCommands.literal("clear")
                                        .executes(this::clearHud))
                                .then(ClientCommands.literal("pos")
                                        .then(ClientCommands.argument("x", IntegerArgumentType.integer(0, 4000))
                                                .then(ClientCommands.argument("y", IntegerArgumentType.integer(0, 4000))
                                                        .executes(this::setHudPos)))))));
    }

    // ---------- Chat del mod ----------

    /** Cambia la pestaña por defecto del chat (null = chat normal). Se guarda en la config. */
    public void setChatTab(ChatClient.Room room) {
        if (config.chatTab != room) {
            config.chatTab = room;
            config.save();
        }
    }

    // Último ChatScreen que ya se revisó (para no revisarlo cada tick)
    private Screen checkedChat;

    // Campo EditBox del ChatScreen (la barra donde se escribe), buscado por tipo para no depender del nombre
    private Field chatInputField;
    private boolean chatInputSearched;

    /** Texto que trae escrito un ChatScreen recién abierto, o null si no se puede leer. */
    private String vanillaChatText(ChatScreen screen) {
        try {
            if (!chatInputSearched) {
                chatInputSearched = true;
                for (Field f : ChatScreen.class.getDeclaredFields()) {
                    if (f.getType() == EditBox.class) {
                        f.setAccessible(true);
                        chatInputField = f;
                        break;
                    }
                }
            }
            if (chatInputField == null) return null;
            EditBox box = (EditBox) chatInputField.get(screen);
            return box == null ? null : box.getValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Si el chat quedó en Global o Party, al abrir el chat normal (T) se cambia por el del mod.
     * Se hace en el mismo tick de la pulsación, así que no se ve el chat normal ni un parpadeo.
     * Si abres el chat con "/" (para escribir un comando) se respeta y se queda el normal.
     */
    private void redirectToModChat(Minecraft mc) {
        if (!(mc.screen instanceof ChatScreen screen)) {
            checkedChat = null;
            return;
        }
        if (screen == checkedChat) return;
        checkedChat = screen;

        ChatClient.Room tab = config.chatTab;
        if (tab == null || !config.chatEnabled) return;

        String text = vanillaChatText(screen);
        if (text == null || text.startsWith("/")) return; // comando (o no se pudo leer): chat normal

        mc.setScreen(new ModChatScreen(tab));
    }

    private boolean cycleWasDown;

    /** Ctrl + Tab (configurable) pasa al siguiente chat: Chat normal -> Global -> Party -> Chat normal. Con Shift, al anterior. */
    private void tickChatCycle(Minecraft mc) {
        int key = config.chatCycleKey;
        if (key < 0) return;

        long window = mc.getWindow().handle();
        boolean down = GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
        boolean pressed = down && !cycleWasDown;
        cycleWasDown = down;
        if (!pressed || !config.chatEnabled) return;

        boolean ctrl = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        if (!ctrl) return;

        ChatClient.Room current;
        if (mc.screen instanceof ModChatScreen m) {
            current = m.room();
        } else if (mc.screen instanceof ChatScreen) {
            current = null;
        } else {
            return; // solo funciona con algún chat abierto
        }

        boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        // Orden: [chat normal, Global, Party]
        ChatClient.Room[] rooms = ChatClient.Room.values();
        int count = rooms.length + 1;
        int index = current == null ? 0 : current.ordinal() + 1;
        int next = ((index + (shift ? -1 : 1)) % count + count) % count;

        if (next == 0) {
            ModChatScreen.openVanilla(mc);
        } else {
            mc.setScreen(new ModChatScreen(rooms[next - 1]));
        }
    }

    // /blc <mensaje> = chat global, /blp <mensaje> = chat de la party. Sin mensaje abren la pantalla del chat.
    private void registerChatCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        for (ChatClient.Room room : ChatClient.Room.values()) {
            String name = room == ChatClient.Room.GLOBAL ? "blc" : "blp";
            dispatcher.register(ClientCommands.literal(name)
                    .executes(ctx -> {
                        // Se abre un tick después: si no, el chat la cierra al terminar el comando
                        pendingOpen = () -> Minecraft.getInstance().setScreen(new ModChatScreen(room));
                        return 1;
                    })
                    .then(ClientCommands.argument("mensaje", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                chat.send(room, StringArgumentType.getString(ctx, "mensaje"));
                                return 1;
                            })));
        }
    }

    private int chatStatus(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Component.literal("[Blacklist] " + chat.status()
                + "\nUsa /blc <mensaje>, /blp <mensaje>, /blacklistmod chat on | off | party"));
        return 1;
    }

    private int setChat(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
        config.chatEnabled = enabled;
        config.save();
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] Chat del mod " + (enabled ? "activado" : "desactivado")
                        + " (las pestañas se actualizan al volver a abrir el chat)"));
        return 1;
    }

    private int refreshParty(CommandContext<FabricClientCommandSource> ctx) {
        chat.refreshParty();
        ctx.getSource().sendFeedback(Component.literal("[Blacklist] Comprobando tu party..."));
        return 1;
    }

    // ---------- Comandos ----------
    private int showStatus(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] Modo actual: " + describe(config.mode)
                        + " | Jugadores en la lista: " + blacklist.size()
                        + "\nUsa /blacklistmod off | alert | kick | refresh | hud | gui | shards on/off | events on/off/pos | profit on/off/reset/prices/tab/zone | debug on/off | chat on/off/party | run start/stop/save/mimic/prince | add <jugador> <razón>"));
        return 1;
    }

    private int setMode(CommandContext<FabricClientCommandSource> ctx, BlacklistConfig.Mode mode) {
        config.mode = mode;
        config.save();
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] Modo cambiado a: " + describe(mode)));
        return 1;
    }

    private int forceRefresh(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] Actualizando la lista... (puede tardar hasta un minuto si el servidor estaba dormido)"));
        refreshAsync();
        return 1;
    }

    // Mensaje en el chat local (seguro de llamar desde cualquier hilo)
    private void chat(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(text).withStyle(color));
            }
        });
    }

    // ---------- Estado del último intento de agregar (lo lee la ventana "Agregar jugador") ----------
    public enum AddState { IDLE, SENDING, OK, ERROR }

    private volatile AddState addState = AddState.IDLE;
    private volatile String addMessage = "";

    public AddState getAddState() {
        return addState;
    }

    public String getAddMessage() {
        return addMessage;
    }

    public void resetAddState() {
        addState = AddState.IDLE;
        addMessage = "";
    }

    private void addOk(String message) {
        addMessage = message;
        addState = AddState.OK;
        chat("[Blacklist] " + message, ChatFormatting.GREEN);
    }

    private void addFail(String message) {
        addMessage = message;
        addState = AddState.ERROR;
        chat("[Blacklist] " + message, ChatFormatting.RED);
    }

    /**
     * Valida y empieza a agregar un jugador. Lo usan el comando y la ventana del mod.
     * Devuelve un mensaje de error si algo está mal, o null si el envío ya está en marcha
     * (el resultado se consulta con getAddState / getAddMessage).
     */
    public String startAdd(String rawPlayer, String rawReason) {
        String player = rawPlayer == null ? "" : rawPlayer.trim();
        String reason = rawReason == null ? "" : rawReason.trim();

        if (!player.matches("[A-Za-z0-9_]{1,16}")) {
            return "Nombre de jugador inválido (1-16 letras, números o _).";
        }
        if (reason.isEmpty() || reason.length() > 200) {
            return "La razón es obligatoria y debe tener máximo 200 caracteres.";
        }
        if (blacklist.containsKey(player.toLowerCase())) {
            return player + " ya está en la lista.";
        }
        if (addState == AddState.SENDING) {
            return "Ya hay un envío en curso, espera un momento.";
        }

        addMessage = "Verificando tu cuenta y enviando...";
        addState = AddState.SENDING;

        scheduler.execute(() -> {
            try {
                sendAdd(player, reason);
            } finally {
                // Por si algo falló sin avisar: que la ventana nunca se quede esperando
                if (addState == AddState.SENDING) {
                    addFail("No se pudo completar el envío.");
                }
            }
        });
        return null;
    }

    // /blacklistmod add <jugador> <razón>
    private int addPlayer(CommandContext<FabricClientCommandSource> ctx) {
        String error = startAdd(StringArgumentType.getString(ctx, "jugador"),
                StringArgumentType.getString(ctx, "razon"));

        if (error != null) {
            chat("[Blacklist] " + error, ChatFormatting.RED);
            return 0;
        }

        chat("[Blacklist] Verificando tu cuenta y enviando... (si el servidor estaba dormido puede tardar hasta un minuto)",
                ChatFormatting.GRAY);
        return 1;
    }

    // Único punto que depende de cómo 26.1 expone el servicio de sesión.
    // Si no compila, busca en la clase Minecraft el método que devuelve MinecraftSessionService.
    public MinecraftSessionService sessionService() {
        return Minecraft.getInstance().services().sessionService();
    }

    private void sendAdd(String player, String reason) {
        try {
            Minecraft mc = Minecraft.getInstance();
            var user = mc.getUser();

            // 1) Pedir un nonce al servidor
            HttpRequest nonceRequest = HttpRequest.newBuilder(URI.create(BASE_URL + "/auth/nonce"))
                    .timeout(Duration.ofSeconds(60)) // Render puede estar despertando
                    .header("User-Agent", "BlacklistMod/1.0")
                    .GET()
                    .build();
            HttpResponse<String> nonceResponse =
                    http.send(nonceRequest, HttpResponse.BodyHandlers.ofString());
            if (nonceResponse.statusCode() != 200) {
                addFail("No se pudo iniciar la verificación (código " + nonceResponse.statusCode() + ").");
                return;
            }
            String nonce = JsonParser.parseString(nonceResponse.body())
                    .getAsJsonObject().get("nonce").getAsString();

            // 2) Demostrar a Mojang que somos este jugador (el token nunca sale de tu PC hacia nuestro servidor)
            try {
                sessionService().joinServer(user.getProfileId(), user.getAccessToken(), nonce);
            } catch (Exception e) {
                addFail("No se pudo verificar tu sesión de Minecraft. ¿Cuenta no oficial o sin conexión a Mojang?");
                return;
            }

            // 3) Enviar la petición; el servidor le pregunta a Mojang si el nonce es nuestro
            JsonObject body = new JsonObject();
            body.addProperty("player", player);
            body.addProperty("reason", reason);
            body.addProperty("username", user.getName());
            body.addProperty("nonce", nonce);

            HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL + "/add"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "BlacklistMod/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                // Se refleja de inmediato en la lista local y en el HUD
                Map<String, String> copy = new HashMap<>(blacklist);
                copy.put(player.toLowerCase(), reason);
                blacklist = copy;

                addOk(player + " fue agregado a la lista.");
            } else {
                String error = "código " + response.statusCode();
                try {
                    error = JsonParser.parseString(response.body())
                            .getAsJsonObject().get("error").getAsString();
                } catch (Exception ignored) {
                    // respuesta sin JSON: se deja el código
                }
                addFail("No se pudo agregar: " + error);
            }
        } catch (Exception e) {
            addFail("No se pudo conectar con el servidor.");
        }
    }

    // /blacklistmod gui  -> editor para arrastrar el botón del chat y las alertas
    private int openEditor(CommandContext<FabricClientCommandSource> ctx) {
        pendingOpen = () -> Minecraft.getInstance().setScreen(new BlacklistEditScreen(null));
        return 1;
    }

    // /blacklistmod gui reset  -> vuelve todo a su posición original
    private int resetGui(CommandContext<FabricClientCommandSource> ctx) {
        config.resetPositions();
        config.save();
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] Posiciones restablecidas (se aplican al volver a abrir el chat)"));
        return 1;
    }

    // /blacklistmod events on|off
    private int setEvents(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
        config.eventsEnabled = enabled;
        config.save();
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] Tracker de eventos " + (enabled ? "activado" : "desactivado")));
        return 1;
    }

    // /blacklistmod events pos <x> <y>
    private int setEventsPos(CommandContext<FabricClientCommandSource> ctx) {
        config.eventsX = IntegerArgumentType.getInteger(ctx, "x");
        config.eventsY = IntegerArgumentType.getInteger(ctx, "y");
        config.save();
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] Tracker de eventos movido a " + config.eventsX + ", " + config.eventsY));
        return 1;
    }

    // /blacklistmod profit on|off
    private int setProfit(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
        config.profitEnabled = enabled;
        config.save();
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] Profit de cofres " + (enabled ? "activado" : "desactivado")));
        return 1;
    }

    // /blacklistmod profit reset  (pone a cero los totales de la sesión)
    private int resetProfit(CommandContext<FabricClientCommandSource> ctx) {
        ProfitHud.reset();
        ctx.getSource().sendFeedback(Component.literal("[Blacklist] Profit de la sesión reiniciado"));
        return 1;
    }

    // /blacklistmod profit zone  (muestra qué lee el mod del Tab y del marcador y qué zona cree que es)
    private int showZone(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft mc = Minecraft.getInstance();
        List<String> lines = Location.sidebar(mc);
        Zone zone = Location.current();
        String area = Location.areaName().isEmpty() ? "(no hay línea Area)" : Location.areaName();
        // El chat interpreta los códigos de color, así que se muestran con "&" para poder verlos
        String shown = String.join(" | ", lines).replace('\u00a7', '&');
        ctx.getSource().sendFeedback(Component.literal("[Blacklist] (loc " + Location.VERSION + ") Zona: "
                + (zone == null ? "no reconocida" : zone.label)
                + " | Dungeons: " + (Location.inDungeons() ? "sí" : "no")
                + " | Area: " + area
                + " | Marcador: " + shown));
        return 1;
    }

    // /blacklistmod profit tab  (guarda las líneas del Tab en config/blacklist-tab.txt)
    private int dumpTab(CommandContext<FabricClientCommandSource> ctx) {
        int n = TabInfo.dump(Minecraft.getInstance());
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] " + n + " líneas del Tab guardadas en config/blacklist-tab.txt"));
        return 1;
    }

    // /blacklistmod profit prices  (vuelve a descargar los precios y dice cuántos hay)
    private int refreshPrices(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Component.literal("[Blacklist] Descargando precios..."));
        scheduler.execute(() -> {
            boolean ok = PriceService.refresh(http);
            chat(ok ? "[Blacklist] Precios actualizados: " + PriceService.size() + " ítems"
                    : "[Blacklist] No se pudieron descargar los precios", ok ? ChatFormatting.GREEN : ChatFormatting.RED);
        });
        return 1;
    }

    // /blacklistmod debug on|off  (guarda el contenido de los cofres que abras)
    private int setDebug(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
        ChestDebug.setEnabled(enabled);
        ctx.getSource().sendFeedback(Component.literal(enabled
                ? "[Blacklist] Debug activado: abre un cofre y se guardará en config/blacklist-debug.txt"
                : "[Blacklist] Debug desactivado"));
        return 1;
    }

    // /blacklistmod shards on|off
    private int setShards(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
        config.shardAlerts = enabled;
        config.save();
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] Alertas de shards " + (enabled ? "activadas" : "desactivadas")));
        return 1;
    }

    private int setHud(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
        config.hudEnabled = enabled;
        config.save();
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] HUD " + (enabled ? "activado" : "desactivado")));
        return 1;
    }

    private int clearHud(CommandContext<FabricClientCommandSource> ctx) {
        BlacklistHud.clearAlerts();
        ChatToastHud.clear();
        ctx.getSource().sendFeedback(Component.literal("[Blacklist] Alertas del HUD borradas"));
        return 1;
    }

    private int setHudPos(CommandContext<FabricClientCommandSource> ctx) {
        config.hudX = IntegerArgumentType.getInteger(ctx, "x");
        config.hudY = IntegerArgumentType.getInteger(ctx, "y");
        config.save();
        ctx.getSource().sendFeedback(Component.literal(
                "[Blacklist] HUD movido a " + config.hudX + ", " + config.hudY));
        return 1;
    }

    private String describe(BlacklistConfig.Mode mode) {
        return switch (mode) {
            case OFF -> "desactivado";
            case ALERT -> "solo alertar";
            case KICK -> "alertar y expulsar automáticamente";
        };
    }

    // ---------- Lista ----------
    private void refreshBlacklist() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(60)) // Render puede tardar en despertar
                    .header("User-Agent", "BlacklistMod/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;

            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            Map<String, String> map = new HashMap<>();
            for (JsonElement el : obj.getAsJsonArray("entries")) {
                JsonObject e = el.getAsJsonObject();
                map.put(e.get("player").getAsString().toLowerCase(),
                        e.get("reason").getAsString());
            }
            blacklist = map;
            lastRefresh = System.currentTimeMillis();
        } catch (Exception ignored) {
            // si falla, se conserva la lista anterior
        }
    }

    // ---------- Detección ----------
    private boolean onHypixel(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        return server != null && server.ip.toLowerCase().contains("hypixel.net");
    }

    // Revisa Rekindle (Murkbat) y Reborn (Prince) del jugador que acaba de entrar
    private void checkShards(String name, Minecraft mc) {
        if (!config.shardAlerts) return;

        shardCoordinator.check(name, levels -> {
            if (levels == null) return; // la consulta falló: no se avisa nada
            dungeonRun.registerMurkbat(name, hasMurkbat(levels));

            int missing = ShardChecker.missing(levels);
            String info = ShardChecker.describe(levels); // null si no tiene ninguno

            // Qué se dice según lo que le falte
            String verdict = switch (missing) {
                case 0 -> null;                                  // tiene los dos: solo se muestran los niveles
                case 1 -> name + " es medio burro";              // le falta uno
                default -> name + " es un burro sin shards";     // no tiene ninguno
            };

            // Pantalla (HUD)
            if (verdict != null) BlacklistHud.addWarning(verdict);
            if (info != null) BlacklistHud.addInfo(name + ": " + info);

            // Chat
            mc.execute(() -> {
                if (mc.player == null) return;
                if (verdict != null) {
                    mc.player.sendSystemMessage(Component.literal(verdict).withStyle(ChatFormatting.RED));
                }
                if (info != null) {
                    mc.player.sendSystemMessage(Component.literal(
                            "[Shards] " + name + ": " + info).withStyle(ChatFormatting.GREEN));
                }
            });
        });
    }

    // Si en ShardChecker el murkbat se llama distinto, ajustar los nombres de abajo
    private static boolean hasMurkbat(Map<String, Integer> levels) {
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            String key = e.getKey().toLowerCase();
            if ((key.contains("murkbat") || key.contains("rekindle"))
                    && e.getValue() != null && e.getValue() > 0) {
                return true;
            }
        }
        return false;
    }

    private void onGameMessage(String text) {
        BlacklistConfig.Mode mode = config.mode;
        if (mode == BlacklistConfig.Mode.OFF) return;

        Minecraft mc = Minecraft.getInstance();
        if (!onHypixel(mc)) return;

        // Entra al party (mensaje normal) o al grupo de dungeon (Party Finder)
        String trimmed = text.trim();
        Matcher m = JOIN.matcher(trimmed);
        if (!m.matches()) {
            m = FINDER.matcher(trimmed);
            if (!m.matches()) return;
        }

        String name = m.group(1);

        // Shards: se revisa a todos los que entran, estén o no en la blacklist
        checkShards(name, mc);

        String reason = blacklist.get(name.toLowerCase());
        if (reason == null) return;

        boolean kick = mode == BlacklistConfig.Mode.KICK;

        BlacklistHud.addAlert(name, reason, kick);

        mc.execute(() -> {
            if (mc.player != null) {
                String msg = kick
                        ? "[Blacklist] " + name + " está en la lista (" + reason + "). Expulsando..."
                        : "[Blacklist] ¡Cuidado! " + name + " está en la lista (" + reason + ").";
                mc.player.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.RED));
            }
        });

        if (kick) {
            scheduler.schedule(() -> mc.execute(() -> {
                // Se vuelve a comprobar el modo por si lo cambiaste durante la espera
                if (mc.player != null && config.mode == BlacklistConfig.Mode.KICK) {
                    mc.player.connection.sendCommand("p kick " + name);
                }
            }), 750, TimeUnit.MILLISECONDS);
        }
    }
}