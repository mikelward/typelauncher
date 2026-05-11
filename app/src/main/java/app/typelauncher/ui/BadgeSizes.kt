package app.typelauncher

// Flag/disambiguator corner badge size, expressed as a fraction of the app
// icon's edge. Scales with the icon so the badge stays in proportion at every
// render size (settings list at 32 dp through user-configurable dock/grid up
// to 80 dp), matching the way Android composites the work-profile badge into
// the icon bitmap.
internal const val APP_ICON_CORNER_BADGE_FRACTION = 0.40f
