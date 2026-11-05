package com.pawmiaw.business

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

//
//  Brand typography, shared with the web design system (iOS BrandFont.swift):
//    headings → Fredoka SemiBold (--font-heading)
//    body / UI → Nunito Sans (--font-sans), static weights Regular … ExtraBold.
//  Dancing Script draws typed signatures, the same font the backend stamps them in.
//  Swift weights map as: .regular → Normal, .medium → Medium, .semibold → SemiBold,
//  .bold → Bold, .heavy/.black → ExtraBold.
//

object BrandFonts {
    val heading = FontFamily(Font(R.font.fredoka_semibold, FontWeight.SemiBold))
    val text = FontFamily(
        Font(R.font.nunito_sans_regular, FontWeight.Normal),
        Font(R.font.nunito_sans_medium, FontWeight.Medium),
        Font(R.font.nunito_sans_semibold, FontWeight.SemiBold),
        Font(R.font.nunito_sans_bold, FontWeight.Bold),
        Font(R.font.nunito_sans_extrabold, FontWeight.ExtraBold),
    )
    val signature = FontFamily(Font(R.font.dancing_script_regular, FontWeight.Normal))
}

/** Fredoka SemiBold — the web's `--font-heading` (iOS `Font.brandHeading(size)`). */
fun brandHeading(size: Number): TextStyle =
    TextStyle(fontFamily = BrandFonts.heading, fontWeight = FontWeight.SemiBold, fontSize = size.toFloat().sp)

/** Nunito Sans — the web's `--font-sans` (iOS `Font.brandText(size, weight:)`). */
fun brandText(size: Number, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(fontFamily = BrandFonts.text, fontWeight = weight, fontSize = size.toFloat().sp)

/** Dancing Script — how a typed signature looks everywhere. */
fun signatureScript(size: Number): TextStyle =
    TextStyle(fontFamily = BrandFonts.signature, fontSize = size.toFloat().sp)
