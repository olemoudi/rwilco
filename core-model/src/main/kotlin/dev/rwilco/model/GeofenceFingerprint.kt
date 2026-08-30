package dev.rwilco.model

import java.security.MessageDigest

/**
 * What a set of registered geofences amounts to, as one short string.
 *
 * Registration was wholesale — remove everything, add what should be there — on every process
 * start, and the place watch's own alarm starts the process every few minutes to an hour. On a
 * phone that keeps the process alive that is nothing; on one that kills it, the fences were
 * torn down and put back at the watch's cadence, and a crossing in the gap between the two is
 * a crossing nobody saw. This is the thing to compare against before touching Play Services:
 * the ids (each carries its circle and which way it is waited on, so a moved pin is a new id)
 * in a fixed order, and whether the app is allowed to watch at all — a grant taken away is a
 * different registration from the same ids with it.
 */
fun geofenceFingerprint(ids: Collection<String>, permitted: Boolean): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(if (permitted) 1 else 0)
    for (id in ids.sorted()) {
        digest.update(id.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
