package com.pawmiaw.business

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

//
//  The colours of the business side (iOS Theme.swift): the web's provider tokens
//  (styles.css), the six status states every provider screen shares
//  (lib/provider-colors.ts), the category hues and the chip tones. Adaptive colours
//  are `@Composable` getters that read `LocalIsDark`; fixed ones are plain values.
//

/** Dark appearance, set once at the root (iOS `colorScheme == .dark`). Light by default, like the web. */
val LocalIsDark = compositionLocalOf { false }

/** A colour of two appearances (iOS `Color.scheme(light:dark:)`). */
class SchemeColor(val light: Color, val dark: Color) {
    @Composable @ReadOnlyComposable
    fun resolve(): Color = if (LocalIsDark.current) dark else light
    fun resolve(dark: Boolean): Color = if (dark) this.dark else light
}

fun rgb(r: Int, g: Int, b: Int): Color = Color(r, g, b)
fun hex(value: String): Color = Color(android.graphics.Color.parseColor(if (value.startsWith("#")) value else "#$value"))
fun scheme(light: Triple<Int, Int, Int>, dark: Triple<Int, Int, Int>) =
    SchemeColor(rgb(light.first, light.second, light.third), rgb(dark.first, dark.second, dark.third))

/** `color-mix(in srgb, a p%, b)`. */
fun mixColors(a: Color, percent: Double, b: Color): Color {
    val t = (percent / 100).toFloat()
    return Color(
        red = b.red + (a.red - b.red) * t,
        green = b.green + (a.green - b.green) * t,
        blue = b.blue + (a.blue - b.blue) * t,
        alpha = 1f,
    )
}

object Theme {
    // MARK: Surfaces and ink (web --canvas/--surface/--ink-*)
    val canvasPair = scheme(Triple(245, 247, 251), Triple(18, 18, 20))          // --provider-canvas
    val surfacePair = scheme(Triple(255, 255, 255), Triple(28, 28, 30))
    val surfaceMutedPair = scheme(Triple(245, 248, 251), Triple(36, 36, 40))
    val borderSoftPair = scheme(Triple(220, 228, 237), Triple(58, 58, 64))
    val borderStrongPair = scheme(Triple(191, 204, 220), Triple(84, 84, 92))
    val inkStrongPair = scheme(Triple(16, 34, 59), Triple(242, 242, 247))
    val inkSoftPair = scheme(Triple(102, 117, 138), Triple(174, 174, 184))
    val inputFillPair = scheme(Triple(255, 255, 255), Triple(34, 34, 37))
    val switchOnPair = scheme(Triple(5, 150, 105), Triple(16, 185, 129))
    val switchOffPair = scheme(Triple(254, 202, 202), Triple(124, 66, 66))
    val destructivePair = scheme(Triple(214, 60, 47), Triple(255, 138, 111))
    val bluePair = scheme(Triple(0, 106, 252), Triple(154, 206, 250))
    val blueStrongPair = scheme(Triple(0, 84, 202), Triple(198, 227, 253))
    val onBluePair = scheme(Triple(255, 253, 249), Triple(16, 34, 59))
    val blueWashPair = scheme(Triple(234, 244, 255), Triple(24, 42, 66))
    val coralTextPair = scheme(Triple(178, 38, 28), Triple(255, 163, 152))
    val coralWashPair = scheme(Triple(253, 236, 234), Triple(64, 30, 28))
    val goldTextPair = scheme(Triple(185, 125, 6), Triple(216, 154, 22))

    val canvas: Color @Composable @ReadOnlyComposable get() = canvasPair.resolve()
    val surface: Color @Composable @ReadOnlyComposable get() = surfacePair.resolve()
    val surfaceMuted: Color @Composable @ReadOnlyComposable get() = surfaceMutedPair.resolve()
    val borderSoft: Color @Composable @ReadOnlyComposable get() = borderSoftPair.resolve()
    val borderStrong: Color @Composable @ReadOnlyComposable get() = borderStrongPair.resolve()
    val inkStrong: Color @Composable @ReadOnlyComposable get() = inkStrongPair.resolve()
    val inkSoft: Color @Composable @ReadOnlyComposable get() = inkSoftPair.resolve()
    /** = --border-soft (inputs are grey). */
    val inputBorder: Color @Composable @ReadOnlyComposable get() = borderSoftPair.resolve()
    /** --input over the surface in dark. */
    val inputFill: Color @Composable @ReadOnlyComposable get() = inputFillPair.resolve()
    val switchOn: Color @Composable @ReadOnlyComposable get() = switchOnPair.resolve()
    val switchOff: Color @Composable @ReadOnlyComposable get() = switchOffPair.resolve()
    val petFace = Color(241, 228, 208)                                           // --pet-face
    val petFaceInk = Color(61, 31, 20)                                           // --pet-face-ink (cocoa)
    /** --destructive: the red of errors and late, paler in dark. */
    val destructive: Color @Composable @ReadOnlyComposable get() = destructivePair.resolve()

    // MARK: Brand (styles.css --brand-*; PAW Blue flips to Sky Blue in dark, as on the web)
    val blue: Color @Composable @ReadOnlyComposable get() = bluePair.resolve()              // --brand-teal
    val blueFixed = Color(0, 106, 252)                                                      // --brand-teal-fixed
    val blueStrong: Color @Composable @ReadOnlyComposable get() = blueStrongPair.resolve()  // --brand-teal-strong
    /** Ink on a PAW Blue fill (--on-brand-teal / --selected-accent-ink). */
    val onBlue: Color @Composable @ReadOnlyComposable get() = onBluePair.resolve()
    val blueText: Color @Composable @ReadOnlyComposable get() = bluePair.resolve()
    val blueWash: Color @Composable @ReadOnlyComposable get() = blueWashPair.resolve()
    val mango = Color(252, 165, 31)                                                         // --brand-mango
    val lime = Color(253, 180, 14)                                                          // --brand-lime (MIAW Yellow)
    val coral = Color(214, 60, 47)
    val coralText: Color @Composable @ReadOnlyComposable get() = coralTextPair.resolve()
    val coralWash: Color @Composable @ReadOnlyComposable get() = coralWashPair.resolve()
    val gold = Color(216, 154, 22)
    val goldText: Color @Composable @ReadOnlyComposable get() = goldTextPair.resolve()
    val navy = Color(16, 34, 59)
    val butter = Color(255, 224, 122)                                                       // --brand-butter
    val aqua = Color(154, 206, 250)                                                         // --brand-aqua
    val pink = Color(245, 194, 217)                                                         // --brand-pink
    val lavender = Color(220, 203, 255)                                                     // --brand-lavender
    val apricot = Color(255, 193, 143)                                                      // --brand-apricot
    val cocoa = Color(61, 31, 20)                                                           // --brand-cocoa
    /** Ink on a bright pastel fill: navy in both themes (--accent-card-ink). */
    val inkOnBright = Color(16, 34, 59)

    /** `color-mix(in srgb, color p%, var(--surface))`: a wash of a colour over the surface. */
    @Composable @ReadOnlyComposable
    fun wash(color: Color, percent: Double): Color = mixColors(color, percent, surface)

    /** `color-mix(in srgb, a p%, b)`. */
    fun mix(a: Color, percent: Double, with: Color): Color = mixColors(a, percent, with)

    // MARK: Type
    fun heading(size: Number): TextStyle = brandHeading(size)
    fun text(size: Number = 16, weight: FontWeight = FontWeight.Normal): TextStyle = brandText(size, weight)

    val titleFont: TextStyle get() = brandHeading(24)
    val sectionFont: TextStyle get() = brandHeading(19)
    val bodyFont: TextStyle get() = brandText(16)
    val rowTitleFont: TextStyle get() = brandText(16, FontWeight.Bold)
    val captionFont: TextStyle get() = brandText(13, FontWeight.SemiBold)
    val smallFont: TextStyle get() = brandText(14)

    // MARK: Sidebar (styles.css --provider-sidebar-*)
    val sidebarBackgroundPair = scheme(Triple(245, 250, 254), Triple(28, 28, 30))
    val sidebarBorderPair = scheme(Triple(189, 211, 239), Triple(58, 58, 64))
    val sidebarActivePair = scheme(Triple(0, 106, 252), Triple(76, 94, 111))
    val sidebarActiveBorderPair = scheme(Triple(177, 158, 85), Triple(127, 160, 190))
    val sidebarActiveInkPair = scheme(Triple(255, 253, 249), Triple(242, 242, 247))
    val sidebarControlPair = scheme(Triple(239, 247, 254), Triple(36, 36, 40))
    val sidebarControlBorderPair = scheme(Triple(194, 213, 239), Triple(58, 58, 64))
    val sidebarBackground: Color @Composable @ReadOnlyComposable get() = sidebarBackgroundPair.resolve()
    val sidebarBorder: Color @Composable @ReadOnlyComposable get() = sidebarBorderPair.resolve()
    val sidebarActive: Color @Composable @ReadOnlyComposable get() = sidebarActivePair.resolve()
    val sidebarActiveBorder: Color @Composable @ReadOnlyComposable get() = sidebarActiveBorderPair.resolve()
    val sidebarActiveInk: Color @Composable @ReadOnlyComposable get() = sidebarActiveInkPair.resolve()
    val sidebarControl: Color @Composable @ReadOnlyComposable get() = sidebarControlPair.resolve()
    val sidebarControlBorder: Color @Composable @ReadOnlyComposable get() = sidebarControlBorderPair.resolve()

    // MARK: Shape (styles/radius.css; --organic is 0, so every corner is even)
    val cardRadius: Dp = 28.dp      // --radius-card: cards, panels, modals, the module header
    val boxRadius: Dp = 16.dp       // --radius-box: a bordered box or row inside a card, a menu
    val fieldRadius: Dp = 16.dp     // --radius-field: inputs, selects, switch cards
    val chipRadius: Dp = 14.dp      // --radius-chip: small tiles, toggles, compact chips
    val tickRadius: Dp = 6.dp       // --radius-tick: checkboxes, swatches
    val buttonRadius: Dp = 24.dp    // --radius-button
}

/** The web's `--shadow-card` (0 18 36, navy 8%; black 32% in dark), iOS `.cardShadow()`. */
@Composable
fun Modifier.cardShadow(shape: Shape): Modifier {
    val dark = LocalIsDark.current
    val color = if (dark) Color.Black.copy(alpha = 0.32f) else Theme.navy.copy(alpha = 0.08f)
    return this.shadow(elevation = 18.dp, shape = shape, clip = false, ambientColor = color, spotColor = color)
}

/**
 * The six states every provider status is drawn in (lib/provider-colors.ts):
 * upcoming light blue · waiting mango · active blue · done green · problem red · closed grey.
 */
enum class ProviderState(val rawValue: String) {
    upcoming("upcoming"), waiting("waiting"), active("active"), done("done"), problem("problem"), closed("closed");

    val colorPair: SchemeColor
        get() = when (this) {
            upcoming -> SchemeColor(hex("#9acefa"), hex("#9acefa"))
            waiting -> SchemeColor(hex("#fca51f"), hex("#fca51f"))
            active -> scheme(Triple(0, 106, 252), Triple(91, 157, 255))
            done -> scheme(Triple(48, 164, 108), Triple(76, 195, 138))
            problem -> scheme(Triple(214, 60, 47), Triple(255, 122, 107))
            closed -> scheme(Triple(102, 117, 138), Triple(139, 150, 168))
        }

    val color: Color @Composable @ReadOnlyComposable get() = colorPair.resolve()

    /** Ink on the state's solid colour. */
    val ink: Color
        @Composable @ReadOnlyComposable get() = when (this) {
            upcoming, waiting, done -> Theme.inkOnBright
            active, problem, closed -> scheme(Triple(255, 255, 255), Triple(16, 34, 59)).resolve()
        }

    /** A pale wash for a row or card behind ink-strong text. */
    val wash: Color @Composable @ReadOnlyComposable get() = color.copy(alpha = 0.14f)

    companion object {
        // The web's status tables.
        fun appointment(status: String?): ProviderState = when (status) {
            "requested", "checked_in" -> waiting
            "scheduled" -> upcoming
            "in_progress" -> active
            "completed" -> done
            "no_show" -> problem
            else -> closed
        }

        fun hospital(status: String?): ProviderState = when (status) {
            "pending" -> waiting
            "admitted" -> active
            "discharged" -> done
            else -> closed
        }

        fun slot(state: String?): ProviderState = when (state) {
            "upcoming", "due" -> upcoming
            "late" -> waiting
            "missed" -> problem
            "given", "given_late" -> done
            else -> closed
        }

        fun stay(status: String?): ProviderState = when (status) {
            "requested" -> waiting
            "confirmed" -> upcoming
            "checked_in" -> active
            "no_show" -> problem
            else -> closed
        }

        fun groom(stage: String?): ProviderState = when (stage) {
            "scheduled" -> upcoming
            "waiting" -> waiting
            "bath", "styling" -> active
            "ready" -> done
            else -> closed
        }

        fun invoice(status: String?): ProviderState = when (status) {
            "draft", "review" -> upcoming
            "unpriced", "ready_for_payment", "open", "partially_paid" -> waiting
            "overdue", "late" -> problem
            "paid" -> done
            else -> closed
        }

        fun vaccine(status: String?): ProviderState = when (status) {
            "current" -> done
            "expires_during_stay" -> waiting
            else -> problem
        }

        fun confirmation(status: String?): ProviderState = when (status) {
            "confirmed" -> done
            "pending", "sent", "reschedule_requested" -> waiting
            "declined", "cancelled" -> problem
            else -> closed
        }
    }
}

/**
 * Category hues (a service, a room, a role, a payment method): the family app's hues, so a
 * thing is the same colour on both sides and never looks like a status (`--hue-*`).
 */
enum class Hue(val rawValue: String) {
    sky("sky"), lavender("lavender"), mint("mint"), peach("peach"), butter("butter"), teal("teal"), periwinkle("periwinkle"), grey("grey");

    val color: Color
        get() = when (this) {
            sky -> hex("#5aa9f5")
            lavender -> hex("#c08ae8")
            mint -> hex("#86cf8e")
            peach -> hex("#ff9447")
            butter -> hex("#ffd43b")
            teal -> hex("#35c3be")
            periwinkle -> hex("#8b93f7")
            grey -> hex("#c9d2dd")
        }

    val inkPair: SchemeColor
        get() = when (this) {
            sky -> scheme(Triple(13, 79, 148), Triple(176, 212, 250))
            lavender -> scheme(Triple(91, 45, 166), Triple(226, 204, 247))
            mint -> scheme(Triple(27, 102, 48), Triple(191, 232, 195))
            peach -> scheme(Triple(147, 64, 10), Triple(255, 193, 143))
            butter -> scheme(Triple(111, 82, 0), Triple(255, 224, 122))
            teal -> scheme(Triple(12, 95, 92), Triple(165, 228, 224))
            periwinkle -> scheme(Triple(52, 60, 168), Triple(201, 204, 252))
            grey -> scheme(Triple(59, 70, 86), Triple(213, 220, 230))
        }

    val ink: Color @Composable @ReadOnlyComposable get() = inkPair.resolve()

    companion object {
        fun from(raw: String?): Hue? = entries.firstOrNull { it.rawValue == raw }

        /** A stable hue for any key (a service id, a room name). */
        fun stable(key: String): Hue {
            val all = listOf(sky, lavender, mint, peach, butter, teal, periwinkle)
            var sum = 0
            for (ch in key.codePoints()) sum = (sum * 31 + ch) and 0xFFFF
            return all[sum % all.size]
        }

        /** A business category's hue (web PROVIDER_CATEGORY_HUE). */
        fun category(category: String?): Hue = when (category) {
            "vet", "emergency", "pharmacy" -> sky
            "groomer" -> lavender
            "walker" -> peach
            "sitter" -> mint
            "trainer" -> butter
            "daycare" -> teal
            "boarding" -> periwinkle
            else -> grey
        }

        /** A business category's icon (web PROVIDER_CATEGORY_ICONS). */
        fun categoryIcon(category: String?): String = when (category) {
            "vet" -> "stethoscope"
            "groomer" -> "scissors"
            "walker" -> "dog"
            "sitter" -> "house"
            "trainer" -> "dumbbell"
            "daycare", "boarding" -> "building-2"
            "pharmacy" -> "package"
            "emergency" -> "siren"
            else -> "ellipsis"
        }
    }
}

/**
 * A colour a toned chip, tab or segment can wear (web `ChipTone`): the colour it tints with
 * while quiet, and the deep shade it is filled with under white ink while chosen (`TONE_FILLS`).
 */
sealed class ChipTone {
    /** `PAGE_TAB_TONE`: the page tabs' selection blue. */
    data object page : ChipTone()
    data class hue(val hue: Hue) : ChipTone()
    data class state(val state: ProviderState) : ChipTone()
    /** `EXPIRED_TONE`: the red of "out", deepened with cocoa. */
    data object expired : ChipTone()

    companion object {
        val upcoming: ChipTone = state(ProviderState.upcoming)
        val waiting: ChipTone = state(ProviderState.waiting)
        val active: ChipTone = state(ProviderState.active)
        val done: ChipTone = state(ProviderState.done)
        val problem: ChipTone = state(ProviderState.problem)
        val closed: ChipTone = state(ProviderState.closed)
        val sky: ChipTone = hue(Hue.sky)
    }

    /** The tone itself (`--tab-tone`). */
    val color: Color
        @Composable @ReadOnlyComposable get() = when (this) {
            page -> Theme.blue
            is hue -> hue.color
            is state -> state.color
            expired -> scheme(Triple(145, 47, 35), Triple(168, 90, 70)).resolve()
        }

    /** The deep shade a chosen tab or chip is filled with (`--tab-fill` / `--tab-fill-dark`). */
    val fill: Color
        @Composable @ReadOnlyComposable get() = when (this) {
            page -> scheme(Triple(0, 84, 202), Triple(31, 99, 173))
            is hue -> when (hue) {
                Hue.sky -> scheme(Triple(13, 79, 148), Triple(31, 99, 173))
                Hue.teal -> scheme(Triple(12, 95, 92), Triple(18, 122, 118))
                Hue.mint -> scheme(Triple(27, 102, 48), Triple(35, 122, 58))
                Hue.butter -> scheme(Triple(111, 82, 0), Triple(125, 93, 0))
                Hue.peach -> scheme(Triple(147, 64, 10), Triple(163, 74, 16))
                Hue.lavender -> scheme(Triple(91, 45, 166), Triple(107, 61, 187))
                Hue.periwinkle -> scheme(Triple(52, 60, 168), Triple(64, 72, 189))
                Hue.grey -> scheme(Triple(59, 70, 86), Triple(74, 86, 104))
            }
            is state -> when (state) {
                ProviderState.upcoming -> scheme(Triple(27, 111, 179), Triple(26, 115, 192))
                ProviderState.waiting -> scheme(Triple(173, 87, 0), Triple(184, 92, 0))
                ProviderState.active -> scheme(Triple(0, 84, 202), Triple(31, 99, 173))
                ProviderState.done -> scheme(Triple(26, 127, 79), Triple(33, 131, 88))
                ProviderState.problem -> scheme(Triple(200, 55, 43), Triple(204, 65, 48))
                ProviderState.closed -> scheme(Triple(74, 86, 104), Triple(82, 94, 112))
            }
            expired -> scheme(Triple(122, 31, 31), Triple(190, 59, 83))
        }.resolve()
}

/** Light unless the person chose dark (the account menu); it doesn't follow the system, like the web. */
object ThemeMode {
    private const val KEY = "pawmiaw.negocios.theme"
    var value: String
        get() = AppContext.defaults.getString(KEY, "light") ?: "light"
        set(v) { AppContext.defaults.edit().putString(KEY, v).apply() }
}
