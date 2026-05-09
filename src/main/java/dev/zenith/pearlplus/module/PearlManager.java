package dev.zenith.pearlplus.module;

import com.zenith.Proxy;
import com.zenith.discord.Embed;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.util.ChatUtil;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.raycast.BlockRaycastResult;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.feature.player.raycast.RayIntersection;
import com.zenith.mc.block.Direction;
import com.zenith.module.impl.KillAura;
import org.cloudburstmc.math.vector.Vector2f;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import dev.zenith.pearlplus.PearlPlusConfig;
import com.zenith.module.api.Module;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.function.Predicate;

import static com.zenith.Globals.*;
import static dev.zenith.pearlplus.PearlPlusPlugin.LOG;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class PearlManager {
    private static final double TRAPDOOR_INTERACT_DISTANCE_SQ = 20.25D;
    private static final int PEARLPLUS_ACTION_PRIORITY = 1500;
    public static final String MESSAGE_PREFIX = "[SyntaxPearl] ";
    private static final long IDLE_HOME_INTERVAL_MS = 10_000L;
    private static final double HOME_REACHED_DISTANCE_SQ = 0.36D;
    private static final long TRAPDOOR_SEQUENCE_DELAY_MS = 75L;
    private static final long ACTION_TIMEOUT_MS = 15_000L;

    private final Module notifier;
    private int killAuraSuppressionDepth = 0;
    private boolean restoreKillAuraEnabled = false;
    private boolean actionInProgress = false;
    private long lastIdleHomeAttemptMs = 0L;
    private long trapdoorSequenceId = 0L;
    private long actionStartedAtMs = 0L;
    private String actionDescription = "";

    public PearlManager(Module notifier) {
        this.notifier = notifier;
    }

    public record PlayerPearl(UUID ownerUuid, String ownerName, PearlPlusConfig.StoredPearl pearl) {
    }

    public static String prefixMessage(final String message) {
        return MESSAGE_PREFIX + message;
    }

    public boolean isActionInProgress() {
        return actionInProgress;
    }

    private void beginAction(final String description) {
        actionInProgress = true;
        actionStartedAtMs = System.currentTimeMillis();
        actionDescription = description;
    }

    private void endAction() {
        actionInProgress = false;
        actionStartedAtMs = 0L;
        actionDescription = "";
    }

    public Optional<PlayerPearl> findPearl(UUID ownerUuid, String pearlId) {
        if (ownerUuid == null || pearlId == null || pearlId.isBlank()) {
            return Optional.empty();
        }
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return Optional.empty();
        PearlPlusConfig.StoredPearl stored = entry.pearls.get(pearlId);
        if (stored == null) return Optional.empty();
        return Optional.of(new PlayerPearl(ownerUuid, entry.playerName, stored));
    }

    public List<PearlPlusConfig.StoredPearl> listPearls(UUID ownerUuid) {
        if (ownerUuid == null) return List.of();
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return List.of();
        return new ArrayList<>(entry.pearls.values());
    }

    public PearlPlusConfig.StoredPearl recordPearl(UUID ownerUuid, String ownerName, String pearlId, int x, int y, int z) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.computeIfAbsent(ownerUuid, uuid -> new PearlPlusConfig.PlayerPearls());
        entry.playerName = ownerName;
        if (entry.defaultPearlId == null || entry.defaultPearlId.isBlank()) {
            entry.defaultPearlId = pearlId;
        }
        PearlPlusConfig.StoredPearl stored = entry.pearls.computeIfAbsent(pearlId, id -> new PearlPlusConfig.StoredPearl());
        stored.pearlId = pearlId;
        stored.x = x;
        stored.y = y;
        stored.z = z;
        return stored;
    }

    public void removePearl(UUID ownerUuid, String pearlId) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return;
        entry.pearls.remove(pearlId);
        if (pearlId != null && pearlId.equals(entry.defaultPearlId)) {
            entry.defaultPearlId = entry.pearls.keySet().stream().findFirst().orElse(null);
        }
        if (entry.pearls.isEmpty()) {
            PLUGIN_CONFIG.players.remove(ownerUuid);
        }
    }

    public boolean renamePearl(UUID ownerUuid, String oldPearlId, String newPearlId) {
        if (ownerUuid == null || oldPearlId == null || newPearlId == null
                || oldPearlId.isBlank() || newPearlId.isBlank()) {
            return false;
        }

        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return false;

        PearlPlusConfig.StoredPearl existing = entry.pearls.get(oldPearlId);
        if (existing == null) return false;
        if (entry.pearls.containsKey(newPearlId)) return false;

        entry.pearls.remove(oldPearlId);
        existing.pearlId = newPearlId;
        entry.pearls.put(newPearlId, existing);

        if (oldPearlId.equals(entry.defaultPearlId)) {
            entry.defaultPearlId = newPearlId;
        }
        return true;
    }

    public String defaultPearlId(UUID ownerUuid) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return null;
        String closestPresentPearl = closestPresentPearlIdToHome(entry);
        if (closestPresentPearl != null) {
            return closestPresentPearl;
        }

        String configuredDefault = entry.defaultPearlId;
        if (configuredDefault != null && entry.pearls.containsKey(configuredDefault)) {
            if (!PLUGIN_CONFIG.autoLoad.autoDefaultToPresent || isPearlPresent(entry.pearls.get(configuredDefault))) {
                return configuredDefault;
            }
        }

        if (PLUGIN_CONFIG.autoLoad.autoDefaultToPresent) {
            for (Map.Entry<String, PearlPlusConfig.StoredPearl> pearlEntry : entry.pearls.entrySet()) {
                if (isPearlPresent(pearlEntry.getValue())) {
                    return pearlEntry.getKey();
                }
            }
        }

        if (configuredDefault != null && entry.pearls.containsKey(configuredDefault)) {
            return configuredDefault;
        }

        return entry.pearls.keySet().stream().findFirst().orElse(null);
    }

    private String closestPresentPearlIdToHome(final PearlPlusConfig.PlayerPearls entry) {
        if (entry == null || entry.pearls == null || entry.pearls.isEmpty()) {
            return null;
        }

        double anchorX;
        double anchorY;
        double anchorZ;
        if (hasConfiguredHome()) {
            anchorX = PLUGIN_CONFIG.autoLoad.home.x;
            anchorY = PLUGIN_CONFIG.autoLoad.home.y;
            anchorZ = PLUGIN_CONFIG.autoLoad.home.z;
        } else if (CACHE != null && CACHE.getPlayerCache() != null && CACHE.getPlayerCache().getThePlayer() != null) {
            anchorX = CACHE.getPlayerCache().getThePlayer().getX();
            anchorY = CACHE.getPlayerCache().getThePlayer().getY();
            anchorZ = CACHE.getPlayerCache().getThePlayer().getZ();
        } else {
            return null;
        }

        String bestId = null;
        double bestDistSq = Double.MAX_VALUE;
        for (var pearlEntry : entry.pearls.entrySet()) {
            PearlPlusConfig.StoredPearl pearl = pearlEntry.getValue();
            if (!isPearlPresent(pearl)) {
                continue;
            }
            double dx = pearl.x - anchorX;
            double dy = pearl.y - anchorY;
            double dz = pearl.z - anchorZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestId = pearlEntry.getKey();
            }
        }
        return bestId;
    }

    public void setDefaultPearl(UUID ownerUuid, String pearlId) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null) return;
        if (pearlId != null && entry.pearls.containsKey(pearlId)) {
            entry.defaultPearlId = pearlId;
        }
    }

    public String resolvePearlId(UUID ownerUuid, String pearlId) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || pearlId == null) return null;
        return entry.pearls.keySet().stream()
                .filter(id -> id.equalsIgnoreCase(pearlId))
                .findFirst()
                .orElse(null);
    }

    public boolean isPearlPresent(PearlPlusConfig.StoredPearl pearl) {
        if (pearl == null || CACHE == null || CACHE.getEntityCache() == null) {
            return false;
        }
        if (!isWithinPresenceRange(pearl)) {
            return false;
        }
        return CACHE.getEntityCache().getEntities().values().stream()
                .anyMatch(entity -> entity.getEntityType() == org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType.ENDER_PEARL
                        && Math.floor(entity.getX()) == pearl.x
                        && Math.floor(entity.getZ()) == pearl.z);
    }

    private boolean isWithinPresenceRange(PearlPlusConfig.StoredPearl pearl) {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }

        var player = CACHE.getPlayerCache().getThePlayer();
        double dx = player.getX() - pearl.x;
        double dy = player.getY() - pearl.y;
        double dz = player.getZ() - pearl.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance <= PLUGIN_CONFIG.autoDetect.temporaryRemovalRange;
    }

    // find the nearest trapdoor block around the stored pearl location.
    private BlockPos findNearestTrapdoorAround(final PearlPlusConfig.StoredPearl pearl, final int radius) {
        if (pearl == null || CACHE == null || CACHE.getChunkCache() == null) {
            return null;
        }

        var chunkCache = CACHE.getChunkCache();

        final int baseX = pearl.x;
        final int baseY = pearl.y;
        final int baseZ = pearl.z;

        BlockPos bestPos = null;
        int bestDistSq = Integer.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            final int x = baseX + dx;

            for (int dy = -radius; dy <= radius; dy++) {
                final int y = baseY + dy;

                for (int dz = -radius; dz <= radius; dz++) {
                    final int z = baseZ + dz;

                    var section = chunkCache.getChunkSection(x, y, z);
                    if (section == null) {
                        continue;
                    }

                    int relX = x & 15;
                    int relY = y & 15;
                    int relZ = z & 15;

                    int stateId = section.getBlock(relX, relY, relZ);
                    if (stateId == 0) {
                        continue; // air / unknown, skip quickly
                    }

                    var block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
                    if (block == null) {
                        continue;
                    }

                    String name = block.name();
                    if (!name.endsWith("_trapdoor")) {
                        continue;
                    }

                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestPos = new BlockPos(x, y, z);
                    }
                }
            }
        }

        return bestPos;
    }

    private boolean isTrapdoorAt(final int x, final int y, final int z) {
        if (CACHE == null || CACHE.getChunkCache() == null) {
            return false;
        }

        var section = CACHE.getChunkCache().getChunkSection(x, y, z);
        if (section == null) {
            return false;
        }

        int stateId = section.getBlock(x & 15, y & 15, z & 15);
        if (stateId == 0) {
            return false;
        }

        var block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
        return block != null && block.name().endsWith("_trapdoor");
    }

    private boolean isTrapdoorOpenAt(final int x, final int y, final int z) {
        if (CACHE == null || CACHE.getChunkCache() == null) {
            return false;
        }

        var section = CACHE.getChunkCache().getChunkSection(x, y, z);
        if (section == null) {
            return false;
        }

        int stateId = section.getBlock(x & 15, y & 15, z & 15);
        if (stateId == 0) {
            return false;
        }

        var block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
        if (block == null || !block.name().endsWith("_trapdoor")) {
            return false;
        }

        try {
            var boxes = BLOCK_DATA.localizeCollisionBoxes(
                    BLOCK_DATA.getCollisionBoxesFromBlockStateId(stateId),
                    block,
                    x,
                    y,
                    z
            );
            if (boxes == null || boxes.isEmpty()) {
                return false;
            }

            // Closed trapdoors are thin horizontal slabs; open trapdoors are thin vertical planes.
            boolean mostlyHorizontal = boxes.stream().anyMatch(box -> {
                double thicknessY = box.maxY() - box.minY();
                double spanX = box.maxX() - box.minX();
                double spanZ = box.maxZ() - box.minZ();
                return thicknessY <= 0.30D && spanX >= 0.90D && spanZ >= 0.90D;
            });
            return !mostlyHorizontal;
        } catch (Exception e) {
            LOG.warn("Failed inferring trapdoor state at [{}, {}, {}]", x, y, z, e);
            return false;
        }
    }

    private boolean isAirLike(final int x, final int y, final int z) {
        if (CACHE == null || CACHE.getChunkCache() == null) {
            return false;
        }

        var section = CACHE.getChunkCache().getChunkSection(x, y, z);
        if (section == null) {
            return false;
        }

        int stateId = section.getBlock(x & 15, y & 15, z & 15);
        if (stateId == 0) {
            return true;
        }

        var block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
        if (block == null) {
            return false;
        }

        String name = block.name();
        return name.contains("air")
                || name.contains("cave_air")
                || name.contains("void_air");
    }

    private boolean isStandableGround(final int x, final int y, final int z) {
        if (CACHE == null || CACHE.getChunkCache() == null) {
            return false;
        }

        var section = CACHE.getChunkCache().getChunkSection(x, y, z);
        if (section == null) {
            return false;
        }

        int stateId = section.getBlock(x & 15, y & 15, z & 15);
        if (stateId == 0) {
            return false;
        }

        var block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
        if (block == null) {
            return false;
        }

        String name = block.name();
        return !name.contains("water")
                && !name.contains("lava")
                && !name.endsWith("_trapdoor")
                && !name.contains("ladder")
                && !name.contains("vine")
                && !name.contains("scaffolding");
    }

    private double distanceSqToBlockCenter(final int ax, final int ay, final int az,
                                           final int bx, final int by, final int bz) {
        double dx = (ax + 0.5D) - (bx + 0.5D);
        double dy = (ay + 0.5D) - (by + 0.5D);
        double dz = (az + 0.5D) - (bz + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private double distanceSqToPoint(final int blockX, final int blockY, final int blockZ,
                                     final double pointX, final double pointY, final double pointZ) {
        double dx = (blockX + 0.5D) - pointX;
        double dy = (blockY + 0.5D) - pointY;
        double dz = (blockZ + 0.5D) - pointZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private void finishPearlLoad(final PearlPlusConfig.StoredPearl pearl, final String requesterName, final BlockPos startPos) {
        var builder = Embed.builder()
                .title("Pearl Loaded!")
                .addField("Pearl ID", pearl.pearlId, false)
                .successColor();
        if (requesterName != null) {
            builder.addField("Requested By", requesterName, false);
        }
        notifier.discordAndIngameNotification(builder);

        if (PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad) {
            handlePearlDropAfterLoad(requesterName);
        }

        returnAfterLoad(startPos);
    }

    private void returnAfterLoad(final BlockPos startPos) {
        if (PLUGIN_CONFIG.autoLoad.returnHomeEnabled && hasConfiguredHome()) {
            pathToConfiguredHome(false);
            return;
        }

        if (PLUGIN_CONFIG.autoLoad.returnToStartPos && startPos != null) {
            BARITONE.pathTo(startPos.x(), startPos.y(), startPos.z())
                    .addExecutedListener(f2 -> {
                        notifier.discordAndIngameNotification(
                                Embed.builder()
                                        .description("Returned to start pos")
                                        .successColor()
                        );
                        endAction();
                        releaseKillAuraSuppression();
                    });
            return;
        }

        endAction();
        releaseKillAuraSuppression();
    }

    private boolean hasConfiguredHome() {
        return PLUGIN_CONFIG.autoLoad.home.x != null
                && PLUGIN_CONFIG.autoLoad.home.y != null
                && PLUGIN_CONFIG.autoLoad.home.z != null;
    }

    private void pathToConfiguredHome(final boolean idleRecovery) {
        double homeX = PLUGIN_CONFIG.autoLoad.home.x;
        double homeY = PLUGIN_CONFIG.autoLoad.home.y;
        double homeZ = PLUGIN_CONFIG.autoLoad.home.z;

        int blockX = (int) Math.floor(homeX);
        int blockY = (int) Math.floor(homeY);
        int blockZ = (int) Math.floor(homeZ);

        info(String.format("Returning to configured home at [%.3f, %.3f, %.3f]", homeX, homeY, homeZ));
        BARITONE.pathTo(blockX, blockY, blockZ)
                .addExecutedListener(future -> {
                    if (idleRecovery) {
                        info(String.format("Idle recovery returned home to [%.3f, %.3f, %.3f]", homeX, homeY, homeZ));
                    } else {
                        notifyReturnedHome(homeX, homeY, homeZ);
                    }
                    endAction();
                    releaseKillAuraSuppression();
                });
    }

    private void notifyReturnedHome(final double homeX, final double homeY, final double homeZ) {
        notifier.discordAndIngameNotification(
                Embed.builder()
                        .description(String.format("Returned home to %.3f %.3f %.3f", homeX, homeY, homeZ))
                        .successColor()
        );
    }

    private boolean isWithinTrapdoorInteractRange(final int x, final int y, final int z) {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }

        var player = CACHE.getPlayerCache().getThePlayer();
        double dx = (x + 0.5D) - player.getX();
        double dy = (y + 0.5D) - player.getY();
        double dz = (z + 0.5D) - player.getZ();
        double distanceSq = dx * dx + dy * dy + dz * dz;
        return distanceSq <= TRAPDOOR_INTERACT_DISTANCE_SQ;
    }

    private boolean isAtConfiguredHome() {
        if (!hasConfiguredHome() || CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }

        double dx = CACHE.getPlayerCache().getThePlayer().getX() - PLUGIN_CONFIG.autoLoad.home.x;
        double dy = CACHE.getPlayerCache().getThePlayer().getY() - PLUGIN_CONFIG.autoLoad.home.y;
        double dz = CACHE.getPlayerCache().getThePlayer().getZ() - PLUGIN_CONFIG.autoLoad.home.z;
        return (dx * dx) + (dy * dy) + (dz * dz) <= HOME_REACHED_DISTANCE_SQ;
    }

    private void clickTrapdoorAndFinish(final PearlPlusConfig.StoredPearl pearl,
                                        final String requesterName,
                                        final BlockPos startPos,
                                        final int targetX,
                                        final int targetY,
                                        final int targetZ,
                                        final boolean hasUpperTrapdoor,
                                        final int lidY) {
        final long sequenceId = ++trapdoorSequenceId;
        BARITONE.rightClickBlock(targetX, targetY, targetZ)
                .addExecutedListener(f -> {
                    if (sequenceId != trapdoorSequenceId) {
                        return;
                    }
                    final boolean upperTrapdoorOpenNow = hasUpperTrapdoor && isTrapdoorOpenAt(targetX, lidY, targetZ);
                    if (hasUpperTrapdoor) {
                        info("Post-load upper trapdoor state at ["
                                + targetX + ", " + lidY + ", " + targetZ + "] open=" + upperTrapdoorOpenNow);
                    }
                    if (hasUpperTrapdoor && !upperTrapdoorOpenNow) {
                        EXECUTOR.schedule(() -> {
                            if (sequenceId != trapdoorSequenceId) {
                                return;
                            }
                            openTrapdoorDirect(targetX, lidY, targetZ, () ->
                                    EXECUTOR.schedule(() -> {
                                        if (sequenceId != trapdoorSequenceId) {
                                            return;
                                        }
                                        BARITONE.rightClickBlock(targetX, targetY, targetZ)
                                                .addExecutedListener(f2 -> {
                                                    if (sequenceId != trapdoorSequenceId) {
                                                        return;
                                                    }
                                                    finishPearlLoad(pearl, requesterName, startPos);
                                                });
                                    }, TRAPDOOR_SEQUENCE_DELAY_MS, TimeUnit.MILLISECONDS));
                        }, TRAPDOOR_SEQUENCE_DELAY_MS, TimeUnit.MILLISECONDS);
                        return;
                    }
                    EXECUTOR.schedule(() -> {
                        if (sequenceId != trapdoorSequenceId) {
                            return;
                        }
                        BARITONE.rightClickBlock(targetX, targetY, targetZ)
                                .addExecutedListener(f2 -> {
                                    if (sequenceId != trapdoorSequenceId) {
                                        return;
                                    }
                                    finishPearlLoad(pearl, requesterName, startPos);
                                });
                    }, TRAPDOOR_SEQUENCE_DELAY_MS, TimeUnit.MILLISECONDS);
                });
    }

    private void acquireKillAuraSuppression() {
        if (killAuraSuppressionDepth++ > 0) {
            return;
        }

        boolean enabled = CONFIG.client.extra.killAura.enabled;
        restoreKillAuraEnabled = enabled;
        if (enabled) {
            CONFIG.client.extra.killAura.enabled = false;
            MODULE.get(KillAura.class).syncEnabledFromConfig();
            info("Temporarily disabled KillAura for SyntaxPearl action");
        }
    }

    private void releaseKillAuraSuppression() {
        if (killAuraSuppressionDepth == 0) {
            return;
        }

        killAuraSuppressionDepth--;
        if (killAuraSuppressionDepth > 0) {
            return;
        }

        if (restoreKillAuraEnabled) {
            CONFIG.client.extra.killAura.enabled = true;
            MODULE.get(KillAura.class).syncEnabledFromConfig();
            info("Restored KillAura after SyntaxPearl action");
        }
        restoreKillAuraEnabled = false;
    }

    private void openTrapdoorDirect(final int x, final int y, final int z, final Runnable afterAttempt) {
        try {
            Vector2f rotationVec = RotationHelper.rotationTo(x + 0.5D, y + 0.5D, z + 0.5D);
            float yaw = rotationVec.getX();
            float pitch = rotationVec.getY();

            var section = CACHE != null && CACHE.getChunkCache() != null
                    ? CACHE.getChunkCache().getChunkSection(x, y, z)
                    : null;
            if (section == null) {
                info("Direct trapdoor interaction skipped: missing chunk section at [" + x + ", " + y + ", " + z + "]");
                if (afterAttempt != null) {
                    afterAttempt.run();
                }
                return;
            }

            int stateId = section.getBlock(x & 15, y & 15, z & 15);
            var block = BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
            if (block == null) {
                info("Direct trapdoor interaction skipped: missing block data at [" + x + ", " + y + ", " + z + "]");
                if (afterAttempt != null) {
                    afterAttempt.run();
                }
                return;
            }

            var hit = new BlockRaycastResult(
                    true,
                    x,
                    y,
                    z,
                    new RayIntersection(x + 0.5D, y + 0.5D, z + 0.5D, Direction.UP),
                    block
            );

            var rotateOnly = InputRequest.builder()
                    .owner(this)
                    .input(Input.builder().build())
                    .yaw(yaw)
                    .pitch(pitch)
                    .priority(PEARLPLUS_ACTION_PRIORITY)
                    .build();

            INPUTS.submit(rotateOnly).addInputExecutedListener(future -> {
                try {
                    Method useItemOn = BOT.getInteractions().getClass()
                            .getDeclaredMethod("useItemOn", Hand.class, BlockRaycastResult.class);
                    useItemOn.setAccessible(true);
                    useItemOn.invoke(BOT.getInteractions(), Hand.MAIN_HAND, hit);
                    info("Interacted with trapdoor directly at [" + x + ", " + y + ", " + z + "]");
                    if (afterAttempt != null) {
                        afterAttempt.run();
                    }
                } catch (Exception e) {
                    LOG.warn("Failed direct upper trapdoor interaction at [{}, {}, {}]", x, y, z, e);
                    if (afterAttempt != null) {
                        afterAttempt.run();
                    }
                }
            });
        } catch (Exception e) {
            LOG.warn("Failed to prepare direct upper trapdoor interaction at [{}, {}, {}]", x, y, z, e);
            if (afterAttempt != null) {
                afterAttempt.run();
            }
        }
    }

    private BlockPos findAdjacentWalkableBlock(final BlockPos trapdoorPos) {
        if (trapdoorPos == null || CACHE == null || CACHE.getChunkCache() == null) {
            info("Walkable search aborted: missing trapdoorPos or chunk cache");
            return null;
        }

        final int tx = (int) trapdoorPos.x();
        final int ty = (int) trapdoorPos.y();
        final int tz = (int) trapdoorPos.z();
        final double anchorX = hasConfiguredHome() ? PLUGIN_CONFIG.autoLoad.home.x : tx + 0.5D;
        final double anchorY = hasConfiguredHome() ? PLUGIN_CONFIG.autoLoad.home.y : ty + 0.5D;
        final double anchorZ = hasConfiguredHome() ? PLUGIN_CONFIG.autoLoad.home.z : tz + 0.5D;

        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = tx - 3; x <= tx + 3; x++) {
            for (int z = tz - 3; z <= tz + 3; z++) {
                for (int feetY = ty - 2; feetY <= ty + 2; feetY++) {
                    int groundY = feetY - 1;
                    if (!isStandableGround(x, groundY, z)) {
                        continue;
                    }
                    if (!isAirLike(x, feetY, z) || !isAirLike(x, feetY + 1, z)) {
                        continue;
                    }

                    double interactDistSq = distanceSqToBlockCenter(x, feetY, z, tx, ty, tz);
                    if (interactDistSq > TRAPDOOR_INTERACT_DISTANCE_SQ) {
                        continue;
                    }

                    double candidateDistSq = distanceSqToPoint(x, feetY, z, anchorX, anchorY, anchorZ);
                    if (candidateDistSq < bestDistSq) {
                        bestDistSq = candidateDistSq;
                        bestPos = new BlockPos(x, feetY, z);
                    }
                }
            }
        }

        if (bestPos != null) {
            info("Found walkable interact block near trapdoor at ["
                    + tx + ", " + ty + ", " + tz + "] -> ["
                    + bestPos.x() + ", " + bestPos.y() + ", " + bestPos.z() + "]");
            return bestPos;
        }

        info("No adjacent walkable block found around trapdoor at ["
                + tx + ", " + ty + ", " + tz + "]");
        return null;
    }

    public void loadPearl(PearlPlusConfig.StoredPearl pearl, String requesterName) {
        if (pearl == null) {
            return;
        }
        if (actionInProgress) {
            info("Ignoring pearl load request while another SyntaxPearl action is already running");
            return;
        }
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) {
            notifier.discordAndIngameNotification(Embed.builder()
                    .title("Can't Load Pearl")
                    .description("Bot is not online")
                    .errorColor());
            return;
        }
        if (proxy.hasActivePlayer()) {
            notifier.discordAndIngameNotification(Embed.builder()
                    .title("Can't Load Pearl")
                    .description("Player is controlling")
                    .errorColor());
            return;
        }

        acquireKillAuraSuppression();
        beginAction("load pearl " + pearl.pearlId);

        // make sure there is a pearl to drop for the player before walking.
        if (PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad) {
            ensurePearlsAvailable();
        }

        // remember where we started so we can go back later.
        BlockPos current = CACHE.getPlayerCache().getThePlayer().blockPos();

        // locate the trapdoor for this pearl
        BlockPos trapdoorPos = findNearestTrapdoorAround(pearl, 3); // radius 3 is usually plenty

        if (trapdoorPos == null) {
            info("No trapdoor detected for pearl " + pearl.pearlId
                    + ", falling back to original behaviour (click stored block)");
            // fall back
            final int targetX = pearl.x;
            final int targetY = pearl.y;
            final int targetZ = pearl.z;
            final BlockPos startPos = current;

            BARITONE.rightClickBlock(targetX, targetY, targetZ)
                    .addExecutedListener(f -> finishPearlLoad(pearl, requesterName, startPos));

            notifier.discordAndIngameNotification(Embed.builder()
                    .title("Loading Pearl")
                    .addField("Pearl", pearl.pearlId, false)
                    .primaryColor());
            return;
        }

        int trapX = (int) trapdoorPos.x();
        int trapY = (int) trapdoorPos.y();
        int trapZ = (int) trapdoorPos.z();
        info("Loading pearl " + pearl.pearlId + " using trapdoor at ["
                + trapX + ", " + trapY + ", " + trapZ + "]");

        // 2) Find a safe adjacent floor block to stand on
        BlockPos walkPos = findAdjacentWalkableBlock(trapdoorPos);

        int pathX = trapX;
        int pathZ = trapZ;

        if (walkPos != null) {
            pathX = (int) walkPos.x();
            pathZ = (int) walkPos.z();
            info("Pathing to adjacent walkable block [" + pathX + ", " + walkPos.y() + ", " + pathZ + "]"
                    + " and then clicking trapdoor");
        } else {
            info("No adjacent walkable block found, pathing directly to trapdoor column [" + pathX + ", " + pathZ + "]");
        }

        final int targetX = trapX;
        final int targetY = trapY;
        final int targetZ = trapZ;
        final int lidY = trapY + 1;
        final boolean hasUpperTrapdoor = isTrapdoorAt(trapX, lidY, trapZ);
        final int pathTargetX = pathX;
        final int pathTargetY = walkPos != null ? (int) walkPos.y() : Math.max(trapY - 1, 0);
        final int pathTargetZ = pathZ;
        final BlockPos startPos = current;

        if (hasUpperTrapdoor) {
            info("Detected upper trapdoor lid at ["
                    + trapX + ", " + lidY + ", " + trapZ + "]");
        }

        if (isWithinTrapdoorInteractRange(targetX, targetY, targetZ)) {
            info("Already within trapdoor interact range, skipping pathing and clicking loader directly");
            clickTrapdoorAndFinish(pearl, requesterName, startPos, targetX, targetY, targetZ, hasUpperTrapdoor, lidY);
            notifier.discordAndIngameNotification(Embed.builder()
                    .title("Loading Pearl")
                    .addField("Pearl", pearl.pearlId, false)
                    .primaryColor());
            return;
        }

        // path to the walkable block and right-click the trapdoor
        BARITONE.pathTo(pathTargetX, pathTargetY, pathTargetZ)
                .addExecutedListener(pathFuture -> {
                    // once pathing attempt is "done", try the click regardless of success/failure.
                    clickTrapdoorAndFinish(pearl, requesterName, startPos, targetX, targetY, targetZ, hasUpperTrapdoor, lidY);
                });

        notifier.discordAndIngameNotification(Embed.builder()
                .title("Loading Pearl")
                .addField("Pearl", pearl.pearlId, false)
                .primaryColor());
    }

    public void tickIdleHomeCheck(final long now) {
        if (actionInProgress && now - actionStartedAtMs >= ACTION_TIMEOUT_MS) {
            info("SyntaxPearl action timed out: " + actionDescription + ". Stopping Baritone and clearing action state");
            BARITONE.stop();
            endAction();
            releaseKillAuraSuppression();
        }
        if (!PLUGIN_CONFIG.autoLoad.returnHomeEnabled || !hasConfiguredHome()) {
            return;
        }
        if (actionInProgress || now - lastIdleHomeAttemptMs < IDLE_HOME_INTERVAL_MS) {
            return;
        }

        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue() || proxy.hasActivePlayer()) {
            return;
        }
        if (isAtConfiguredHome()) {
            return;
        }

        lastIdleHomeAttemptMs = now;
        acquireKillAuraSuppression();
        beginAction("idle return home");
        info("Idle recovery detected bot away from home, pathing back");
        pathToConfiguredHome(true);
    }

    public String pearlsList(UUID ownerUuid) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || entry.pearls.isEmpty()) return "None";

        StringBuilder sb = new StringBuilder("PearlIDs: ");
        boolean first = true;
        for (Map.Entry<String, PearlPlusConfig.StoredPearl> e : entry.pearls.entrySet()) {
            PearlPlusConfig.StoredPearl pearl = e.getValue();
            if (!first) {
                sb.append(", ");
            }
            sb.append(pearl.pearlId);
            if (!isPearlPresent(pearl)) {
                sb.append("*");
            }
            first = false;
        }
        return sb.toString();
    }

    public String pearlsListWithCoords(UUID ownerUuid) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || entry.pearls.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, PearlPlusConfig.StoredPearl> e : entry.pearls.entrySet()) {
            PearlPlusConfig.StoredPearl pearl = e.getValue();
            sb.append("**").append(pearl.pearlId).append("**");
            sb.append(": ");
            if (CONFIG.discord.reportCoords) {
                sb.append("||[").append(pearl.x).append(", ").append(pearl.y).append(", ").append(pearl.z).append("]||");
            } else {
                sb.append("coords hidden");
            }
            sb.append("\n");
        }
        String result = sb.toString();
        return result.isBlank() ? "None" : result.substring(0, result.length() - 1);
    }

    public String pearlsListWithCoordsAllPlayers() {
        if (PLUGIN_CONFIG.players.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        for (var entry : PLUGIN_CONFIG.players.entrySet()) {
            PearlPlusConfig.PlayerPearls playerPearls = entry.getValue();
            if (playerPearls == null || playerPearls.pearls == null || playerPearls.pearls.isEmpty()) {
                continue;
            }
            String playerName = playerPearls.playerName != null ? playerPearls.playerName : entry.getKey().toString();
            sb.append("**").append(playerName).append("**").append("\n");
            for (PearlPlusConfig.StoredPearl pearl : playerPearls.pearls.values()) {
                sb.append("- ").append(pearl.pearlId);
                sb.append(": ");
                if (CONFIG.discord.reportCoords) {
                    sb.append("||[").append(pearl.x).append(", ").append(pearl.y).append(", ").append(pearl.z).append("]||");
                } else {
                    sb.append("coords hidden");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        String result = sb.toString().trim();
        return result.isBlank() ? "None" : result;
    }

    public String nextAvailablePearlId(UUID ownerUuid, String ownerName) {
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        String base;
        if (ownerName == null || ownerName.isBlank()) {
            base = "pearl";
        } else {
            base = ownerName.replaceAll("\\s+", "");
        }

        int suffix = 1;
        while (suffix < 10_000) {
            String candidate = base + suffix;
            if (entry == null || !entry.pearls.containsKey(candidate)) {
                return candidate;
            }
            suffix++;
        }
        return null;
    }

    public void info(String message) {
        LOG.info(message);
    }

    // Check if first hotbar slot (slot 0) contains ender pearls
    private boolean hasPearlsInHotbarSlot0() {
        if (CACHE == null || CACHE.getPlayerCache() == null) {
            return false;
        }
        
        var playerInventory = CACHE.getPlayerCache().getPlayerInventory();
        if (playerInventory == null) {
            return false;
        }
        
        var itemStack = playerInventory.get(36); // Hotbar slot 0 = index 36
        if (itemStack == null) {
            return false;
        }
        
        return isEnderPearl(itemStack);
    }

    // Find pearls in inventory and move to hotbar slot 0
    private boolean ensurePearlsAvailable() {
        if (hasPearlsInHotbarSlot0()) {
            return true;
        }
        
        // Search for pearls in inventory
        int pearlSlot = findEnderPearlInInventory();
        if (pearlSlot == -1) {
            return false; // No pearls found
        }
        
        // Move pearls to hotbar slot 0
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(new MoveToHotbarSlot(pearlSlot, MoveToHotbarAction.SLOT_1))
                .priority(1000)
                .build());
                info("Moved pearls to hotbar");
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to move pearls to hotbar slot 0", e);
            return false;
        }
    }

    // Drop one pearl from hotbar slot 0
    private void dropPearlFromHotbarSlot0() {
        try {
            INVENTORY.submit(InventoryActionRequest.builder()
                .owner(this)
                .actions(new DropItem(36, org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction.DROP_FROM_SELECTED))
                .priority(1000)
                .build());
        } catch (Exception e) {
            LOG.warn("Failed to drop pearl from hotbar slot 0", e);
        }
    }

    // Send out-of-pearls message to player
    private void sendOutOfPearlsMessage(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        
        String message = prefixMessage("I'm all out of pearls, can you give me some?");
        notifier.sendClientPacketAsync(ChatUtil.getWhisperChatPacket(playerName, message));
        info("Sent out-of-pearls message to " + playerName);
    }

    /**
     * Drops a new pearl to the player that just got teleported.
     * Will beg them for a restock if the bot ran out of pearls.
     * 
     * @param playerName    
     */
    public void handlePearlDropAfterLoad(String playerName) {
        if (!PLUGIN_CONFIG.autoLoad.dropPearlAfterLoad) {
            return;
        }
        
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        
        info("Attempting to drop pearl for " + playerName);
        
        if (ensurePearlsAvailable()) {
            dropPearlFromHotbarSlot0();
            info("Successfully dropped pearl for " + playerName);
        } else {
            sendOutOfPearlsMessage(playerName);
            info("No pearls available to drop for " + playerName + ". Begged them to drop me some.");
        }
    }

    private boolean isEnderPearl(Object itemStack) {
        if (itemStack == null) {
            return false;
        }
        
        try {
            // Check if it's an ItemStack and get the item ID
            int itemId = -1;
            if (itemStack instanceof org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack) {
                itemId = ((org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack) itemStack).getId();
            } else if (itemStack instanceof com.zenith.cache.data.inventory.Container) {
                // Handle Container.EMPTY_STACK case
                return false;
            }
            
            if (itemId == -1) {
                return false;
            }
            
            ItemData itemData = ItemRegistry.REGISTRY.get(itemId);
            if (itemData == null) {
                return false;
            }
            info(itemData.name());
            info("id: "+itemId);
            
            String itemName = itemData.name();
            
            return itemName != null && (
            "ENDER_PEARL".equals(itemName) ||
            "ender_pearl".equals(itemName) ||
            itemName.contains("ENDER_PEARL") ||
            itemName.contains("ender_pearl")
        );

        } catch (Exception e) {
            LOG.debug("Error checking if item is ender pearl", e);
            return false;
        }
    }

    // Helper method to find ender pearls in inventory
    private int findEnderPearlInInventory() {
        if (CACHE == null || CACHE.getPlayerCache() == null) {
            return -1;
        }
        
        var playerInventory = CACHE.getPlayerCache().getPlayerInventory();
        if (playerInventory == null) {
            return -1;
        }
        
        // Search through all inventory slots (9-44, excluding hotbar 36-44)
        for (int i = 9; i < playerInventory.size(); i++) {
            var itemStack = playerInventory.get(i);
            if (itemStack != null && isEnderPearl(itemStack)) {
                return i;
            }
        }
        
        return -1;
    }

    /**
     * Returns the amount of pearls a player has left in the stasis. 
     * 
     * @param ownerUuid UUID of the player we want to check the pearlcount for. 
     * @return  Pearlcount
     */
    public int countPresentPearls(UUID ownerUuid) {
        if (ownerUuid == null) return 0;
        PearlPlusConfig.PlayerPearls entry = PLUGIN_CONFIG.players.get(ownerUuid);
        if (entry == null || entry.pearls == null) return 0;
        
        int presentCount = 0;
        for (PearlPlusConfig.StoredPearl pearl : entry.pearls.values()) {
            if (isPearlPresent(pearl)) {
                presentCount++;
            }
        }
        
        return presentCount;
    }

    /**
     * Looks up the uuid from a playername. 
     * 
     * @param username
     * @return
     */
    public UUID getUuidFromUsername(String username) {
        if (username == null || username.isBlank()) return null;
        
        for (var entry : PLUGIN_CONFIG.players.entrySet()) {
            UUID uuid = entry.getKey();
            PearlPlusConfig.PlayerPearls playerPearls = entry.getValue();
            if (playerPearls != null && username.equals(playerPearls.playerName)) {
                return uuid;
            }
        }
        return null;
    }
}
