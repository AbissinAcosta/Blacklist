package com.tuusuario.blacklist.dungeonrun;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Un archivo JSON por run dentro de una carpeta. Gson ya viene incluido con Minecraft. */
public final class RunStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final Path dir;

    public RunStorage(Path dir) {
        this.dir = dir;
    }

    /** Guarda la run y devuelve su id (nombre del archivo sin extension). */
    public String save(RunData run) throws IOException {
        Files.createDirectories(dir);
        LocalDateTime now = LocalDateTime.now();
        run.savedAt = now.toString();

        String base = STAMP.format(now) + "-" + sanitize(run.floor);
        String id = base;
        int n = 2;
        while (Files.exists(dir.resolve(id + ".json"))) {
            id = base + "-" + n++;
        }
        run.id = id;
        Files.writeString(dir.resolve(id + ".json"), GSON.toJson(run));
        return id;
    }

    /** Todas las runs guardadas, de la mas nueva a la mas vieja. Los archivos danados se ignoran. */
    public List<RunData> list() {
        List<RunData> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    RunData run = GSON.fromJson(Files.readString(p), RunData.class);
                    if (run != null) {
                        String name = p.getFileName().toString();
                        run.id = name.substring(0, name.length() - ".json".length());
                        out.add(run);
                    }
                } catch (Exception ignored) {
                    // archivo corrupto: se saltea
                }
            });
        } catch (IOException ignored) {
            // carpeta ilegible: lista vacia
        }
        out.sort(Comparator.comparing((RunData r) -> r.savedAt == null ? "" : r.savedAt).reversed());
        return out;
    }

    public boolean delete(String id) throws IOException {
        if (id == null || !SAFE_ID.matcher(id).matches()) return false; // evita rutas raras
        return Files.deleteIfExists(dir.resolve(id + ".json"));
    }

    private static String sanitize(String s) {
        String clean = s == null ? "" : s.replaceAll("[^A-Za-z0-9_-]", "_");
        return clean.isEmpty() ? "run" : clean;
    }
}
