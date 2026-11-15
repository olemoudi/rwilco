package dev.rwilco.voice

import java.util.Locale

/**
 * An ear that can start listening before the sheet asks it to (0.209.0, the owner: "empiezo a
 * hablar demasiado pronto y la captura empieza con retardo").
 *
 * The recognizer takes a moment to open — longer than the sheet takes to appear — and the hold
 * on the microphone button already ends in a buzz that reads as "go". So the ear is [warm]ed while
 * the finger is still holding, and what it hears meanwhile (that it is ready, the first words) is
 * kept until the sheet [start]s it, which takes it over as it is: the microphone is open by the
 * time anybody sees it, and words said a beat early are not lost.
 *
 * An ear warmed that nobody takes is [cool]ed; one the sheet has taken is the sheet's, and only
 * [stop] or [cancel] touch it. [inner] is asked for at first use, so a device test can put its
 * fake ear in [VoiceInput] after the screen is up. Every call on the main thread, as [Listener]'s.
 */
class EarlyEar(private val make: () -> Listener) : Listener {
    private var made: Listener? = null
    private val inner: Listener get() = made ?: make().also { made = it }

    /** Whether the inner ear is listening, warmed or taken. */
    private var running = false

    /** What a warmed ear heard before anybody took it, in order: null once taken, or when not warmed. */
    private var kept: MutableList<VoiceEvent>? = null

    /** Whoever took the ear: the sheet. */
    private var taker: ((VoiceEvent) -> Unit)? = null

    /** Whether an ear is warmed and waiting to be taken. */
    val warm: Boolean get() = running && taker == null

    /** Starts listening now, keeping what is heard for whoever [start]s next. Nothing if it already listens. */
    fun warm(locale: Locale, words: List<String>) {
        if (running) return
        running = true
        val early = mutableListOf<VoiceEvent>()
        kept = early
        inner.start(locale, words) { event ->
            val sink = taker
            when {
                sink != null -> sink(event)
                // Only how loud it is *now* matters to a sheet that is not there yet.
                event is VoiceEvent.Level -> {
                    early.removeAll { it is VoiceEvent.Level }
                    early += event
                }
                else -> early += event
            }
        }
    }

    /** A warmed ear nobody took is let go of; one the sheet has is left alone. */
    fun cool() {
        if (warm) cancel()
    }

    /** Takes a warmed ear over, with what it has heard so far, or starts one. */
    override fun start(locale: Locale, words: List<String>, onEvent: (VoiceEvent) -> Unit) {
        val early = kept
        kept = null
        taker = onEvent
        if (running && early != null) {
            early.forEach(onEvent)
            return
        }
        running = true
        inner.start(locale, words) { event -> taker?.invoke(event) }
    }

    override fun stop() {
        if (running) inner.stop()
    }

    override fun cancel() {
        running = false
        taker = null
        kept = null
        made?.cancel()
    }
}
