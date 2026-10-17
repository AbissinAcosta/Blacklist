package com.tuusuario.blacklist; // <- ajusta al paquete de tu proyecto

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;

/**
 * Auto-update del mod.
 *
 *  1. checkAsync():    pregunta a tu servidor (GET /update) cuál es la última versión.
 *  2. downloadAsync(): baja el jar nuevo a mods/ como archivo temporal (.update) y lo verifica.
 *  3. Al CERRAR el juego se lanza un script que borra el jar viejo y pone el nuevo
 *     (en Windows el jar está bloqueado mientras Minecraft corre, por eso no se puede antes).
 *
 * La pantalla solo necesita state(), latest(), percent(), error() y los dos métodos async.
 * Nada de esto toca el hilo del juego salvo tickNotice().
 */
public final class Updater {

    public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

    // Mismo id que en fabric.mod.json
    private static final String MOD_ID = "shitterlist";

    // Solo se descargan jars de tu servidor (o de GitHub); la URL la manda el servidor, así que se valida igual
    private static final String[] ALLOWED_PREFIXES = {
            BlacklistClient.BASE_URL + "/",
            "https://github.com/", "https://objects.githubusercontent.com/"
    };

    // Cliente propio: el de BlacklistClient no sigue redirecciones y GitHub redirige las descargas
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile State state = State.IDLE;
    private static volatile String latest = "";
    private static volatile String notes = "";
    private static volatile String error = "";
    private static volatile int percent = 0;

    private static String url = "";
    private static String sha256 = "";
    private static String fileName = "";

    private static boolean hookInstalled;
    private static boolean noticePending;

    // Re-comprobacion automatica mientras el juego esta abierto
    private static final long RECHECK_MS = 30 * 60_000;       // sin errores: cada 30 min
    private static final long RECHECK_ERROR_MS = 5 * 60_000;  // si la comprobacion fallo: cada 5 min
    private static volatile long lastCheckAt;
    private static volatile boolean errorFromCheck;           // el ultimo error fue al comprobar (no al descargar)
    private static String announced = "";                     // version ya anunciada en el chat

    private Updater() {
    }

    // ------------------------------------------------------------------ estado

    public static State state() {
        return state;
    }

    /** Última versión publicada ("" si aún no se sabe). */
    public static String latest() {
        return latest;
    }

    public static String notes() {
        return notes;
    }

    public static String error() {
        return error;
    }

    /** Progreso de la descarga, 0-100. */
    public static int percent() {
        return percent;
    }

    // ------------------------------------------------------------- comprobar

    /** Consulta GET baseUrl/update. Seguro de llamar varias veces: ignora si ya está ocupado. */
    public static synchronized void checkAsync(String baseUrl) {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.READY) return;
        state = State.CHECKING;
        error = "";
        errorFromCheck = false;
        lastCheckAt = System.currentTimeMillis();

        Thread t = new Thread(() -> check(baseUrl), "blacklist-updater");
        t.setDaemon(true);
        t.start();
    }

    private static void check(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/update"))
                    .timeout(Duration.ofSeconds(60)) // Render puede estar despertando
                    .header("User-Agent", "BlacklistMod/" + ModInfo.VERSION)
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                failCheck("el servidor respondió " + response.statusCode());
                return;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("version") || !root.has("url")) {
                failCheck("respuesta incompleta del servidor");
                return;
            }

            latest = root.get("version").getAsString();
            url = root.get("url").getAsString();
            fileName = root.has("file") ? root.get("file").getAsString() : "";
            sha256 = root.has("sha256") && !root.get("sha256").isJsonNull()
                    ? root.get("sha256").getAsString().toLowerCase(Locale.ROOT) : "";
            notes = root.has("notes") && !root.get("notes").isJsonNull() ? root.get("notes").getAsString() : "";

            if (compare(latest, ModInfo.VERSION) > 0) {
                state = State.AVAILABLE;
                if (!latest.equals(announced)) { // solo se avisa una vez por version, aunque se vuelva a comprobar
                    announced = latest;
                    noticePending = true;
                }
            } else {
                state = State.UP_TO_DATE;
            }
        } catch (Exception e) {
            failCheck("no se pudo conectar con el servidor");
        }
    }

    /** Compara versiones tipo "1.10" vs "1.4" número a número. >0 si a es más nueva. */
    static int compare(String a, String b) {
        String[] pa = a.replaceFirst("^[vV]", "").split("\\.");
        String[] pb = b.replaceFirst("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? num(pa[i]) : 0;
            int y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------- descargar

    public static synchronized void downloadAsync() {
        if (state != State.AVAILABLE && state != State.ERROR) return;
        if (state == State.ERROR && (errorFromCheck || url.isEmpty())) {
            checkAsync(BlacklistClient.BASE_URL); // el fallo fue al buscar la version: no hay nada que descargar aun
            return;
        }
        if (url.isEmpty()) return;
        state = State.DOWNLOADING;
        percent = 0;
        error = "";

        Thread t = new Thread(Updater::download, "blacklist-updater");
        t.setDaemon(true);
        t.start();
    }

    private static void download() {
        Path temp = null;
        try {
            if (!allowed(url)) {
                fail("la dirección de descarga no es de confianza");
                return;
            }
            Path current = currentJar();
            if (current == null) {
                fail("no se puede actualizar desde el entorno de desarrollo");
                return;
            }

            String name = safeName(fileName);
            temp = current.resolveSibling(name + ".update");

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "BlacklistMod/" + ModInfo.VERSION)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                fail("la descarga respondió " + response.statusCode());
                return;
            }

            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long read = 0;
            try (InputStream in = response.body();
                 var out = Files.newOutputStream(temp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    read += n;
                    if (total > 0) percent = (int) Math.min(100, read * 100 / total);
                }
            }

            // Un jar es un zip: empieza por "PK"
            byte[] head = new byte[2];
            try (InputStream in = Files.newInputStream(temp)) {
                if (in.read(head) < 2 || head[0] != 'P' || head[1] != 'K') {
                    Files.deleteIfExists(temp);
                    fail("el archivo descargado no es un jar");
                    return;
                }
            }

            if (!sha256.isEmpty()) {
                String got = hex(digest.digest());
                if (!got.equals(sha256)) {
                    Files.deleteIfExists(temp);
                    fail("la descarga está corrupta (sha256 no coincide)");
                    return;
                }
            }

            percent = 100;
            installOnExit(current, temp, current.resolveSibling(name));
            state = State.READY;
        } catch (Exception e) {
            try {
                if (temp != null) Files.deleteIfExists(temp);
            } catch (Exception ignored) {
            }
            fail("falló la descarga");
        }
    }

    private static boolean allowed(String u) {
        for (String p : ALLOWED_PREFIXES) {
            if (u.startsWith(p)) return true;
        }
        return false;
    }

    /** Nombre de archivo sin rutas ni caracteres raros; si no viene, se arma con la versión. */
    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9._+-]", "");
        if (!n.endsWith(".jar") || n.length() < 5) n = "blacklist-" + latest + ".jar";
        return n;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void fail(String message) {
        error = message;
        state = State.ERROR;
    }

    private static void failCheck(String message) {
        errorFromCheck = true;
        fail(message);
    }

    // -------------------------------------------------------------- instalar

    /** Jar de este mod en la carpeta mods, o null si se ejecuta desde carpetas (entorno de desarrollo). */
    private static Path currentJar() {
        try {
            return FabricLoader.getInstance().getModContainer(MOD_ID)
                    .flatMap(c -> c.getOrigin().getPaths().stream().findFirst())
                    .filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Registra (una sola vez) lo que debe pasar cuando se cierre Minecraft. */
    private static synchronized void installOnExit(Path oldJar, Path temp, Path target) {
        if (hookInstalled) return;
        hookInstalled = true;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (!Files.exists(temp)) return;
                boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

                if (windows) {
                    Path bat = Files.createTempFile("blacklist-update", ".bat");
                    Files.writeString(bat, String.join("\r\n",
                            "@echo off",
                            "set /a n=0",
                            ":retry",
                            "ping -n 3 127.0.0.1 >nul",
                            "del /f /q \"%~1\" >nul 2>&1",
                            "if not exist \"%~1\" goto done",
                            "set /a n+=1",
                            "if %n% lss 15 goto retry",
                            "exit /b 1",
                            ":done",
                            "move /y \"%~2\" \"%~3\" >nul",
                            "del \"%~f0\"",
                            ""));
                    new ProcessBuilder("cmd", "/c", bat.toString(),
                            oldJar.toString(), temp.toString(), target.toString())
                            .inheritIO().start();
                } else {
                    new ProcessBuilder("sh", "-c",
                            "sleep 3; rm -f \"$1\"; mv -f \"$2\" \"$3\"", "sh",
                            oldJar.toString(), temp.toString(), target.toString())
                            .start();
                }
            } catch (Exception ignored) {
                // si falla, el jar nuevo queda como .update y el viejo sigue funcionando
            }
        }, "blacklist-updater-exit"));
    }

    // ----------------------------------------------------------------- aviso

    /**
     * Llamar desde un tick del cliente. Avisa UNA vez en el chat cuando hay versión nueva
     * (cuando se detecta aún puede no haber mundo cargado, por eso se espera a mc.player).
     */
    public static void tickNotice(Minecraft mc) {
        recheckIfDue();
        if (!noticePending || mc.player == null) return;
        noticePending = false;
        mc.player.sendSystemMessage(Component.literal(
                "[Blacklist] Hay una versión nueva (v" + latest + "). Ábrela en /blacklistmod > Inicio > Actualizar.")
                .withStyle(ChatFormatting.AQUA));
    }

    /** Vuelve a buscar versión nueva cada cierto rato (no solo al arrancar el juego). */
    private static void recheckIfDue() {
        State s = state;
        boolean checkError = s == State.ERROR && errorFromCheck;
        if (s != State.UP_TO_DATE && s != State.IDLE && !checkError) return; // ocupado, o ya hay una versión esperando
        long every = checkError ? RECHECK_ERROR_MS : RECHECK_MS;
        if (System.currentTimeMillis() - lastCheckAt < every) return;
        checkAsync(BlacklistClient.BASE_URL);
    }

    /** Texto corto para el botón de la pantalla (cabe en ~110 px). */
    public static String shortLabel() {
        return switch (state) {
            case IDLE, CHECKING -> "Buscando...";
            case UP_TO_DATE -> "Estás al día";
            case AVAILABLE -> "Actualizar a v" + latest;
            case DOWNLOADING -> "Descargando " + percent + "%";
            case READY -> "Se instala al salir";
            case ERROR -> "Reintentar";
        };
    }

    /** Texto largo según el estado (para chat o mensajes). */
    public static String buttonLabel() {
        return switch (state) {
            case IDLE, CHECKING -> "Buscando actualización...";
            case UP_TO_DATE -> "Estás al día (v" + ModInfo.VERSION + ")";
            case AVAILABLE -> "Actualizar a v" + latest;
            case DOWNLOADING -> "Descargando... " + percent + "%";
            case READY -> "Listo: se instala al cerrar el juego";
            case ERROR -> "Error: " + error + " (reintentar)";
        };
    }
}
