package com.e8.navhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavAccessibilityParserTest {

    @Test
    fun parsesYandexNavigatorRouteSpeedAndStreets() {
        val nodes = listOf(
            AccessibilityNodeSnapshot(1, "android.widget.TextView", "74", "Скорость 74"),
            AccessibilityNodeSnapshot(1, "android.widget.TextView", "60", "Ограничение 60"),
            AccessibilityNodeSnapshot(2, "android.widget.ImageView", "", "Поверните направо"),
            AccessibilityNodeSnapshot(2, "android.widget.TextView", "350 м", ""),
            AccessibilityNodeSnapshot(2, "android.widget.TextView", "Ленинградский проспект", ""),
            AccessibilityNodeSnapshot(2, "android.widget.TextView", "Тверская улица", ""),
            AccessibilityNodeSnapshot(3, "android.widget.TextView", "Поиск", ""),
            AccessibilityNodeSnapshot(3, "android.widget.TextView", "14:35", "Прибытие 14:35"),
            AccessibilityNodeSnapshot(3, "android.widget.TextView", "12 км", "Осталось 12 км"),
            AccessibilityNodeSnapshot(3, "android.widget.TextView", "18 мин", "В пути 18 мин")
        )

        val parsed = NavAccessibilityParser.parseNavData(nodes)
        assertTrue(parsed.hasRoute)
        assertTrue(parsed.hasSpeed)
        assertEquals("74", parsed.speed)
        assertEquals("60", parsed.speedLimit)
        assertEquals("Поверните направо", parsed.maneuverDesc)
        assertEquals("350 м", parsed.distToManeuver)
        assertEquals("Ленинградский проспект", parsed.nextStreet)
        assertEquals("Тверская улица", parsed.currentStreet)
        assertEquals("14:35", parsed.eta)
        assertEquals("12 км", parsed.totalDistance)
        assertEquals("18 мин", parsed.travelTime)
    }

    @Test
    fun splitsSplitNumberAndUnitDistanceTokensAndFiltersNoise() {
        val nodes = listOf(
            AccessibilityNodeSnapshot(1, "android.widget.TextView", "Карта", ""),
            AccessibilityNodeSnapshot(1, "android.widget.TextView", "Поиск и выбор мест", ""),
            AccessibilityNodeSnapshot(2, "android.widget.TextView", "Прямо по проспекту", "Двигайтесь прямо"),
            AccessibilityNodeSnapshot(2, "android.widget.TextView", "1,2", ""),
            AccessibilityNodeSnapshot(2, "android.widget.TextView", "км", ""),
            AccessibilityNodeSnapshot(2, "android.widget.TextView", "Кутузовский проспект", "")
        )

        val parsed = NavAccessibilityParser.parseNavData(nodes)
        assertEquals("1,2 км", parsed.distToManeuver)
        assertEquals("Кутузовский проспект", parsed.nextStreet)
        assertTrue(NavAccessibilityParser.isUiNoise("Поиск и выбор организаций"))
        assertFalse(NavAccessibilityParser.isUiNoise("Кутузовский проспект"))
    }

    @Test
    fun mapsManeuversAndGuidanceNotifications() {
        assertEquals(R.drawable.man_directions_uturn_left_48, ManeuverMapper.resolveDrawableRes("Через 100 м разворот"))
        assertEquals(R.drawable.man_directions_round_48, ManeuverMapper.resolveDrawableRes("Круговое движение"))
        assertEquals(R.drawable.man_directions_exit_left_48, ManeuverMapper.resolveDrawableRes("Съезд налево"))
        assertEquals(R.drawable.man_directions_hard_right_48, ManeuverMapper.resolveDrawableRes("Резко направо"))
        assertEquals(R.drawable.man_directions_left_48, ManeuverMapper.resolveDrawableRes("Поверните налево"))
        assertEquals("→", ManeuverMapper.getFallbackArrowGlyph("Поверните направо"))

        assertTrue(NavAccessibilityParser.isGuidanceText("Через 250 м направо", "", ""))
        assertEquals("Через 250 м" to "Направо", NavAccessibilityParser.splitDistanceAndManeuver("Через 250 м направо"))
    }
}
