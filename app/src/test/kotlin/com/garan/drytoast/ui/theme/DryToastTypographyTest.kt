package com.garan.drytoast.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DryToastTypographyTest {

    @Test
    fun allTypographyStylesEnforceTabularFiguresAndSlashedZero() {
        val styles = listOf(
            DryToastTypography.displayLarge,
            DryToastTypography.headlineMedium,
            DryToastTypography.bodyLarge,
            DryToastTypography.bodyMedium,
            DryToastTypography.labelSmall,
        )

        for (style in styles) {
            assertThat(style.fontFeatureSettings).contains("tnum")
            assertThat(style.fontFeatureSettings).contains("zero")
        }
    }

    @Test
    fun allTypographyStylesDisableFontPaddingToPreventVerticalDrift() {
        val styles = listOf(
            DryToastTypography.displayLarge,
            DryToastTypography.headlineMedium,
            DryToastTypography.bodyLarge,
            DryToastTypography.bodyMedium,
            DryToastTypography.labelSmall,
        )

        for (style in styles) {
            assertThat(style.platformStyle?.paragraphStyle?.includeFontPadding).isFalse()
        }
    }
}
