package dev.qianhua.containerscan;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

record ScanSettings(Set<Identifier> types) {
    static final Logger LOGGER = LoggerFactory.getLogger("litematica_container_scan");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Identifier> DEFAULT_TYPES = List.of(
            "chest", "trapped_chest", "barrel", "shulker_box", "hopper", "dispenser",
            "dropper", "furnace", "blast_furnace", "smoker", "brewing_stand", "crafter")
            .stream().map(Identifier::withDefaultNamespace).toList();

    ScanSettings {
        types = Collections.unmodifiableSet(new LinkedHashSet<>(types));
    }

    static Set<Identifier> defaults() {
        return new LinkedHashSet<>(DEFAULT_TYPES);
    }

    static boolean defaultType(Identifier id) {
        return id != null && DEFAULT_TYPES.contains(id);
    }

    static ScanSettings load() {
        Path file = settingsPath();
        if (Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                Set<Identifier> ids = defaults();
                if (json.has("containerTypes")) {
                    ids.clear();
                    for (var value : json.getAsJsonArray("containerTypes")) {
                        ids.add(Identifier.parse(value.getAsString()));
                    }
                }
                return new ScanSettings(ids);
            } catch (IOException | RuntimeException error) {
                LOGGER.warn("Could not read container scan settings, using defaults", error);
            }
        }
        return new ScanSettings(defaults());
    }

    void save() throws IOException {
        Path file = settingsPath();
        JsonArray ids = new JsonArray();
        types.forEach(id -> ids.add(id.toString()));
        JsonObject json = new JsonObject();
        json.add("containerTypes", ids);

        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "container-scan-", ".json");
        try {
            Files.writeString(temporary, GSON.toJson(json) + "\n");
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path settingsPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("litematica_container_scan.json");
    }
}
