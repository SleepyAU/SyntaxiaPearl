package dev.zenith.pearlplus.scanner;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.zenith.pearlplus.module.ChestScannerModule;

import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class ChestScanCommand extends Command {
    private final ChestScannerModule scannerModule;

    public ChestScanCommand(final ChestScannerModule scannerModule) {
        this.scannerModule = scannerModule;
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("stashscan")
            .category(CommandCategory.MODULE)
            .description("Chest scanner and stash dashboard control")
            .usageLines(
                "<on/off> - enable or disable scheduled scans",
                "scan - trigger immediate scan",
                "status - show scanner status",
                "cancel - cancel active scan",
                "return - return to the scanner marker",
                "clear - clear indexed stash data from the dashboard API"
            )
            .aliases("chestscan", "cheststcan")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("stashscan")
            .requires(Command::validateAccountOwner)
            .executes(c -> OK)
            .then(argument("toggle", toggle()).executes(c -> {
                boolean enabled = getToggle(c, "toggle");
                PLUGIN_CONFIG.scanner.enabled = enabled;
                scannerModule.syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Chest Scanner " + toggleStrCaps(enabled))
                    .description(enabled ? scannerModule.status() : "Scanner disabled");
                return OK;
            }))
            .then(literal("scan").executes(c -> {
                if (!scannerModule.triggerManualScan()) {
                    c.getSource().getEmbed()
                        .title("Chest Scanner")
                        .description("Scanner is disabled or not initialized")
                        .errorColor();
                    return OK;
                }
                c.getSource().getEmbed()
                    .title("Chest Scan")
                    .description("Chest scan triggered")
                    .successColor();
                return OK;
            }))
            .then(literal("status").executes(c -> {
                c.getSource().getEmbed()
                    .title("Chest Scanner Status")
                    .description(scannerModule.status())
                    .primaryColor();
                return OK;
            }))
            .then(literal("cancel").executes(c -> {
                if (!scannerModule.cancelActiveScan()) {
                    c.getSource().getEmbed()
                        .title("Chest Scan")
                        .description("No active scan to cancel")
                        .primaryColor();
                    return OK;
                }
                c.getSource().getEmbed()
                    .title("Chest Scan")
                    .description("Scan cancellation requested")
                    .primaryColor();
                return OK;
            }))
            .then(literal("return").executes(c -> {
                if (!scannerModule.returnToMarker()) {
                    c.getSource().getEmbed()
                        .title("Chest Scanner")
                        .description("Could not start marker return")
                        .errorColor();
                    return OK;
                }
                c.getSource().getEmbed()
                    .title("Chest Scanner")
                    .description("Returning to marker")
                    .primaryColor();
                return OK;
            }))
            .then(literal("clear").executes(c -> {
                if (!scannerModule.clearRemoteStashData()) {
                    c.getSource().getEmbed()
                        .title("Chest Scanner")
                        .description("Could not clear stash data while the scanner is active or unavailable")
                        .errorColor();
                    return OK;
                }
                c.getSource().getEmbed()
                    .title("Chest Scanner")
                    .description("Remote stash data clear requested")
                    .primaryColor();
                return OK;
            }));
    }

    @Override
    public void defaultEmbed(Embed embed) {
        embed.title("Chest Scanner")
            .description("Status: " + scannerModule.status())
            .primaryColor();
    }
}
