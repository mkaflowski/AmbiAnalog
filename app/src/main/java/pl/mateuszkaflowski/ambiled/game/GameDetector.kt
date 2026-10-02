package pl.mateuszkaflowski.ambiled.game

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import pl.mateuszkaflowski.ambiled.adb.AdbShell
import pl.mateuszkaflowski.ambiled.adb.ExtendedMode
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Watches which game is on the Thor's top screen and publishes it to [GameStatus].
 *
 * Usage events (cheap to poll) tell us *that* the foreground changed. In extended mode the
 * shell's `dumpsys` then tells us exactly what's on display 0, including the ROM an emulator
 * was started with; without it we can only go by package, which recognises Android games.
 */
class GameDetector(private val context: Context, private val scope: CoroutineScope) {

    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val homePackages = homePackages()
    private val ignoredPackages = buildSet {
        add(context.packageName)
        add("com.android.systemui")
        addAll(homePackages)
    }

    // Seen as players in Cocoon's log. Eden declares the game category, but its menu isn't a game.
    private val emulatorPackages = mutableSetOf<String>()
    private var job: Job? = null

    // Without the shell: packages resumed and not yet stopped, most recent last.
    private val resumed = LinkedHashSet<String>()

    fun start() {
        if (job != null) return
        job = scope.launch {
            var since = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
            var first = true
            while (isActive) {
                val now = System.currentTimeMillis()
                val changed = readEvents(since, now)
                since = now
                if (changed || first) {
                    first = false
                    delay(SETTLE_MS) // Let the launch finish before asking dumpsys.
                    resolve()
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        GameStatus.set(null)
    }

    /** Returns whether anything relevant happened. */
    private fun readEvents(from: Long, to: Long): Boolean {
        val events = usageStats.queryEvents(from, to) ?: return false
        val event = UsageEvents.Event()
        var changed = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    resumed.remove(event.packageName)
                    resumed.add(event.packageName)
                    changed = true
                }
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (resumed.remove(event.packageName)) changed = true
                }
            }
        }
        return changed
    }

    private suspend fun resolve() {
        val game = try {
            withContext(Dispatchers.IO) {
                if (ExtendedMode.paired.value) resolveWithShell() else resolveFromEvents()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't resolve the current game", e)
            null
        }
        if (game != GameStatus.current.value) {
            Log.i(TAG, "Current game: ${game?.let { "${it.title} (${it.id}), colors=${it.colors}" }}")
            GameStatus.set(game)
        }
    }

    /**
     * Where the running game comes from, in order:
     * 1. the ROM in the intent data (Azahar, Eden started from Cocoon…);
     * 2. Cocoon's launch log, if Cocoon started the emulator (RetroArch gets the ROM in extras);
     * 3. the emulator's own log, for yuzu forks started from their own menu;
     * 4. the app itself, if it's an Android game and not an emulator sitting in its menu.
     */
    private suspend fun resolveWithShell(): CurrentGame? = AdbShell.withShell(context) { kadb ->
        val top = TopActivityParser.parse(kadb.shell("dumpsys activity activities").output)
            ?: return@withShell null
        if (top.packageName in ignoredPackages) return@withShell null
        val launches = CocoonLaunchLog.parse(kadb.shell("tail -c $LAUNCH_LOG_TAIL_BYTES ${CocoonLaunchLog.PATH}").output)
        emulatorPackages += launches.mapNotNull { it.playerPackage }
        // Only trust the log for emulators the launcher started; otherwise its last entry is stale.
        val launch = launches.lastOrNull { it.playerPackage == top.packageName }
            ?.takeIf { top.launchedFromPackage in homePackages }
        val launchRom = launch?.romUri?.let(RomInfo::fromUri)
        val rom = top.dataUri?.let(RomInfo::fromUri) ?: launchRom
        when {
            rom != null -> romGame(kadb, rom, launch?.title?.takeIf { launchRom == rom } ?: rom.title, top.packageName)
            top.activity == YuzuLog.EMULATION_ACTIVITY ->
                YuzuLog.parse(kadb.shell(YuzuLog.shellCommand(top.packageName)).output)
                    ?.let { switchGame(kadb, it, top.packageName) }
            top.packageName in emulatorPackages -> null
            else -> appGame(top.packageName)
        }
    }

    private fun romGame(kadb: Kadb, rom: RomInfo, title: String, packageName: String): CurrentGame {
        val id = "rom:${rom.platform.lowercase()}/${rom.relativePath}"
        val artwork = GameArtwork.cachedArtwork(context, id)
            ?: GameArtwork.copyArtwork(context, kadb, id, ArtworkMatch.patternsFor(rom), rom.platform)
        // Switch dumps carry the title id in their name, so presets keyed by it apply when
        // Cocoon starts the game too.
        val titleId = SWITCH_TITLE_ID.find(rom.romName)?.groupValues?.get(1)?.uppercase()
        return withColors(id, title, rom.platform, packageName, artwork, titleId?.let { "$SWITCH_ID_PREFIX$it" })
    }

    private fun switchGame(kadb: Kadb, game: YuzuGame, packageName: String): CurrentGame {
        val id = SWITCH_ID_PREFIX + (game.titleId ?: game.title)
        val artwork = GameArtwork.cachedArtwork(context, id) ?: game.titleId?.let {
            GameArtwork.copyArtwork(context, kadb, id, ArtworkMatch.patternsForTitleId(it), SWITCH_SYSTEM)
        }
        return withColors(id, game.title, "Switch", packageName, artwork)
    }

    private fun withColors(
        id: String,
        title: String,
        platform: String,
        packageName: String,
        cover: File?,
        presetId: String? = null,
    ): CurrentGame {
        val auto = GameProfiles.cachedAuto(id)
            ?: cover?.let(GameArtwork::colorsFromFile)?.also { GameProfiles.cacheAuto(id, it) }
        val preset = BundledGameColors.forId(id) ?: presetId?.let(BundledGameColors::forId)
        return CurrentGame(id, title, platform, packageName, cover, auto, preset, GameProfiles.custom(id))
    }

    private fun resolveFromEvents(): CurrentGame? =
        resumed.lastOrNull { it !in ignoredPackages }?.let(::appGame)

    /** Android apps count as games only when they declare the game category. */
    private fun appGame(packageName: String): CurrentGame? {
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return null
        if (info.category != ApplicationInfo.CATEGORY_GAME) return null
        val id = APP_ID_PREFIX + packageName
        val auto = GameProfiles.cachedAuto(id)
            ?: GameArtwork.colorsFromAppIcon(context, packageName)?.also { GameProfiles.cacheAuto(id, it) }
        val title = context.packageManager.getApplicationLabel(info).toString()
        return CurrentGame(id, title, null, packageName, null, auto, BundledGameColors.forId(id), GameProfiles.custom(id))
    }

    private fun homePackages(): List<String> =
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL
        ).map { it.activityInfo.packageName }

    companion object {
        private const val TAG = "GameDetector"
        private const val POLL_MS = 1000L
        private const val SETTLE_MS = 700L
        private const val INITIAL_LOOKBACK_MS = 60_000L
        private const val SWITCH_SYSTEM = "switch"
        private const val APP_ID_PREFIX = "app:"
        private const val SWITCH_ID_PREFIX = "switch:"
        private val SWITCH_TITLE_ID = Regex("""\[([0-9A-Fa-f]{16})]""")
        // A few dozen launches; the whole log is ~500 KB.
        private const val LAUNCH_LOG_TAIL_BYTES = 32_768

        /** Usage access: granted in Settings, or by the shell in extended mode. */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            return appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }
    }
}
