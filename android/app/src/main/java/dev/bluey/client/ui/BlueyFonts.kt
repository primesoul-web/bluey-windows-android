package dev.bluey.client.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.bluey.client.R

/** The bundled fonts from the original (shared-protocol/fonts, SIL OFL). */
object BlueyFonts {
    /** Rounded display face (BlobShape-era design font); bold like Font.fredoka. */
    val fredoka = FontFamily(Font(R.font.fredoka, FontWeight.Bold))

    val plexSans = FontFamily(Font(R.font.ibm_plex_sans, FontWeight.Normal))

    val plexMono = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    )
}
