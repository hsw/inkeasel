package io.github.hsw.inkeasel

/**
 * The rest of a CENTER press that is already handled: its repeats while it is held and its release. A long
 * press on a card fires while CENTER is still down (tv-material `Card.onLongClick` acts on the first repeat)
 * and opens the details panel, whose focused button would get the repeats and the release and act on them;
 * the viewer's own CENTER shows its card and must not also press the button the card brings. A dialog
 * starts [armed] (whatever opened it, the press may still be down) and swallows those events until the
 * release or a fresh press, which goes through as usual. With no key held (a touch, or a press already
 * released) nothing is swallowed: the next CENTER event is a fresh press. Pure.
 */
class HeldCenter(armed: Boolean = true) {
    var armed: Boolean = armed
        private set

    /** The CENTER key just pressed is handled here: its repeats and its release are swallowed. */
    fun arm() {
        armed = true
    }

    /**
     * Whether a CENTER key event is swallowed: [down] (else a release) with its [repeat] count (0 for the
     * first down of a press). A repeat of the handled press is; its release is, and ends it; a fresh press
     * ends it and is not.
     */
    fun swallow(down: Boolean, repeat: Int): Boolean = when {
        !armed -> false
        down && repeat == 0 -> {
            armed = false
            false
        }
        down -> true
        else -> {
            armed = false
            true
        }
    }
}
