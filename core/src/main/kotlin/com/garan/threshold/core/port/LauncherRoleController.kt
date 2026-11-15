package com.garan.threshold.core.port

/**
 * Port for querying whether Threshold currently holds the system default Home launcher role
 * (`android.app.role.HOME`).
 */
fun interface LauncherRoleController {
    /**
     * Returns `true` if Threshold is currently the active default Home launcher.
     */
    fun isDefaultLauncher(): Boolean
}
