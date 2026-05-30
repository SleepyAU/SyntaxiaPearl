package dev.zenith.pearlplus.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.zenith.pearlplus.PearlPlusPlugin.LOG;

public final class ChamberLookup {
    private static final String RESOURCE_PATH = "/chambers.json";

    private static volatile Map<Long, Hit> index = Map.of();
    private static volatile boolean loaded = false;

    public record Hit(String lectern, int page, String button) { }

    private ChamberLookup() { }

    public static synchronized void load() {
        if (loaded) {
            return;
        }

        Map<Long, Hit> newIndex = new HashMap<>();
        List<String> failures = new ArrayList<>();

        try (InputStream in = ChamberLookup.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                LOG.warn("chambers.json resource missing at {}", RESOURCE_PATH);
                loaded = true;
                return;
            }

            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOG.warn("chambers.json root is not a JSON object");
                loaded = true;
                return;
            }

            for (Map.Entry<String, JsonElement> lecternEntry : root.getAsJsonObject().entrySet()) {
                String lecternKey = lecternEntry.getKey();
                JsonElement lecternValue = lecternEntry.getValue();
                if (!lecternValue.isJsonObject()) {
                    failures.add(lecternKey + " (not an object)");
                    continue;
                }

                var lecternObj = lecternValue.getAsJsonObject();
                if (!lecternObj.has("rows") || !lecternObj.get("rows").isJsonArray()) {
                    failures.add(lecternKey + " (missing rows array)");
                    continue;
                }

                int rowIndex = -1;
                for (JsonElement rowElement : lecternObj.getAsJsonArray("rows")) {
                    rowIndex++;
                    if (!rowElement.isJsonObject()) {
                        failures.add(lecternKey + " row[" + rowIndex + "] (not an object)");
                        continue;
                    }

                    var rowObj = rowElement.getAsJsonObject();
                    String buttonKey = readButtonKey(lecternKey, rowIndex, rowObj, failures);
                    if (!rowObj.has("pages") || !rowObj.get("pages").isJsonObject()) {
                        failures.add(lecternKey + " row[" + rowIndex + "] (missing pages object)");
                        continue;
                    }

                    for (Map.Entry<String, JsonElement> chamberEntry : rowObj.getAsJsonObject("pages").entrySet()) {
                        int[] coord = parseCoord(chamberEntry.getKey());
                        if (coord == null) {
                            failures.add(lecternKey + " row[" + rowIndex + "]/" + chamberEntry.getKey() + " (bad coord)");
                            continue;
                        }

                        int page;
                        try {
                            page = chamberEntry.getValue().getAsInt();
                        } catch (Exception e) {
                            failures.add(lecternKey + " row[" + rowIndex + "]/" + chamberEntry.getKey() + " (bad page)");
                            continue;
                        }
                        newIndex.put(packKey(coord[0], coord[1]), new Hit(lecternKey, page, buttonKey));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load chambers.json", e);
            loaded = true;
            return;
        }

        index = newIndex;
        loaded = true;
        LOG.info("Loaded chambers.json: {} chamber positions indexed", newIndex.size());
        if (!failures.isEmpty()) {
            LOG.warn("Skipped {} malformed chambers.json entries: {}", failures.size(), failures);
        }
    }

    public static Optional<Hit> lookup(int relX, int relZ) {
        if (!loaded) {
            load();
        }
        return Optional.ofNullable(index.get(packKey(relX, relZ)));
    }

    public static int[] parseCoord(String key) {
        if (key == null) {
            return null;
        }
        int comma = key.indexOf(',');
        if (comma < 0) {
            return null;
        }
        try {
            int x = Integer.parseInt(key.substring(0, comma).trim());
            int z = Integer.parseInt(key.substring(comma + 1).trim());
            return new int[] {x, z};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readButtonKey(String lecternKey,
                                        int rowIndex,
                                        com.google.gson.JsonObject rowObj,
                                        List<String> failures) {
        if (!rowObj.has("button") || rowObj.get("button").isJsonNull()) {
            return null;
        }

        String buttonKey = rowObj.get("button").getAsString();
        if (parseCoord(buttonKey) == null) {
            failures.add(lecternKey + " row[" + rowIndex + "] button=" + buttonKey + " (bad coord)");
            return null;
        }
        return buttonKey;
    }

    private static long packKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }
}
