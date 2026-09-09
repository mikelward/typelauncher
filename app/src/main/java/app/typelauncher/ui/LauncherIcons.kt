package app.typelauncher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Every Material icon this launcher draws, held locally instead of pulled from
 * `material-icons-extended`.
 *
 * That library ships several thousand `ImageVector`s to supply the twenty-two
 * below. R8 strips the rest from the release build, so the shipped app is
 * unaffected — but the debug build never minifies, and it is the debug build a
 * developer installs on a phone.
 *
 * **All of them are here, including the ones `material-icons-core` would still
 * cover.** A split set leaves no way to tell by looking where a given icon came
 * from, and the next icon someone reaches for would quietly pull the dependency
 * back in. Vendored whole, adding a twenty-third is a deliberate act: copy its
 * vector in here.
 *
 * The vectors were read out of the library itself rather than transcribed, and
 * the builder defaults below are the ones its own `materialIcon` / `materialPath`
 * helpers use, so what is drawn is what was drawn before — and the recorded
 * screenshots are what prove it.
 *
 * They remain Material Design icons from the Android Open Source Project,
 * licensed under Apache-2.0. Holding a copy rather than a dependency does not
 * change what is owed for them, so the Licenses page still carries their
 * attribution — see the vendored entry in `exportBundledLicenses`, which the
 * classpath filter would otherwise drop along with the dependency.
 */
object LauncherIcons {
    /** Material Symbols `AutoMirrored.Filled.ArrowBack`. */
    val ArrowBack: ImageVector by lazy {
        icon("AutoMirrored.Filled.ArrowBack", autoMirror = true) {
            materialPath {
                moveTo(20f, 11f)
                horizontalLineTo(7.83f)
                lineToRelative(5.59f, -5.59f)
                lineTo(12f, 4f)
                lineToRelative(-8f, 8f)
                lineToRelative(8f, 8f)
                lineToRelative(1.41f, -1.41f)
                lineTo(7.83f, 13f)
                horizontalLineTo(20f)
                verticalLineToRelative(-2f)
                close()
            }
        }
    }

    /** Material Symbols `AutoMirrored.Filled.KeyboardArrowLeft`. */
    val KeyboardArrowLeft: ImageVector by lazy {
        icon("AutoMirrored.Filled.KeyboardArrowLeft", autoMirror = true) {
            materialPath {
                moveTo(15.41f, 16.59f)
                lineTo(10.83f, 12f)
                lineToRelative(4.58f, -4.59f)
                lineTo(14f, 6f)
                lineToRelative(-6f, 6f)
                lineToRelative(6f, 6f)
                lineToRelative(1.41f, -1.41f)
                close()
            }
        }
    }

    /** Material Symbols `AutoMirrored.Filled.KeyboardArrowRight`. */
    val KeyboardArrowRight: ImageVector by lazy {
        icon("AutoMirrored.Filled.KeyboardArrowRight", autoMirror = true) {
            materialPath {
                moveTo(8.59f, 16.59f)
                lineTo(13.17f, 12f)
                lineTo(8.59f, 7.41f)
                lineTo(10f, 6f)
                lineToRelative(6f, 6f)
                lineToRelative(-6f, 6f)
                lineToRelative(-1.41f, -1.41f)
                close()
            }
        }
    }

    /** Material Symbols `AutoMirrored.Filled.Message`. */
    val Message: ImageVector by lazy {
        icon("AutoMirrored.Filled.Message", autoMirror = true) {
            materialPath {
                moveTo(20f, 2f)
                lineTo(4f, 2f)
                curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(2f, 22f)
                lineToRelative(4f, -4f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(22f, 4f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(18f, 14f)
                lineTo(6f, 14f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(2f)
                close()
                moveTo(18f, 11f)
                lineTo(6f, 11f)
                lineTo(6f, 9f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(2f)
                close()
                moveTo(18f, 8f)
                lineTo(6f, 8f)
                lineTo(6f, 6f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(2f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Add`. */
    val Add: ImageVector by lazy {
        icon("Filled.Add", autoMirror = false) {
            materialPath {
                moveTo(19f, 13f)
                horizontalLineToRelative(-6f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-6f)
                horizontalLineTo(5f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(6f)
                verticalLineTo(5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(2f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.ArrowDropDown`. */
    val ArrowDropDown: ImageVector by lazy {
        icon("Filled.ArrowDropDown", autoMirror = false) {
            materialPath {
                moveTo(7f, 10f)
                lineToRelative(5f, 5f)
                lineToRelative(5f, -5f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Call`. */
    val Call: ImageVector by lazy {
        icon("Filled.Call", autoMirror = false) {
            materialPath {
                moveTo(20.01f, 15.38f)
                curveToRelative(-1.23f, 0f, -2.42f, -0.2f, -3.53f, -0.56f)
                curveToRelative(-0.35f, -0.12f, -0.74f, -0.03f, -1.01f, 0.24f)
                lineToRelative(-1.57f, 1.97f)
                curveToRelative(-2.83f, -1.35f, -5.48f, -3.9f, -6.89f, -6.83f)
                lineToRelative(1.95f, -1.66f)
                curveToRelative(0.27f, -0.28f, 0.35f, -0.67f, 0.24f, -1.02f)
                curveToRelative(-0.37f, -1.11f, -0.56f, -2.3f, -0.56f, -3.53f)
                curveToRelative(0f, -0.54f, -0.45f, -0.99f, -0.99f, -0.99f)
                horizontalLineTo(4.19f)
                curveTo(3.65f, 3f, 3f, 3.24f, 3f, 3.99f)
                curveTo(3f, 13.28f, 10.73f, 21f, 20.01f, 21f)
                curveToRelative(0.71f, 0f, 0.99f, -0.63f, 0.99f, -1.18f)
                verticalLineToRelative(-3.45f)
                curveToRelative(0f, -0.54f, -0.45f, -0.99f, -0.99f, -0.99f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Clear`. */
    val Clear: ImageVector by lazy {
        icon("Filled.Clear", autoMirror = false) {
            materialPath {
                moveTo(19f, 6.41f)
                lineTo(17.59f, 5f)
                lineTo(12f, 10.59f)
                lineTo(6.41f, 5f)
                lineTo(5f, 6.41f)
                lineTo(10.59f, 12f)
                lineTo(5f, 17.59f)
                lineTo(6.41f, 19f)
                lineTo(12f, 13.41f)
                lineTo(17.59f, 19f)
                lineTo(19f, 17.59f)
                lineTo(13.41f, 12f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.DragHandle`. */
    val DragHandle: ImageVector by lazy {
        icon("Filled.DragHandle", autoMirror = false) {
            materialPath {
                moveTo(20f, 9f)
                horizontalLineTo(4f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(16f)
                verticalLineTo(9f)
                close()
                moveTo(4f, 15f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(-2f)
                horizontalLineTo(4f)
                verticalLineTo(15f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Email`. */
    val Email: ImageVector by lazy {
        icon("Filled.Email", autoMirror = false) {
            materialPath {
                moveTo(20f, 4f)
                lineTo(4f, 4f)
                curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(2f, 18f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(16f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(22f, 6f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(20f, 8f)
                lineToRelative(-8f, 5f)
                lineToRelative(-8f, -5f)
                lineTo(4f, 6f)
                lineToRelative(8f, 5f)
                lineToRelative(8f, -5f)
                verticalLineToRelative(2f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.EventBusy`. */
    val EventBusy: ImageVector by lazy {
        icon("Filled.EventBusy", autoMirror = false) {
            materialPath {
                moveTo(9.31f, 17f)
                lineToRelative(2.44f, -2.44f)
                lineTo(14.19f, 17f)
                lineToRelative(1.06f, -1.06f)
                lineToRelative(-2.44f, -2.44f)
                lineToRelative(2.44f, -2.44f)
                lineTo(14.19f, 10f)
                lineToRelative(-2.44f, 2.44f)
                lineTo(9.31f, 10f)
                lineToRelative(-1.06f, 1.06f)
                lineToRelative(2.44f, 2.44f)
                lineToRelative(-2.44f, 2.44f)
                lineTo(9.31f, 17f)
                close()
                moveTo(19f, 3f)
                horizontalLineToRelative(-1f)
                lineTo(18f, 1f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(2f)
                lineTo(8f, 3f)
                lineTo(8f, 1f)
                lineTo(6f, 1f)
                verticalLineToRelative(2f)
                lineTo(5f, 3f)
                curveToRelative(-1.11f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(3f, 19f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(21f, 5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(19f, 19f)
                lineTo(5f, 19f)
                lineTo(5f, 8f)
                horizontalLineToRelative(14f)
                verticalLineToRelative(11f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.ExpandLess`. */
    val ExpandLess: ImageVector by lazy {
        icon("Filled.ExpandLess", autoMirror = false) {
            materialPath {
                moveTo(12f, 8f)
                lineToRelative(-6f, 6f)
                lineToRelative(1.41f, 1.41f)
                lineTo(12f, 10.83f)
                lineToRelative(4.59f, 4.58f)
                lineTo(18f, 14f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.ExpandMore`. */
    val ExpandMore: ImageVector by lazy {
        icon("Filled.ExpandMore", autoMirror = false) {
            materialPath {
                moveTo(16.59f, 8.59f)
                lineTo(12f, 13.17f)
                lineTo(7.41f, 8.59f)
                lineTo(6f, 10f)
                lineToRelative(6f, 6f)
                lineToRelative(6f, -6f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.KeyboardArrowDown`. */
    val KeyboardArrowDown: ImageVector by lazy {
        icon("Filled.KeyboardArrowDown", autoMirror = false) {
            materialPath {
                moveTo(7.41f, 8.59f)
                lineTo(12f, 13.17f)
                lineToRelative(4.59f, -4.58f)
                lineTo(18f, 10f)
                lineToRelative(-6f, 6f)
                lineToRelative(-6f, -6f)
                lineToRelative(1.41f, -1.41f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.KeyboardArrowUp`. */
    val KeyboardArrowUp: ImageVector by lazy {
        icon("Filled.KeyboardArrowUp", autoMirror = false) {
            materialPath {
                moveTo(7.41f, 15.41f)
                lineTo(12f, 10.83f)
                lineToRelative(4.59f, 4.58f)
                lineTo(18f, 14f)
                lineToRelative(-6f, -6f)
                lineToRelative(-6f, 6f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.MoreVert`. */
    val MoreVert: ImageVector by lazy {
        icon("Filled.MoreVert", autoMirror = false) {
            materialPath {
                moveTo(12f, 8f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
                reflectiveCurveToRelative(-2f, 0.9f, -2f, 2f)
                reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
                close()
                moveTo(12f, 10f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
                reflectiveCurveToRelative(2f, -0.9f, 2f, -2f)
                reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
                close()
                moveTo(12f, 16f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
                reflectiveCurveToRelative(2f, -0.9f, 2f, -2f)
                reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Person`. */
    val Person: ImageVector by lazy {
        icon("Filled.Person", autoMirror = false) {
            materialPath {
                moveTo(12f, 12f)
                curveToRelative(2.21f, 0f, 4f, -1.79f, 4f, -4f)
                reflectiveCurveToRelative(-1.79f, -4f, -4f, -4f)
                reflectiveCurveToRelative(-4f, 1.79f, -4f, 4f)
                reflectiveCurveToRelative(1.79f, 4f, 4f, 4f)
                close()
                moveTo(12f, 14f)
                curveToRelative(-2.67f, 0f, -8f, 1.34f, -8f, 4f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(-2f)
                curveToRelative(0f, -2.66f, -5.33f, -4f, -8f, -4f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Search`. */
    val Search: ImageVector by lazy {
        icon("Filled.Search", autoMirror = false) {
            materialPath {
                moveTo(15.5f, 14f)
                horizontalLineToRelative(-0.79f)
                lineToRelative(-0.28f, -0.27f)
                curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
                curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
                reflectiveCurveTo(3f, 5.91f, 3f, 9.5f)
                reflectiveCurveTo(5.91f, 16f, 9.5f, 16f)
                curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
                lineToRelative(0.27f, 0.28f)
                verticalLineToRelative(0.79f)
                lineToRelative(5f, 4.99f)
                lineTo(20.49f, 19f)
                lineToRelative(-4.99f, -5f)
                close()
                moveTo(9.5f, 14f)
                curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
                reflectiveCurveTo(7.01f, 5f, 9.5f, 5f)
                reflectiveCurveTo(14f, 7.01f, 14f, 9.5f)
                reflectiveCurveTo(11.99f, 14f, 9.5f, 14f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Settings`. */
    val Settings: ImageVector by lazy {
        icon("Filled.Settings", autoMirror = false) {
            materialPath {
                moveTo(19.14f, 12.94f)
                curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
                curveToRelative(0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
                lineToRelative(2.03f, -1.58f)
                curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
                lineToRelative(-1.92f, -3.32f)
                curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
                lineToRelative(-2.39f, 0.96f)
                curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
                lineTo(14.4f, 2.81f)
                curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
                horizontalLineToRelative(-3.84f)
                curveToRelative(-0.24f, 0f, -0.43f, 0.17f, -0.47f, 0.41f)
                lineTo(9.25f, 5.35f)
                curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
                lineTo(5.24f, 5.33f)
                curveToRelative(-0.22f, -0.08f, -0.47f, 0f, -0.59f, 0.22f)
                lineTo(2.74f, 8.87f)
                curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
                lineToRelative(2.03f, 1.58f)
                curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12f)
                reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
                lineToRelative(-2.03f, 1.58f)
                curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
                lineToRelative(1.92f, 3.32f)
                curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
                lineToRelative(2.39f, -0.96f)
                curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
                lineToRelative(0.36f, 2.54f)
                curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
                horizontalLineToRelative(3.84f)
                curveToRelative(0.24f, 0f, 0.44f, -0.17f, 0.47f, -0.41f)
                lineToRelative(0.36f, -2.54f)
                curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
                lineToRelative(2.39f, 0.96f)
                curveToRelative(0.22f, 0.08f, 0.47f, 0f, 0.59f, -0.22f)
                lineToRelative(1.92f, -3.32f)
                curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
                lineTo(19.14f, 12.94f)
                close()
                moveTo(12f, 15.6f)
                curveToRelative(-1.98f, 0f, -3.6f, -1.62f, -3.6f, -3.6f)
                reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
                reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
                reflectiveCurveTo(13.98f, 15.6f, 12f, 15.6f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Star`. */
    val Star: ImageVector by lazy {
        icon("Filled.Star", autoMirror = false) {
            materialPath {
                moveTo(12f, 17.27f)
                lineTo(18.18f, 21f)
                lineToRelative(-1.64f, -7.03f)
                lineTo(22f, 9.24f)
                lineToRelative(-7.19f, -0.61f)
                lineTo(12f, 2f)
                lineTo(9.19f, 8.63f)
                lineTo(2f, 9.24f)
                lineToRelative(5.46f, 4.73f)
                lineTo(5.82f, 21f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Warning`. */
    val Warning: ImageVector by lazy {
        icon("Filled.Warning", autoMirror = false) {
            materialPath {
                moveTo(1f, 21f)
                horizontalLineToRelative(22f)
                lineTo(12f, 2f)
                lineTo(1f, 21f)
                close()
                moveTo(13f, 18f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                close()
                moveTo(13f, 14f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-4f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(4f)
                close()
            }
        }
    }

    /** Material Symbols `Filled.Widgets`. */
    val Widgets: ImageVector by lazy {
        icon("Filled.Widgets", autoMirror = false) {
            materialPath {
                moveTo(13f, 13f)
                verticalLineToRelative(8f)
                horizontalLineToRelative(8f)
                verticalLineToRelative(-8f)
                horizontalLineToRelative(-8f)
                close()
                moveTo(3f, 21f)
                horizontalLineToRelative(8f)
                verticalLineToRelative(-8f)
                lineTo(3f, 13f)
                verticalLineToRelative(8f)
                close()
                moveTo(3f, 3f)
                verticalLineToRelative(8f)
                horizontalLineToRelative(8f)
                lineTo(11f, 3f)
                lineTo(3f, 3f)
                close()
                moveTo(16.66f, 1.69f)
                lineTo(11f, 7.34f)
                lineTo(16.66f, 13f)
                lineToRelative(5.66f, -5.66f)
                lineToRelative(-5.66f, -5.65f)
                close()
            }
        }
    }
}

/** The frame every Material icon is drawn in: a 24dp square on a 24-unit viewport. */
private fun icon(
    name: String,
    autoMirror: Boolean = false,
    block: ImageVector.Builder.() -> ImageVector.Builder,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
    autoMirror = autoMirror,
).block().build()

/**
 * A filled path with no stroke — the only shape any of these icons takes.
 *
 * Named apart from the library's own `path` because that one defaults every
 * parameter, so a bare `path { … }` would be ambiguous between the two.
 */
private fun ImageVector.Builder.materialPath(
    pathBuilder: PathBuilder.() -> Unit,
): ImageVector.Builder = path(
    fill = SolidColor(Color.Black),
    fillAlpha = 1f,
    stroke = null,
    strokeAlpha = 1f,
    strokeLineWidth = 1f,
    strokeLineCap = StrokeCap.Butt,
    strokeLineJoin = StrokeJoin.Bevel,
    strokeLineMiter = 1f,
    pathFillType = PathFillType.NonZero,
    pathBuilder = pathBuilder,
)
