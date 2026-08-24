package dev.rwilco.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateStepTest {

    private fun step(
        isNewer: Boolean = true,
        hasStagedApk: Boolean = false,
        trustedUrl: Boolean = true,
        enoughSpace: Boolean = true,
    ) = nextUpdateStep(isNewer, hasStagedApk, trustedUrl, enoughSpace)

    @Test
    fun `the happy path downloads`() {
        assertEquals(UpdateStep.DOWNLOAD, step())
    }

    @Test
    fun `nothing newer wins over everything`() {
        assertEquals(UpdateStep.NOTHING_TO_DO, step(isNewer = false, hasStagedApk = true, trustedUrl = false, enoughSpace = false))
    }

    @Test
    fun `bytes already on disk beat every network gate below them`() {
        assertEquals(UpdateStep.INSTALL_STAGED, step(hasStagedApk = true))
        assertEquals(UpdateStep.INSTALL_STAGED, step(hasStagedApk = true, trustedUrl = false))
        assertEquals(UpdateStep.INSTALL_STAGED, step(hasStagedApk = true, enoughSpace = false))
    }

    @Test
    fun `an untrusted url is refused before space is even considered`() {
        assertEquals(UpdateStep.UNTRUSTED_URL, step(trustedUrl = false, enoughSpace = false))
        assertEquals(UpdateStep.NEED_SPACE, step(enoughSpace = false))
    }
}
