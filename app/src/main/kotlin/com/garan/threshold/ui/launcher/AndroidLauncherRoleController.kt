package com.garan.threshold.ui.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.garan.threshold.core.port.LauncherRoleController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of [LauncherRoleController] backed by [RoleManager.ROLE_HOME].
 */
class AndroidLauncherRoleController(context: Context, private val overrideProvider: () -> Boolean? = { null }) :
    LauncherRoleController {

    private val appContext = context.applicationContext ?: context

    private val _isDefaultLauncherFlow = MutableStateFlow(resolveIsDefaultLauncher())
    val isDefaultLauncherFlow: StateFlow<Boolean> = _isDefaultLauncherFlow.asStateFlow()

    override fun isDefaultLauncher(): Boolean {
        val current = resolveIsDefaultLauncher()
        _isDefaultLauncherFlow.value = current
        return current
    }

    /**
     * Re-evaluates whether Threshold currently holds [RoleManager.ROLE_HOME] and updates
     * [isDefaultLauncherFlow].
     */
    fun refresh(): Boolean = isDefaultLauncher()

    /**
     * Creates an [Intent] prompting the user to grant [RoleManager.ROLE_HOME] to Threshold,
     * falling back to [Settings.ACTION_HOME_SETTINGS] if the role request intent cannot be created.
     */
    fun createRequestRoleIntent(): Intent {
        val roleManager = appContext.getSystemService(RoleManager::class.java)
        val roleIntent = if (roleManager != null &&
            runCatching { roleManager.isRoleAvailable(RoleManager.ROLE_HOME) }.getOrDefault(false)
        ) {
            runCatching { roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME) }.getOrNull()
        } else {
            null
        }
        return roleIntent ?: Intent(Settings.ACTION_HOME_SETTINGS)
    }

    private fun resolveIsDefaultLauncher(): Boolean {
        overrideProvider()?.let { return it }
        val roleManager = appContext.getSystemService(RoleManager::class.java) ?: return false
        return runCatching {
            roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        }.getOrDefault(false)
    }
}
