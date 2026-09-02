package dev.rwilco.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.PendingIntentCompat
import dev.rwilco.Distribution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Outcome of one update check, so callers can decide whether a retry makes sense. */
enum class UpdateCheckOutcome {
    UP_TO_DATE,
    /** An install session was committed; the system asks for confirmation from here. */
    INSTALL_STARTED,
    /** Transient problem (network fetch/download) — worth retrying with backoff. */
    TRANSIENT_FAILURE,
    /** The install session itself failed — retrying immediately won't help. */
    INSTALL_FAILURE,
    /** Another check already holds the lock; this caller stood aside instead of fighting it. */
    BUSY,
}

/**
 * Self-updates from GitHub Releases. The install goes through PackageInstaller; when the system
 * insists on confirmation, [InstallReceiver] surfaces it as a notification.
 *
 * The downloaded APK in the cache is deliberately the ONLY record that an update is pending.
 * Nothing else is persisted, and nothing needs to be: the file answers "is there one?", "which
 * build?" and "can it be installed right now?" by itself (see [stagedUpdate]), it survives the
 * process dying between download and install, and a phone that has just been updated proves it
 * by no longer accepting it.
 */
class Updater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        // GitHub redirects the stable asset urls to its CDN, which is both fine and necessary —
        // but never to plaintext. Left at the default, a single redirect is all it would take
        // for the APK to arrive over a connection anyone on the same Wi-Fi can rewrite.
        .followSslRedirects(false)
        .build()

    /**
     * Single-flight wrapper: update checks fire from several places and two overlapping runs
     * are actively harmful — install() abandons stale sessions, so a concurrent run would abort
     * the other's half-written session, and both would download the full APK. A second caller
     * reports [UpdateCheckOutcome.BUSY] and lets the first finish.
     */
    suspend fun checkAndUpdate(): UpdateCheckOutcome {
        if (!updateMutex.tryLock()) {
            Log.i(TAG, "update check already in flight; skipping")
            return UpdateCheckOutcome.BUSY
        }
        try {
            return doCheckAndUpdate()
        } finally {
            updateMutex.unlock()
        }
    }

    /**
     * Installs an update that is already on disk, touching no network at all: the way back for
     * somebody who dismissed the system's install prompt or swiped the notification away.
     */
    suspend fun installStaged(): UpdateCheckOutcome {
        val staged = stagedUpdate() ?: return UpdateCheckOutcome.UP_TO_DATE
        if (!updateMutex.tryLock()) {
            Log.i(TAG, "install already in flight; skipping")
            return UpdateCheckOutcome.BUSY
        }
        try {
            return withContext(Dispatchers.IO) { commit(apkFile(), staged) }
        } finally {
            updateMutex.unlock()
        }
    }

    /**
     * The update already downloaded and waiting in the cache, if it is one this device could
     * install right now — otherwise null. Null covers every way the file can be useless: there
     * is no file; it does not parse as an APK at all (a captive portal's login page served with
     * a 200, a body cut short when the connection dropped); it is not this package; or it is not
     * newer than the build already running (the leftovers of the update that just succeeded).
     */
    fun stagedUpdate(): UpdateInfo? {
        val apk = apkIdentity(apkFile()) ?: return null
        if (!apkIsInstallable(apk.packageName, apk.versionCode, context.packageName, currentVersionCode())) {
            return null
        }
        return UpdateInfo(versionCode = apk.versionCode, versionName = apk.versionName)
    }

    /** What a file on disk says it is, or null if it is not an APK at all (the platform parses it). */
    internal fun apkIdentity(file: File): ApkIdentity? {
        if (!file.isFile || file.length() == 0L) return null
        val archive = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return null
        return ApkIdentity(archive.packageName, archive.longVersionCode.toInt(), archive.versionName.orEmpty())
    }

    private suspend fun doCheckAndUpdate(): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        Log.i(TAG, "checking for update")
        UpdateCenter.report(UpdateUiState.Checking)
        val info = runCatching { fetchInfo() }.onFailure { Log.w(TAG, "fetch failed", it) }.getOrNull()
        if (info == null) {
            Log.w(TAG, "no version info")
            UpdateCenter.report(UpdateUiState.Failed("fetch"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        val current = currentVersionCode()
        Log.i(TAG, "installed=$current latest=${info.versionCode}")
        val staged = stagedUpdate()
        val step = nextUpdateStep(
            isNewer = info.isNewerThan(current),
            hasStagedApk = staged != null,
            trustedUrl = trustedApkUrl(info.apk),
            enoughSpace = context.cacheDir.usableSpace >= REQUIRED_FREE_BYTES,
        )
        Log.i(TAG, "next step: $step")
        // An update there is no permission to install would fail every twelve hours with only
        // the Settings card to say so (0.68.0): said in the shade instead, with the way to it.
        if (step != UpdateStep.NOTHING_TO_DO && !context.packageManager.canRequestPackageInstalls()) {
            Log.w(TAG, "an update is available but installing from this app is not allowed")
            UpdateNotifications.notifyInstallPermissionNeeded(context)
            UpdateCenter.report(UpdateUiState.Failed("install permission"))
            return@withContext UpdateCheckOutcome.INSTALL_FAILURE
        }
        when (step) {
            UpdateStep.NOTHING_TO_DO -> {
                // Anything still in the cache is for a build this device has already passed —
                // usually the APK it just installed, whose success broadcast never reached the
                // process that was replaced.
                discardStagedApk()
                UpdateCenter.report(UpdateUiState.UpToDate(current))
                return@withContext UpdateCheckOutcome.UP_TO_DATE
            }
            UpdateStep.INSTALL_STAGED -> {
                // Committed again on every check, deliberately, even when the last one is still
                // waiting to be confirmed: on Android 14 an ongoing notification can still be
                // swiped away, and this is what brings the prompt back.
                val ready = staged!!
                Log.i(TAG, "installing the ${ready.versionName} APK already in the cache")
                return@withContext commit(apkFile(), ready)
            }
            UpdateStep.UNTRUSTED_URL -> {
                Log.e(TAG, "refusing an APK url outside the release host: ${info.apk}")
                UpdateCenter.report(UpdateUiState.Failed("untrusted url"))
                return@withContext UpdateCheckOutcome.INSTALL_FAILURE
            }
            UpdateStep.NEED_SPACE -> {
                Log.w(TAG, "not enough free space for the update (${context.cacheDir.usableSpace} bytes)")
                UpdateCenter.report(UpdateUiState.Failed("no space"))
                return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
            }
            UpdateStep.DOWNLOAD -> Unit
        }
        UpdateCenter.report(UpdateUiState.Downloading(info))
        val downloaded = runCatching { download(info.apk) }
            .onFailure { Log.w(TAG, "download failed", it) }
            .isSuccess
        if (!downloaded) {
            UpdateCenter.report(UpdateUiState.Failed("download"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        val arrived = stagedUpdate()
        if (arrived == null) {
            // What came down is not a Rwilco build newer than this one. Handing it to the
            // installer would only fail later, and less legibly.
            Log.e(TAG, "the downloaded file is not an installable Rwilco APK; discarding it")
            discardStagedApk()
            UpdateCenter.report(UpdateUiState.Failed("bad download"))
            return@withContext UpdateCheckOutcome.TRANSIENT_FAILURE
        }
        commit(apkFile(), arrived)
    }

    /** Commits the install session for a checked APK; the final status lands in [InstallReceiver]. */
    private fun commit(apk: File, target: UpdateInfo): UpdateCheckOutcome {
        Log.i(TAG, "installing ${apk.length()} bytes (${target.versionName})")
        UpdateCenter.report(UpdateUiState.Installing(target))
        // Any prompt still in the shade belongs to a session install() is about to abandon.
        UpdateNotifications.cancel(context)
        val installError = runCatching { install(apk) }
            .onFailure { Log.e(TAG, "install failed", it) }
            .exceptionOrNull()
        if (installError != null) {
            UpdateCenter.report(UpdateUiState.Failed("install: ${installError.javaClass.simpleName}"))
            return UpdateCheckOutcome.INSTALL_FAILURE
        }
        return UpdateCheckOutcome.INSTALL_STARTED
    }

    private fun currentVersionCode(): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()

    private fun apkFile(): File = File(context.cacheDir, APK_FILE)

    private fun discardStagedApk() {
        runCatching { apkFile().delete() }
    }

    private fun fetchInfo(): UpdateInfo? {
        client.newCall(Request.Builder().url(Distribution.VERSION_JSON_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            // Bounded: version.json is a hundred bytes. Whatever else ends up behind that url
            // must not be read into memory whole.
            return UpdateInfo.parse(resp.peekBody(MAX_INFO_BYTES).string())
        }
    }

    private fun download(url: String) {
        val target = apkFile()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            require(resp.isSuccessful) { "download failed: ${resp.code}" }
            resp.body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        Log.i(TAG, "downloaded ${target.length()} bytes")
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        // Abandon sessions leaked by earlier failed attempts, so createSession can't eventually
        // hit "Too many active sessions". One at a time: a session that refuses to be abandoned
        // must not stop the rest from being cleared.
        runCatching { installer.mySessions }.getOrDefault(emptyList()).forEach {
            runCatching { installer.abandonSession(it.sessionId) }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // A request, not a guarantee: silent once Rwilco is its own installer of record;
            // otherwise the system asks, which lands in InstallReceiver as pending-user-action.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        Log.i(TAG, "session $sessionId created")
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("rwilco", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                Log.i(TAG, "wrote ${apk.length()} bytes; committing")
                val statusIntent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
                val pending = PendingIntentCompat.getBroadcast(
                    context, sessionId, statusIntent, PendingIntent.FLAG_UPDATE_CURRENT, true,
                )!!
                session.commit(pending.intentSender)
                Log.i(TAG, "session committed")
            }
        } catch (t: Throwable) {
            // Don't leave the half-written session behind for the next attempt to trip over.
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }

    companion object {
        private const val TAG = "RwilcoUpdater"

        /** Process-wide: Updater is instantiated per check, so the lock must be shared. */
        private val updateMutex = Mutex()

        /** Downloaded APK, deleted once the install reaches a terminal state (see InstallReceiver). */
        const val APK_FILE = "update.apk"

        /** Free space required before downloading, so a full phone fails fast instead of mid-write. */
        private const val REQUIRED_FREE_BYTES = 200L * 1024 * 1024

        /** Ceiling on the version.json body we will read (see [fetchInfo]). */
        private const val MAX_INFO_BYTES = 64L * 1024

        /** Where the APK must come from. See [trustedApkUrl]. */
        private const val APK_HOST = "github.com"
        private const val APK_PATH_PREFIX = "/olemoudi/rwilco/"

        /**
         * Whether an APK url from version.json may be downloaded. The OS already refuses to
         * install anything not signed with our key, so this is belt-and-braces — but pointing
         * the downloader at an arbitrary host is not something version.json should be able to do.
         *
         * Parsed rather than string-matched: OkHttp normalises
         * `https://github.com/olemoudi/rwilco/../../someone/evil.apk` down to a different
         * repository entirely, and a `startsWith` over the raw text waves that straight through.
         */
        fun trustedApkUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            return parsed.scheme == "https" &&
                parsed.host == APK_HOST &&
                parsed.encodedPath.startsWith(APK_PATH_PREFIX)
        }
    }
}
