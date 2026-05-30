package dev.zenith.pearlplus.scanner;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.discord.Embed;
import dev.zenith.pearlplus.module.ChestScannerModule;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;

public class StashWithdrawCommand extends Command {
    private final ChestScannerModule scannerModule;
    private String pendingTitle = null;
    private String pendingDescription = null;
    private ResultColor pendingColor = ResultColor.PRIMARY;

    public StashWithdrawCommand(final ChestScannerModule scannerModule) {
        this.scannerModule = scannerModule;
    }

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("stashwithdraw")
            .category(CommandCategory.MODULE)
            .description("Queue a stash withdrawal from storage chests into withdrawal chests")
            .usageLines(
                "<item> <amount> - queue withdrawal, for example ender_pearl 1s or ender_pearl 1d"
            )
            .aliases("stashWithdraw")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("stashwithdraw")
            .requires(Command::validateAccountOwner)
            .executes(c -> OK)
            .then(argument("item", word()).then(argument("amount", word()).executes(c -> {
                final String itemId = getString(c, "item");
                final String amount = getString(c, "amount");
                try {
                    final ChestScannerService.WithdrawRequest request =
                        scannerModule.queueConsoleWithdrawRequest(itemId, amount);
                    setPendingResult(
                        "Stash Withdraw Queued",
                        request.shulkerCount() + " shulker(s) of " + request.itemId()
                            + "\nRequest: " + request.requestId(),
                        ResultColor.SUCCESS
                    );
                } catch (final Exception e) {
                    setPendingResult(
                        "Stash Withdraw Failed",
                        e.getMessage() == null ? "Unknown error" : e.getMessage(),
                        ResultColor.ERROR
                    );
                }
                return OK;
            })));
    }

    @Override
    public void defaultEmbed(final Embed embed) {
        final String title;
        final String description;
        final ResultColor color;
        synchronized (this) {
            title = pendingTitle;
            description = pendingDescription;
            color = pendingColor;
            pendingTitle = null;
            pendingDescription = null;
            pendingColor = ResultColor.PRIMARY;
        }

        if (title != null) {
            embed.title(title).description(description == null ? "" : description);
            applyColor(embed, color);
            return;
        }

        embed.title("Stash Withdraw")
            .description("Usage: stashwithdraw <item> <amount>")
            .addField("Amounts", "1s = 1 shulker, 1d = 54 shulkers", false)
            .primaryColor();
    }

    private synchronized void setPendingResult(final String title,
                                               final String description,
                                               final ResultColor color) {
        pendingTitle = title;
        pendingDescription = description;
        pendingColor = color;
    }

    private void applyColor(final Embed embed, final ResultColor color) {
        switch (color) {
            case SUCCESS -> embed.successColor();
            case ERROR -> embed.errorColor();
            default -> embed.primaryColor();
        }
    }

    private enum ResultColor {
        PRIMARY,
        SUCCESS,
        ERROR
    }
}
