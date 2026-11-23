package com.garan.drytoast.core.port

/**
 * Port for querying whether Dry Toast currently holds the system default Home launcher role
 * (`android.app.role.HOME`).
 */
fun interface LauncherRoleController {
    /**
     * Returns `true` if Dry Toast is currently the active default Home launcher.
     */
    fun isDefaultLauncher(): Boolean
}
