package dev.rwilco.model

/**
 * What the Home list is narrowed down to.
 *
 * A tag somebody typed, or one of the three the app keeps for itself. Those three are not tags —
 * they are never stored on a reminder, never suggested, never edited — but they behave like them
 * where it matters: they sit in the same row, they filter the same list, and one of them is
 * usually the fastest way to find the reminders that need attention.
 */
sealed interface TagFilter {

    /** A tag as it was typed. */
    data class Named(val tag: String) : TagFilter

    /**
     * Everything carrying no tag at all. Not a filing category — a to-do list about the filing:
     * the reminders somebody has not got round to naming, which is why it sits at the end of
     * the row rather than in the alphabet.
     */
    data object Untagged : TagFilter

    /** Everything paused. The only way to see, at a glance, what has been quietly switched off. */
    data object Paused : TagFilter

    /**
     * Everything that rings somewhere rather than at some time.
     *
     * A tag nobody types, and the one the app can work out best: a place is the thing people
     * write most and remember least — "¿qué tenía yo puesto para cuando llegue?" — and the row
     * is where that question gets asked. Only a *trigger* counts. A place used as a fence ("a
     * las nueve, y sólo si estoy en casa") is a reminder about nine o'clock with a condition on
     * it, and filing it here would answer a question nobody asked.
     */
    data object Place : TagFilter
}

/** Whether a reminder belongs under this filter. */
fun TagFilter.matches(reminder: Reminder): Boolean = when (this) {
    is TagFilter.Named -> reminder.tags.any { it.equals(tag, ignoreCase = true) }
    TagFilter.Untagged -> reminder.tags.isEmpty()
    TagFilter.Paused -> reminder.status == Status.PAUSED
    TagFilter.Place -> reminder.rules.any { it.trigger is Trigger.Location }
}

/**
 * The chips Home offers: every tag in use, and then the app's own three — but only while they
 * have anything in them.
 *
 * The named ones come in the order [pinnedFirst] gives them: whatever the person put at the
 * front of the row, and then the ones they use most.
 *
 * That last part is the whole point of them. "Sin etiqueta" is a job to do, so it appears when
 * there is one and disappears when it is done; a row that always ends in chips nobody can act on
 * is a row people stop reading. Same for "en pausa" and for the places: nothing under one,
 * nothing to say about it.
 */
fun tagFilters(reminders: List<Reminder>, prefs: List<TagPref> = emptyList()): List<TagFilter> {
    val open = reminders.filter { it.status != Status.DONE }
    val chips = ArrayList<TagFilter>()
    // Pinned first, then the rest by use. A pinned tag nothing wears gets no chip all the same:
    // a filter that finds nothing is not a filter, which is the rule the app's own three follow.
    pinnedFirst(tagsInUse(reminders), prefs).mapTo(chips) { TagFilter.Named(it) }
    if (open.any { it.tags.isEmpty() }) chips += TagFilter.Untagged
    if (open.any { it.status == Status.PAUSED }) chips += TagFilter.Paused
    if (open.any { reminder -> reminder.rules.any { it.trigger is Trigger.Location } }) chips += TagFilter.Place
    return chips
}

/** How many reminders a filter would show, for a chip that wants to say so. */
fun TagFilter.countIn(reminders: List<Reminder>): Int =
    reminders.count { it.status != Status.DONE && matches(it) }
