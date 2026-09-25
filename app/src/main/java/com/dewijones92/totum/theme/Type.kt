package com.dewijones92.totum.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.dewijones92.totum.R

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: FontWeight, opticalSize: TextUnit) = Font(
    resId = R.font.bricolage_grotesque,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.opticalSizing(opticalSize),
    ),
)

val DisplayFamily = FontFamily(
    bricolage(FontWeight.Medium, 48.sp),
    bricolage(FontWeight.SemiBold, 48.sp),
    bricolage(FontWeight.Bold, 48.sp),
    bricolage(FontWeight.ExtraBold, 48.sp),
)

val TitleFamily = FontFamily(
    bricolage(FontWeight.Medium, 16.sp),
    bricolage(FontWeight.SemiBold, 16.sp),
    bricolage(FontWeight.Bold, 16.sp),
    bricolage(FontWeight.ExtraBold, 16.sp),
)

private val Base = Typography()

private fun TextStyle.display(weight: FontWeight, size: Int, line: Int, tracking: Double) = copy(
    fontFamily = DisplayFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private fun TextStyle.title(weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) = copy(
    fontFamily = TitleFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val Typography = Typography(
    displayLarge = Base.displayLarge.display(FontWeight.ExtraBold, 56, 60, -1.5),
    displayMedium = Base.displayMedium.display(FontWeight.ExtraBold, 44, 48, -1.0),
    displaySmall = Base.displaySmall.display(FontWeight.ExtraBold, 36, 40, -0.8),
    headlineLarge = Base.headlineLarge.display(FontWeight.ExtraBold, 32, 38, -0.6),
    headlineMedium = Base.headlineMedium.display(FontWeight.Bold, 28, 34, -0.4),
    headlineSmall = Base.headlineSmall.display(FontWeight.Bold, 24, 30, -0.2),
    titleLarge = Base.titleLarge.title(FontWeight.Bold, 21, 27, -0.1),
    titleMedium = Base.titleMedium.title(FontWeight.SemiBold, 17, 23),
    titleSmall = Base.titleSmall.title(FontWeight.SemiBold, 15, 20),
    labelLarge = Base.labelLarge.title(FontWeight.SemiBold, 14, 20, 0.1),
    labelMedium = Base.labelMedium.title(FontWeight.SemiBold, 12, 16, 0.3),
    labelSmall = Base.labelSmall.title(FontWeight.Bold, 11, 14, 0.4),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp),
    bodyMedium = Base.bodyMedium.copy(letterSpacing = 0.15.sp),
    bodySmall = Base.bodySmall.copy(letterSpacing = 0.2.sp),
)
