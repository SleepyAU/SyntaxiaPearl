package dev.zenith.pearlplus.scanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.World;
import com.zenith.feature.player.raycast.BlockRaycastResult;
import com.zenith.feature.player.raycast.RayIntersection;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.block.Direction;
import com.zenith.mc.block.properties.ChestType;
import com.zenith.mc.block.properties.api.BlockStateProperties;
import com.zenith.mc.item.ContainerTypeInfoRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ItemTags;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import dev.zenith.pearlplus.PearlPlusConfig;
import dev.zenith.pearlplus.PearlPlusPlugin;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.INVENTORY;
import static com.zenith.Globals.INPUTS;

public class ChestScannerService {
    private static final int SCAN_ACTION_PRIORITY = 2_000;
    private static final double CHEST_INTERACT_DISTANCE_SQ = 20.25D;
    private static final int CHEST_DISCOVERY_VERTICAL_MARGIN = 4;
    private static final int SHULKER_CONTAINER_CAPACITY = 27;
    private static final long PATH_TIMEOUT_SECONDS = 20L;
    private static final long CUSTOM_PATH_TIMEOUT_SECONDS = 90L;
    private static final long CUSTOM_PATH_DIRECT_MOVE_TIMEOUT_MS = 8_000L;
    private static final int CUSTOM_PATH_NEAR_RANGE_SQ = 2;
    private static final double CUSTOM_PATH_CENTER_DISTANCE_SQ = 0.16D;
    private static final double DROP_CENTER_DISTANCE_SQ = 0.04D;
    private static final double DROP_STRICT_CENTER_DISTANCE_SQ = 0.01D;
    private static final long DROP_WAIT_TIMEOUT_MS = 30_000L;
    private static final long DROP_RECOVERY_DIRECT_MOVE_TIMEOUT_MS = 8_000L;
    private static final long DROP_RECOVERY_WAIT_TIMEOUT_MS = 20_000L;
    private static final double CHEST_STAND_DIRECT_MOVE_MAX_DISTANCE_SQ = 64.0D;
    private static final long CHEST_STAND_DIRECT_MOVE_BASE_TIMEOUT_MS = 650L;
    private static final long CHEST_STAND_DIRECT_MOVE_PER_BLOCK_TIMEOUT_MS = 450L;
    private static final long CONTAINER_OPEN_TIMEOUT_MS = 750L;
    private static final long CONTAINER_LATE_OPEN_CLOSE_GRACE_MS = 300L;
    private static final long CONTAINER_CLOSE_TIMEOUT_MS = 2_000L;
    private static final long CONTAINER_POLL_MS = 10L;
    private static final long WITHDRAW_BATCH_SETTLE_MS = 250L;
    private static final long WITHDRAW_POLL_INTERVAL_SECONDS = 15L;
    private static final int WITHDRAW_CLICK_PACKET_LIMIT = 79;
    private static final long WITHDRAW_CLICK_PACKET_WINDOW_MS = 4_200L;
    private static final int WITHDRAW_ACTION_PRIORITY = 2_200;
    private static final int MAX_CUSTOM_PATH_POINTS = 20;
    private static final Path SCAN_LOCK_PATH = Path.of(System.getProperty("java.io.tmpdir"), "syntaxia-chest-scan-active.lock");

    private final List<ChestData> scannedChests = new ArrayList<>();
    private final HttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService withdrawExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Deque<Long> withdrawClickPacketTimes = new ArrayDeque<>();
    private Future<?> scanTask = null;
    private ScheduledFuture<?> withdrawPollTask = null;

    private volatile boolean scanActive = false;
    private volatile boolean withdrawActive = false;
    private volatile boolean markerReturnActive = false;
    private volatile boolean markerReturnForPearlLoad = false;
    private volatile boolean withdrawPauseRequested = false;
    private volatile boolean withdrawPausedForPearl = false;
    private volatile boolean pauseRequested = false;
    private volatile boolean cancelRequested = false;
    private volatile boolean discoveryComplete = false;
    private volatile int markerX;
    private volatile int markerY;
    private volatile int markerZ;
    private final Set<String> scannedThisScan = new HashSet<>();
    private final Set<String> readChestKeys = new HashSet<>();
    private final List<String> pendingChestKeys = new ArrayList<>();
    private final Object withdrawPauseMonitor = new Object();

    // Local mapping: chestKey -> ChestLocationData (never sent to API)
    private final Map<String, ChestLocationData> localChestLocations = new LinkedHashMap<>();

    private record ZoneMatch(String name, String type, PearlPlusConfig.ScanZone zone) { }

    private record LaneMatch(String key,
                             PearlPlusConfig.ScanLane lane,
                             double projection,
                             double distanceSq) { }

    public record WithdrawRequest(String requestId,
                                  String itemId,
                                  int shulkerCount,
                                  String requesterName,
                                  List<String> candidateChestIds,
                                  List<WithdrawItem> items) {
        public WithdrawRequest {
            candidateChestIds = candidateChestIds == null ? List.of() : List.copyOf(candidateChestIds);
            if (items == null || items.isEmpty()) {
                items = itemId == null || itemId.isBlank() || shulkerCount <= 0
                    ? List.of()
                    : List.of(new WithdrawItem(itemId, shulkerCount, candidateChestIds));
            } else {
                items = List.copyOf(items);
            }
        }

        public WithdrawRequest(final String requestId,
                               final String itemId,
                               final int shulkerCount,
                               final String requesterName,
                               final List<String> candidateChestIds) {
            this(requestId, itemId, shulkerCount, requesterName, candidateChestIds, List.of());
        }

        public int totalShulkerCount() {
            return items.stream().mapToInt(WithdrawItem::shulkerCount).sum();
        }
    }

    public record WithdrawItem(String itemId, int shulkerCount, List<String> candidateChestIds) {
        public WithdrawItem {
            candidateChestIds = candidateChestIds == null ? List.of() : List.copyOf(candidateChestIds);
        }
    }

    private record OpenedChest(Container container, int topSlots) { }

    private record DepositResult(int deposited, boolean exhausted) { }

    private static final class ChestLocationData {
        final int x;
        final int y;
        final int z;
        final String dimension;
        final String blockType;
        final boolean doubleChest;
        final String zoneName;
        final String zoneType;

        ChestLocationData(final int x,
                          final int y,
                          final int z,
                          final String dimension,
                          final String blockType,
                          final boolean doubleChest,
                          final String zoneName,
                          final String zoneType) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.blockType = blockType;
            this.doubleChest = doubleChest;
            this.zoneName = zoneName;
            this.zoneType = zoneType;
        }
    }

    public ChestScannerService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    public synchronized void startWithdrawWorker() {
        if (withdrawPollTask != null && !withdrawPollTask.isDone()) {
            return;
        }
        withdrawPollTask = withdrawExecutor.scheduleWithFixedDelay(
            this::pollWithdrawQueueSafely,
            10L,
            WITHDRAW_POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        PearlPlusPlugin.LOG.info("Withdraw worker started");
    }

    public synchronized void stopWithdrawWorker() {
        if (withdrawPollTask != null) {
            withdrawPollTask.cancel(true);
            withdrawPollTask = null;
        }
    }

    public synchronized void startScan(final int markerX, final int markerY, final int markerZ) {
        if (busyForNewWork()) {
            PearlPlusPlugin.LOG.warn("Cannot start scan while scanner, withdrawal, or pearl-load return is active");
            return;
        }
        resetScanState();
        this.markerX = markerX;
        this.markerY = markerY;
        this.markerZ = markerZ;
        this.scanActive = true;
        this.pauseRequested = false;
        this.cancelRequested = false;
        createScanLock();

        scanTask = executor.submit(this::performScan);
    }

    public synchronized boolean returnToConfiguredMarker() {
        if (scanActive) {
            return false;
        }

        final int[] marker = parseConfiguredMarkerPos();
        if (marker == null) {
            return false;
        }

        this.markerX = marker[0];
        this.markerY = marker[1];
        this.markerZ = marker[2];
        this.scanActive = true;
        this.pauseRequested = false;
        this.cancelRequested = false;
        createScanLock();
        scanTask = executor.submit(() -> {
            try {
                PearlPlusPlugin.LOG.info("Manual scanner return requested");
                returnToMarkerAfterScan();
            } finally {
                scanActive = false;
                BARITONE.stop();
                clearScanLock();
            }
        });
        return true;
    }

    public boolean returnPausedScannerToMarkerForPearlLoad() {
        if (!pauseRequested && !withdrawPauseRequested && !withdrawPausedForPearl) {
            return false;
        }

        final int[] marker = parseConfiguredMarkerPos();
        if (marker == null) {
            return false;
        }

        this.markerX = marker[0];
        this.markerY = marker[1];
        this.markerZ = marker[2];
        markerReturnActive = true;
        markerReturnForPearlLoad = true;
        createScanLock();
        try {
            PearlPlusPlugin.LOG.info("Returning to scanner marker before pearl load");
            final boolean returned = returnToMarkerAfterScan();
            closeOpenContainerIfPresentSafely();
            return returned;
        } finally {
            markerReturnForPearlLoad = false;
            markerReturnActive = false;
            BARITONE.stop();
            clearScanLock();
        }
    }

    public synchronized boolean clearRemoteStashData() {
        if (busyForNewWork()) {
            return false;
        }

        executor.execute(() -> {
            try {
                final String endpoint = PearlPlusPlugin.PLUGIN_CONFIG.scanner.apiEndpoint;
                final String apiKey = PearlPlusPlugin.PLUGIN_CONFIG.scanner.apiKey;
                if (endpoint == null || endpoint.isBlank()) {
                    PearlPlusPlugin.LOG.warn("Cannot clear stash data: scanner API endpoint is not configured");
                    return;
                }

                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");

                if (apiKey != null && !apiKey.isEmpty()) {
                    builder.header("X-API-Key", apiKey);
                }

                final HttpResponse<String> response = httpClient.send(
                    builder.DELETE().build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    PearlPlusPlugin.LOG.info("Remote stash data clear completed: {}", response.body());
                    synchronized (this) {
                        scannedChests.clear();
                        scannedThisScan.clear();
                        readChestKeys.clear();
                        pendingChestKeys.clear();
                        localChestLocations.clear();
                    }
                } else {
                    PearlPlusPlugin.LOG.error("Remote stash data clear failed with status {}: {}", response.statusCode(), response.body());
                }
            } catch (final Exception e) {
                PearlPlusPlugin.LOG.error("Error clearing remote stash data", e);
            }
        });
        return true;
    }

    private int[] parseConfiguredMarkerPos() {
        final String pos = PearlPlusPlugin.PLUGIN_CONFIG.scanner.markerBlockPos;
        try {
            final String[] parts = pos.split(",");
            if (parts.length != 3) {
                return null;
            }
            return new int[]{
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim())
            };
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.error("Failed to parse marker position: {}", pos, e);
            return null;
        }
    }

    private void performScan() {
        try {
            if (CACHE == null || CACHE.getPlayerCache() == null || !scanActive) {
                PearlPlusPlugin.LOG.error("Player cache unavailable, aborting scan");
                return;
            }

            if (!moveThroughCustomPathIfEnabled()) {
                return;
            }

            if (discoveryComplete) {
                PearlPlusPlugin.LOG.info("Resuming chest scan with {} remaining targets", getRemainingChestCount());
                readChestContents();
                return;
            }

            final String dimension = World.getCurrentDimension() != null ? World.getCurrentDimension().name() : "unknown";
            final int chestCount = discoverChestLocations(dimension);

            PearlPlusPlugin.LOG.info("Found {} double chests/trapped chests, now reading contents", chestCount);
            discoveryComplete = true;
            rebuildPendingChestKeys();
            readChestContents();
            PearlPlusPlugin.LOG.info("Contents read, preparing to send inventory data to API");
            PearlPlusPlugin.LOG.info("Returning to marker at {},{},{}", markerX, markerY, markerZ);
            returnToMarkerAfterScan();
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.error("Error during chest scan", e);
        } finally {
            finishScan();
        }
    }

    private int discoverChestLocations(final String dimension) {
        if (hasConfiguredScanZones()) {
            PearlPlusPlugin.LOG.info("Starting chest scan in {} using configured zone bounds", dimension);
            return discoverConfiguredZoneChestLocations(dimension);
        }

        final double playerX = CACHE.getPlayerCache().getThePlayer().getX();
        final double playerY = CACHE.getPlayerCache().getThePlayer().getY();
        final int searchRadius = PearlPlusPlugin.PLUGIN_CONFIG.scanner.searchRadiusBlocks;
        final int yOffset = PearlPlusPlugin.PLUGIN_CONFIG.scanner.yLevelOffset;

        PearlPlusPlugin.LOG.info("Starting chest scan in {}, radius={}, y±{}", dimension, searchRadius, yOffset);

        final int minY = Math.max(
            World.getCurrentDimension().minY(),
            (int) Math.floor(playerY) - yOffset - CHEST_DISCOVERY_VERTICAL_MARGIN
        );
        final int maxY = Math.min(
            World.getCurrentDimension().buildHeight(),
            (int) Math.floor(playerY) + yOffset + CHEST_DISCOVERY_VERTICAL_MARGIN
        );

        int chestCount = 0;
        for (int dx = -searchRadius; dx <= searchRadius && operationActive(); dx++) {
            for (int checkY = minY; checkY <= maxY && operationActive(); checkY++) {
                for (int dz = -searchRadius; dz <= searchRadius && operationActive(); dz++) {
                    final int checkX = (int) Math.floor(playerX) + dx;
                    final int checkZ = (int) Math.floor(CACHE.getPlayerCache().getThePlayer().getZ()) + dz;
                    final String blockType = getBlockType(checkX, checkY, checkZ);
                    if (blockType == null) {
                        continue;
                    }
                    if (recordChestLocation(checkX, checkY, checkZ, blockType, dimension)) {
                        chestCount++;
                    }
                }
            }
        }
        return chestCount;
    }

    private boolean hasConfiguredScanZones() {
        return PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones != null
            && PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones.values().stream().anyMatch(this::zoneHasBounds);
    }

    private int discoverConfiguredZoneChestLocations(final String dimension) {
        int chestCount = 0;
        for (final Map.Entry<String, PearlPlusConfig.ScanZone> entry : orderedZonesForScan()) {
            final PearlPlusConfig.ScanZone zone = entry.getValue();
            if (zone == null || !zoneHasBounds(zone)) {
                continue;
            }
            final int minX = (int) Math.floor(Math.min(zone.pos1.x, zone.pos2.x));
            final int maxX = (int) Math.floor(Math.max(zone.pos1.x, zone.pos2.x));
            final int minY = Math.max(World.getCurrentDimension().minY(), (int) Math.floor(Math.min(zone.pos1.y, zone.pos2.y)));
            final int maxY = Math.min(World.getCurrentDimension().buildHeight(), (int) Math.floor(Math.max(zone.pos1.y, zone.pos2.y)));
            final int minZ = (int) Math.floor(Math.min(zone.pos1.z, zone.pos2.z));
            final int maxZ = (int) Math.floor(Math.max(zone.pos1.z, zone.pos2.z));

            for (int x = minX; x <= maxX && operationActive(); x++) {
                for (int y = minY; y <= maxY && operationActive(); y++) {
                    for (int z = minZ; z <= maxZ && operationActive(); z++) {
                        final String blockType = getBlockType(x, y, z);
                        if (blockType == null) {
                            continue;
                        }
                        if (recordChestLocation(x, y, z, blockType, dimension)) {
                            chestCount++;
                        }
                    }
                }
            }
        }
        return chestCount;
    }

    private boolean recordChestLocation(final int x, final int y, final int z, final String blockType, final String dimension) {
        try {
            final boolean doubleChest = blockType.startsWith("DOUBLE_");
            final String chestKey = generateChestKey(x, y, z, dimension);
            if (scannedThisScan.contains(chestKey)) {
                return false;
            }
            scannedThisScan.add(chestKey);

            final ZoneMatch zoneMatch = zoneForChest(x, y, z);
            if (zoneMatch != null && "IGNORE".equals(zoneMatch.type())) {
                PearlPlusPlugin.LOG.debug("Skipping ignored chest location (local): {},{},{} [{}]", x, y, z, chestKey);
                return false;
            }

            localChestLocations.put(chestKey, new ChestLocationData(
                x,
                y,
                z,
                dimension,
                blockType,
                doubleChest,
                zoneMatch != null ? zoneMatch.name() : null,
                zoneMatch != null ? zoneMatch.type() : null
            ));

            PearlPlusPlugin.LOG.debug("Recorded chest location (local): {},{},{} [{}]", x, y, z, chestKey);
            return true;
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.error("Error recording chest location", e);
            return false;
        }
    }

    private ZoneMatch zoneForChest(final int x, final int y, final int z) {
        if (PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones == null || PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones.isEmpty()) {
            return null;
        }

        for (final Map.Entry<String, PearlPlusConfig.ScanZone> entry : PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones.entrySet()) {
            final PearlPlusConfig.ScanZone zone = entry.getValue();
            if (zone == null || !zoneHasBounds(zone)) {
                continue;
            }
            if (zoneContains(zone, x, y, z)) {
                return new ZoneMatch(entry.getKey(), normalizedZoneType(zone), zone);
            }
        }
        return null;
    }

    private boolean zoneHasBounds(final PearlPlusConfig.ScanZone zone) {
        return zone != null && zone.pos1 != null && zone.pos2 != null;
    }

    private boolean zoneContains(final PearlPlusConfig.ScanZone zone, final int x, final int y, final int z) {
        if (!zoneHasBounds(zone)) {
            return false;
        }
        final double minX = Math.min(zone.pos1.x, zone.pos2.x);
        final double maxX = Math.max(zone.pos1.x, zone.pos2.x);
        final double minY = Math.min(zone.pos1.y, zone.pos2.y);
        final double maxY = Math.max(zone.pos1.y, zone.pos2.y);
        final double minZ = Math.min(zone.pos1.z, zone.pos2.z);
        final double maxZ = Math.max(zone.pos1.z, zone.pos2.z);
        return x >= Math.floor(minX) && x <= Math.floor(maxX)
            && y >= Math.floor(minY) && y <= Math.floor(maxY)
            && z >= Math.floor(minZ) && z <= Math.floor(maxZ);
    }

    private String normalizedZoneType(final PearlPlusConfig.ScanZone zone) {
        if (zone == null || zone.type == null) {
            return "STORAGE";
        }
        return switch (zone.type.trim().toUpperCase(Locale.ROOT)) {
            case "WITHDRAW", "WITHDRAWAL" -> "WITHDRAWAL";
            case "IGNORE", "IGNORED", "SKIP" -> "IGNORE";
            default -> "STORAGE";
        };
    }

    private boolean moveThroughCustomPathIfEnabled() {
        if (!PearlPlusPlugin.PLUGIN_CONFIG.scanner.customPathEnabled) {
            return true;
        }

        final List<CustomPathPoint> path = configuredCustomPath();
        if (path.isEmpty()) {
            PearlPlusPlugin.LOG.warn("Custom scanner path is enabled but no waypoints are set");
            return true;
        }

        PearlPlusPlugin.LOG.info("Following custom scanner path with {} waypoints", path.size());
        for (int i = 0; i < path.size(); i++) {
            final CustomPathPoint point = path.get(i);
            if (!waitIfWithdrawPaused()) {
                return false;
            }
            if (!operationActive()) {
                return false;
            }

            final int x = (int) Math.floor(point.x());
            final int y = (int) Math.floor(point.y());
            final int z = (int) Math.floor(point.z());
            final CustomPathPoint nextPoint = i + 1 < path.size() ? path.get(i + 1) : null;
            if (isColumnEntryWaypoint(point, nextPoint)) {
                if (!moveIntoColumnEntry(point, x, y, z)) {
                    return false;
                }
                continue;
            }

            if (shouldRideVerticalColumn(x, y, z)) {
                if (!rideVerticalColumnTo(point, x, y, z)) {
                    return false;
                }
                continue;
            }

            PearlPlusPlugin.LOG.info("Pathing to custom scanner waypoint {}: [{}, {}, {}]", point.index(), x, y, z);
            try {
                final boolean reached = BARITONE.pathTo(x, y, z).get(CUSTOM_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!reached && waitIfWithdrawPausedAndRetry()) {
                    i--;
                    continue;
                }
                if (!reached && operationActive()) {
                    PearlPlusPlugin.LOG.warn("Failed to reach custom scanner waypoint {} at [{}, {}, {}]", point.index(), x, y, z);
                    return false;
                }
            } catch (final Exception e) {
                if (waitIfWithdrawPausedAndRetry()) {
                    i--;
                    continue;
                }
                if (operationActive()) {
                    PearlPlusPlugin.LOG.warn("Error pathing to custom scanner waypoint {}", point.index(), e);
                }
                return false;
            }
        }
        return operationActive();
    }

    private boolean isColumnEntryWaypoint(final CustomPathPoint point, final CustomPathPoint nextPoint) {
        if (nextPoint == null) {
            return false;
        }

        final int x = (int) Math.floor(point.x());
        final int y = (int) Math.floor(point.y());
        final int z = (int) Math.floor(point.z());
        final int nextX = (int) Math.floor(nextPoint.x());
        final int nextY = (int) Math.floor(nextPoint.y());
        final int nextZ = (int) Math.floor(nextPoint.z());
        return x == nextX && z == nextZ && nextY > y + 1;
    }

    private boolean moveIntoColumnEntry(final CustomPathPoint point, final int x, final int y, final int z) {
        if (isInsideHorizontalTarget(x, z, CUSTOM_PATH_CENTER_DISTANCE_SQ)) {
            return true;
        }

        PearlPlusPlugin.LOG.info("Pathing near column entry waypoint {}: [{}, {}, {}]", point.index(), x, y, z);
        while (operationActive()) {
            if (!waitIfWithdrawPaused()) {
                return false;
            }
            try {
                final boolean reachedNear = BARITONE.pathTo(new GoalNear(x, y, z, CUSTOM_PATH_NEAR_RANGE_SQ))
                    .get(CUSTOM_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (reachedNear) {
                    break;
                }
                if (waitIfWithdrawPausedAndRetry()) {
                    continue;
                }
                if (operationActive()) {
                    PearlPlusPlugin.LOG.warn("Failed to path near column entry waypoint {} at [{}, {}, {}]", point.index(), x, y, z);
                    return false;
                }
            } catch (final Exception e) {
                if (waitIfWithdrawPausedAndRetry()) {
                    continue;
                }
                if (operationActive()) {
                    PearlPlusPlugin.LOG.warn("Error pathing near column entry waypoint {}", point.index(), e);
                }
                return false;
            }
        }

        return directMoveIntoColumn(point, x, z);
    }

    private boolean directMoveIntoColumn(final CustomPathPoint point, final int targetX, final int targetZ) {
        return directMoveToHorizontalTarget("column entry waypoint " + point.index(), targetX, targetZ,
            CUSTOM_PATH_DIRECT_MOVE_TIMEOUT_MS, CUSTOM_PATH_CENTER_DISTANCE_SQ);
    }

    private boolean directMoveToHorizontalTarget(final String label,
                                                 final int targetX,
                                                 final int targetZ,
                                                 final long timeoutMs,
                                                 final double centerDistanceSq) {
        PearlPlusPlugin.LOG.info("Walking to {} at [{}, {}]", label, targetX, targetZ);
        BARITONE.stop();

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (operationActive() && System.currentTimeMillis() < deadline) {
            final boolean wasPaused = withdrawActive && withdrawPauseRequested && !markerReturnForPearlLoad;
            if (!waitIfWithdrawPaused()) {
                break;
            }
            if (wasPaused) {
                deadline = System.currentTimeMillis() + timeoutMs;
            }
            if (isInsideHorizontalTarget(targetX, targetZ, centerDistanceSq)) {
                stopDirectMovement();
                return true;
            }

            if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
                sleepSilently(100L);
                continue;
            }

            final double targetCenterX = targetX + 0.5D;
            final double targetCenterZ = targetZ + 0.5D;
            final double dx = targetCenterX - CACHE.getPlayerCache().getThePlayer().getX();
            final double dz = targetCenterZ - CACHE.getPlayerCache().getThePlayer().getZ();
            final double distanceSq = dx * dx + dz * dz;
            if (distanceSq <= centerDistanceSq) {
                stopDirectMovement();
                return true;
            }

            final float yaw = RotationHelper.yawToXZ(targetCenterX, targetCenterZ);
            final Input input = Input.builder()
                .pressingForward(true)
                .build();
            final InputRequest request = InputRequest.builder()
                .owner(this)
                .input(input)
                .yaw(yaw)
                .pitch(CACHE.getPlayerCache().getPitch())
                .priority(SCAN_ACTION_PRIORITY)
                .build();
            INPUTS.submit(request);
            sleepSilently(50L);
        }

        stopDirectMovement();
        if (operationActive()) {
            PearlPlusPlugin.LOG.warn("Timed out walking to {} at [{}, {}]", label, targetX, targetZ);
        }
        return false;
    }

    private boolean isInsideHorizontalTarget(final int targetX, final int targetZ, final double centerDistanceSq) {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }

        final double playerX = CACHE.getPlayerCache().getThePlayer().getX();
        final double playerZ = CACHE.getPlayerCache().getThePlayer().getZ();
        final double dx = (targetX + 0.5D) - playerX;
        final double dz = (targetZ + 0.5D) - playerZ;
        return dx * dx + dz * dz <= centerDistanceSq;
    }

    private void stopDirectMovement() {
        try {
            INPUTS.submit(InputRequest.builder()
                .owner(this)
                .input(Input.builder().build())
                .priority(SCAN_ACTION_PRIORITY)
                .build());
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.debug("Failed to stop direct custom path movement", e);
        }
    }

    private boolean shouldRideVerticalColumn(final int targetX, final int targetY, final int targetZ) {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }

        final int playerX = (int) Math.floor(CACHE.getPlayerCache().getThePlayer().getX());
        final int playerY = (int) Math.floor(CACHE.getPlayerCache().getThePlayer().getY());
        final int playerZ = (int) Math.floor(CACHE.getPlayerCache().getThePlayer().getZ());
        return playerX == targetX && playerZ == targetZ && targetY > playerY + 1;
    }

    private boolean rideVerticalColumnTo(final CustomPathPoint point, final int x, final int y, final int z) {
        PearlPlusPlugin.LOG.info("Riding vertical column to custom scanner waypoint {}: [{}, {}, {}]", point.index(), x, y, z);
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CUSTOM_PATH_TIMEOUT_SECONDS);
        while (operationActive() && System.currentTimeMillis() < deadline) {
            final boolean wasPaused = withdrawActive && withdrawPauseRequested && !markerReturnForPearlLoad;
            if (!waitIfWithdrawPaused()) {
                break;
            }
            if (wasPaused) {
                deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CUSTOM_PATH_TIMEOUT_SECONDS);
            }
            if (CACHE != null && CACHE.getPlayerCache() != null && CACHE.getPlayerCache().getThePlayer() != null) {
                final double playerY = CACHE.getPlayerCache().getThePlayer().getY();
                if (playerY >= point.y() - 0.25D) {
                    return true;
                }
            }
            sleepSilently(100L);
        }
        if (operationActive()) {
            PearlPlusPlugin.LOG.warn("Timed out riding vertical column to custom scanner waypoint {} at [{}, {}, {}]",
                point.index(), x, y, z);
        }
        return false;
    }

    private boolean returnToMarkerAfterScan() {
        if (!operationActive()) {
            return false;
        }
        if (tryCustomDropReturnToMarker()) {
            return true;
        }

        while (operationActive()) {
            if (!waitIfWithdrawPaused()) {
                return false;
            }
            try {
                final boolean reached = BARITONE.pathTo(markerX, markerY, markerZ)
                    .get(PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (reached) {
                    return true;
                }
                if (waitIfWithdrawPausedAndRetry()) {
                    continue;
                }
                if (operationActive()) {
                    PearlPlusPlugin.LOG.warn("Failed to return to scanner marker at [{}, {}, {}]", markerX, markerY, markerZ);
                }
                return false;
            } catch (final Exception e) {
                if (waitIfWithdrawPausedAndRetry()) {
                    continue;
                }
                if (operationActive()) {
                    PearlPlusPlugin.LOG.warn("Error returning to scanner marker at [{}, {}, {}]", markerX, markerY, markerZ, e);
                }
                return false;
            }
        }
        return false;
    }

    private boolean tryCustomDropReturnToMarker() {
        if (!PearlPlusPlugin.PLUGIN_CONFIG.scanner.customPathEnabled) {
            return false;
        }
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }
        if (CACHE.getPlayerCache().getThePlayer().getY() <= markerY + 2.0D) {
            return false;
        }

        final Integer upperY = highestCustomPathY();
        if (upperY == null || upperY <= markerY + 1) {
            return false;
        }

        PearlPlusPlugin.LOG.info("Returning through custom scanner drop at [{}, {}, {}]", markerX, upperY, markerZ);
        while (operationActive()) {
            if (!waitIfWithdrawPaused()) {
                return false;
            }
            try {
                final boolean reachedNear = BARITONE.pathTo(new GoalNear(markerX, upperY, markerZ, CUSTOM_PATH_NEAR_RANGE_SQ))
                    .get(CUSTOM_PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (reachedNear) {
                    break;
                }
                if (waitIfWithdrawPausedAndRetry()) {
                    continue;
                }
                if (operationActive()) {
                    PearlPlusPlugin.LOG.warn("Failed to path near scanner drop at [{}, {}, {}]", markerX, upperY, markerZ);
                    return false;
                }
            } catch (final Exception e) {
                if (waitIfWithdrawPausedAndRetry()) {
                    continue;
                }
                if (operationActive()) {
                    PearlPlusPlugin.LOG.warn("Error pathing near scanner drop at [{}, {}, {}]", markerX, upperY, markerZ, e);
                }
                return false;
            }
        }

        if (!directMoveToHorizontalTarget("scanner drop", markerX, markerZ, CUSTOM_PATH_DIRECT_MOVE_TIMEOUT_MS, DROP_CENTER_DISTANCE_SQ)) {
            return false;
        }
        if (waitForDropToMarker(DROP_WAIT_TIMEOUT_MS)) {
            return true;
        }

        PearlPlusPlugin.LOG.warn("Scanner drop did not start after initial center; trying strict drop recenter");
        if (!directMoveToHorizontalTarget("scanner drop recovery", markerX, markerZ,
            DROP_RECOVERY_DIRECT_MOVE_TIMEOUT_MS, DROP_STRICT_CENTER_DISTANCE_SQ)) {
            return false;
        }
        return waitForDropToMarker(DROP_RECOVERY_WAIT_TIMEOUT_MS);
    }

    private Integer highestCustomPathY() {
        Integer highestY = null;
        for (final CustomPathPoint point : configuredCustomPath()) {
            final int y = (int) Math.floor(point.y());
            if (highestY == null || y > highestY) {
                highestY = y;
            }
        }
        return highestY;
    }

    private boolean waitForDropToMarker(final long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (operationActive() && System.currentTimeMillis() < deadline) {
            final boolean wasPaused = withdrawActive && withdrawPauseRequested && !markerReturnForPearlLoad;
            if (!waitIfWithdrawPaused()) {
                break;
            }
            if (wasPaused) {
                deadline = System.currentTimeMillis() + timeoutMs;
            }
            if (CACHE != null && CACHE.getPlayerCache() != null && CACHE.getPlayerCache().getThePlayer() != null) {
                final double playerX = CACHE.getPlayerCache().getThePlayer().getX();
                final double playerY = CACHE.getPlayerCache().getThePlayer().getY();
                final double playerZ = CACHE.getPlayerCache().getThePlayer().getZ();
                final double dx = (markerX + 0.5D) - playerX;
                final double dz = (markerZ + 0.5D) - playerZ;
                if (dx * dx + dz * dz <= CUSTOM_PATH_CENTER_DISTANCE_SQ && playerY <= markerY + 0.75D) {
                    stopDirectMovement();
                    return true;
                }
            }
            sleepSilently(100L);
        }

        stopDirectMovement();
        if (operationActive()) {
            if (CACHE != null && CACHE.getPlayerCache() != null && CACHE.getPlayerCache().getThePlayer() != null) {
                PearlPlusPlugin.LOG.warn("Timed out waiting to drop to scanner marker at [{}, {}, {}]; bot position [{}, {}, {}]",
                    markerX,
                    markerY,
                    markerZ,
                    CACHE.getPlayerCache().getThePlayer().getX(),
                    CACHE.getPlayerCache().getThePlayer().getY(),
                    CACHE.getPlayerCache().getThePlayer().getZ());
            } else {
                PearlPlusPlugin.LOG.warn("Timed out waiting to drop to scanner marker at [{}, {}, {}]", markerX, markerY, markerZ);
            }
        }
        return false;
    }

    private List<CustomPathPoint> configuredCustomPath() {
        final List<CustomPathPoint> path = new ArrayList<>();
        for (int i = 1; i <= MAX_CUSTOM_PATH_POINTS; i++) {
            final var point = PearlPlusPlugin.PLUGIN_CONFIG.scanner.customPath.get(String.valueOf(i));
            if (point == null || isUnsetPoint(point.x, point.y, point.z)) {
                continue;
            }
            path.add(new CustomPathPoint(i, point.x, point.y, point.z));
        }
        return path;
    }

    private boolean isUnsetPoint(final double x, final double y, final double z) {
        return x == 0.0D && y == 0.0D && z == 0.0D;
    }

    private record CustomPathPoint(int index, double x, double y, double z) { }

    private boolean operationActive() {
        return scanActive || withdrawActive || markerReturnActive;
    }

    private boolean busyForNewWork() {
        return scanActive
            || withdrawActive
            || markerReturnActive
            || pauseRequested
            || withdrawPauseRequested
            || withdrawPausedForPearl;
    }

    private boolean waitIfWithdrawPaused() {
        if (markerReturnForPearlLoad) {
            return operationActive();
        }
        if (!withdrawActive || !withdrawPauseRequested) {
            return operationActive();
        }

        BARITONE.stop();
        stopDirectMovement();
        closeOpenContainerIfPresentSafely();
        clearScanLock();

        synchronized (withdrawPauseMonitor) {
            if (!withdrawPausedForPearl) {
                PearlPlusPlugin.LOG.info("Withdrawal paused for pearl load request");
            }
            withdrawPausedForPearl = true;
            withdrawPauseMonitor.notifyAll();
            while (withdrawActive && withdrawPauseRequested) {
                try {
                    withdrawPauseMonitor.wait(250L);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            withdrawPausedForPearl = false;
        }

        if (withdrawActive) {
            createScanLock();
            PearlPlusPlugin.LOG.info("Resuming withdrawal after pearl load");
        }
        return operationActive();
    }

    private boolean waitIfWithdrawPausedAndRetry() {
        return !markerReturnForPearlLoad && withdrawActive && withdrawPauseRequested && waitIfWithdrawPaused();
    }

    private void readChestContents() {
        if (pendingChestKeys.isEmpty()) {
            rebuildPendingChestKeys();
        }

        PearlPlusPlugin.LOG.info("Reading contents for {} remaining chests", pendingChestKeys.size());

        String currentRouteSegment = null;
        while (operationActive() && !pendingChestKeys.isEmpty()) {
            final String chestKey = nextPendingChestKey();
            if (chestKey == null) {
                break;
            }

            final ChestLocationData location = localChestLocations.get(chestKey);
            if (location == null) {
                pendingChestKeys.remove(chestKey);
                continue;
            }

            try {
                final String routeSegment = routeSegmentFor(location);
                if (routeSegment != null && !routeSegment.equals(currentRouteSegment)) {
                    moveToRouteSegmentStart(location);
                    if (!scanActive) {
                        break;
                    }
                    currentRouteSegment = routeSegment;
                }
                final ChestData chestData = openAndReadChest(chestKey, location);
                if (chestData == null) {
                    if (!scanActive && pauseRequested) {
                        PearlPlusPlugin.LOG.info("Chest scan paused before reading {}", chestKey);
                        break;
                    }
                    PearlPlusPlugin.LOG.warn("Failed to open/read chest {}", chestKey);
                    pendingChestKeys.remove(chestKey);
                    continue;
                }
                synchronized (scannedChests) {
                    scannedChests.add(chestData);
                }
                readChestKeys.add(chestKey);
                pendingChestKeys.remove(chestKey);
                PearlPlusPlugin.LOG.info("Read {} item stacks from chest {}", chestData.items.size(), chestKey);
            } catch (final Exception e) {
                if (!scanActive && pauseRequested) {
                    PearlPlusPlugin.LOG.info("Chest scan paused while reading {}", chestKey);
                    break;
                }
                PearlPlusPlugin.LOG.error("Error reading chest contents for {}", chestKey, e);
                pendingChestKeys.remove(chestKey);
            }
        }
    }

    private void rebuildPendingChestKeys() {
        pendingChestKeys.clear();
        final List<String> unreadChestKeys = new ArrayList<>();
        for (final String chestKey : localChestLocations.keySet()) {
            if (!readChestKeys.contains(chestKey)) {
                unreadChestKeys.add(chestKey);
            }
        }
        pendingChestKeys.addAll(routeAwareOrderingEnabled() ? routeOrderedChestKeys(unreadChestKeys) : unreadChestKeys);
    }

    private String nextPendingChestKey() {
        if (routeAwareOrderingEnabled()) {
            return pendingChestKeys.isEmpty() ? null : pendingChestKeys.getFirst();
        }
        return closestPendingChestKey();
    }

    private String closestPendingChestKey() {
        return pendingChestKeys.stream()
            .filter(localChestLocations::containsKey)
            .min(Comparator.comparingDouble(key -> {
                final ChestLocationData location = localChestLocations.get(key);
                return distanceSqToPlayer(location.x, location.y, location.z);
            }))
            .orElse(null);
    }

    private boolean routeAwareOrderingEnabled() {
        if (PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones == null || PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones.isEmpty()) {
            return false;
        }
        return PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones.values().stream().anyMatch(this::zoneHasBounds);
    }

    private List<String> routeOrderedChestKeys(final List<String> chestKeys) {
        final List<String> ordered = new ArrayList<>();
        final Set<String> remaining = new HashSet<>(chestKeys);

        for (final Map.Entry<String, PearlPlusConfig.ScanZone> zoneEntry : orderedZonesForScan()) {
            final String zoneName = zoneEntry.getKey();
            final PearlPlusConfig.ScanZone zone = zoneEntry.getValue();
            final List<String> zoneKeys = chestKeys.stream()
                .filter(remaining::contains)
                .filter(key -> {
                    final ChestLocationData location = localChestLocations.get(key);
                    return location != null && zoneName.equals(location.zoneName);
                })
                .toList();
            if (zoneKeys.isEmpty()) {
                continue;
            }

            final List<String> orderedZoneKeys = routeOrderedZoneKeys(zone, zoneKeys);
            ordered.addAll(orderedZoneKeys);
            remaining.removeAll(orderedZoneKeys);
        }

        final List<String> unzoned = chestKeys.stream()
            .filter(remaining::contains)
            .sorted(Comparator.comparingDouble(key -> {
                final ChestLocationData location = localChestLocations.get(key);
                return location != null ? distanceSqToPlayer(location.x, location.y, location.z) : Double.MAX_VALUE;
            }))
            .toList();
        ordered.addAll(unzoned);
        return ordered;
    }

    private List<Map.Entry<String, PearlPlusConfig.ScanZone>> orderedZonesForScan() {
        final List<Map.Entry<String, PearlPlusConfig.ScanZone>> zones = new ArrayList<>();
        if (PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones == null) {
            return zones;
        }

        for (final Map.Entry<String, PearlPlusConfig.ScanZone> entry : PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones.entrySet()) {
            if (entry.getValue() == null || !zoneHasBounds(entry.getValue())) {
                continue;
            }
            if ("IGNORE".equals(normalizedZoneType(entry.getValue()))) {
                continue;
            }
            zones.add(entry);
        }
        zones.sort(Comparator.comparingInt(entry -> zoneScanWeight(entry.getValue())));
        return zones;
    }

    private int zoneScanWeight(final PearlPlusConfig.ScanZone zone) {
        return switch (normalizedZoneType(zone)) {
            case "STORAGE" -> 0;
            case "WITHDRAWAL" -> 2;
            default -> 1;
        };
    }

    private List<String> routeOrderedZoneKeys(final PearlPlusConfig.ScanZone zone, final List<String> zoneKeys) {
        final List<Map.Entry<String, PearlPlusConfig.ScanLane>> lanes = configuredLanes(zone);
        if (lanes.isEmpty()) {
            return zoneKeys.stream()
                .sorted(Comparator
                    .comparingInt((String key) -> localChestLocations.get(key).x)
                    .thenComparingInt(key -> localChestLocations.get(key).z)
                    .thenComparingInt(key -> localChestLocations.get(key).y))
                .toList();
        }

        final Map<String, List<String>> byLane = new LinkedHashMap<>();
        for (final Map.Entry<String, PearlPlusConfig.ScanLane> lane : lanes) {
            byLane.put(lane.getKey(), new ArrayList<>());
        }
        final List<String> unassigned = new ArrayList<>();
        for (final String key : zoneKeys) {
            final ChestLocationData location = localChestLocations.get(key);
            final LaneMatch laneMatch = closestLaneMatch(location, lanes);
            if (laneMatch == null) {
                unassigned.add(key);
                continue;
            }
            byLane.get(laneMatch.key()).add(key);
        }

        final List<String> ordered = new ArrayList<>();
        for (final Map.Entry<String, PearlPlusConfig.ScanLane> lane : lanes) {
            final List<String> laneKeys = byLane.get(lane.getKey());
            laneKeys.sort(Comparator
                .comparingDouble((String key) -> laneProjection(localChestLocations.get(key), lane.getValue()))
                .thenComparingDouble(key -> laneDistanceSq(localChestLocations.get(key), lane.getValue()))
                .thenComparingInt(key -> localChestLocations.get(key).y));
            ordered.addAll(laneKeys);
        }
        unassigned.sort(Comparator.comparingDouble(key -> {
            final ChestLocationData location = localChestLocations.get(key);
            return location != null ? distanceSqToPlayer(location.x, location.y, location.z) : Double.MAX_VALUE;
        }));
        ordered.addAll(unassigned);
        return ordered;
    }

    private List<Map.Entry<String, PearlPlusConfig.ScanLane>> configuredLanes(final PearlPlusConfig.ScanZone zone) {
        if (zone == null || zone.lanes == null || zone.lanes.isEmpty()) {
            return List.of();
        }
        final List<Map.Entry<String, PearlPlusConfig.ScanLane>> lanes = new ArrayList<>();
        for (final Map.Entry<String, PearlPlusConfig.ScanLane> laneEntry : zone.lanes.entrySet()) {
            final PearlPlusConfig.ScanLane lane = laneEntry.getValue();
            if (lane == null || lane.start == null || lane.end == null) {
                continue;
            }
            lanes.add(laneEntry);
        }
        lanes.sort(Comparator.comparingInt(entry -> numericKey(entry.getKey())));
        return lanes;
    }

    private int numericKey(final String key) {
        try {
            return Integer.parseInt(key);
        } catch (final Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private LaneMatch closestLaneMatch(final ChestLocationData location,
                                       final List<Map.Entry<String, PearlPlusConfig.ScanLane>> lanes) {
        if (location == null || lanes.isEmpty()) {
            return null;
        }
        LaneMatch best = null;
        for (final Map.Entry<String, PearlPlusConfig.ScanLane> laneEntry : lanes) {
            final PearlPlusConfig.ScanLane lane = laneEntry.getValue();
            final double distanceSq = laneDistanceSq(location, lane);
            final double projection = laneProjection(location, lane);
            if (best == null || distanceSq < best.distanceSq()) {
                best = new LaneMatch(laneEntry.getKey(), lane, projection, distanceSq);
            }
        }
        return best;
    }

    private double laneProjection(final ChestLocationData location, final PearlPlusConfig.ScanLane lane) {
        if (location == null || lane == null || lane.start == null || lane.end == null) {
            return 0.0D;
        }
        final double startX = lane.start.x;
        final double startZ = lane.start.z;
        final double dx = lane.end.x - startX;
        final double dz = lane.end.z - startZ;
        final double lengthSq = dx * dx + dz * dz;
        if (lengthSq <= 0.0001D) {
            return 0.0D;
        }
        final double chestX = location.x + 0.5D;
        final double chestZ = location.z + 0.5D;
        return ((chestX - startX) * dx + (chestZ - startZ) * dz) / lengthSq;
    }

    private double laneDistanceSq(final ChestLocationData location, final PearlPlusConfig.ScanLane lane) {
        if (location == null || lane == null || lane.start == null || lane.end == null) {
            return Double.MAX_VALUE;
        }
        final double startX = lane.start.x;
        final double startZ = lane.start.z;
        final double dx = lane.end.x - startX;
        final double dz = lane.end.z - startZ;
        final double lengthSq = dx * dx + dz * dz;
        final double chestX = location.x + 0.5D;
        final double chestZ = location.z + 0.5D;
        if (lengthSq <= 0.0001D) {
            final double sx = chestX - startX;
            final double sz = chestZ - startZ;
            return sx * sx + sz * sz;
        }
        final double projection = Math.clamp(((chestX - startX) * dx + (chestZ - startZ) * dz) / lengthSq, 0.0D, 1.0D);
        final double closestX = startX + projection * dx;
        final double closestZ = startZ + projection * dz;
        final double px = chestX - closestX;
        final double pz = chestZ - closestZ;
        return px * px + pz * pz;
    }

    private String routeSegmentFor(final ChestLocationData location) {
        if (!routeAwareOrderingEnabled() || location == null || location.zoneName == null) {
            return null;
        }
        final LaneMatch laneMatch = laneMatchForLocation(location);
        if (laneMatch == null) {
            return location.zoneName;
        }
        return location.zoneName + ":" + laneMatch.key();
    }

    private LaneMatch laneMatchForLocation(final ChestLocationData location) {
        if (location == null || location.zoneName == null || PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones == null) {
            return null;
        }
        final PearlPlusConfig.ScanZone zone = PearlPlusPlugin.PLUGIN_CONFIG.scanner.zones.get(location.zoneName);
        return closestLaneMatch(location, configuredLanes(zone));
    }

    private void moveToRouteSegmentStart(final ChestLocationData location) {
        final LaneMatch laneMatch = laneMatchForLocation(location);
        if (laneMatch == null || laneMatch.lane() == null || laneMatch.lane().start == null) {
            return;
        }
        final int x = (int) Math.floor(laneMatch.lane().start.x);
        final int y = (int) Math.floor(laneMatch.lane().start.y);
        final int z = (int) Math.floor(laneMatch.lane().start.z);
        PearlPlusPlugin.LOG.info("Moving to scan lane {}:{} at [{}, {}, {}]",
            location.zoneName, laneMatch.key(), x, y, z);
        try {
            BARITONE.pathTo(new GoalNear(x, y, z, CUSTOM_PATH_NEAR_RANGE_SQ))
                .get(PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final Exception e) {
            if (scanActive) {
                PearlPlusPlugin.LOG.debug("Failed to move to scan lane {}:{}", location.zoneName, laneMatch.key(), e);
            }
        }
    }

    private OpenedChest openChestContainer(final String chestKey, final ChestLocationData location) throws Exception {
        closeOpenContainerIfPresent();

        final int[][] chestBlocks = getChestBlocks(location);
        final int[] interactTarget = closestChestBlock(chestBlocks);
        if (interactTarget == null) {
            PearlPlusPlugin.LOG.warn("Could not determine interact target for chest {}", chestKey);
            return null;
        }
        final List<int[]> standPositions = findStandPositions(chestBlocks);
        if (standPositions.isEmpty()) {
            PearlPlusPlugin.LOG.warn("No reachable stand position found near chest {}", chestKey);
            return null;
        }

        if (!isPlayerInInteractRange(chestBlocks)) {
            boolean pathAccepted = false;
            int[] chosenStandPos = null;
            for (int i = 0; i < standPositions.size(); i++) {
                final int[] candidate = standPositions.get(i);
                if (!waitIfWithdrawPaused()) {
                    return null;
                }
                if (tryDirectMoveToNearbyStandPosition(candidate, chestKey)) {
                    pathAccepted = true;
                    chosenStandPos = candidate;
                    break;
                }

                final boolean reached;
                try {
                    reached = BARITONE.pathTo(candidate[0], candidate[1], candidate[2]).get(PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (final Exception e) {
                    if (waitIfWithdrawPausedAndRetry()) {
                        i--;
                        continue;
                    }
                    throw e;
                }
                if (!reached && waitIfWithdrawPausedAndRetry()) {
                    i--;
                    continue;
                }
                if (reached) {
                    pathAccepted = true;
                    chosenStandPos = candidate;
                    break;
                }
            }
            if (!pathAccepted) {
                final int[] firstCandidate = standPositions.getFirst();
                PearlPlusPlugin.LOG.warn("Baritone could not path to chest stand position {} via [{}, {}, {}]",
                    chestKey, firstCandidate[0], firstCandidate[1], firstCandidate[2]);
                return null;
            }
            PearlPlusPlugin.LOG.debug("Using stand position [{}, {}, {}] for chest {}", chosenStandPos[0], chosenStandPos[1], chosenStandPos[2], chestKey);
        }

        if (!waitIfWithdrawPaused()) {
            return null;
        }
        final Container container = openChestContainerWithFallbacks(chestKey, chestBlocks, interactTarget);
        if (container == null) {
            closeLateOpeningContainer(chestKey);
            PearlPlusPlugin.LOG.warn("Timed out waiting for chest container to open: {}", chestKey);
            return null;
        }

        final var containerInfo = ContainerTypeInfoRegistry.REGISTRY.get(container.getType());
        final int topSlots = containerInfo != null ? containerInfo.topSlots() : 0;
        if (topSlots <= 0) {
            PearlPlusPlugin.LOG.warn("Opened non-storage container {} type={}", chestKey, container.getType());
            closeContainer(container.getContainerId());
            return null;
        }

        return new OpenedChest(container, topSlots);
    }

    private Container openChestContainerWithFallbacks(final String chestKey,
                                                      final int[][] chestBlocks,
                                                      final int[] preferredTarget) {
        boolean sentAnyInteraction = false;
        for (final int[] target : orderedInteractTargets(chestBlocks, preferredTarget)) {
            if (!waitIfWithdrawPaused()) {
                return null;
            }
            if (!tryOpenChestBlock(target)) {
                continue;
            }
            sentAnyInteraction = true;

            final Container container = waitForOpenContainer(CONTAINER_OPEN_TIMEOUT_MS);
            if (container != null) {
                return container;
            }

            PearlPlusPlugin.LOG.debug("No container opened for {} via half [{}, {}, {}], trying fallback",
                chestKey, target[0], target[1], target[2]);
            closeOpenContainerIfPresent();
        }

        if (!sentAnyInteraction) {
            PearlPlusPlugin.LOG.warn("Direct chest interaction failed for {}", chestKey);
        }
        return null;
    }

    private List<int[]> orderedInteractTargets(final int[][] chestBlocks, final int[] preferredTarget) {
        final List<int[]> targets = new ArrayList<>();
        if (preferredTarget != null) {
            targets.add(preferredTarget);
        }
        for (final int[] chestBlock : chestBlocks) {
            if (chestBlock == preferredTarget) {
                continue;
            }
            targets.add(chestBlock);
        }
        return targets;
    }

    private boolean tryDirectMoveToNearbyStandPosition(final int[] candidate, final String chestKey) {
        if (candidate == null
            || CACHE == null
            || CACHE.getPlayerCache() == null
            || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }

        final int feetY = (int) Math.floor(CACHE.getPlayerCache().getThePlayer().getY());
        if (candidate[1] != feetY) {
            return false;
        }

        final double dx = (candidate[0] + 0.5D) - CACHE.getPlayerCache().getThePlayer().getX();
        final double dz = (candidate[2] + 0.5D) - CACHE.getPlayerCache().getThePlayer().getZ();
        final double distanceSq = dx * dx + dz * dz;
        if (distanceSq > CHEST_STAND_DIRECT_MOVE_MAX_DISTANCE_SQ) {
            return false;
        }

        final long timeoutMs = Math.min(
            CUSTOM_PATH_DIRECT_MOVE_TIMEOUT_MS,
            CHEST_STAND_DIRECT_MOVE_BASE_TIMEOUT_MS
                + (long) Math.ceil(Math.sqrt(distanceSq) * CHEST_STAND_DIRECT_MOVE_PER_BLOCK_TIMEOUT_MS)
        );
        return directMoveToHorizontalTarget(
            "nearby chest stand position for " + chestKey,
            candidate[0],
            candidate[2],
            timeoutMs,
            CUSTOM_PATH_CENTER_DISTANCE_SQ
        );
    }

    private ChestData openAndReadChest(final String chestKey, final ChestLocationData location) throws Exception {
        final OpenedChest opened = openChestContainer(chestKey, location);
        if (opened == null) {
            return null;
        }
        final Container container = opened.container();
        final int topSlots = opened.topSlots();

        final ChestData chestData = new ChestData(
            apiChestId(chestKey),
            location.blockType,
            location.x,
            location.y,
            location.z,
            location.doubleChest,
            location.dimension
        );
        chestData.zoneName = location.zoneName;
        chestData.zoneType = location.zoneType;

        for (int slot = 0; slot < topSlots; slot++) {
            final ItemStack stack = container.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            final ItemData itemData = ItemRegistry.REGISTRY.get(stack.getId());
            final String itemName = itemData != null ? itemData.name() : "unknown:" + stack.getId();
            final ChestData.ItemData storedItem = chestData.addItem(slot, itemName, stack.getAmount());
            populateContainerSummary(storedItem, itemData, stack);
        }

        closeContainer(container.getContainerId());
        return chestData;
    }

    private Container waitForOpenContainer(final long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && operationActive()) {
            final boolean wasPaused = withdrawActive && withdrawPauseRequested && !markerReturnForPearlLoad;
            if (!waitIfWithdrawPaused()) {
                return null;
            }
            if (wasPaused) {
                deadline = System.currentTimeMillis() + timeoutMs;
            }
            final var inventoryCache = CACHE.getPlayerCache().getInventoryCache();
            final int openContainerId = inventoryCache.getOpenContainerId();
            if (openContainerId != 0) {
                final Container container = inventoryCache.getOpenContainer();
                if (container != null && container.getContainerId() == openContainerId && container.getSize() > 0) {
                    return container;
                }
            }
            sleepSilently(CONTAINER_POLL_MS);
        }
        return null;
    }

    private void closeLateOpeningContainer(final String chestKey) {
        final long deadline = System.currentTimeMillis() + CONTAINER_LATE_OPEN_CLOSE_GRACE_MS;
        while (System.currentTimeMillis() < deadline) {
            final int openContainerId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
            if (openContainerId != 0) {
                PearlPlusPlugin.LOG.debug("Closing late-opening container {} after timeout for {}", openContainerId, chestKey);
                closeContainer(openContainerId);
                return;
            }
            sleepSilently(CONTAINER_POLL_MS);
        }
    }

    private void closeOpenContainerIfPresent() {
        final int openContainerId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (openContainerId != 0) {
            closeContainer(openContainerId);
        }
    }

    private void closeContainer(final int containerId) {
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(SCAN_ACTION_PRIORITY)
                    .actions(new CloseContainer(containerId))
                    .build())
                .get(5, TimeUnit.SECONDS);
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.warn("Error submitting close-container action {}", containerId, e);
        }

        final long deadline = System.currentTimeMillis() + CONTAINER_CLOSE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() == 0) {
                return;
            }
            sleepSilently(CONTAINER_POLL_MS);
        }
    }

    private String getBlockType(final int x, final int y, final int z) {
        try {
            final Block block = World.getBlock(x, y, z);
            if (block != BlockRegistry.CHEST && block != BlockRegistry.TRAPPED_CHEST) {
                return null;
            }

            final int blockStateId = World.getBlockStateId(x, y, z);
            final ChestType chestType = World.getBlockStateProperty(blockStateId, BlockStateProperties.CHEST_TYPE);
            if (chestType == null || chestType == ChestType.SINGLE) {
                return null;
            }

            if (block == BlockRegistry.CHEST) {
                return "DOUBLE_CHEST";
            }
            if (block == BlockRegistry.TRAPPED_CHEST) {
                return "DOUBLE_TRAPPED_CHEST";
            }
            return null;
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.debug("Failed to read block type at {},{},{}", x, y, z, e);
            return null;
        }
    }

    private void finishScan() {
        final boolean paused = pauseRequested && !cancelRequested;
        final boolean cancelled = cancelRequested;
        scanActive = false;
        BARITONE.stop();
        closeOpenContainerIfPresentSafely();
        clearScanLock();

        if (paused) {
            PearlPlusPlugin.LOG.info("Chest scan paused. {} chests read, {} remaining.",
                getReadChestCount(), getRemainingChestCount());
            return;
        }

        if (cancelled) {
            resetScanState();
            pauseRequested = false;
            cancelRequested = false;
            PearlPlusPlugin.LOG.info("Chest scan cancelled");
            return;
        }

        final List<ChestData> scanned;
        synchronized (scannedChests) {
            scanned = new ArrayList<>(scannedChests);
        }
        if (!scanned.isEmpty()) {
            sendInventoryToAPI(scanned);
        }

        PearlPlusPlugin.LOG.info("Chest scan completed. {} chests read. Coordinates kept local. Contents sent to API.", scanned.size());
        resetScanState();
        pauseRequested = false;
        cancelRequested = false;
    }

    private void pollWithdrawQueueSafely() {
        try {
            pollWithdrawQueue();
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.warn("Withdraw worker poll failed", e);
        }
    }

    private void pollWithdrawQueue() {
        if (!PearlPlusPlugin.PLUGIN_CONFIG.scanner.enabled || busyForNewWork()) {
            return;
        }
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return;
        }

        final WithdrawRequest request = claimNextWithdrawRequest();
        if (request == null) {
            return;
        }

        PearlPlusPlugin.LOG.info("Claimed withdraw request {}: {} for {} ({} indexed candidate chest(s))",
            request.requestId(), withdrawSummary(request), request.requesterName(), request.candidateChestIds().size());
        try {
            fulfillWithdrawRequest(request);
            updateWithdrawRequestStatus(request.requestId(), "FULFILLED", null,
                "Moved " + withdrawSummary(request) + " into withdrawal storage.");
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.error("Withdraw request {} failed", request.requestId(), e);
            updateWithdrawRequestStatus(request.requestId(), "FAILED", e.getMessage(), null);
        } finally {
            withdrawActive = false;
            withdrawPauseRequested = false;
            synchronized (withdrawPauseMonitor) {
                withdrawPausedForPearl = false;
                withdrawPauseMonitor.notifyAll();
            }
            BARITONE.stop();
            clearScanLock();
        }
    }

    private WithdrawRequest claimNextWithdrawRequest() {
        try {
            final HttpResponse<String> response = sendWorkerRequest("claim", new JsonObject());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                PearlPlusPlugin.LOG.warn("Withdraw claim failed with status {}: {}", response.statusCode(), response.body());
                return null;
            }
            final JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            final JsonElement requestElement = body.get("request");
            if (requestElement == null || requestElement.isJsonNull()) {
                return null;
            }
            final JsonObject request = requestElement.getAsJsonObject();
            final List<String> candidateChestIds = parseCandidateChestIds(request);
            final List<WithdrawItem> items = parseWithdrawItems(request, candidateChestIds);
            final int shulkerCount = request.has("shulkerCount") && !request.get("shulkerCount").isJsonNull()
                ? request.get("shulkerCount").getAsInt()
                : items.stream().mapToInt(WithdrawItem::shulkerCount).sum();
            return new WithdrawRequest(
                request.get("requestId").getAsString(),
                request.has("itemId") && !request.get("itemId").isJsonNull()
                    ? request.get("itemId").getAsString()
                    : (items.isEmpty() ? "" : items.getFirst().itemId()),
                shulkerCount,
                request.has("requesterName") && !request.get("requesterName").isJsonNull()
                    ? request.get("requesterName").getAsString()
                    : "unknown",
                candidateChestIds,
                items
            );
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.warn("Error claiming withdraw request", e);
            return null;
        }
    }

    private List<WithdrawItem> parseWithdrawItems(final JsonObject request, final List<String> fallbackCandidateChestIds) {
        final List<WithdrawItem> items = new ArrayList<>();
        if (request.has("items") && request.get("items").isJsonArray()) {
            for (final JsonElement itemElement : request.getAsJsonArray("items")) {
                if (itemElement == null || !itemElement.isJsonObject()) {
                    continue;
                }
                final JsonObject item = itemElement.getAsJsonObject();
                if (!item.has("itemId") || item.get("itemId").isJsonNull()
                    || !item.has("shulkerCount") || item.get("shulkerCount").isJsonNull()) {
                    continue;
                }
                final int shulkerCount = item.get("shulkerCount").getAsInt();
                if (shulkerCount <= 0) {
                    continue;
                }
                List<String> candidateChestIds = parseCandidateChestIds(item);
                if (candidateChestIds.isEmpty()) {
                    candidateChestIds = fallbackCandidateChestIds;
                }
                items.add(new WithdrawItem(item.get("itemId").getAsString(), shulkerCount, candidateChestIds));
            }
        }

        if (!items.isEmpty()) {
            return List.copyOf(items);
        }
        if (!request.has("itemId") || request.get("itemId").isJsonNull()
            || !request.has("shulkerCount") || request.get("shulkerCount").isJsonNull()) {
            return List.of();
        }
        final int shulkerCount = request.get("shulkerCount").getAsInt();
        if (shulkerCount <= 0) {
            return List.of();
        }
        return List.of(new WithdrawItem(request.get("itemId").getAsString(), shulkerCount, fallbackCandidateChestIds));
    }

    private List<String> parseCandidateChestIds(final JsonObject object) {
        final List<String> candidateChestIds = new ArrayList<>();
        final JsonArray candidateIds = object.has("candidateChestIds") && object.get("candidateChestIds").isJsonArray()
            ? object.getAsJsonArray("candidateChestIds")
            : null;
        if (candidateIds != null) {
            for (final JsonElement candidateId : candidateIds) {
                if (candidateId != null && !candidateId.isJsonNull()) {
                    candidateChestIds.add(candidateId.getAsString());
                }
            }
        }
        if (candidateChestIds.isEmpty()
            && object.has("candidateChests")
            && object.get("candidateChests").isJsonArray()) {
            for (final JsonElement candidateElement : object.getAsJsonArray("candidateChests")) {
                if (candidateElement == null || !candidateElement.isJsonObject()) {
                    continue;
                }
                final JsonObject candidate = candidateElement.getAsJsonObject();
                if (candidate.has("chestId") && !candidate.get("chestId").isJsonNull()) {
                    candidateChestIds.add(candidate.get("chestId").getAsString());
                }
            }
        }
        return List.copyOf(candidateChestIds);
    }

    private void updateWithdrawRequestStatus(final String requestId,
                                             final String status,
                                             final String errorMessage,
                                             final String notes) {
        try {
            final JsonObject payload = new JsonObject();
            payload.addProperty("status", status);
            if (errorMessage != null && !errorMessage.isBlank()) {
                payload.addProperty("errorMessage", errorMessage);
            }
            if (notes != null && !notes.isBlank()) {
                payload.addProperty("notes", notes);
            }
            final HttpResponse<String> response = sendWorkerRequest(requestId + "/status", payload);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                PearlPlusPlugin.LOG.warn("Withdraw status update failed with status {}: {}", response.statusCode(), response.body());
            }
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.warn("Error updating withdraw request {}", requestId, e);
        }
    }

    public WithdrawRequest queueConsoleWithdrawRequest(final String itemId, final String amountText) throws Exception {
        final JsonObject payload = new JsonObject();
        payload.addProperty("itemId", itemId);
        payload.addProperty("amount", amountText);
        payload.addProperty("requesterName", "admin");

        final HttpResponse<String> response = sendApiKeyRequest("/api/withdraw/console", payload);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(apiErrorMessage(response));
        }

        final JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        final JsonObject request = body.getAsJsonObject("request");
        return new WithdrawRequest(
            request.get("requestId").getAsString(),
            request.get("itemId").getAsString(),
            request.get("shulkerCount").getAsInt(),
            request.has("requesterName") && !request.get("requesterName").isJsonNull()
                ? request.get("requesterName").getAsString()
                : "admin",
            List.of()
        );
    }

    private HttpResponse<String> sendWorkerRequest(final String path, final JsonObject payload) throws Exception {
        return sendApiKeyRequest("/api/withdraw/worker/" + path, payload);
    }

    private HttpResponse<String> sendApiKeyRequest(final String path, final JsonObject payload) throws Exception {
        final String apiKey = PearlPlusPlugin.PLUGIN_CONFIG.scanner.apiKey;
        final URI uri = URI.create(scannerApiBaseUrl() + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("X-API-Key", apiKey);
        }
        return httpClient.send(
            builder.POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private String apiErrorMessage(final HttpResponse<String> response) {
        try {
            final JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            final JsonElement error = body.get("error");
            if (error != null && !error.isJsonNull()) {
                return error.getAsString();
            }
        } catch (final Exception ignored) {
        }
        return "API request failed with status " + response.statusCode();
    }

    private String scannerApiBaseUrl() {
        final String endpoint = PearlPlusPlugin.PLUGIN_CONFIG.scanner.apiEndpoint;
        if (endpoint == null || endpoint.isBlank()) {
            return "http://127.0.0.1:3000";
        }
        final int apiIndex = endpoint.indexOf("/api/");
        if (apiIndex >= 0) {
            return endpoint.substring(0, apiIndex);
        }
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private void fulfillWithdrawRequest(final WithdrawRequest request) throws Exception {
        withdrawActive = true;
        createScanLock();
        final int[] marker = parseConfiguredMarkerPos();
        if (marker != null) {
            markerX = marker[0];
            markerY = marker[1];
            markerZ = marker[2];
        }

        if (!moveThroughCustomPathIfEnabled()) {
            throw new IllegalStateException("Failed to reach storage area for withdrawal");
        }
        ensureWithdrawLocations();
        ensureIndexedStorageLocations(request);

        final Map<String, Integer> remaining = new LinkedHashMap<>();
        for (final WithdrawItem item : request.items()) {
            remaining.merge(item.itemId(), item.shulkerCount(), Integer::sum);
        }
        if (remaining.isEmpty()) {
            throw new IllegalStateException("Withdrawal request did not include any item lines");
        }
        int safetyPasses = 0;
        final Set<String> exhaustedWithdrawalChests = new HashSet<>();
        while (totalRemaining(remaining) > 0 && operationActive() && safetyPasses++ < request.totalShulkerCount() + 10) {
            if (!waitIfWithdrawPaused()) {
                break;
            }
            int carried = countCarriedRequestedShulkers(remaining);
            final int collected = takeRequestedShulkersFromStorage(request, remaining);
            if (collected > 0) {
                carried = countCarriedRequestedShulkers(remaining);
            }
            if (carried <= 0) {
                throw new IllegalStateException("No matching full shulker boxes found in indexed storage");
            }

            final int deposited = depositRequestedShulkersToWithdraw(request, remaining, exhaustedWithdrawalChests);
            if (deposited <= 0) {
                throw new IllegalStateException("No withdrawal chest space was reachable");
            }
            PearlPlusPlugin.LOG.info("Withdraw request {} progress: {} shulker(s) remaining", request.requestId(), totalRemaining(remaining));
        }

        if (totalRemaining(remaining) > 0) {
            throw new IllegalStateException("Withdrawal stopped with " + totalRemaining(remaining) + " shulker(s) remaining");
        }

        returnToMarkerAfterScan();
    }

    private void ensureWithdrawLocations() {
        final boolean hasWithdraw = localChestLocations.values().stream()
            .anyMatch(location -> "WITHDRAWAL".equals(location.zoneType));
        final boolean hasStorage = localChestLocations.values().stream()
            .anyMatch(location -> "STORAGE".equals(location.zoneType) || location.zoneType == null);
        if (hasWithdraw && hasStorage) {
            return;
        }

        localChestLocations.clear();
        scannedThisScan.clear();
        final String dimension = World.getCurrentDimension() != null ? World.getCurrentDimension().name() : "unknown";
        final int discovered = discoverChestLocations(dimension);
        PearlPlusPlugin.LOG.info("Withdraw worker discovered {} configured chest locations", discovered);
    }

    private List<Map.Entry<String, ChestLocationData>> storageLocations() {
        return localChestLocations.entrySet().stream()
            .filter(entry -> entry.getValue() != null)
            .filter(entry -> !"WITHDRAWAL".equals(entry.getValue().zoneType))
            .filter(entry -> !"IGNORE".equals(entry.getValue().zoneType))
            .sorted(Comparator.comparingDouble(entry -> distanceSqToPlayer(entry.getValue().x, entry.getValue().y, entry.getValue().z)))
            .toList();
    }

    private List<Map.Entry<String, ChestLocationData>> withdrawalLocations() {
        return localChestLocations.entrySet().stream()
            .filter(entry -> entry.getValue() != null && "WITHDRAWAL".equals(entry.getValue().zoneType))
            .sorted(Comparator.comparingDouble(entry -> distanceSqToPlayer(entry.getValue().x, entry.getValue().y, entry.getValue().z)))
            .toList();
    }

    private void ensureIndexedStorageLocations(final WithdrawRequest request) {
        for (final WithdrawItem item : request.items()) {
            if (indexedStorageLocations(item).isEmpty()) {
                throw new IllegalStateException("No indexed storage chest candidates matched the local scanner map for " + item.itemId() + "; run stashscan scan");
            }
        }
    }

    private List<Map.Entry<String, ChestLocationData>> indexedStorageLocations(final WithdrawItem item) {
        final List<Map.Entry<String, ChestLocationData>> storage = storageLocations();
        if (item.candidateChestIds() == null || item.candidateChestIds().isEmpty()) {
            return List.of();
        }

        final Map<String, Integer> candidateRank = new HashMap<>();
        for (int i = 0; i < item.candidateChestIds().size(); i++) {
            candidateRank.putIfAbsent(item.candidateChestIds().get(i), i);
        }

        final List<Map.Entry<String, ChestLocationData>> matched = storage.stream()
            .filter(entry -> candidateRank.containsKey(apiChestId(entry.getKey())))
            .sorted(Comparator
                .comparingInt((Map.Entry<String, ChestLocationData> entry) -> candidateRank.get(apiChestId(entry.getKey())))
                .thenComparingDouble(entry -> distanceSqToPlayer(entry.getValue().x, entry.getValue().y, entry.getValue().z)))
            .toList();
        PearlPlusPlugin.LOG.info("Matched {} of {} indexed candidate chest IDs to local storage locations for {}",
            matched.size(), candidateRank.size(), item.itemId());
        return matched;
    }

    private int takeRequestedShulkersFromStorage(final WithdrawRequest request,
                                                 final Map<String, Integer> remaining) throws Exception {
        int moved = 0;
        for (final WithdrawItem item : request.items()) {
            if (!waitIfWithdrawPaused()) {
                break;
            }
            final int remainingForItem = remaining.getOrDefault(item.itemId(), 0);
            if (remainingForItem <= 0) {
                continue;
            }
            final int alreadyCarried = countCarriedTargetShulkers(item.itemId());
            final int needed = remainingForItem - alreadyCarried;
            if (needed <= 0) {
                continue;
            }
            final int freeInventorySlots = Math.max(0, 36 - occupiedPlayerInventorySlots());
            if (freeInventorySlots <= 0) {
                if (moved > 0 || countCarriedRequestedShulkers(remaining) > 0) {
                    break;
                }
                throw new IllegalStateException("No free player inventory slots for withdrawal");
            }
            moved += takeTargetShulkersFromStorage(item, Math.min(needed, freeInventorySlots));
        }
        return moved;
    }

    private int takeTargetShulkersFromStorage(final WithdrawItem item, final int maxCount) throws Exception {
        int moved = 0;
        final List<Map.Entry<String, ChestLocationData>> storage = indexedStorageLocations(item);
        if (storage.isEmpty()) {
            throw new IllegalStateException("No indexed storage chest candidates matched the local scanner map for " + item.itemId() + "; run stashscan scan");
        }
        PearlPlusPlugin.LOG.info("Opening {} indexed candidate chest(s) for full shulker(s) of {}",
            storage.size(), item.itemId());
        for (final Map.Entry<String, ChestLocationData> entry : storage) {
            if (!waitIfWithdrawPaused()) {
                break;
            }
            if (moved >= maxCount || !operationActive()) {
                break;
            }
            final int freeInventorySlots = Math.max(0, 36 - occupiedPlayerInventorySlots());
            if (freeInventorySlots <= 0) {
                if (moved > 0) {
                    PearlPlusPlugin.LOG.info("Player inventory full after collecting {} shulker(s); depositing this batch before continuing", moved);
                    break;
                }
                throw new IllegalStateException("No free player inventory slots for withdrawal");
            }
            moved += takeTargetShulkersFromChest(entry.getKey(), entry.getValue(), item.itemId(), Math.min(maxCount - moved, freeInventorySlots));
        }
        return moved;
    }

    private int depositRequestedShulkersToWithdraw(final WithdrawRequest request,
                                                  final Map<String, Integer> remaining,
                                                  final Set<String> exhaustedWithdrawalChests) throws Exception {
        int depositedTotal = 0;
        final Set<String> handledItems = new HashSet<>();
        for (final WithdrawItem item : request.items()) {
            if (!waitIfWithdrawPaused()) {
                break;
            }
            if (!handledItems.add(item.itemId())) {
                continue;
            }
            final int remainingForItem = remaining.getOrDefault(item.itemId(), 0);
            if (remainingForItem <= 0) {
                continue;
            }
            final int carried = countCarriedTargetShulkers(item.itemId());
            if (carried <= 0) {
                continue;
            }
            final int deposited = depositTargetShulkersToWithdraw(
                item.itemId(),
                Math.min(remainingForItem, carried),
                exhaustedWithdrawalChests
            );
            if (deposited > 0) {
                remaining.put(item.itemId(), Math.max(0, remainingForItem - deposited));
                depositedTotal += deposited;
            }
        }
        return depositedTotal;
    }

    private int takeTargetShulkersFromChest(final String chestKey,
                                            final ChestLocationData location,
                                            final String itemId,
                                            final int maxCount) throws Exception {
        if (maxCount <= 0) {
            return 0;
        }
        final OpenedChest opened = openChestContainer(chestKey, location);
        if (opened == null) {
            return 0;
        }

        int moved = 0;
        try {
            final Container current = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
            if (current != null) {
                final List<Integer> slots = topTargetShulkerSlots(current, opened.topSlots(), itemId, maxCount);
                moved = shiftClickSlots(current.getContainerId(), slots);
            }
        } finally {
            closeContainer(opened.container().getContainerId());
        }
        return moved;
    }

    private int depositTargetShulkersToWithdraw(final String itemId,
                                               final int maxCount,
                                               final Set<String> exhaustedWithdrawalChests) throws Exception {
        int deposited = 0;
        for (final Map.Entry<String, ChestLocationData> entry : withdrawalLocations()) {
            if (!waitIfWithdrawPaused()) {
                break;
            }
            if (deposited >= maxCount || !operationActive()) {
                break;
            }
            if (exhaustedWithdrawalChests.contains(entry.getKey())) {
                continue;
            }
            final DepositResult result = depositTargetShulkersToChest(entry.getKey(), entry.getValue(), itemId, maxCount - deposited);
            deposited += result.deposited();
            if (result.exhausted()) {
                exhaustedWithdrawalChests.add(entry.getKey());
            }
        }
        return deposited;
    }

    private DepositResult depositTargetShulkersToChest(final String chestKey,
                                                       final ChestLocationData location,
                                                       final String itemId,
                                                       final int maxCount) throws Exception {
        if (maxCount <= 0) {
            return new DepositResult(0, false);
        }
        final OpenedChest opened = openChestContainer(chestKey, location);
        if (opened == null) {
            return new DepositResult(0, false);
        }

        int deposited = 0;
        boolean exhausted = false;
        try {
            final Container current = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
            if (current != null) {
                final int emptySlots = countEmptyTopSlots(current, opened.topSlots());
                if (emptySlots <= 0) {
                    return new DepositResult(0, true);
                }
                final List<Integer> slots = carriedTargetContainerSlots(opened.topSlots(), itemId, Math.min(maxCount, emptySlots));
                deposited = shiftClickSlots(current.getContainerId(), slots);
                exhausted = deposited >= emptySlots;
            }
        } finally {
            closeContainer(opened.container().getContainerId());
        }
        return new DepositResult(deposited, exhausted);
    }

    private int shiftClickSlots(final int containerId, final List<Integer> slots) throws Exception {
        if (slots.isEmpty()) {
            return 0;
        }

        int submitted = 0;
        while (submitted < slots.size() && operationActive()) {
            if (!waitIfWithdrawPaused()) {
                break;
            }
            final int batchSize = Math.min(WITHDRAW_CLICK_PACKET_LIMIT, slots.size() - submitted);
            if (!reserveWithdrawClickBudget(batchSize)) {
                break;
            }

            final InventoryAction[] actions = new InventoryAction[batchSize];
            for (int i = 0; i < batchSize; i++) {
                actions[i] = new ShiftClick(containerId, slots.get(submitted + i), ShiftClickItemAction.LEFT_CLICK);
            }

            final Boolean accepted = INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(WITHDRAW_ACTION_PRIORITY)
                    .actionDelayTicks(0)
                    .actions(actions)
                    .build())
                .get(5, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(accepted)) {
                throw new IllegalStateException("Inventory click burst was rejected");
            }

            submitted += batchSize;
            sleepSilently(WITHDRAW_BATCH_SETTLE_MS);
        }
        return submitted;
    }

    private boolean reserveWithdrawClickBudget(final int packetCount) {
        while (operationActive()) {
            if (!waitIfWithdrawPaused()) {
                return false;
            }
            long sleepMs = 0L;
            synchronized (withdrawClickPacketTimes) {
                final long now = System.currentTimeMillis();
                while (!withdrawClickPacketTimes.isEmpty()
                    && now - withdrawClickPacketTimes.peekFirst() >= WITHDRAW_CLICK_PACKET_WINDOW_MS) {
                    withdrawClickPacketTimes.removeFirst();
                }
                if (withdrawClickPacketTimes.size() + packetCount <= WITHDRAW_CLICK_PACKET_LIMIT) {
                    for (int i = 0; i < packetCount; i++) {
                        withdrawClickPacketTimes.addLast(now);
                    }
                    return true;
                }

                final Long oldest = withdrawClickPacketTimes.peekFirst();
                if (oldest != null) {
                    sleepMs = Math.max(25L, WITHDRAW_CLICK_PACKET_WINDOW_MS - (now - oldest) + 25L);
                }
            }
            sleepSilently(Math.min(sleepMs == 0L ? 100L : sleepMs, 250L));
        }
        return false;
    }

    private List<Integer> topTargetShulkerSlots(final Container container, final int topSlots, final String itemId, final int maxCount) {
        final List<Integer> slots = new ArrayList<>();
        if (container == null) {
            return slots;
        }
        int count = 0;
        for (int slot = 0; slot < topSlots; slot++) {
            final ItemStack stack = container.getItemStack(slot);
            if (isFullShulkerOfItem(stack, itemId)) {
                slots.add(slot);
                count += Math.max(1, stack.getAmount());
                if (count >= maxCount) {
                    break;
                }
            }
        }
        return slots;
    }

    private List<Integer> bottomTargetShulkerSlots(final Container container, final int topSlots, final String itemId, final int maxCount) {
        final List<Integer> slots = new ArrayList<>();
        if (container == null) {
            return slots;
        }
        int count = 0;
        for (int slot = topSlots; slot < container.getSize(); slot++) {
            final ItemStack stack = container.getItemStack(slot);
            if (isFullShulkerOfItem(stack, itemId)) {
                slots.add(slot);
                count += Math.max(1, stack.getAmount());
                if (count >= maxCount) {
                    break;
                }
            }
        }
        return slots;
    }

    private List<Integer> carriedTargetContainerSlots(final int topSlots, final String itemId, final int maxCount) {
        final List<Integer> slots = new ArrayList<>();
        final var playerInventory = CACHE.getPlayerCache().getPlayerInventory();
        if (playerInventory == null) {
            return slots;
        }
        int count = 0;
        for (int playerSlot = 9; playerSlot < Math.min(playerInventory.size(), 45); playerSlot++) {
            final ItemStack stack = playerInventory.get(playerSlot);
            if (isFullShulkerOfItem(stack, itemId)) {
                slots.add(topSlots + (playerSlot - 9));
                count += Math.max(1, stack.getAmount());
                if (count >= maxCount) {
                    break;
                }
            }
        }
        return slots;
    }

    private int countEmptyTopSlots(final int topSlots) {
        final Container container = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        return countEmptyTopSlots(container, topSlots);
    }

    private int countEmptyTopSlots(final Container container, final int topSlots) {
        if (container == null) {
            return 0;
        }
        int empty = 0;
        for (int slot = 0; slot < topSlots; slot++) {
            final ItemStack stack = container.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) {
                empty++;
            }
        }
        return empty;
    }

    private int occupiedPlayerInventorySlots() {
        final var playerInventory = CACHE.getPlayerCache().getPlayerInventory();
        if (playerInventory == null) {
            return 36;
        }
        int occupied = 0;
        for (int slot = 9; slot < Math.min(playerInventory.size(), 45); slot++) {
            final ItemStack stack = playerInventory.get(slot);
            if (stack != null && stack.getAmount() > 0) {
                occupied++;
            }
        }
        return occupied;
    }

    private int countCarriedTargetShulkers(final String itemId) {
        final var playerInventory = CACHE.getPlayerCache().getPlayerInventory();
        if (playerInventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 9; slot < Math.min(playerInventory.size(), 45); slot++) {
            final ItemStack stack = playerInventory.get(slot);
            if (isFullShulkerOfItem(stack, itemId)) {
                count += Math.max(1, stack.getAmount());
            }
        }
        return count;
    }

    private int countCarriedRequestedShulkers(final Map<String, Integer> remaining) {
        int count = 0;
        for (final String itemId : remaining.keySet()) {
            final int remainingForItem = remaining.getOrDefault(itemId, 0);
            if (remainingForItem <= 0) {
                continue;
            }
            count += Math.min(remainingForItem, countCarriedTargetShulkers(itemId));
        }
        return count;
    }

    private int totalRemaining(final Map<String, Integer> remaining) {
        int total = 0;
        for (final int count : remaining.values()) {
            total += Math.max(0, count);
        }
        return total;
    }

    private String withdrawSummary(final WithdrawRequest request) {
        if (request.items().size() <= 1) {
            final WithdrawItem item = request.items().isEmpty()
                ? new WithdrawItem(request.itemId(), request.shulkerCount(), request.candidateChestIds())
                : request.items().getFirst();
            return item.shulkerCount() + " shulker(s) of " + item.itemId();
        }
        final StringBuilder summary = new StringBuilder();
        for (final WithdrawItem item : request.items()) {
            if (!summary.isEmpty()) {
                summary.append(", ");
            }
            summary.append(item.shulkerCount()).append(" shulker(s) of ").append(item.itemId());
        }
        return summary.toString();
    }

    private boolean isFullShulkerOfItem(final ItemStack stack, final String itemId) {
        if (stack == null || stack.getAmount() <= 0 || stack.getDataComponents() == null) {
            return false;
        }
        final ItemData itemData = ItemRegistry.REGISTRY.get(stack.getId());
        if (itemData == null || !itemData.itemTags().contains(ItemTags.SHULKER_BOXES)) {
            return false;
        }
        final List<ItemStack> containerItems = stack.getDataComponents().get(DataComponentTypes.CONTAINER);
        if (containerItems == null) {
            return false;
        }
        int slotsUsed = 0;
        for (final ItemStack containerStack : containerItems) {
            if (containerStack == null || containerStack.getAmount() <= 0) {
                continue;
            }
            slotsUsed++;
            final ItemData nestedItemData = ItemRegistry.REGISTRY.get(containerStack.getId());
            final String nestedItemId = nestedItemData != null ? nestedItemData.name() : "unknown:" + containerStack.getId();
            if (!itemKey(nestedItemId).equals(itemKey(itemId))) {
                return false;
            }
        }
        return slotsUsed == SHULKER_CONTAINER_CAPACITY;
    }

    private String itemKey(final String itemId) {
        if (itemId == null) {
            return "";
        }
        final String normalized = itemId.trim().toLowerCase(Locale.ROOT);
        final int colon = normalized.indexOf(':');
        return colon >= 0 ? normalized.substring(colon + 1) : normalized;
    }

    private void sendInventoryToAPI(final List<ChestData> inventoryData) {
        executor.execute(() -> {
            try {
                final String endpoint = PearlPlusPlugin.PLUGIN_CONFIG.scanner.apiEndpoint;
                final String strategy = PearlPlusPlugin.PLUGIN_CONFIG.scanner.mergeStrategy;
                final String apiKey = PearlPlusPlugin.PLUGIN_CONFIG.scanner.apiKey;

                final JsonObject payload = new JsonObject();
                payload.addProperty("timestamp", System.currentTimeMillis());
                final String dim = inventoryData.getFirst().dimension != null ? inventoryData.getFirst().dimension : "unknown";
                payload.addProperty("dimension", dim);
                payload.addProperty("mergeStrategy", strategy);

                final JsonArray inventoryArray = new JsonArray();
                for (final ChestData chest : inventoryData) {
                    final JsonObject inventoryObj = new JsonObject();
                    inventoryObj.addProperty("chestId", chest.chestKey);
                    inventoryObj.addProperty("type", chest.blockType);
                    if (chest.zoneName != null && !chest.zoneName.isEmpty()) {
                        inventoryObj.addProperty("zoneName", chest.zoneName);
                    }
                    if (chest.zoneType != null && !chest.zoneType.isEmpty()) {
                        inventoryObj.addProperty("zoneType", chest.zoneType);
                    }

                    final JsonArray itemsArray = new JsonArray();
                    for (final Map.Entry<Integer, ChestData.ItemData> itemEntry : chest.items.entrySet()) {
                        final JsonObject itemObj = new JsonObject();
                        itemObj.addProperty("slot", itemEntry.getKey());
                        itemObj.addProperty("id", itemEntry.getValue().itemId);
                        itemObj.addProperty("count", itemEntry.getValue().count);
                        if (itemEntry.getValue().containerSlotsUsed != null) {
                            itemObj.addProperty("containerSlotsUsed", itemEntry.getValue().containerSlotsUsed);
                        }
                        if (itemEntry.getValue().containerCapacity != null) {
                            itemObj.addProperty("containerCapacity", itemEntry.getValue().containerCapacity);
                        }
                        if (itemEntry.getValue().fullOfItemId != null && !itemEntry.getValue().fullOfItemId.isEmpty()) {
                            itemObj.addProperty("fullOfItemId", itemEntry.getValue().fullOfItemId);
                        }
                        if (itemEntry.getValue().containerItemTotals != null && !itemEntry.getValue().containerItemTotals.isEmpty()) {
                            final JsonObject nestedTotalsObj = new JsonObject();
                            for (final Map.Entry<String, Integer> nestedEntry : itemEntry.getValue().containerItemTotals.entrySet()) {
                                nestedTotalsObj.addProperty(nestedEntry.getKey(), nestedEntry.getValue());
                            }
                            itemObj.add("containerItemTotals", nestedTotalsObj);
                        }
                        itemsArray.add(itemObj);
                    }
                    inventoryObj.add("items", itemsArray);
                    inventoryArray.add(inventoryObj);
                }
                payload.add("inventory", inventoryArray);

                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");

                if (apiKey != null && !apiKey.isEmpty()) {
                    builder.header("X-API-Key", apiKey);
                }

                final HttpRequest request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    PearlPlusPlugin.LOG.info("Inventory data sent to API (location data kept local)");
                } else {
                    PearlPlusPlugin.LOG.error("API returned status {}: {}", response.statusCode(), response.body());
                }
            } catch (final Exception e) {
                PearlPlusPlugin.LOG.error("Error sending inventory to API", e);
            }
        });
    }

    private String generateChestKey(final int x, final int y, final int z, final String dimension) {
        final int blockStateId = World.getBlockStateId(x, y, z);
        final ChestType chestType = World.getBlockStateProperty(blockStateId, BlockStateProperties.CHEST_TYPE);
        final Direction facing = World.getBlockStateProperty(blockStateId, BlockStateProperties.HORIZONTAL_FACING);
        if (chestType == null || facing == null || chestType == ChestType.SINGLE) {
            return String.format("%s:%d_%d_%d", dimension, x, y, z);
        }

        final int[] partner = findDoubleChestPartner(x, y, z, chestType, facing);
        final int ax;
        final int ay;
        final int az;
        final int bx;
        final int by;
        final int bz;

        if (compareCoords(x, y, z, partner[0], partner[1], partner[2]) <= 0) {
            ax = x;
            ay = y;
            az = z;
            bx = partner[0];
            by = partner[1];
            bz = partner[2];
        } else {
            ax = partner[0];
            ay = partner[1];
            az = partner[2];
            bx = x;
            by = y;
            bz = z;
        }

        return String.format("%s:%d_%d_%d|%d_%d_%d", dimension, ax, ay, az, bx, by, bz);
    }

    private String apiChestId(final String localChestKey) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(localChestKey.getBytes(StandardCharsets.UTF_8));
            return "ID_" + HexFormat.of().formatHex(hash, 0, 16);
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.warn("Failed hashing chest key for API; using local key fallback", e);
            return localChestKey;
        }
    }

    private int[] findDoubleChestPartner(final int x, final int y, final int z, final ChestType chestType, final Direction facing) {
        final Direction left = rotateLeft(facing);
        final Direction right = rotateRight(facing);
        final Direction offset = chestType == ChestType.LEFT ? right : left;
        return new int[]{x + offset.x(), y + offset.y(), z + offset.z()};
    }

    private Direction rotateLeft(final Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.WEST;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            case WEST -> Direction.SOUTH;
            default -> facing;
        };
    }

    private Direction rotateRight(final Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case EAST -> Direction.SOUTH;
            case WEST -> Direction.NORTH;
            default -> facing;
        };
    }

    private int compareCoords(final int ax, final int ay, final int az, final int bx, final int by, final int bz) {
        if (ax != bx) {
            return Integer.compare(ax, bx);
        }
        if (ay != by) {
            return Integer.compare(ay, by);
        }
        return Integer.compare(az, bz);
    }

    private double distanceSqToPlayer(final int x, final int y, final int z) {
        final double dx = CACHE.getPlayerCache().getThePlayer().getX() - x;
        final double dy = CACHE.getPlayerCache().getThePlayer().getY() - y;
        final double dz = CACHE.getPlayerCache().getThePlayer().getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private int[][] getChestBlocks(final ChestLocationData location) {
        final String dimension = location.dimension;
        final String chestKey = generateChestKey(location.x, location.y, location.z, dimension);
        if (!chestKey.contains("|")) {
            return new int[][]{{location.x, location.y, location.z}};
        }
        final String coordSection = chestKey.substring(chestKey.indexOf(':') + 1);
        final String[] halves = coordSection.split("\\|");
        final int[][] result = new int[halves.length][3];
        for (int i = 0; i < halves.length; i++) {
            final String[] parts = halves[i].split("_");
            result[i][0] = Integer.parseInt(parts[0]);
            result[i][1] = Integer.parseInt(parts[1]);
            result[i][2] = Integer.parseInt(parts[2]);
        }
        return result;
    }

    private int[] closestChestBlock(final int[][] chestBlocks) {
        int[] best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (final int[] chestBlock : chestBlocks) {
            final double distSq = distanceSqToPlayer(chestBlock[0], chestBlock[1], chestBlock[2]);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = chestBlock;
            }
        }
        return best;
    }

    private List<int[]> findStandPositions(final int[][] chestBlocks) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (final int[] chestBlock : chestBlocks) {
            minX = Math.min(minX, chestBlock[0]);
            maxX = Math.max(maxX, chestBlock[0]);
            minY = Math.min(minY, chestBlock[1]);
            maxY = Math.max(maxY, chestBlock[1]);
            minZ = Math.min(minZ, chestBlock[2]);
            maxZ = Math.max(maxZ, chestBlock[2]);
        }

        final List<int[]> candidates = new ArrayList<>();
        for (int x = minX - 3; x <= maxX + 3; x++) {
            for (int z = minZ - 3; z <= maxZ + 3; z++) {
                for (int feetY = minY - 2; feetY <= maxY + 2; feetY++) {
                    final int groundY = feetY - 1;
                    if (!isStandableGround(x, groundY, z)) {
                        continue;
                    }
                    if (!isAirLike(x, feetY, z) || !isAirLike(x, feetY + 1, z)) {
                        continue;
                    }
                    if (!isWithinInteractRange(x, feetY, z, chestBlocks)) {
                        continue;
                    }
                    candidates.add(new int[]{x, feetY, z});
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> distanceSqToPlayer(candidate[0], candidate[1], candidate[2])));
        return candidates;
    }

    private boolean isPlayerInInteractRange(final int[][] chestBlocks) {
        final int feetX = (int) Math.floor(CACHE.getPlayerCache().getThePlayer().getX());
        final int feetY = (int) Math.floor(CACHE.getPlayerCache().getThePlayer().getY());
        final int feetZ = (int) Math.floor(CACHE.getPlayerCache().getThePlayer().getZ());
        return isWithinInteractRange(feetX, feetY, feetZ, chestBlocks);
    }

    private boolean isWithinInteractRange(final int feetX, final int feetY, final int feetZ, final int[][] chestBlocks) {
        for (final int[] chestBlock : chestBlocks) {
            if (distanceSqToBlockCenter(feetX, feetY, feetZ, chestBlock[0], chestBlock[1], chestBlock[2]) <= CHEST_INTERACT_DISTANCE_SQ) {
                return true;
            }
        }
        return false;
    }

    private boolean isAirLike(final int x, final int y, final int z) {
        final Block block = World.getBlock(x, y, z);
        if (block == null) {
            return false;
        }
        return block.isAir();
    }

    private boolean isStandableGround(final int x, final int y, final int z) {
        final Block block = World.getBlock(x, y, z);
        if (block == null || block.isAir()) {
            return false;
        }
        final String name = block.name();
        return !name.contains("water")
            && !name.contains("lava")
            && !name.endsWith("_trapdoor")
            && !name.contains("ladder")
            && !name.contains("vine")
            && !name.contains("scaffolding");
    }

    private double distanceSqToBlockCenter(final int feetX, final int feetY, final int feetZ,
                                           final int blockX, final int blockY, final int blockZ) {
        final double dx = (feetX + 0.5D) - (blockX + 0.5D);
        final double dy = (feetY + 0.5D) - (blockY + 0.5D);
        final double dz = (feetZ + 0.5D) - (blockZ + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean tryOpenChestBlock(final int[] blockPos) {
        if (blockPos == null) {
            return false;
        }
        try {
            if (!waitIfWithdrawPaused()) {
                return false;
            }
            final var rotation = RotationHelper.rotationTo(blockPos[0] + 0.5D, blockPos[1] + 0.5D, blockPos[2] + 0.5D);
            final float yaw = rotation.getX();
            final float pitch = rotation.getY();
            final InputRequest rotateOnly = InputRequest.builder()
                .owner(this)
                .input(Input.builder().build())
                .yaw(yaw)
                .pitch(pitch)
                .priority(SCAN_ACTION_PRIORITY)
                .build();
            INPUTS.submit(rotateOnly).get(2, TimeUnit.SECONDS);

            if (!waitIfWithdrawPaused()) {
                return false;
            }
            final Block block = World.getBlock(blockPos[0], blockPos[1], blockPos[2]);
            if (block == null) {
                return false;
            }
            final BlockRaycastResult hit = buildBlockInteractResult(blockPos[0], blockPos[1], blockPos[2], block);
            final Method useItemOn = BOT.getInteractions().getClass().getDeclaredMethod("useItemOn", Hand.class, BlockRaycastResult.class);
            useItemOn.setAccessible(true);
            useItemOn.invoke(BOT.getInteractions(), Hand.MAIN_HAND, hit);
            return true;
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.debug("Direct chest interaction failed at [{}, {}, {}]", blockPos[0], blockPos[1], blockPos[2], e);
            return false;
        }
    }

    private void populateContainerSummary(final ChestData.ItemData storedItem,
                                          final ItemData itemData,
                                          final ItemStack stack) {
        if (storedItem == null || itemData == null || stack == null || stack.getDataComponents() == null) {
            return;
        }
        if (!itemData.itemTags().contains(ItemTags.SHULKER_BOXES)) {
            return;
        }

        final List<ItemStack> containerItems = stack.getDataComponents().get(DataComponentTypes.CONTAINER);
        if (containerItems == null || containerItems.isEmpty()) {
            storedItem.setContainerSummary(0, SHULKER_CONTAINER_CAPACITY, null, Map.of());
            return;
        }

        int slotsUsed = 0;
        String uniformItemId = null;
        boolean uniform = true;
        final Map<String, Integer> totals = new HashMap<>();

        for (final ItemStack containerStack : containerItems) {
            if (containerStack == null || containerStack.getAmount() <= 0) {
                continue;
            }
            slotsUsed++;
            final ItemData containerItemData = ItemRegistry.REGISTRY.get(containerStack.getId());
            final String nestedItemId = containerItemData != null ? containerItemData.name() : "unknown:" + containerStack.getId();
            totals.merge(nestedItemId, containerStack.getAmount(), Integer::sum);

            if (uniformItemId == null) {
                uniformItemId = nestedItemId;
            } else if (!uniformItemId.equals(nestedItemId)) {
                uniform = false;
            }
        }

        final String fullOfItemId = (slotsUsed == SHULKER_CONTAINER_CAPACITY && uniform) ? uniformItemId : null;
        storedItem.setContainerSummary(slotsUsed, SHULKER_CONTAINER_CAPACITY, fullOfItemId, totals);
    }

    private BlockRaycastResult buildBlockInteractResult(final int x, final int y, final int z, final Block block) {
        final double playerX = CACHE.getPlayerCache().getThePlayer().getX();
        final double playerZ = CACHE.getPlayerCache().getThePlayer().getZ();
        final double centerX = x + 0.5D;
        final double centerY = y + 0.5D;
        final double centerZ = z + 0.5D;
        final double dx = playerX - centerX;
        final double dz = playerZ - centerZ;

        final Direction face;
        final double hitX;
        final double hitY;
        final double hitZ;
        final double edge = 0.05D;

        // Chests stacked vertically or covered by stairs/slabs are still normally opened from a
        // horizontal face. Using UP/DOWN here can target an occluded face and cause the upper row
        // to fail even though the chest is reachable from the side.
        if (Math.abs(dx) >= Math.abs(dz)) {
            face = dx > 0 ? Direction.EAST : Direction.WEST;
            hitX = dx > 0 ? x + 1.0D - edge : x + edge;
            hitY = centerY;
            hitZ = centerZ;
        } else {
            face = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            hitX = centerX;
            hitY = centerY;
            hitZ = dz > 0 ? z + 1.0D - edge : z + edge;
        }

        return new BlockRaycastResult(
            true,
            x,
            y,
            z,
            new RayIntersection(hitX, hitY, hitZ, face),
            block
        );
    }

    private void createScanLock() {
        try {
            Files.writeString(SCAN_LOCK_PATH, Long.toString(System.currentTimeMillis()));
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.warn("Failed creating external scan lock {}", SCAN_LOCK_PATH, e);
        }
    }

    private void clearScanLock() {
        try {
            Files.deleteIfExists(SCAN_LOCK_PATH);
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.warn("Failed clearing external scan lock {}", SCAN_LOCK_PATH, e);
        }
    }

    private void sleepSilently(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void cancelScan() {
        final boolean wasActive = busyForNewWork();
        scanActive = false;
        withdrawActive = false;
        markerReturnActive = false;
        markerReturnForPearlLoad = false;
        cancelRequested = true;
        pauseRequested = false;
        withdrawPauseRequested = false;
        synchronized (withdrawPauseMonitor) {
            withdrawPausedForPearl = false;
            withdrawPauseMonitor.notifyAll();
        }
        BARITONE.stop();
        stopDirectMovement();
        closeOpenContainerIfPresentSafely();
        clearScanLock();
        if (!wasActive) {
            resetScanState();
            cancelRequested = false;
            PearlPlusPlugin.LOG.info("Chest scan cancelled");
        }
    }

    public synchronized boolean pauseForPearlRequest() {
        if (withdrawActive) {
            return pauseWithdrawForPearlRequest();
        }

        if (!scanActive) {
            return false;
        }
        pauseRequested = true;
        cancelRequested = false;
        scanActive = false;
        BARITONE.stop();
        closeOpenContainerIfPresentSafely();
        clearScanLock();
        waitForScanTaskToSettle(3L);
        closeLateOpeningContainer("scan pause");
        closeOpenContainerIfPresentSafely();
        return true;
    }

    private boolean pauseWithdrawForPearlRequest() {
        withdrawPauseRequested = true;
        BARITONE.stop();
        stopDirectMovement();
        closeOpenContainerIfPresentSafely();
        clearScanLock();

        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(6L);
        synchronized (withdrawPauseMonitor) {
            while (withdrawActive && !withdrawPausedForPearl && System.currentTimeMillis() < deadline) {
                try {
                    withdrawPauseMonitor.wait(50L);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!withdrawPausedForPearl && withdrawActive) {
            PearlPlusPlugin.LOG.warn("Withdraw worker did not acknowledge pearl-load pause before timeout; continuing pearl load anyway");
        }
        return true;
    }

    public synchronized boolean resumePausedScan() {
        if (withdrawPauseRequested || withdrawPausedForPearl) {
            withdrawPauseRequested = false;
            synchronized (withdrawPauseMonitor) {
                withdrawPauseMonitor.notifyAll();
            }
            PearlPlusPlugin.LOG.info("Withdraw worker resume requested after pearl load");
            return true;
        }

        if (scanActive || !hasPausedScan()) {
            return false;
        }

        pauseRequested = false;
        cancelRequested = false;
        scanActive = true;
        createScanLock();
        scanTask = executor.submit(this::performScan);
        PearlPlusPlugin.LOG.info("Resuming paused chest scan with {} remaining targets", getRemainingChestCount());
        return true;
    }

    public boolean hasPausedScan() {
        return pauseRequested;
    }

    private void waitForScanTaskToSettle(final long seconds) {
        final Future<?> task = scanTask;
        if (task == null || task.isDone()) {
            return;
        }
        try {
            task.get(seconds, TimeUnit.SECONDS);
        } catch (final Exception ignored) {
            PearlPlusPlugin.LOG.debug("Scanner task did not settle within {} seconds after pause", seconds);
        }
    }

    private void closeOpenContainerIfPresentSafely() {
        try {
            if (CACHE != null && CACHE.getPlayerCache() != null) {
                closeOpenContainerIfPresent();
            }
        } catch (final Exception e) {
            PearlPlusPlugin.LOG.warn("Failed closing active container during scan interruption", e);
        }
    }

    public boolean isScanActive() {
        return scanActive || withdrawActive || markerReturnActive;
    }

    public boolean isScanPaused() {
        return hasPausedScan();
    }

    public int getReadChestCount() {
        synchronized (scannedChests) {
            return scannedChests.size();
        }
    }

    public int getRemainingChestCount() {
        return pendingChestKeys.size();
    }

    public void shutdown() {
        cancelScan();
        stopWithdrawWorker();
        clearScanLock();
        withdrawExecutor.shutdownNow();
        executor.shutdownNow();
    }

    private void resetScanState() {
        synchronized (scannedChests) {
            scannedChests.clear();
        }
        scannedThisScan.clear();
        readChestKeys.clear();
        pendingChestKeys.clear();
        localChestLocations.clear();
        discoveryComplete = false;
    }
}
