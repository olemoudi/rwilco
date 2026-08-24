package dev.rwilco.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The latest release, as described by the CI-published version.json. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String = "",
    val apk: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): UpdateInfo? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** Pure update decision: newer version code than what's installed, and it has an APK url. */
fun UpdateInfo.isNewerThan(installedVersionCode: Int): Boolean =
    versionCode > installedVersionCode && apk.isNotBlank()

/** What an APK file on disk says it is. See [Updater.apkIdentity]. */
data class ApkIdentity(val packageName: String, val versionCode: Int, val versionName: String)

/**
 * Whether an APK file on disk may be handed to the installer.
 *
 * Three questions, and every one of them has bitten: did it parse as an APK at all ([pkg] is
 * null when it did not — a captive portal's login page served with a 200, or a body cut short
 * when the connection dropped); is it THIS app (nothing else may be installed under our name);
 * and is it newer than the build already running (the installer refuses a downgrade anyway, so
 * retrying one forever is just how a device gets stuck re-downloading fifty megabytes).
 *
 * Deliberately "newer than installed" rather than "exactly what version.json promised": if the
 * release moved on mid-download, the newer APK that arrived is still progress.
 */
fun apkIsInstallable(
    pkg: String?,
    apkVersionCode: Int,
    ourPackage: String,
    installedVersionCode: Int,
): Boolean = pkg == ourPackage && apkVersionCode > installedVersionCode

/**
 * Whether the downloaded APK survives an install that did not succeed.
 *
 * ABORTED is somebody tapping "Cancel" — by reflex at least as often as on purpose — and
 * BLOCKED is a policy or another installer standing in the way. In both, the bytes are perfectly
 * good and the retry should cost nothing. Anything else means the file, the storage or the
 * device is the problem, and keeping fifty megabytes of it helps nobody.
 */
fun keepsApkAfterFailure(status: Int): Boolean =
    status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED ||
        status == android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED

/** What a check does next, once it knows everything it can learn without acting. */
enum class UpdateStep {
    NOTHING_TO_DO,
    /** An APK for this update is already on disk and checked: install it, download nothing. */
    INSTALL_STAGED,
    UNTRUSTED_URL,
    NEED_SPACE,
    DOWNLOAD,
}

/**
 * The order in which one update check makes its decisions, as a table rather than as the shape
 * of some function — because the order IS the behaviour: a staged APK outranks every network
 * consideration under it, because bytes already on disk cost no data and need no url to be
 * trusted — they were checked when they arrived. This is what makes "I cancelled the prompt by
 * mistake" a one-tap fix instead of another fifty-megabyte download on the next check.
 */
fun nextUpdateStep(
    isNewer: Boolean,
    hasStagedApk: Boolean,
    trustedUrl: Boolean,
    enoughSpace: Boolean,
): UpdateStep = when {
    !isNewer -> UpdateStep.NOTHING_TO_DO
    hasStagedApk -> UpdateStep.INSTALL_STAGED
    !trustedUrl -> UpdateStep.UNTRUSTED_URL
    !enoughSpace -> UpdateStep.NEED_SPACE
    else -> UpdateStep.DOWNLOAD
}
