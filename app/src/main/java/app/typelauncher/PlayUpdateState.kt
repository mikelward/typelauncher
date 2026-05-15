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
        val progress: UpdateProgress = UpdateProgress.Idle,
    ) : PlayUpdateState {
        override val shouldPrompt: Boolean
            get() = !isDismissed
    }
}

internal sealed interface UpdateProgress {
    data object Idle : UpdateProgress
    data object Starting : UpdateProgress
    data object Downloading : UpdateProgress
    data object Downloaded : UpdateProgress
}
