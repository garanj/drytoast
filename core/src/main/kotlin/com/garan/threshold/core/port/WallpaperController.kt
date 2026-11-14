package com.garan.threshold.core.port

/**
 * Port for applying Threshold's solid AMOLED black wallpaper to the system and lock screen.
 */
fun interface WallpaperController {
    /**
     * Applies a solid black (`#000000`) wallpaper to both the system home and lock screen.
     * Returns `true` if the wallpaper was applied successfully.
     */
    suspend fun applyPureBlackWallpaper(): Boolean
}
