package app.typelauncher

internal sealed interface PlayUpdateState {
    val shouldPrompt: Boolean
    val showBadge: Boolean
        get() = shouldPrompt

    data object NotAvailable : PlayUpdateState {
        override val shouldPrompt: Boolean = false
    }

    data class Available(
        val versionCode: Int?,
        val isDismissed: Boolean = false,
    ) : PlayUpdateState {
        override val shouldPrompt: Boolean
            get() = !isDismissed
    }
}
