## SyntaxPearl

SyntaxPearl is a Syntaxia Development fork of PearlPlus. It automatically detects new stasis pearls and registers them with its own pearl loader. Pearl throwers can then load these pearls through chat whispers.
Credits to the original PearlPlus developers: duccss, steve2b2t, and Leonetic.
The config is saved to `plugins/config/syntaxpearl.json`

This fork also supports mapped redstone/lectern stasis chambers and can run the stash chest scanner that posts scan data to the dashboard API.

Place `SyntaxPearl-2.1.0.jar` in your proxy's plugin folder, or build it from this fork and use the generated jar from `build/libs/`.

This plugin **WILL NOT WORK** unless a correct `chatschema` is set in Zenith. Most vanilla servers like 2b2t and Constantiam don't require you to set one but other servers with custom whisper builders for example 9b9t will need one. Please check the wiki [here](https://wiki.2b2t.vc/Commands/#chatschema).
You might also need to set the whisper command for the server you're playing on using `extraChat whisperCommand <command>` to allow the bot to whisper back.

If you're chat banned/muted you can load pearls in your client using [PearlPlusMod](https://github.com/duccss/PearlPlusMod) and [PearlPlusWebAPI](https://github.com/duccss/PearlPlusWebAPI) which bypass's chat.

### Management Commands

#### You can use either `pp` or `pearlplus`

```bash
pearlplus <on/off>
```
```bash
pearlplus add <playerName> <pearlId> <x> <y> <z>
```
```bash
pearlplus del <playerName> <pearlId>
```
```bash
pearlplus list
```
```bash
pearlplus list clear
```
```bash
pearlplus defaultpearlid <word/none>
```
```bash
pearlplus autodefault <on/off>
```
```bash
pearlplus strict <on/off>
```
```bash
pearlplus loadcommand <word>
```
```bash
pearlplus autodetect <on/off>
```
```bash
pearlplus autodetect temp <on/off>
```
```bash
pearlplus returnpos <on/off>
```
```bash
pearlplus distancecheck <on/off>
```

```bash
pearlplus whitelist <on/off>
pearlplus whitelist add <playername>
pearlplus whitelist del <playername>
pearlplus whitelist list
pearlplus whitelist clear
```

```bash
pearlplus droppearlafterload <on/off>
```

```bash
stashscan <on/off>
stashscan scan
stashscan status
stashscan cancel
stashscan clear
customPath <on/off>
customPath <1-20> coords <x> <y> <z>
customPath reset
stashscan return
scanzone list
scanzone add <name> <storage|withdrawal|ignore>
scanzone <name> pos1 here
scanzone <name> pos2 here
scanzone <name> bounds <x1 y1 z1> <x2 y2 z2>
scanzone <name> lane <1-20> start here
scanzone <name> lane <1-20> end here
scanzone <name> lane <1-20> clear
```

`stashscan` also accepts the aliases `chestscan` and `cheststcan`.

### In-game Whisper Commands

There are a few in-game commands players can whisper to the bot to manage their pearls.

`pearls` will list all pearlID's with an asterisk next to ID's where a pearl isn't detected.

`rename oldPearlID newPearlID` changes the pearlID.

`default PearlID` sets that pearl as default if `autodefault` disabled.

### Usage

Simply throw a new ender pearl and once it becomes stable the bot will register it, using the player name with a numeric suffix such as `SleepyFemboy1`, `SleepyFemboy2`, and so on. That player can then whisper `load` (or your configured load command) to the Zenith bot and the bot will load the closest present pearl to the configured home position. Players with multiple pearls can still add a specific pearl ID after the trigger word to force a specific chamber. Players will receive a warning whisper when loading a stasis chamber where a pearl isn't detected.
```bash
/w <botName> load <optionalID> 
```
By default, when a player doesn't specify which pearl they want loaded the bot will load whatever one where a pearl is detected. Can be disabled with `pp autodefault off`

Temp mode automatically removes pearl positions where a pearl isn't detected. May be buggy. Not recommended. Do **NOT** use `pp distancecheck` with temp mode.

Can be enabled with `pp autodetect temp on` 

#### Manual setup
Use the `pp add/del` commands to set up manually.

#### Lectern/redstone chambers

Lectern chamber mappings are loaded from `chambers.json` in the plugin jar. When a stored pearl matches a mapped chamber relative to the configured home position, SyntaxPearl will open the matching lectern, select the configured page, close the lectern UI, and press the mapped pull button. Pearls that do not match a lectern mapping keep using the existing trapdoor/stored-block loading path.

Set home coordinates before using mapped lectern chambers:

```bash
pp home coords <x> <y> <z>
```

#### Stash scanner

The stash scanner is disabled by default. Configure these fields in `plugins/config/syntaxpearl.json`, then enable it with `stashscan on`:

```json
"scanner": {
  "enabled": false,
  "minIntervalMinutes": 10,
  "maxIntervalMinutes": 30,
  "searchRadiusBlocks": 50,
  "yLevelOffset": 3,
  "markerBlockPos": "0,100,0",
  "apiEndpoint": "http://127.0.0.1:3000/api/chests",
  "apiKey": "your-api-key",
  "mergeStrategy": "LATEST_WINS",
  "customPathEnabled": false,
  "customPath": {},
  "zones": {}
}
```

The scanner only sends chest IDs, chest type, dimension, and item contents to the API. Chest coordinates are used locally while scanning and are not sent to the dashboard API.

`customPath` configures up to 20 scanner travel waypoints. Set a point to `0 0 0` or run `customPath reset` to clear it. When a pearl load is requested during a scan, the scanner pauses, remembers unread chests, loads the pearl, and resumes the remaining chest queue afterward. After a custom-path scan, the scanner can use the highest custom waypoint Y as the upper return level, walk to the marker X/Z, and drop back to the marker; `stashscan return` runs that marker-return step manually. `stashscan clear` asks the dashboard API to clear indexed stash data and is intended for debugging.

`scanzone` configures named scan regions and lane order. Zone names are arbitrary labels; zone type controls behavior. `storage` zones are scanned first, `withdrawal` zones are scanned last, and `ignore` zones are skipped. Use `here` while standing at each corner or lane endpoint to avoid typing coordinates. When zones with lanes are configured, the scanner orders chests by zone and lane instead of choosing the closest chest globally.

#### 2b2t / Anti-spam

By default, the bot resolves the username of pearl throwers with entity ID's. Some servers might not allow this so if the bot is unable to register pearls automatically use `pp distancecheck on`. This will get the throwers name from the closest player to the pearl. 2b2t players have reported autodetect ceasing to work occasionally. Always test before enabling this feature.

By default, you can add a random word after `load` or the `pearlID` to get around anti-spam. This can be disabled using `pp strict on`.

#### Recommended Zenith settings

`antiAFK walk off`

`b allowBreak off`

`b allowPlace off`

These settings will stop your pearl bot walking off and prevent it breaking/placing blocks as baritone paths to the pearl trapdoor.

### Building The Plugin

Clone the repo or download the zip.
Run `chmod +x gradlew`
 then `./gradlew build`
