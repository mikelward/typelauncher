package app.typelauncher

import androidx.annotation.StringRes

internal enum class AppCategory(@StringRes val displayNameRes: Int) {
    Games(R.string.category_games),
    Music(R.string.category_music),
    Video(R.string.category_video),
    Photos(R.string.category_photos),
    Social(R.string.category_social),
    News(R.string.category_news),
    Maps(R.string.category_maps),
    Productivity(R.string.category_productivity),
    Accessibility(R.string.category_accessibility),
    Browser(R.string.category_browser),
    Email(R.string.category_email),
    Messaging(R.string.category_messaging),
    Calendar(R.string.category_calendar),
    Contacts(R.string.category_contacts),
    Files(R.string.category_files),
    Fitness(R.string.category_fitness),
    Finance(R.string.category_finance),
    Other(R.string.category_other),
}
