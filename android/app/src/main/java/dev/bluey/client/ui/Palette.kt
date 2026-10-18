package dev.bluey.client.ui

import androidx.compose.ui.graphics.Color

/** Blueberry colors from the design canvas — the port of Shared/Palette.swift. */
object Palette {
    val berry1 = Color(0xFFA9BCFF) // light periwinkle
    val berry2 = Color(0xFF6C86F5)
    val berry3 = Color(0xFF4254D6)
    val berry4 = Color(0xFF2B2F8F) // deep indigo
    val nose = Color(0xFF1C1F66)
    val ink = Color(0xFF17151F)
    val inkSoft = Color(0xFFB9B2CC)
    val panel = Color(0xFF1E1B29)

    val gradient = listOf(berry1, berry2, berry3, berry4)
    val gradientStops = listOf(0f, 0.34f, 0.68f, 1f)
}
