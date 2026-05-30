package dev.zenith.pearlplus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class PearlPlusConfig {
    public final AutoLoadConfig autoLoad = new AutoLoadConfig();
    public final AutoDetectConfig autoDetect = new AutoDetectConfig();
    public final ScannerConfig scanner = new ScannerConfig();

    public String defaultPearlId = null;

    public final Map<UUID, PlayerPearls> players = new LinkedHashMap<>();
    public final Map<UUID, WhitelistedPlayer> whitelist = new LinkedHashMap<>();

    public static class AutoLoadConfig {
        public boolean enabled = true;
        public boolean allowNoiseAfterPearl = true;
        public boolean returnToStartPos = true;
        public boolean returnHomeEnabled = false;
        public boolean autoDefaultToPresent = true;
        public boolean whitelistEnabled = false;
        public boolean dropPearlAfterLoad = true;
        public boolean allowTrapdoorFallback = false;
        public String loadCommand = "load";
        public final HomePosition home = new HomePosition();
    }

    public static final class AutoDetectConfig {
        public boolean enabled = true;
        public boolean temporaryMode = false;
        public boolean distanceCheck = false;
        public int temporaryRemovalRange = 64; //blocks
    }

    public static final class ScannerConfig {
        public boolean enabled = false;
        public int minIntervalMinutes = 10;
        public int maxIntervalMinutes = 30;
        public int searchRadiusBlocks = 50;
        public int yLevelOffset = 3;
        public String markerBlockPos = "0,100,0";
        public String apiEndpoint = "http://localhost:3000/api/chests";
        public String apiKey = "";
        public String mergeStrategy = "LATEST_WINS";
        public boolean customPathEnabled = false;
        public Map<String, PathPoint> customPath = new LinkedHashMap<>();
        public Map<String, ScanZone> zones = new LinkedHashMap<>();
    }

    public static final class PathPoint {
        public double x;
        public double y;
        public double z;
    }

    public static final class ScanZone {
        public String type = "STORAGE";
        public PathPoint pos1;
        public PathPoint pos2;
        public Map<String, ScanLane> lanes = new LinkedHashMap<>();
    }

    public static final class ScanLane {
        public PathPoint start;
        public PathPoint end;
    }

    public static final class PlayerPearls {
        public String playerName;
        public String defaultPearlId;
        public Map<String, StoredPearl> pearls = new LinkedHashMap<>();
    }

    public static final class StoredPearl {
        public String pearlId;
        public int x;
        public int y;
        public int z;
        public int relX;
        public int relZ;
    }

    public static final class HomePosition {
        public Double x;
        public Double y;
        public Double z;
    }

    public static final class WhitelistedPlayer {
        public String username;
        public UUID uuid;
        
        public WhitelistedPlayer(String username, UUID uuid) {
            this.username = username;
            this.uuid = uuid;
        }
    }
}
