package com.e8.navhud

import java.util.Locale

/**
 * Flattened Accessibility node metadata captured from Yandex.Navigator UI hierarchy.
 */
data class AccessibilityNodeSnapshot(
    val depth: Int,
    val cls: String,
    val text: String,
    val desc: String
)

/**
 * Pure parser transforming Yandex.Navigator Accessibility nodes and notification strings into HUD data.
 */
object NavAccessibilityParser {
    private val DIST_REGEX = Regex("^(\\d+[,.]?\\d*)\\s*(м|км|m|km)$", RegexOption.IGNORE_CASE)
    private val NUMBER_TOKEN_REGEX = Regex("\\d+[,.]?\\d*")
    private val GUIDANCE_TEXT_REGEX = Regex("(?iu)(?:^|\\s)(?:через\\s+)?\\d+[,.]?\\d*\\s*(?:м|км|метр\\p{L}*|километр\\p{L}*|мин|ч)(?:\\s|$)")
    private val SPLIT_MANEUVER_REGEX = Regex("(?iu)^(через\\s+[\\d,.]+\\s*(?:м|км|метр\\p{L}*|километр\\p{L}*))\\s+(.+)$")

    private val MANEUVER_KEYWORDS = listOf(
        "поверн", "направо", "налево", "прямо", "разворот", "развернит",
        "съезд", "съехат", "круг", "кольц", "правее", "левее",
        "выезд", "въезд", "вправо", "влево", "круто", "резко"
    )

    private val UI_NOISE = listOf(
        "поиск и выбор мест", "поиск", "маршрут", "навигатор", "меню",
        "настройки", "слои", "закладки", "избранное", "мои места",
        "общественный транспорт", "парковки", "карта", "куда едем",
        "куда поедем", "куда", "домой", "пробки", "голосовой поиск",
        "построить маршрут", "место назначения", "точка маршрута"
    )

    fun isUiNoise(s: String): Boolean {
        val lower = s.trim().lowercase(Locale.ROOT)
        return UI_NOISE.any { lower == it } || lower.startsWith("поиск и выбор")
    }

    fun isGuidanceText(title: String, text: String, subText: String): Boolean {
        return GUIDANCE_TEXT_REGEX.containsMatchIn(title) ||
            GUIDANCE_TEXT_REGEX.containsMatchIn(text) ||
            GUIDANCE_TEXT_REGEX.containsMatchIn(subText)
    }

    fun splitDistanceAndManeuver(raw: String): Pair<String, String> {
        val match = SPLIT_MANEUVER_REGEX.find(raw.trim()) ?: return "" to raw
        val dist = match.groupValues[1]
        val maneuver = match.groupValues[2].let { value ->
            if (value.isNotEmpty()) value[0].uppercaseChar() + value.substring(1) else value
        }
        return dist to maneuver
    }

    fun parseNavData(nodes: List<AccessibilityNodeSnapshot>): NavData {
        val streetCandidates = mutableListOf<String>()
        val unmatchedNodes = mutableListOf<AccessibilityNodeSnapshot>()

        var distToManeuver = ""
        var maneuverDesc = ""
        var speed = ""
        var speedLimit = ""
        var eta = ""
        var totalDistance = ""
        var travelTime = ""

        for (node in nodes) {
            val text = node.text.trim()
            val desc = node.desc.trim()
            val textLower = text.lowercase(Locale.ROOT)
            val descLower = desc.lowercase(Locale.ROOT)

            when {
                descLower.startsWith("скорост") || textLower.startsWith("скорост") -> {
                    if (speed.isBlank()) speed = text.ifBlank { desc }
                }

                descLower.startsWith("ограничен") || textLower.startsWith("ограничен") || descLower.contains("знак скорост") -> {
                    if (speedLimit.isBlank()) speedLimit = text.ifBlank { desc }
                }

                descLower.contains("прибыти") || descLower.contains("приезд") ||
                    textLower.contains("прибыти") || textLower.contains("приезд") -> {
                    if (eta.isBlank()) eta = text.ifBlank { desc }
                }

                descLower.contains("осталось") || descLower.contains("расстояни") ||
                    descLower.contains("пути") || descLower.contains("до конца") -> {
                    val value = text.ifBlank { desc }
                    if (value.contains("мин") || value.contains(" ч")) {
                        if (travelTime.isBlank()) travelTime = value
                    } else if (value.contains("км") || (value.contains("м") && !value.contains("мин"))) {
                        if (totalDistance.isBlank()) totalDistance = value
                    }
                }

                node.cls.contains("Image") && desc.isNotBlank() && MANEUVER_KEYWORDS.any { descLower.contains(it) } -> {
                    if (maneuverDesc.isBlank()) maneuverDesc = desc
                }

                desc.length > 4 && MANEUVER_KEYWORDS.any { descLower.contains(it) } -> {
                    if (maneuverDesc.isBlank()) maneuverDesc = desc
                    if (text.isNotBlank()) {
                        if (DIST_REGEX.matchEntire(text) != null && distToManeuver.isBlank()) {
                            distToManeuver = text
                        } else if (text.any { it.isLetter() }) {
                            unmatchedNodes.add(node)
                        }
                    }
                }

                text.length > 4 && MANEUVER_KEYWORDS.any { textLower.contains(it) } -> {
                    if (maneuverDesc.isBlank()) maneuverDesc = text
                }

                textLower == "карта" || descLower == "карта" || textLower == "меню" -> Unit

                UI_NOISE.any { textLower == it || descLower == it } -> Unit

                else -> unmatchedNodes.add(node)
            }
        }

        val otherNodes = mutableListOf<String>()
        var foundDist = distToManeuver.isNotBlank()
        var pendingNumber = false
        var numberToken = ""

        for (node in unmatchedNodes) {
            val text = node.text.trim()
            if (text.isBlank()) continue

            if (!foundDist) {
                if (DIST_REGEX.matchEntire(text) != null) {
                    distToManeuver = text
                    foundDist = true
                    continue
                }
                if (!pendingNumber && NUMBER_TOKEN_REGEX.matches(text) && text.length <= 5) {
                    numberToken = text
                    pendingNumber = true
                    continue
                }
                if (pendingNumber && isDistanceUnit(text)) {
                    distToManeuver = "$numberToken $text"
                    foundDist = true
                    pendingNumber = false
                    continue
                }
                if (pendingNumber) {
                    pendingNumber = false
                    numberToken = ""
                }
                if (text.length > 2 && text.any { it in '\u0400'..'\u04FF' } && !isUiNoise(text)) {
                    otherNodes.add(text)
                }
            } else {
                if (text.length > 2 && text.any { it.isLetter() } && !isUiNoise(text)) {
                    streetCandidates.add(text)
                }
            }
        }

        val cyrillicStreets = streetCandidates.filter { candidate -> candidate.any { it in '\u0400'..'\u04FF' } }
        val finalStreets = cyrillicStreets.ifEmpty { streetCandidates }

        val currentStreet: String
        val nextStreet: String
        if (maneuverDesc.isNotBlank()) {
            nextStreet = finalStreets.firstOrNull() ?: ""
            currentStreet = otherNodes.lastOrNull() ?: finalStreets.getOrNull(1) ?: ""
        } else {
            currentStreet = otherNodes.lastOrNull() ?: finalStreets.firstOrNull() ?: ""
            nextStreet = if (currentStreet.isNotBlank() && finalStreets.isNotEmpty()) {
                finalStreets[0]
            } else {
                finalStreets.getOrNull(1) ?: ""
            }
        }

        return NavData(
            maneuverDesc = maneuverDesc,
            distToManeuver = distToManeuver,
            currentStreet = currentStreet,
            nextStreet = nextStreet,
            speed = speed,
            speedLimit = speedLimit,
            eta = eta,
            totalDistance = totalDistance,
            travelTime = travelTime
        )
    }

    private fun isDistanceUnit(token: String): Boolean {
        return token.equals("м", ignoreCase = true) ||
            token.equals("км", ignoreCase = true) ||
            token.equals("m", ignoreCase = true) ||
            token.equals("km", ignoreCase = true)
    }
}
