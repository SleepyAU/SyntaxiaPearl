package dev.zenith.pearlplus.scanner;

import dev.zenith.pearlplus.PearlPlusPlugin;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ScanScheduler {
    private final ChestScannerService scanner;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Random random = new Random();

    private ScheduledFuture<?> nextScanTask = null;
    private volatile boolean running = false;
    private volatile long pauseUntil = 0L;

    public ScanScheduler(ChestScannerService scanner) {
        this.scanner = scanner;
    }

    public synchronized void start() {
        if (running) {
            PearlPlusPlugin.LOG.warn("Scheduler already running");
            return;
        }
        running = true;
        pauseUntil = 0L;
        scheduleNextScan();
        PearlPlusPlugin.LOG.info("Chest scanner scheduler started");
    }

    public synchronized void stop() {
        running = false;
        if (nextScanTask != null) {
            nextScanTask.cancel(false);
            nextScanTask = null;
        }
        PearlPlusPlugin.LOG.info("Chest scanner scheduler stopped");
    }

    public synchronized void triggerManualScan() {
        if (!running) {
            PearlPlusPlugin.LOG.warn("Scanner not enabled");
            return;
        }
        if (scanner.isScanActive() || scanner.isScanPaused()) {
            PearlPlusPlugin.LOG.warn("Scan already in progress");
            return;
        }

        if (nextScanTask != null) {
            nextScanTask.cancel(false);
        }

        performScan();
        scheduleNextScan();
    }

    public synchronized void onCommandInterruption() {
        if (!scanner.isScanActive()) {
            return;
        }

        PearlPlusPlugin.LOG.info("Scan interrupted by command, will resume after 1 minute");
        scanner.cancelScan();

        if (nextScanTask != null) {
            nextScanTask.cancel(false);
        }

        pauseUntil = System.currentTimeMillis() + 60_000; // 1 minute
        scheduleNextScan();
    }

    private void scheduleNextScan() {
        if (!running) return;

        int minMinutes = Math.max(1, PearlPlusPlugin.PLUGIN_CONFIG.scanner.minIntervalMinutes);
        int maxMinutes = Math.max(minMinutes, PearlPlusPlugin.PLUGIN_CONFIG.scanner.maxIntervalMinutes);

        int delayMinutes = minMinutes + random.nextInt(maxMinutes - minMinutes + 1);
        long delayMillis = delayMinutes * 60_000L;

        long now = System.currentTimeMillis();
        if (pauseUntil > now) {
            delayMillis = Math.max(delayMillis, pauseUntil - now);
        }

        PearlPlusPlugin.LOG.info("Next scan scheduled in {} minutes", delayMinutes);

        nextScanTask = scheduler.schedule(() -> {
            if (running && pauseUntil <= System.currentTimeMillis()) {
                performScan();
                scheduleNextScan();
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void performScan() {
        if (!running) return;

        int[] marker = parseMarkerPos();
        if (marker == null) {
            PearlPlusPlugin.LOG.error("Invalid marker position in config");
            return;
        }
        if (scanner.isScanActive() || scanner.isScanPaused()) {
            PearlPlusPlugin.LOG.info("Skipping automatic chest scan because scanner or withdrawal work is already active");
            return;
        }

        PearlPlusPlugin.LOG.info("Starting automatic chest scan");
        scanner.startScan(marker[0], marker[1], marker[2]);
    }

    private int[] parseMarkerPos() {
        String pos = PearlPlusPlugin.PLUGIN_CONFIG.scanner.markerBlockPos;
        try {
            String[] parts = pos.split(",");
            if (parts.length != 3) return null;
            return new int[]{
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim())
            };
        } catch (Exception e) {
            PearlPlusPlugin.LOG.error("Failed to parse marker position: {}", pos, e);
            return null;
        }
    }

    public synchronized String getStatus() {
        if (!running) {
            return "Scheduler not running";
        }
        if (scanner.isScanPaused()) {
            return String.format("Paused (%d chests read, %d remaining)",
                scanner.getReadChestCount(), scanner.getRemainingChestCount());
        }
        if (scanner.isScanActive()) {
            return String.format("Scan in progress (%d chests read, %d remaining)",
                scanner.getReadChestCount(), scanner.getRemainingChestCount());
        }
        if (pauseUntil > System.currentTimeMillis()) {
            long remainingMs = pauseUntil - System.currentTimeMillis();
            return String.format("Paused, resuming in %d seconds", remainingMs / 1000);
        }
        return "Waiting for next scan";
    }

    public boolean isRunning() {
        return running;
    }

    public void shutdown() {
        stop();
        scheduler.shutdown();
    }
}
