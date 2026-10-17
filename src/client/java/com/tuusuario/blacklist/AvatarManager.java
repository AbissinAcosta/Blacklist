package com.tuusuario.blacklist;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fotos de perfil de los usuarios del chat del mod.
 *
 *  - request(uuid, hash): baja la foto del servidor (una sola vez por version) y la registra como textura.
 *  - draw(...): la dibuja si ya esta lista (devuelve false si todavia no: se muestra la inicial).
 *  - pickAndUpload(): abre el selector de archivos del sistema, valida el PNG y lo sube.
 *
 * El servidor ya la recorta a cuadrado y la reduce a 128x128.
 */
public final class AvatarManager {

    private record Entry(String hash, Identifier id, int size) {
    }

    private static final String NAMESPACE = "shitterlist";
    private static final long MAX_BYTES = 256 * 1024; // el mismo limite que el servidor

    private static final int GRAY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFAA00;
    private static final int RED = 0xFFFF5555;

    private static final Map<String, Entry> READY = new ConcurrentHashMap<>();      // uuid -> foto lista
    private static final Map<String, Boolean> LOADING = new ConcurrentHashMap<>();  // "uuid:hash" en descarga
    private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<>();    // "uuid:hash" -> cuando reintentar

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "avatar-loader");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile String selfHash = "";
    private static volatile String status = "";
    private static volatile int statusColor = GRAY;

    private AvatarManager() {
    }

    // ------------------------------------------------------------ estado (para la pestana Chat)

    public static boolean busy() {
        return BUSY.get();
    }

    public static String status() {
        return status;
    }

    public static int statusColor() {
        return statusColor;
    }

    private static void setStatus(String text, int color) {
        status = text;
        statusColor = color;
    }

    /** Tu propio hash (lo dice el servidor al subir o en tus mensajes): sirve para la vista previa. */
    public static void noteSelf(String hash) {
        if (hash == null || hash.isEmpty()) return;
        selfHash = hash;
        request(ChatClient.selfUuid(), hash);
    }

    // ------------------------------------------------------------ descarga y textura

    /** Pide la foto si no esta ya (o si cambio). No bloquea. */
    public static void request(String uuid, String hash) {
        if (uuid == null || uuid.isEmpty() || hash == null || hash.isEmpty()) return;

        Entry current = READY.get(uuid);
        if (current != null && current.hash().equals(hash)) return;

        String key = uuid + ":" + hash;
        Long retry = RETRY_AT.get(key);
        if (retry != null && System.currentTimeMillis() < retry) return;
        if (LOADING.putIfAbsent(key, Boolean.TRUE) != null) return;

        POOL.execute(() -> {
            byte[] data = null;
            try {
                data = BlacklistClient.get().chat().downloadAvatar(uuid);
            } catch (Exception ignored) {
            }
            if (data == null) {
                RETRY_AT.put(key, System.currentTimeMillis() + 60_000);
                LOADING.remove(key);
                return;
            }
            byte[] bytes = data;
            // Las texturas se crean en el hilo principal
            Minecraft.getInstance().execute(() -> {
                try {
                    register(uuid, hash, bytes);
                } catch (Exception e) {
                    RETRY_AT.put(key, System.currentTimeMillis() + 60_000);
                } finally {
                    LOADING.remove(key);
                }
            });
        });
    }

    private static void register(String uuid, String hash, byte[] data) throws Exception {
        NativeImage image = NativeImage.read(new ByteArrayInputStream(data));
        int size = image.getWidth();
        Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, "avatar/" + uuid + "_" + hash);
        // (la textura anterior de ese usuario no se libera: son unos KB y solo pasa al cambiar de foto)
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "avatar " + uuid, image));
        READY.put(uuid, new Entry(hash, id, size));
    }

    // ------------------------------------------------------------ dibujo

    /** Dibuja la foto en (x, y) con ese lado. false = todavia no esta lista (o el usuario no tiene). */
    public static boolean draw(GuiGraphicsExtractor g, String uuid, String hash, int x, int y, int size, float alpha) {
        if (uuid == null || uuid.isEmpty() || hash == null || hash.isEmpty()) return false;

        Entry e = READY.get(uuid);
        if (e == null || !e.hash().equals(hash)) {
            request(uuid, hash);
            if (e == null) return false; // mientras baja la nueva se sigue viendo la anterior
        }

        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        g.blit(RenderPipelines.GUI_TEXTURED, e.id(), x, y, 0f, 0f, size, size,
                e.size(), e.size(), e.size(), e.size(), (a << 24) | 0xFFFFFF);
        return true;
    }

    /** Tu propia foto (vista previa de la pestana Chat). */
    public static void drawSelf(GuiGraphicsExtractor g, int x, int y, int size) {
        draw(g, ChatClient.selfUuid(), selfHash, x, y, size, 1f);
    }

    // ------------------------------------------------------------ elegir y subir

    /** Abre el selector de archivos del sistema (en otro hilo), valida el PNG y lo sube al servidor. */
    public static void pickAndUpload() {
        if (!BUSY.compareAndSet(false, true)) return;
        setStatus("Elige un PNG en la ventana que se abrió...", GRAY);

        POOL.execute(() -> {
            try {
                String picked = pickPng();
                if (picked == null || picked.isBlank()) {
                    setStatus("", GRAY);
                    return;
                }

                Path file = Path.of(picked);
                long size = Files.size(file);
                if (size > MAX_BYTES) {
                    setStatus("La imagen pesa " + (size / 1024) + " KB (máximo 256 KB).", RED);
                    return;
                }
                byte[] data = Files.readAllBytes(file);
                if (!isPng(data)) {
                    setStatus("El archivo no es un PNG.", RED);
                    return;
                }

                setStatus("Subiendo...", YELLOW);
                String error = BlacklistClient.get().chat().uploadAvatar(data);
                if (error == null) {
                    setStatus("¡Foto actualizada! Tus mensajes nuevos la mostrarán.", GREEN);
                } else {
                    setStatus("No se pudo subir: " + error, RED);
                }
            } catch (Exception e) {
                setStatus("No se pudo leer el archivo.", RED);
            } finally {
                BUSY.set(false);
            }
        });
    }

    private static String pickPng() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.png")).flip();
            return TinyFileDialogs.tinyfd_openFileDialog(
                    "Elige tu foto de perfil (PNG)", "", filters, "Imágenes PNG", false);
        }
    }

    private static boolean isPng(byte[] d) {
        return d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
                && d[4] == 0x0D && d[5] == 0x0A && d[6] == 0x1A && d[7] == 0x0A;
    }
}
