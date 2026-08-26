package dev.rwilco.vault

import dev.rwilco.data.ReminderEntity
import dev.rwilco.model.InstantSerializer
import dev.rwilco.model.ReminderCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * The data version of a snapshot. `1` is the column set of Room schema 5 (`app/schemas/…/5.json`).
 *
 * Bump it — with a step in [VAULT_MIGRATIONS] and a fixture under `src/test/resources/vault` —
 * for any change to the rows or to this shape that is not purely additive. An additive change
 * (a new column with a default, a new field here) needs nothing: the vault is read with the same
 * leniency as the database. `VaultSchemaTest` is what makes forgetting fail in CI.
 */
const val VAULT_SCHEMA = 1

/**
 * Everything the phone would need back, in one piece. Never written to disk in this form: it
 * exists in memory between the database and [VaultCrypto.seal].
 *
 * The rows go as rows rather than as domain objects, on purpose: their columns are already a
 * frozen contract, their JSON text columns are read by [ReminderCodec] on the way back in, and
 * so a vault inherits every rule the database has for reading what a newer or older build
 * wrote — unknown trigger kinds dropped one at a time, a bare trigger list from v0.1.0, an
 * unknown status read as active. The settings go as the blob they are, whole, for the same
 * reason. Nothing is stripped: the armed moments are what lets a restore ring, late, whatever
 * fell due while there was no phone to ring it — the restore behaves like a reboot.
 */
@Serializable
data class VaultSnapshot(
    val schema: Int = VAULT_SCHEMA,
    /** [dev.rwilco.data.RwilcoDatabase.VERSION] at export, for the diagnostics only. */
    val dbVersion: Int = 0,
    /** The build that wrote it; a restore from a newer one is warned about (see [summary]). */
    val appVersionCode: Int = 0,
    @Serializable(with = InstantSerializer::class) val exportedAt: Instant = Instant.EPOCH,
    /** Which install wrote it — a random id minted when the vault was enabled; preview text only. */
    val deviceId: String = "",
    val reminders: List<ReminderEntity> = emptyList(),
    /** `settings_json` as `SettingsStore` holds it. */
    val settingsJson: String = "",
)

/** What a snapshot says about itself before anybody agrees to restore it. */
data class VaultSummary(
    val exportedAt: Instant,
    val deviceId: String,
    val appVersionCode: Int,
    val active: Int,
    val done: Int,
    val presets: Int,
    val places: Int,
    /**
     * Written by a newer Rwilco than the one running. Not refused — the rows keep what this build
     * does not understand until it updates — but said out loud, because the editor's next save
     * would rewrite a row without the parts it could not read.
     */
    val newerThanThisApp: Boolean,
)

/** The JSON of the snapshot and the envelope: lenient on the way in, complete on the way out. */
internal val vaultJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // A nullable column left out by some future writer reads as null, not as a refusal.
    explicitNulls = false
}

private val rowList = ListSerializer(ReminderEntity.serializer())

fun buildSnapshot(
    rows: List<ReminderEntity>,
    settingsJson: String,
    exportedAt: Instant,
    deviceId: String,
    appVersionCode: Int,
    dbVersion: Int,
): VaultSnapshot = VaultSnapshot(
    dbVersion = dbVersion,
    appVersionCode = appVersionCode,
    exportedAt = exportedAt,
    deviceId = deviceId,
    reminders = rows.sortedBy { it.id },
    settingsJson = settingsJson,
)

fun encodeSnapshot(snapshot: VaultSnapshot): ByteArray =
    vaultJson.encodeToString(VaultSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8)

/**
 * What decides whether there is anything to upload: the content, and only the content. The
 * scheduler's own write-backs — the moment armed and which rule it is for — are left out, or
 * every re-arm would be a change worth a commit; so is the order the rows came in, and so are
 * the stamps that differ between two exports of the same thing.
 */
fun fingerprint(rows: List<ReminderEntity>, settingsJson: String): String {
    val content = rows.sortedBy { it.id }.map { it.copy(armedFor = null, armedRule = null) }
    val text = vaultJson.encodeToString(rowList, content) + "\n" + settingsJson
    return VaultCrypto.sha256Hex(text.toByteArray(Charsets.UTF_8))
}

fun VaultSnapshot.fingerprint(): String = fingerprint(reminders, settingsJson)

fun VaultSnapshot.summary(runningVersionCode: Int): VaultSummary {
    val settings = ReminderCodec.decodeSettings(settingsJson)
    val done = reminders.count { it.status == "DONE" }
    return VaultSummary(
        exportedAt = exportedAt,
        deviceId = deviceId,
        appVersionCode = appVersionCode,
        active = reminders.size - done,
        done = done,
        presets = settings.presets.size,
        places = settings.savedPlaces.size,
        newerThanThisApp = appVersionCode > runningVersionCode,
    )
}
