package com.e8.navhud

import androidx.annotation.DrawableRes
import java.util.Locale

/**
 * Maps textual Yandex.Navigator maneuver descriptions to HUD vector icons and fallback glyphs.
 */
object ManeuverMapper {
    @DrawableRes
    fun resolveDrawableRes(maneuverText: String): Int {
        val lower = maneuverText.lowercase(Locale.ROOT)
        return when {
            lower.contains("разворот") || lower.contains("развернит") ->
                R.drawable.man_directions_uturn_left_48

            lower.contains("круг") || lower.contains("кольц") ->
                R.drawable.man_directions_round_48

            lower.contains("прибыт") || lower.contains("финиш") || lower.contains("назначен") ->
                R.drawable.man_directions_finish_48

            lower.contains("слияние") || lower.contains("merge") ->
                R.drawable.man_directions_merge_48

            (lower.contains("съезд") || lower.contains("выезд")) && lower.contains("лев") ->
                R.drawable.man_directions_exit_left_48

            lower.contains("съезд") || lower.contains("выезд") ->
                R.drawable.man_directions_exit_right_48

            (lower.contains("направо") || lower.contains("вправо")) &&
                (lower.contains("резко") || lower.contains("круто")) ->
                R.drawable.man_directions_hard_right_48

            (lower.contains("налево") || lower.contains("влево")) &&
                (lower.contains("резко") || lower.contains("круто")) ->
                R.drawable.man_directions_hard_left_48

            lower.contains("направо") || lower.contains("вправо") ->
                R.drawable.man_directions_right_48

            lower.contains("налево") || lower.contains("влево") ->
                R.drawable.man_directions_left_48

            lower.contains("правее") ->
                R.drawable.man_directions_slight_right_48

            lower.contains("левее") ->
                R.drawable.man_directions_slight_left_48

            lower.contains("прямо") || maneuverText.isNotBlank() ->
                R.drawable.man_directions_straight_48

            else -> 0
        }
    }

    fun getFallbackArrowGlyph(maneuverText: String): String {
        val lower = maneuverText.lowercase(Locale.ROOT)
        return when {
            lower.contains("разворот") -> "↩"
            lower.contains("круг") || lower.contains("кольц") -> "↻"
            (lower.contains("направо") || lower.contains("вправо")) &&
                (lower.contains("резко") || lower.contains("круто")) -> "⤵"
            lower.contains("направо") || lower.contains("вправо") -> "→"
            lower.contains("правее") -> "↗"
            (lower.contains("налево") || lower.contains("влево")) &&
                (lower.contains("резко") || lower.contains("круто")) -> "⤴"
            lower.contains("налево") || lower.contains("влево") -> "←"
            lower.contains("левее") -> "↖"
            lower.contains("прямо") -> "↑"
            lower.contains("съезд") -> "↗"
            else -> "▸"
        }
    }
}
