package com.garan.drytoast.data.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidWallpaperControllerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun applyPureBlackWallpaper_setsSolidBlackBitmapForSystemAndLockScreen() = runTest(testDispatcher) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val controller = AndroidWallpaperController(context, ioDispatcher = testDispatcher)

        val success = controller.applyPureBlackWallpaper()
        assertThat(success).isTrue()

        val wallpaperManager = WallpaperManager.getInstance(context)
        val shadowWallpaperManager = shadowOf(wallpaperManager)
        val systemBitmap = shadowWallpaperManager.getBitmap(WallpaperManager.FLAG_SYSTEM)
        val lockBitmap = shadowWallpaperManager.getBitmap(WallpaperManager.FLAG_LOCK)
        assertThat(systemBitmap).isNotNull()
        assertThat(systemBitmap!!.getPixel(0, 0)).isEqualTo(Color.BLACK)
        assertThat(lockBitmap).isNotNull()
        assertThat(lockBitmap!!.getPixel(0, 0)).isEqualTo(Color.BLACK)
    }
}
