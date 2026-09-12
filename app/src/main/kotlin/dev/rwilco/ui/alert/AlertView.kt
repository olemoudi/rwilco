package dev.rwilco.ui.alert

/*
 * What "Ver" does about the lock screen.
 *
 * The alert draws over the keyguard (showWhenLocked) and MainActivity does not — and must not:
 * the whole app over a locked phone is everybody's reminders readable by whoever picks it up. So
 * on a locked phone "Ver" asks the system for the unlock first, and the reminder stays on the
 * alert until that goes through. Letting it go any earlier is what left somebody holding "Ver" at
 * three in the morning looking at their lock screen with the alert gone and nothing to come back
 * to but the notification.
 */

/** Where "Ver" gets to. */
enum class ViewStep {
    /** Straight in: the phone is not locked, or the unlock has just gone through. */
    OPEN,

    /** Ask the system to take the keyguard away, and ask this again with what it answers. */
    ASK_TO_UNLOCK,

    /** Nowhere. The alert stays exactly as it was, with every answer still on it. */
    STAY,
}

/** What the keyguard answered, once it has been asked. */
enum class UnlockAnswer { SUCCEEDED, CANCELLED, ERROR }

/** The step "Ver" takes, given whether the phone is locked and what the unlock said, if anything. */
fun viewStep(locked: Boolean, unlock: UnlockAnswer? = null): ViewStep = when {
    !locked -> ViewStep.OPEN
    unlock == null -> ViewStep.ASK_TO_UNLOCK
    unlock == UnlockAnswer.SUCCEEDED -> ViewStep.OPEN
    else -> ViewStep.STAY
}
