package dev.zenith.pearlplus.scanner;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.zenith.pearlplus.PearlPlusConfig;
import org.cloudburstmc.math.vector.Vector3d;

import java.util.Locale;
import java.util.Map;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.CACHE;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.Vec3Argument.getVec3;
import static com.zenith.command.brigadier.Vec3Argument.vec3;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class ScanZoneCommand extends Command {
    private static final int MAX_LANES = 20;

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("scanzone")
            .category(CommandCategory.MODULE)
            .description("Configure scanner zones and lane routes")
            .usageLines(
                "list - show zones",
                "add <name> <storage|withdrawal|ignore> - create/update a zone",
                "remove <name> - delete a zone",
                "<name> type <storage|withdrawal|ignore> - change zone type",
                "<name> pos1 here|<x> <y> <z> - set first zone corner",
                "<name> pos2 here|<x> <y> <z> - set second zone corner",
                "<name> bounds <x1 y1 z1> <x2 y2 z2> - set both corners",
                "<name> lane <1-20> start here|<x> <y> <z> - set lane start",
                "<name> lane <1-20> end here|<x> <y> <z> - set lane end",
                "<name> lane <1-20> clear - remove a lane"
            )
            .aliases("scanZone", "scanroute", "scanRoute")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        final var root = command("scanzone")
            .requires(Command::validateAccountOwner)
            .executes(c -> {
                renderList(c);
                return OK;
            });

        root.then(literal("list").executes(c -> {
            renderList(c);
            return OK;
        }));

        root.then(literal("add").then(argument("name", wordWithChars()).then(argument("type", wordWithChars()).executes(c -> {
            final String name = normalizeName(getString(c, "name"));
            final String type = normalizeType(getString(c, "type"));
            if (type == null) {
                c.getSource().getEmbed()
                    .title("Invalid Zone Type")
                    .description("Use storage, withdrawal, or ignore.")
                    .errorColor();
                return OK;
            }
            final PearlPlusConfig.ScanZone zone = zone(name);
            zone.type = type;
            c.getSource().getEmbed()
                .title("Scan Zone Saved")
                .description(formatZone(name, zone))
                .primaryColor();
            return OK;
        }))));

        root.then(literal("remove").then(argument("name", wordWithChars()).executes(c -> {
            final String name = normalizeName(getString(c, "name"));
            PLUGIN_CONFIG.scanner.zones.remove(name);
            c.getSource().getEmbed()
                .title("Scan Zone Removed")
                .description(name)
                .primaryColor();
            return OK;
        })));

        final var zoneNode = argument("name", wordWithChars());
        zoneNode.then(literal("show").executes(c -> {
            final String name = normalizeName(getString(c, "name"));
            final PearlPlusConfig.ScanZone zone = PLUGIN_CONFIG.scanner.zones.get(name);
            if (zone == null) {
                renderMissingZone(c, name);
                return OK;
            }
            c.getSource().getEmbed()
                .title("Scan Zone " + name)
                .description(formatZone(name, zone))
                .primaryColor();
            return OK;
        }));
        zoneNode.then(literal("type").then(argument("type", wordWithChars()).executes(c -> {
            final String name = normalizeName(getString(c, "name"));
            final String type = normalizeType(getString(c, "type"));
            if (type == null) {
                c.getSource().getEmbed()
                    .title("Invalid Zone Type")
                    .description("Use storage, withdrawal, or ignore.")
                    .errorColor();
                return OK;
            }
            final PearlPlusConfig.ScanZone zone = zone(name);
            zone.type = type;
            renderSavedZone(c, name, zone);
            return OK;
        })));
        zoneNode.then(literal("pos1")
            .then(literal("here").executes(c -> {
                return setZonePoint(c, "pos1", currentPos());
            }))
            .then(argument("pos", vec3(false)).executes(c -> {
                return setZonePoint(c, "pos1", getVec3(c, "pos"));
            })));
        zoneNode.then(literal("pos2")
            .then(literal("here").executes(c -> {
                return setZonePoint(c, "pos2", currentPos());
            }))
            .then(argument("pos", vec3(false)).executes(c -> {
                return setZonePoint(c, "pos2", getVec3(c, "pos"));
            })));
        zoneNode.then(literal("bounds").then(argument("pos1", vec3(false)).then(argument("pos2", vec3(false)).executes(c -> {
            final String name = normalizeName(getString(c, "name"));
            final PearlPlusConfig.ScanZone zone = zone(name);
            zone.pos1 = pathPoint(getVec3(c, "pos1"));
            zone.pos2 = pathPoint(getVec3(c, "pos2"));
            renderSavedZone(c, name, zone);
            return OK;
        }))));
        zoneNode.then(literal("lane").then(argument("index", integer(1, MAX_LANES))
            .then(literal("clear").executes(c -> {
                final String name = normalizeName(getString(c, "name"));
                final int index = getInteger(c, "index");
                final PearlPlusConfig.ScanZone zone = zone(name);
                zone.lanes.remove(String.valueOf(index));
                renderSavedZone(c, name, zone);
                return OK;
            }))
            .then(literal("start")
                .then(literal("here").executes(c -> {
                    return setLanePoint(c, "start", currentPos());
                }))
                .then(argument("pos", vec3(false)).executes(c -> {
                    return setLanePoint(c, "start", getVec3(c, "pos"));
                })))
            .then(literal("end")
                .then(literal("here").executes(c -> {
                    return setLanePoint(c, "end", currentPos());
                }))
                .then(argument("pos", vec3(false)).executes(c -> {
                    return setLanePoint(c, "end", getVec3(c, "pos"));
                })))));
        root.then(zoneNode);
        return root;
    }

    @Override
    public void defaultEmbed(final Embed embed) {
        embed.title("Scan Zones")
            .description(zoneList())
            .primaryColor();
    }

    private int setZonePoint(final com.mojang.brigadier.context.CommandContext<CommandContext> c,
                             final String corner,
                             final Vector3d pos) {
        if (pos == null) {
            c.getSource().getEmbed()
                .title("Position Unavailable")
                .description("The bot position is not available yet.")
                .errorColor();
            return OK;
        }

        final String name = normalizeName(getString(c, "name"));
        final PearlPlusConfig.ScanZone zone = zone(name);
        if ("pos1".equals(corner)) {
            zone.pos1 = pathPoint(pos);
        } else {
            zone.pos2 = pathPoint(pos);
        }
        renderSavedZone(c, name, zone);
        return OK;
    }

    private int setLanePoint(final com.mojang.brigadier.context.CommandContext<CommandContext> c,
                             final String side,
                             final Vector3d pos) {
        if (pos == null) {
            c.getSource().getEmbed()
                .title("Position Unavailable")
                .description("The bot position is not available yet.")
                .errorColor();
            return OK;
        }

        final String name = normalizeName(getString(c, "name"));
        final int index = getInteger(c, "index");
        final PearlPlusConfig.ScanZone zone = zone(name);
        final PearlPlusConfig.ScanLane lane = zone.lanes.computeIfAbsent(String.valueOf(index), ignored -> new PearlPlusConfig.ScanLane());
        if ("start".equals(side)) {
            lane.start = pathPoint(pos);
        } else {
            lane.end = pathPoint(pos);
        }
        renderSavedZone(c, name, zone);
        return OK;
    }

    private PearlPlusConfig.ScanZone zone(final String name) {
        return PLUGIN_CONFIG.scanner.zones.computeIfAbsent(name, ignored -> new PearlPlusConfig.ScanZone());
    }

    private PearlPlusConfig.PathPoint pathPoint(final Vector3d pos) {
        final PearlPlusConfig.PathPoint point = new PearlPlusConfig.PathPoint();
        point.x = pos.getX();
        point.y = pos.getY();
        point.z = pos.getZ();
        return point;
    }

    private Vector3d currentPos() {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return null;
        }
        return Vector3d.from(
            CACHE.getPlayerCache().getThePlayer().getX(),
            CACHE.getPlayerCache().getThePlayer().getY(),
            CACHE.getPlayerCache().getThePlayer().getZ()
        );
    }

    private void renderList(final com.mojang.brigadier.context.CommandContext<CommandContext> c) {
        c.getSource().getEmbed()
            .title("Scan Zones")
            .description(zoneList())
            .primaryColor();
    }

    private void renderSavedZone(final com.mojang.brigadier.context.CommandContext<CommandContext> c,
                                 final String name,
                                 final PearlPlusConfig.ScanZone zone) {
        c.getSource().getEmbed()
            .title("Scan Zone Saved")
            .description(formatZone(name, zone))
            .primaryColor();
    }

    private void renderMissingZone(final com.mojang.brigadier.context.CommandContext<CommandContext> c, final String name) {
        c.getSource().getEmbed()
            .title("Scan Zone Not Found")
            .description(name)
            .errorColor();
    }

    private String zoneList() {
        if (PLUGIN_CONFIG.scanner.zones.isEmpty()) {
            return "No scan zones configured.";
        }
        final StringBuilder builder = new StringBuilder();
        for (final Map.Entry<String, PearlPlusConfig.ScanZone> entry : PLUGIN_CONFIG.scanner.zones.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(formatZone(entry.getKey(), entry.getValue()));
        }
        return builder.toString();
    }

    private String formatZone(final String name, final PearlPlusConfig.ScanZone zone) {
        final StringBuilder builder = new StringBuilder();
        builder.append(name)
            .append(" [")
            .append(zone.type == null ? "STORAGE" : zone.type)
            .append("]");
        builder.append("\nBounds: ")
            .append(formatPoint(zone.pos1))
            .append(" -> ")
            .append(formatPoint(zone.pos2));
        if (zone.lanes == null || zone.lanes.isEmpty()) {
            builder.append("\nLanes: none");
        } else {
            builder.append("\nLanes:");
            for (final Map.Entry<String, PearlPlusConfig.ScanLane> laneEntry : zone.lanes.entrySet()) {
                final PearlPlusConfig.ScanLane lane = laneEntry.getValue();
                builder.append("\n  ")
                    .append(laneEntry.getKey())
                    .append(": ")
                    .append(formatPoint(lane.start))
                    .append(" -> ")
                    .append(formatPoint(lane.end));
            }
        }
        return builder.toString();
    }

    private String formatPoint(final PearlPlusConfig.PathPoint point) {
        if (point == null) {
            return "unset";
        }
        return String.format("%.3f %.3f %.3f", point.x, point.y, point.z);
    }

    private String normalizeName(final String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeType(final String type) {
        if (type == null) {
            return null;
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "storage", "store" -> "STORAGE";
            case "withdrawal", "withdraw" -> "WITHDRAWAL";
            case "ignore", "ignored", "skip" -> "IGNORE";
            default -> null;
        };
    }
}
