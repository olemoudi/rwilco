package dev.rwilco.vault

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One step per data version, over the raw JSON, the way `RwilcoDatabase.MIGRATIONS` is one step
 * per schema version over SQL: index `i` takes a snapshot from schema `i + 1` to `i + 2`. Empty
 * while there has only ever been one shape.
 */
val VAULT_MIGRATIONS: List<(JsonObject) -> JsonObject> = emptyList()

/**
 * A decrypted snapshot, brought up to [VAULT_SCHEMA] and read leniently. Refuses, never guesses:
 * a snapshot from a data version this build does not know is [VaultException.NewerThanThisApp],
 * and anything that is not a snapshot at all is [VaultException.Corrupt].
 */
fun decodeSnapshot(plain: ByteArray): VaultSnapshot {
    val root = runCatching { vaultJson.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject }
        .getOrElse { throw VaultException.Corrupt("not a snapshot") }
    var schema = root["schema"]?.jsonPrimitive?.intOrNull ?: throw VaultException.Corrupt("no data version")
    if (schema > VAULT_SCHEMA) throw VaultException.NewerThanThisApp("data version $schema")
    if (schema < 1) throw VaultException.Corrupt("data version $schema")
    var current = root
    while (schema < VAULT_SCHEMA) {
        current = VAULT_MIGRATIONS[schema - 1](current)
        schema++
    }
    val stamped = JsonObject(current + ("schema" to JsonPrimitive(VAULT_SCHEMA)))
    return runCatching { vaultJson.decodeFromJsonElement(VaultSnapshot.serializer(), stamped) }
        .getOrElse { throw VaultException.Corrupt("snapshot does not read: ${it.message}") }
}
