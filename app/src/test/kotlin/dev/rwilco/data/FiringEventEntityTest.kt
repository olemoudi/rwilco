package dev.rwilco.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class FiringEventEntityTest {

    @Test
    fun `a row reads back as the happening it recorded`() {
        val row = FiringEventEntity(id = 7, reminderId = "r1", at = 1_700_000_000_000, kind = "SNOOZED", ruleIndex = 2, detail = "2026-08-30T20:45:00Z")
        assertEquals(FiringEvent(FiringKind.SNOOZED, Instant.ofEpochMilli(1_700_000_000_000), 2, "2026-08-30T20:45:00Z"), row.toDomain())
    }

    @Test
    fun `a kind this build has no word for is nothing, not a crash`() {
        // A newer build may write a kind an older one never heard of; the history is diagnostic
        // and a line it cannot say is a line left out.
        assertNull(FiringEventEntity(reminderId = "r1", at = 0, kind = "TELEPORTED").toDomain())
    }
}
