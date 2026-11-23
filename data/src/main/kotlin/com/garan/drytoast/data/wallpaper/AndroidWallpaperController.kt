package com.garan.drytoast.data.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.garan.drytoast.core.port.WallpaperController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [WallpaperController] using [WallpaperManager].
 *
 * Allocates an ephemeral 1x1 `#000000` [Bitmap] and recycles it immediately in `finally`
 * so no uncompressed bitmap memory is ever retained in the launcher process.
 */
class AndroidWallpaperController(context: Context, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
    WallpaperController {

    private val appContext: Context = context.applicationContext ?: context

    override suspend fun applyPureBlackWallpaper(): Boolean = withContext(ioDispatcher) {
        val wallpaperManager = WallpaperManager.getInstance(appContext) ?: return@withContext false
        if (!wallpaperManager.isWallpaperSupported || !wallpaperManager.isSetWallpaperAllowed) {
            return@withContext false
        }

        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return@withContext try {
            bitmap.setPixel(0, 0, Color.BLACK)
            wallpaperManager.setBitmap(
                bitmap,
                null,
                true,
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
