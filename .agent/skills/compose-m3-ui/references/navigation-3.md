# Jetpack Navigation 3 (`androidx.navigation3`) Reference for Dry Toast

Synthesized from Google's official `android/skills` (`navigation/navigation-3`) and adapted for Dry Toast's launcher architecture and optical motion curves.

---

## 1. Dependencies (`gradle/libs.versions.toml`)

```toml
[versions]
nav3Core = "1.0.0" # or latest stable/beta in Compose ecosystem
lifecycleViewmodelNav3 = "2.11.0"
kotlinSerialization = "1.8.0"

[libraries]
androidx-navigation3-runtime = { group = "androidx.navigation3", name = "navigation3-runtime", version.ref = "nav3Core" }
androidx-navigation3-ui = { group = "androidx.navigation3", name = "navigation3-ui", version.ref = "nav3Core" }
androidx-lifecycle-viewmodel-navigation3 = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-navigation3", version.ref = "lifecycleViewmodelNav3" }
kotlinx-serialization-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-core", version.ref = "kotlinSerialization" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

---

## 2. Type-Safe `NavKey` Route Definitions

Define all top-level and nested routes as `@Serializable` types implementing `NavKey`.
Never store route parameters (such as `editingProfileId: String?`) as separate `mutableStateOf` variables outside the back stack.

```kotlin
package com.garan.drytoast.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface DryToastRoute : NavKey {
    @Serializable
    data object Home : DryToastRoute

    @Serializable
    data object Settings : DryToastRoute

    @Serializable
    data object Profiles : DryToastRoute

    /**
     * Dedicated screen for creating (profileId == null) or editing an existing CustomProfile
     * (name, schedule window, active days, and link to profile app overrides).
     */
    @Serializable
    data class ProfileEditor(val profileId: String? = null) : DryToastRoute

    /**
     * Unified app assignment screen.
     * When [profileId] is null, edits the Base/Default profile assignments.
     * When [profileId] is non-null, edits overrides for that specific CustomProfile.
     */
    @Serializable
    data class ConfigureApps(val profileId: String? = null) : DryToastRoute
}
```

---

## 3. `NavDisplay` Setup with Entry Decorators & Optical Transitions

Always configure `NavDisplay` with:
1. `rememberNavBackStack(DryToastRoute.Home)` for configuration-change survival.
2. `rememberSaveableStateHolderNavEntryDecorator()` and `rememberViewModelStoreNavEntryDecorator()`.
3. Dry Toast's decelerating optical transitions (`DryToastMotion.opticalTween`, 80ms–140ms, zero spring physics).
4. `dropUnlessResumed` on forward navigation actions to prevent double-tap duplicate back stack entries.

```kotlin
val backStack = rememberNavBackStack(DryToastRoute.Home)

NavDisplay(
    backStack = backStack,
    onBack = {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    },
    entryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
    ),
    transitionSpec = {
        fadeIn(
            animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_OPEN_DURATION_MS),
        ) togetherWith fadeOut(
            animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_CLOSE_DURATION_MS),
        )
    },
    popTransitionSpec = {
        fadeIn(
            animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_OPEN_DURATION_MS),
        ) togetherWith fadeOut(
            animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_CLOSE_DURATION_MS),
        )
    },
    predictivePopTransitionSpec = {
        fadeIn(
            animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_OPEN_DURATION_MS),
        ) togetherWith fadeOut(
            animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_CLOSE_DURATION_MS),
        )
    },
    entryProvider = entryProvider {
        entry<DryToastRoute.Home> {
            DryToastHomeScreen(
                viewModel = searchViewModel,
                appLauncher = appLauncher,
                hapticEngine = hapticEngine,
                onDismissIme = { ImeUtils.dismissIme(activity) },
                antiMetricsRepository = antiMetricsRepository,
                onOpenSettings = { backStack.add(DryToastRoute.Settings) },
            )
        }
        entry<DryToastRoute.Settings> {
            // Settings route content
        }
        entry<DryToastRoute.Profiles> {
            // Profiles list route content
        }
        entry<DryToastRoute.ProfileEditor> { route ->
            // Profile create/edit destination
        }
        entry<DryToastRoute.ConfigureApps> { route ->
            // App assignment destination for route.profileId
        }
    },
)
```

---

## 4. Handling `onNewIntent` (Home Key Press on Root Launcher)

When the user presses the system Home button while inside `Settings`, `Profiles`, or `ConfigureApps`, `MainActivity.onNewIntent` must collapse the navigation stack back to `[DryToastRoute.Home]`:

```kotlin
// Reset user-owned back stack to root Home destination:
while (backStack.size > 1) {
    backStack.removeLastOrNull()
}
```
