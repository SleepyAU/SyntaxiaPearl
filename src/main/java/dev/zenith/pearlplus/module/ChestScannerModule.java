package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.module.OutboundChatEvent;
import com.zenith.module.api.Module;
import dev.zenith.pearlplus.PearlPlusPlugin;
import dev.zenith.pearlplus.scanner.ChestScannerService;
import dev.zenith.pearlplus.scanner.ScanScheduler;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class ChestScannerModule extends Module {
    private ChestScannerService scanner;
    private ScanScheduler scheduler;

    public ChestScannerModule() {
        super();
    }

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.scanner.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientOnlineEvent.class, this::handleClientOnline),
            of(ClientDisconnectEvent.class, this::handleClientDisconnect),
            of(OutboundChatEvent.class, this::handleOutboundChat)
        );
    }

    @Override
    public void onEnable() {
        PearlPlusPlugin.LOG.info("Enabling Chest Scanner Module");

        scanner = new ChestScannerService();
        scanner.startWithdrawWorker();
        scheduler = new ScanScheduler(scanner);
        scheduler.start();

        PearlPlusPlugin.LOG.info("Chest scanner enabled and scheduler started");
    }

    @Override
    public void onDisable() {
        PearlPlusPlugin.LOG.info("Disabling Chest Scanner Module");

        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        if (scanner != null) {
            scanner.stopWithdrawWorker();
            scanner.shutdown();
            scanner = null;
        }

        PearlPlusPlugin.LOG.info("Chest scanner disabled");
    }

    private void handleClientOnline(ClientOnlineEvent event) {
        if (scheduler != null && !scheduler.isRunning()) {
            scheduler.start();
        }
    }

    private void handleClientDisconnect(ClientDisconnectEvent event) {
        if (scheduler != null) {
            scheduler.stop();
        }
        if (scanner != null && scanner.isScanActive()) {
            scanner.cancelScan();
        }
    }

    private void handleOutboundChat(OutboundChatEvent event) {
        // Detect if a command is interrupting an active scan
        if (scanner != null && scanner.isScanActive()) {
            String message = event.getPacket().getMessage();
            if (isCommandLike(message)) {
                PearlPlusPlugin.LOG.info("Command detected during scan, triggering interruption handling");
                if (scheduler != null) {
                    scheduler.onCommandInterruption();
                }
            }
        }
    }

    private boolean isCommandLike(String message) {
        return message.startsWith("/") || message.startsWith("!");
    }

    public ChestScannerService getScanner() {
        return scanner;
    }

    public ScanScheduler getScheduler() {
        return scheduler;
    }

    public boolean isScanActive() {
        return scanner != null && scanner.isScanActive();
    }

    public boolean isScanPaused() {
        return scanner != null && scanner.isScanPaused();
    }

    public String status() {
        if (!PLUGIN_CONFIG.scanner.enabled) {
            return "Scanner disabled";
        }
        if (scheduler == null) {
            return "Scanner not initialized";
        }
        if (scanner != null && scanner.isScanPaused()) {
            return "Paused (" + scanner.getReadChestCount() + " chests read, "
                + scanner.getRemainingChestCount() + " remaining)";
        }
        return scheduler.getStatus();
    }

    public boolean triggerManualScan() {
        if (!PLUGIN_CONFIG.scanner.enabled || scheduler == null) {
            return false;
        }
        scheduler.triggerManualScan();
        return true;
    }

    public boolean cancelActiveScan() {
        if (scanner == null || (!scanner.isScanActive() && !scanner.isScanPaused())) {
            return false;
        }
        scanner.cancelScan();
        return true;
    }

    public boolean returnToMarker() {
        return scanner != null && scanner.returnToConfiguredMarker();
    }

    public boolean returnPausedScannerToMarkerForPearlLoad() {
        return scanner != null && scanner.returnPausedScannerToMarkerForPearlLoad();
    }

    public boolean clearRemoteStashData() {
        return scanner != null && scanner.clearRemoteStashData();
    }

    public ChestScannerService.WithdrawRequest queueConsoleWithdrawRequest(final String itemId,
                                                                           final String amountText) throws Exception {
        if (scanner == null) {
            throw new IllegalStateException("Scanner is not initialized");
        }
        return scanner.queueConsoleWithdrawRequest(itemId, amountText);
    }

    public boolean pauseForPearlRequest() {
        return scanner != null && scanner.pauseForPearlRequest();
    }

    public boolean resumePausedScan() {
        return scanner != null && scanner.resumePausedScan();
    }
}
