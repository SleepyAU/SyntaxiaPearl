package dev.zenith.pearlplus.scanner;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.zenith.pearlplus.PearlPlusConfig;
import org.cloudburstmc.math.vector.Vector3d;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static com.zenith.command.brigadier.Vec3Argument.getVec3;
import static com.zenith.command.brigadier.Vec3Argument.vec3;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class CustomPathCommand extends Command {
    private static final int MAX_POINTS = 20;

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("customPath")
            .category(CommandCategory.MODULE)
            .description("Configure scanner travel waypoints")
            .usageLines(
                "<on/off> - enable or disable the custom scanner path",
                "<1-20> coords <x> <y> <z> - set a waypoint",
                "<1-20> <x> <y> <z> - set a waypoint",
                "reset - clear all waypoints",
                "list - show configured waypoints"
            )
            .aliases("custompath")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("customPath")
            .requires(Command::validateAccountOwner)
            .executes(c -> {
                renderStatus(c);
                return OK;
            })
            .then(argument("toggle", toggle()).executes(c -> {
                boolean enabled = getToggle(c, "toggle");
                PLUGIN_CONFIG.scanner.customPathEnabled = enabled;
                c.getSource().getEmbed()
                    .title("Custom Scanner Path " + toggleStrCaps(enabled))
                    .description(pathList());
                return OK;
            }))
            .then(literal("reset").executes(c -> {
                PLUGIN_CONFIG.scanner.customPath.clear();
                c.getSource().getEmbed()
                    .title("Custom Scanner Path Reset")
                    .description("All waypoints cleared.");
                return OK;
            }))
            .then(literal("list").executes(c -> {
                renderStatus(c);
                return OK;
            }))
            .then(argument("index", integer(1, MAX_POINTS))
                .then(literal("coords").then(argument("pos", vec3(false)).executes(c -> {
                    return setPoint(c);
                })))
                .then(argument("pos", vec3(false)).executes(c -> {
                    return setPoint(c);
                })));
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed.title("Custom Scanner Path")
            .description(pathList())
            .primaryColor();
    }

    private int setPoint(final com.mojang.brigadier.context.CommandContext<CommandContext> c) {
        int index = getInteger(c, "index");
        Vector3d pos = getVec3(c, "pos");
        String key = String.valueOf(index);

        if (isUnset(pos.getX(), pos.getY(), pos.getZ())) {
            PLUGIN_CONFIG.scanner.customPath.remove(key);
            c.getSource().getEmbed()
                .title("Custom Path Point " + index + " Cleared")
                .description(pathList());
            return OK;
        }

        PearlPlusConfig.PathPoint point = new PearlPlusConfig.PathPoint();
        point.x = pos.getX();
        point.y = pos.getY();
        point.z = pos.getZ();
        PLUGIN_CONFIG.scanner.customPath.put(key, point);

        c.getSource().getEmbed()
            .title("Custom Path Point " + index + " Set")
            .description(formatPoint(index, point));
        return OK;
    }

    private void renderStatus(final com.mojang.brigadier.context.CommandContext<CommandContext> c) {
        c.getSource().getEmbed()
            .title("Custom Scanner Path")
            .addField("Enabled", toggleStr(PLUGIN_CONFIG.scanner.customPathEnabled), false)
            .description(pathList())
            .primaryColor();
    }

    private String pathList() {
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i <= MAX_POINTS; i++) {
            PearlPlusConfig.PathPoint point = PLUGIN_CONFIG.scanner.customPath.get(String.valueOf(i));
            if (point == null || isUnset(point.x, point.y, point.z)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(formatPoint(i, point));
        }
        return builder.isEmpty() ? "No waypoints set." : builder.toString();
    }

    private String formatPoint(final int index, final PearlPlusConfig.PathPoint point) {
        return String.format("%d: %.3f %.3f %.3f", index, point.x, point.y, point.z);
    }

    private boolean isUnset(final double x, final double y, final double z) {
        return x == 0.0D && y == 0.0D && z == 0.0D;
    }
}
