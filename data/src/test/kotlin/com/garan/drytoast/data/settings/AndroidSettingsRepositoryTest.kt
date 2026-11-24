package com.garan.drytoast.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.garan.drytoast.core.model.AppAssignment
import com.garan.drytoast.core.model.BaseProfileSettings
import com.garan.drytoast.core.model.ComponentKey
import com.garan.drytoast.core.model.FrictionChoice
import com.garan.drytoast.core.model.GeneralSettings
import com.garan.drytoast.core.model.InstalledApp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSettingsRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private var currentBootCount: Int = 10
    private lateinit var repository: AndroidSettingsRepository

    private val chromeKey = ComponentKey("com.android.chrome", "com.google.android.apps.chrome.Main")
    private val clockKey = ComponentKey("com.google.android.deskclock", "DeskClock")
    private val socialKey = ComponentKey("com.instagram.android", "MainActivity")
    private val chromeApp = InstalledApp(chromeKey, "Chrome")
    private val clockApp = InstalledApp(clockKey, "Clock")
    private val socialApp = InstalledApp(socialKey, "Instagram")

    @Before
    fun setUp() {
        currentBootCount = 10
        val testFile = tmpFolder.newFile("test_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { testFile },
        )
        repository = AndroidSettingsRepository(
            dataStore = dataStore,
            ioDispatcher = testDispatcher,
            bootCountProvider = { currentBootCount },
        )
    }

    @Test
    fun defaultSettings_matchesInitialSpecifications() = runTest(testDispatcher) {
        val settings = repository.getSettings()

        assertThat(settings.general.showUnlocks).isTrue()
        assertThat(settings.general.showRest).isTrue()
        assertThat(settings.general.showDialerIcon).isTrue()
        assertThat(settings.general.requireFullTypingOfGuardedApps).isFalse()
        assertThat(settings.general.hasSeenFirstRunHint).isFalse()

        assertThat(settings.baseProfile.defaultFriction).isEqualTo(FrictionChoice.TYPED_INTENTION)
        assertThat(settings.baseProfile.quickActions).isEmpty()
        assertThat(settings.baseProfile.appAssignments).isEmpty()
    }

    @Test
    fun updateGeneralSettings_persistsAndEmitsNewValues() = runTest(testDispatcher) {
        val updatedGeneral = GeneralSettings(
            showUnlocks = false,
            showRest = false,
            showDialerIcon = false,
            requireFullTypingOfGuardedApps = true,
            hasSeenFirstRunHint = true,
        )

        repository.updateGeneralSettings(updatedGeneral)

        val latest = repository.observeSettings().first()
        assertThat(latest.general).isEqualTo(updatedGeneral)
    }

    @Test
    fun updateBaseProfile_persistsAssignmentsQuickActionsAndFriction() = runTest(testDispatcher) {
        val updatedBase = BaseProfileSettings(
            defaultFriction = FrictionChoice.DRAG_SORT,
            quickActions = listOf(chromeApp, clockApp),
            appAssignments = mapOf(
                chromeKey to AppAssignment.Guarded(FrictionChoice.CARET_TRACING),
                clockKey to AppAssignment.Standard,
                socialKey to AppAssignment.Blocked,
            ),
        )

        repository.updateBaseProfile(updatedBase)

        val latest = repository.getSettings()
        assertThat(latest.baseProfile.defaultFriction).isEqualTo(FrictionChoice.DRAG_SORT)
        assertThat(latest.baseProfile.quickActions).containsExactly(chromeApp, clockApp).inOrder()
        assertThat(latest.baseProfile.appAssignments[chromeKey])
            .isEqualTo(AppAssignment.Guarded(FrictionChoice.CARET_TRACING))
        assertThat(latest.baseProfile.appAssignments[clockKey]).isEqualTo(AppAssignment.Standard)
        assertThat(latest.baseProfile.appAssignments[socialKey]).isEqualTo(AppAssignment.Blocked)
    }

    @Test
    fun setHasSeenFirstRunHint_updatesOnlyHintFlag() = runTest(testDispatcher) {
        repository.setHasSeenFirstRunHint(true)
        val settings = repository.getSettings()
        assertThat(settings.general.hasSeenFirstRunHint).isTrue()
        assertThat(settings.general.showUnlocks).isTrue()
    }

    @Test
    fun removeApps_removesFromBothQuickActionsAndAssignments() = runTest(testDispatcher) {
        val updatedBase = BaseProfileSettings(
            quickActions = listOf(chromeApp, socialApp),
            appAssignments = mapOf(
                chromeKey to AppAssignment.Guarded(null),
                socialKey to AppAssignment.Blocked,
            ),
        )
        repository.updateBaseProfile(updatedBase)

        // Simulate uninstall of Instagram
        repository.removeApps(setOf(socialKey))

        val result = repository.getSettings()
        assertThat(result.baseProfile.quickActions).containsExactly(chromeApp)
        assertThat(result.baseProfile.appAssignments).doesNotContainKey(socialKey)
        assertThat(result.baseProfile.appAssignments).containsKey(chromeKey)
    }

    @Test
    fun customProfiles_persistedAndRetrievedCorrectly() = runTest(testDispatcher) {
        val profile = com.garan.drytoast.core.model.CustomProfile(
            id = "deep-work",
            name = "Deep Work",
            enabled = true,
            priority = 0,
            frictionOverride = FrictionChoice.DRAG_SORT,
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Schedule(
                com.garan.drytoast.core.model.ScheduleTrigger(
                    days = setOf(
                        com.garan.drytoast.core.model.DayOfWeek.MONDAY,
                        com.garan.drytoast.core.model.DayOfWeek.FRIDAY,
                    ),
                    startTime = com.garan.drytoast.core.model.TimeOfDay(8, 30),
                    endTime = com.garan.drytoast.core.model.TimeOfDay(16, 45),
                ),
            ),
            appAssignments = mapOf(
                socialKey to AppAssignment.Blocked,
                chromeKey to AppAssignment.Guarded(FrictionChoice.CARET_TRACING),
            ),
        )

        repository.saveCustomProfile(profile)

        val latest = repository.getSettings()
        assertThat(latest.customProfiles).hasSize(1)
        val loaded = latest.customProfiles.first()
        assertThat(loaded.id).isEqualTo("deep-work")
        assertThat(loaded.name).isEqualTo("Deep Work")
        assertThat(loaded.enabled).isTrue()
        assertThat(loaded.priority).isEqualTo(0)
        assertThat(loaded.frictionOverride).isEqualTo(FrictionChoice.DRAG_SORT)
        assertThat(loaded.appAssignments[socialKey]).isEqualTo(AppAssignment.Blocked)
        assertThat(loaded.appAssignments[chromeKey]).isEqualTo(AppAssignment.Guarded(FrictionChoice.CARET_TRACING))

        val schedule = (loaded.trigger as com.garan.drytoast.core.model.ProfileTrigger.Schedule).schedule
        assertThat(schedule.days).containsExactly(
            com.garan.drytoast.core.model.DayOfWeek.MONDAY,
            com.garan.drytoast.core.model.DayOfWeek.FRIDAY,
        )
        assertThat(schedule.startTime).isEqualTo(com.garan.drytoast.core.model.TimeOfDay(8, 30))
        assertThat(schedule.endTime).isEqualTo(com.garan.drytoast.core.model.TimeOfDay(16, 45))
        assertThat(schedule.sameScheduleEveryDay).isTrue()
    }

    @Test
    fun customProfiles_perDaySchedulePersistedAndRetrievedCorrectly() = runTest(testDispatcher) {
        val profile = com.garan.drytoast.core.model.CustomProfile(
            id = "flexible-schedule",
            name = "Flexible Schedule",
            enabled = true,
            priority = 0,
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Schedule(
                com.garan.drytoast.core.model.ScheduleTrigger(
                    dailyWindows = mapOf(
                        com.garan.drytoast.core.model.DayOfWeek.MONDAY to
                            com.garan.drytoast.core.model.TimeWindow(
                                com.garan.drytoast.core.model.TimeOfDay(19, 0),
                                com.garan.drytoast.core.model.TimeOfDay(6, 0),
                            ),
                        com.garan.drytoast.core.model.DayOfWeek.TUESDAY to
                            com.garan.drytoast.core.model.TimeWindow(
                                com.garan.drytoast.core.model.TimeOfDay(10, 0),
                                com.garan.drytoast.core.model.TimeOfDay(11, 0),
                            ),
                    ),
                    sameScheduleEveryDay = false,
                ),
            ),
        )

        repository.saveCustomProfile(profile)

        val latest = repository.getSettings()
        val loaded = latest.customProfiles.first()
        val schedule = (loaded.trigger as com.garan.drytoast.core.model.ProfileTrigger.Schedule).schedule
        assertThat(schedule.sameScheduleEveryDay).isFalse()
        assertThat(schedule.dailyWindows[com.garan.drytoast.core.model.DayOfWeek.MONDAY]).isEqualTo(
            com.garan.drytoast.core.model.TimeWindow(
                com.garan.drytoast.core.model.TimeOfDay(19, 0),
                com.garan.drytoast.core.model.TimeOfDay(6, 0),
            ),
        )
        assertThat(schedule.dailyWindows[com.garan.drytoast.core.model.DayOfWeek.TUESDAY]).isEqualTo(
            com.garan.drytoast.core.model.TimeWindow(
                com.garan.drytoast.core.model.TimeOfDay(10, 0),
                com.garan.drytoast.core.model.TimeOfDay(11, 0),
            ),
        )
    }

    @Test
    fun deleteCustomProfile_removesSpecifiedProfile() = runTest(testDispatcher) {
        val p1 = com.garan.drytoast.core.model.CustomProfile(id = "p1", name = "P1", priority = 0)
        val p2 = com.garan.drytoast.core.model.CustomProfile(id = "p2", name = "P2", priority = 1)
        repository.updateCustomProfiles(listOf(p1, p2))

        repository.deleteCustomProfile("p1")

        val latest = repository.getSettings()
        assertThat(latest.customProfiles.map { it.id }).containsExactly("p2")
    }

    @Test
    fun removeApps_prunesCustomProfilesAndDisablesWhenEmpty() = runTest(testDispatcher) {
        val workProfile = com.garan.drytoast.core.model.CustomProfile(
            id = "work",
            name = "Work",
            enabled = true,
            appAssignments = mapOf(
                socialKey to AppAssignment.Blocked,
            ),
        )
        repository.saveCustomProfile(workProfile)

        // Uninstall socialKey -> workProfile has no other apps -> should auto-disable
        repository.removeApps(setOf(socialKey))

        val latest = repository.getSettings()
        val prunedProfile = latest.customProfiles.first { it.id == "work" }
        assertThat(prunedProfile.appAssignments).isEmpty()
        assertThat(prunedProfile.enabled).isFalse() // Auto-disable invariant verified
    }

    @Test
    fun customProfiles_geoTriggerPersistedAndRetrievedCorrectly() = runTest(testDispatcher) {
        val geoProfile = com.garan.drytoast.core.model.CustomProfile(
            id = "away-home",
            name = "Away from Home",
            enabled = true,
            priority = 0,
            colorIndex = 3,
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Geo(
                com.garan.drytoast.core.model.GeoTrigger(
                    latitude = 51.5074,
                    longitude = -0.1278,
                    radiusMeters = 500,
                    mode = com.garan.drytoast.core.model.GeoTriggerMode.OUTSIDE,
                ),
            ),
            appAssignments = mapOf(socialKey to AppAssignment.Blocked),
        )

        repository.saveCustomProfile(geoProfile)

        val latest = repository.getSettings()
        assertThat(latest.customProfiles).hasSize(1)
        val loaded = latest.customProfiles.first()
        assertThat(loaded.id).isEqualTo("away-home")
        assertThat(loaded.name).isEqualTo("Away from Home")
        assertThat(loaded.colorIndex).isEqualTo(3)
        val geo = (loaded.trigger as com.garan.drytoast.core.model.ProfileTrigger.Geo).geo
        assertThat(geo.latitude).isWithin(0.00001).of(51.5074)
        assertThat(geo.longitude).isWithin(0.00001).of(-0.1278)
        assertThat(geo.radiusMeters).isEqualTo(500)
        assertThat(geo.mode).isEqualTo(com.garan.drytoast.core.model.GeoTriggerMode.OUTSIDE)
    }

    @Test
    fun saveTrigger_persistsAndUpdatesScheduleAndGeoTriggers() = runTest(testDispatcher) {
        val workHours = com.garan.drytoast.core.model.SavedTrigger(
            id = "t-work-hours",
            name = "Work Hours",
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Schedule(
                com.garan.drytoast.core.model.ScheduleTrigger(
                    days = setOf(com.garan.drytoast.core.model.DayOfWeek.MONDAY),
                    startTime = com.garan.drytoast.core.model.TimeOfDay(9, 0),
                    endTime = com.garan.drytoast.core.model.TimeOfDay(17, 0),
                ),
            ),
        )
        val atOffice = com.garan.drytoast.core.model.SavedTrigger(
            id = "t-office",
            name = "At Office",
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Geo(
                com.garan.drytoast.core.model.GeoTrigger(
                    latitude = 51.5,
                    longitude = -0.1,
                    radiusMeters = 250,
                    mode = com.garan.drytoast.core.model.GeoTriggerMode.INSIDE,
                ),
            ),
        )

        repository.saveTrigger(workHours)
        repository.saveTrigger(atOffice)

        val settings = repository.getSettings()
        assertThat(settings.savedTriggers).containsExactly(workHours, atOffice).inOrder()

        val renamedOffice = atOffice.copy(name = "HQ Office")
        repository.saveTrigger(renamedOffice)
        assertThat(repository.getSettings().savedTriggers)
            .containsExactly(workHours, renamedOffice)
            .inOrder()
    }

    @Test
    fun customProfiles_activationRulesAndOrPersistedCorrectly() = runTest(testDispatcher) {
        val profile = com.garan.drytoast.core.model.CustomProfile(
            id = "focus",
            name = "Focus",
            enabled = true,
            priority = 0,
            activationRules = listOf(
                com.garan.drytoast.core.model.TriggerRule(listOf("t-work-hours", "t-office")),
                com.garan.drytoast.core.model.TriggerRule(listOf("t-late-night")),
            ),
            trigger = null,
            appAssignments = mapOf(socialKey to AppAssignment.Blocked),
        )

        repository.saveCustomProfile(profile)

        val loaded = repository.getSettings().customProfiles.single()
        assertThat(loaded.trigger).isNull()
        assertThat(loaded.activationRules).containsExactly(
            com.garan.drytoast.core.model.TriggerRule(listOf("t-work-hours", "t-office")),
            com.garan.drytoast.core.model.TriggerRule(listOf("t-late-night")),
        ).inOrder()
    }

    @Test
    fun legacyInlineTrigger_autoMigratesToSavedTriggerAndActivationRule() = runTest(testDispatcher) {
        val legacyProfile = com.garan.drytoast.core.model.CustomProfile(
            id = "legacy-work",
            name = "Legacy Work",
            enabled = true,
            priority = 0,
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Schedule(),
        )

        repository.saveCustomProfile(legacyProfile)

        val settings = repository.getSettings()
        val migratedProfile = settings.customProfiles.single()
        assertThat(migratedProfile.activationRules).containsExactly(
            com.garan.drytoast.core.model.TriggerRule(listOf("trigger-legacy-work")),
        )
        assertThat(settings.savedTriggers.map { it.id }).containsExactly("trigger-legacy-work")
        assertThat(settings.savedTriggers.single().name).isEqualTo("Legacy Work")
    }

    @Test
    fun deleteTrigger_stripsFromProfileRulesAndAutoDisablesWhenNoTriggersRemain() = runTest(testDispatcher) {
        val t1 = com.garan.drytoast.core.model.SavedTrigger(
            id = "t1",
            name = "Work Hours",
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Schedule(),
        )
        val t2 = com.garan.drytoast.core.model.SavedTrigger(
            id = "t2",
            name = "At Office",
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Geo(
                com.garan.drytoast.core.model.GeoTrigger(latitude = 51.5, longitude = -0.1),
            ),
        )
        repository.saveTrigger(t1)
        repository.saveTrigger(t2)

        val singleTriggerProfile = com.garan.drytoast.core.model.CustomProfile(
            id = "p-single",
            name = "Single Trigger",
            enabled = true,
            priority = 0,
            activationRules = listOf(com.garan.drytoast.core.model.TriggerRule(listOf("t1"))),
            trigger = null,
        )
        val multiTriggerProfile = com.garan.drytoast.core.model.CustomProfile(
            id = "p-multi",
            name = "Multi Trigger",
            enabled = true,
            priority = 1,
            activationRules = listOf(com.garan.drytoast.core.model.TriggerRule(listOf("t1", "t2"))),
            trigger = null,
        )
        repository.updateCustomProfiles(listOf(singleTriggerProfile, multiTriggerProfile))

        repository.deleteTrigger("t1")

        val settings = repository.getSettings()
        assertThat(settings.savedTriggers).containsExactly(t2)

        val updatedSingle = settings.customProfiles.first { it.id == "p-single" }
        assertThat(updatedSingle.activationRules).isEmpty()
        assertThat(updatedSingle.enabled).isFalse()

        val updatedMulti = settings.customProfiles.first { it.id == "p-multi" }
        assertThat(updatedMulti.activationRules).containsExactly(
            com.garan.drytoast.core.model.TriggerRule(listOf("t2")),
        )
        assertThat(updatedMulti.enabled).isTrue()
    }

    @Test
    fun saveTrigger_persistsAndRestoresNfcTriggerWithTagsModeAndDuration() = runTest(testDispatcher) {
        val nfcSavedTrigger = com.garan.drytoast.core.model.SavedTrigger(
            id = "t-desk-nfc",
            name = "Desk NFC",
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Nfc(
                com.garan.drytoast.core.model.NfcTrigger(
                    tags = listOf(
                        com.garan.drytoast.core.model.NfcTagEntry(uid = "04:A2:3F:8B", label = "Desk Sticker"),
                        com.garan.drytoast.core.model.NfcTagEntry(uid = "04:B1:22:99", label = "Badge"),
                    ),
                    mode = com.garan.drytoast.core.model.NfcTriggerMode.TIMED_SESSION,
                    durationMinutes = 120,
                ),
            ),
        )

        repository.saveTrigger(nfcSavedTrigger)

        val loaded = repository.getSettings().savedTriggers.single()
        assertThat(loaded).isEqualTo(nfcSavedTrigger)
        val loadedNfc = (loaded.trigger as com.garan.drytoast.core.model.ProfileTrigger.Nfc).nfc
        assertThat(loadedNfc.mode).isEqualTo(com.garan.drytoast.core.model.NfcTriggerMode.TIMED_SESSION)
        assertThat(loadedNfc.durationMinutes).isEqualTo(120)
        assertThat(loadedNfc.tags).containsExactly(
            com.garan.drytoast.core.model.NfcTagEntry(uid = "04:A2:3F:8B", label = "Desk Sticker"),
            com.garan.drytoast.core.model.NfcTagEntry(uid = "04:B1:22:99", label = "Badge"),
        ).inOrder()
    }

    @Test
    fun updateNfcLatches_persistsRoundTripAndReconcilesOnTriggerSaveOrDelete() = runTest(testDispatcher) {
        val t1 = com.garan.drytoast.core.model.SavedTrigger(
            id = "t-nfc-1",
            name = "Bedside",
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Nfc(
                com.garan.drytoast.core.model.NfcTrigger(
                    tags = listOf(com.garan.drytoast.core.model.NfcTagEntry(uid = "04:AA:BB:CC", label = "Night")),
                    mode = com.garan.drytoast.core.model.NfcTriggerMode.TOGGLE_ON_TAP,
                ),
            ),
        )
        val t2 = com.garan.drytoast.core.model.SavedTrigger(
            id = "t-nfc-2",
            name = "Desk",
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Nfc(
                com.garan.drytoast.core.model.NfcTrigger(
                    tags = listOf(com.garan.drytoast.core.model.NfcTagEntry(uid = "04:11:22:33", label = "Desk")),
                    mode = com.garan.drytoast.core.model.NfcTriggerMode.TIMED_SESSION,
                    durationMinutes = 30,
                ),
            ),
        )
        repository.saveTrigger(t1)
        repository.saveTrigger(t2)

        val latch1 = com.garan.drytoast.core.model.NfcLatchState(
            triggerId = "t-nfc-1",
            latchedAtEpochMillis = 1_000_000L,
            expiresAtEpochMillis = null,
            latchedByTagUid = "04:AA:BB:CC",
        )
        val latch2 = com.garan.drytoast.core.model.NfcLatchState(
            triggerId = "t-nfc-2",
            latchedAtEpochMillis = 2_000_000L,
            expiresAtEpochMillis = 3_800_000L,
            latchedByTagUid = "04:11:22:33",
        )
        repository.updateNfcLatches(
            mapOf(
                "t-nfc-1" to latch1,
                "t-nfc-2" to latch2,
            ),
        )

        val loadedLatches = repository.getSettings().nfcLatches
        assertThat(loadedLatches).containsExactly(
            "t-nfc-1",
            latch1,
            "t-nfc-2",
            latch2,
        )

        // Editing t-nfc-1 from TOGGLE_ON_TAP to TIMED_SESSION (15m) updates its persisted expiresAtEpochMillis
        repository.saveTrigger(
            t1.copy(
                trigger = com.garan.drytoast.core.model.ProfileTrigger.Nfc(
                    com.garan.drytoast.core.model.NfcTrigger(
                        tags = listOf(com.garan.drytoast.core.model.NfcTagEntry(uid = "04:AA:BB:CC", label = "Night")),
                        mode = com.garan.drytoast.core.model.NfcTriggerMode.TIMED_SESSION,
                        durationMinutes = 15,
                    ),
                ),
            ),
        )
        assertThat(repository.getSettings().nfcLatches["t-nfc-1"]?.expiresAtEpochMillis)
            .isEqualTo(1_000_000L + 15 * 60_000L)

        // Deleting t-nfc-2 prunes its persisted latch and daily usage
        repository.updateNfcLatches(
            latches = repository.getSettings().nfcLatches,
            dailyUsage = mapOf(
                "t-nfc-1" to com.garan.drytoast.core.model.NfcDailyUsage(localDate = "2026-11-14", tapCount = 1),
                "t-nfc-2" to com.garan.drytoast.core.model.NfcDailyUsage(localDate = "2026-11-14", tapCount = 2),
            ),
        )
        assertThat(repository.getSettings().nfcDailyUsage.keys).containsExactly("t-nfc-1", "t-nfc-2")

        repository.deleteTrigger("t-nfc-2")
        assertThat(repository.getSettings().nfcLatches.keys).containsExactly("t-nfc-1")
        assertThat(repository.getSettings().nfcDailyUsage.keys).containsExactly("t-nfc-1")
    }

    @Test
    fun saveTrigger_persistsNfcTriggerMaxDailyUsesRoundTrip() = runTest(testDispatcher) {
        val limitedTrigger = com.garan.drytoast.core.model.SavedTrigger(
            id = "t-nfc-limited",
            name = "Break Tag",
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Nfc(
                com.garan.drytoast.core.model.NfcTrigger(
                    tags = listOf(com.garan.drytoast.core.model.NfcTagEntry(uid = "04:AA:11:22", label = "Break")),
                    mode = com.garan.drytoast.core.model.NfcTriggerMode.TIMED_SESSION,
                    durationMinutes = 15,
                    maxDailyUses = 3,
                ),
            ),
        )
        repository.saveTrigger(limitedTrigger)

        val loaded = repository.getSettings().savedTriggers.first { it.id == "t-nfc-limited" }
        val loadedNfc = (loaded.trigger as com.garan.drytoast.core.model.ProfileTrigger.Nfc).nfc
        assertThat(loadedNfc.maxDailyUses).isEqualTo(3)
    }

    @Test
    fun getSettings_memoizesDeserializedCollectionsWhenUnrelatedPreferencesChange() = runTest(testDispatcher) {
        val trigger = com.garan.drytoast.core.model.SavedTrigger(
            id = "t-work",
            name = "Work Hours",
            trigger = com.garan.drytoast.core.model.ProfileTrigger.Schedule(),
        )
        val profile = com.garan.drytoast.core.model.CustomProfile(
            id = "work",
            name = "Work",
            enabled = true,
            priority = 0,
            trigger = null,
            activationRules = listOf(com.garan.drytoast.core.model.TriggerRule(listOf("t-work"))),
        )
        repository.saveTrigger(trigger)
        repository.saveCustomProfile(profile)
        repository.updateBaseProfile(
            BaseProfileSettings(
                quickActions = listOf(socialApp),
                appAssignments = mapOf(socialKey to AppAssignment.Blocked),
            ),
        )
        repository.updateNfcLatches(
            mapOf(
                "t-work" to com.garan.drytoast.core.model.NfcLatchState(
                    triggerId = "t-work",
                    latchedAtEpochMillis = 500L,
                ),
            ),
        )

        val firstSnapshot = repository.getSettings()
        repository.setHasSeenFirstRunHint(true)
        val secondSnapshot = repository.getSettings()

        assertThat(secondSnapshot.general.hasSeenFirstRunHint).isTrue()
        assertThat(secondSnapshot.customProfiles).isSameInstanceAs(firstSnapshot.customProfiles)
        assertThat(secondSnapshot.savedTriggers).isSameInstanceAs(firstSnapshot.savedTriggers)
        assertThat(secondSnapshot.baseProfile.appAssignments).isSameInstanceAs(firstSnapshot.baseProfile.appAssignments)
        assertThat(secondSnapshot.baseProfile.quickActions).isSameInstanceAs(firstSnapshot.baseProfile.quickActions)
        assertThat(secondSnapshot.nfcLatches).isSameInstanceAs(firstSnapshot.nfcLatches)
    }

    @Test
    fun requireRebootForSettingsChange_enablesImmediately_stagesSubsequentMutationsUntilReboot() =
        runTest(testDispatcher) {
            // 1. Enabling requireRebootForSettingsChange while OFF applies immediately to live
            repository.updateGeneralSettings(
                GeneralSettings(requireRebootForSettingsChange = true),
            )
            val afterEnable = repository.getSettings()
            assertThat(afterEnable.general.requireRebootForSettingsChange).isTrue()
            assertThat(afterEnable.hasPendingRebootChanges).isFalse()

            // 2. Subsequent edits stage to stagedShadow without changing active live settings
            repository.updateBaseProfile(
                BaseProfileSettings(
                    defaultFriction = FrictionChoice.DRAG_SORT,
                    appAssignments = mapOf(socialKey to AppAssignment.Blocked),
                ),
            )
            val whileStaged = repository.getSettings()
            assertThat(whileStaged.hasPendingRebootChanges).isTrue()
            // Live remains unchanged:
            assertThat(whileStaged.baseProfile.defaultFriction).isEqualTo(FrictionChoice.TYPED_INTENTION)
            assertThat(whileStaged.baseProfile.appAssignments).isEmpty()
            // Editable snapshot reflects staged changes:
            assertThat(whileStaged.editableSnapshot.baseProfile.defaultFriction).isEqualTo(FrictionChoice.DRAG_SORT)
            assertThat(whileStaged.editableSnapshot.baseProfile.appAssignments[socialKey])
                .isEqualTo(AppAssignment.Blocked)

            // 3. Toggling requireRebootForSettingsChange OFF stages in shadow, and further edits STILL stage!
            repository.updateGeneralSettings(
                whileStaged.editableSnapshot.general.copy(
                    requireRebootForSettingsChange = false,
                    showUnlocks = false,
                ),
            )
            val afterStagingDisable = repository.getSettings()
            assertThat(afterStagingDisable.general.requireRebootForSettingsChange).isTrue()
            assertThat(afterStagingDisable.general.showUnlocks).isTrue()
            assertThat(afterStagingDisable.editableSnapshot.general.requireRebootForSettingsChange).isFalse()
            assertThat(afterStagingDisable.editableSnapshot.general.showUnlocks).isFalse()

            // 4. Explicit promotion on boot applies shadow to live and clears pending flag
            val promoted = repository.promotePendingSettingsOnBoot()
            assertThat(promoted).isTrue()
            val afterBoot = repository.getSettings()
            assertThat(afterBoot.hasPendingRebootChanges).isFalse()
            assertThat(afterBoot.general.requireRebootForSettingsChange).isFalse()
            assertThat(afterBoot.general.showUnlocks).isFalse()
            assertThat(afterBoot.baseProfile.defaultFriction).isEqualTo(FrictionChoice.DRAG_SORT)
            assertThat(afterBoot.baseProfile.appAssignments[socialKey]).isEqualTo(AppAssignment.Blocked)
        }

    @Test
    fun requireRebootForSettingsChange_revertToLiveOrDiscardClearsPendingShadow() = runTest(testDispatcher) {
        repository.updateGeneralSettings(GeneralSettings(requireRebootForSettingsChange = true))

        // Stage a change to showUnlocks
        repository.updateGeneralSettings(
            GeneralSettings(requireRebootForSettingsChange = true, showUnlocks = false),
        )
        assertThat(repository.getSettings().hasPendingRebootChanges).isTrue()

        // Changing it back to match live automatically clears stagedShadow
        repository.updateGeneralSettings(
            GeneralSettings(requireRebootForSettingsChange = true, showUnlocks = true),
        )
        assertThat(repository.getSettings().hasPendingRebootChanges).isFalse()
        assertThat(repository.getSettings().stagedShadow).isNull()

        // Stage another change and discardPendingChanges()
        repository.updateGeneralSettings(
            GeneralSettings(requireRebootForSettingsChange = true, showRest = false),
        )
        assertThat(repository.getSettings().hasPendingRebootChanges).isTrue()
        repository.discardPendingChanges()
        assertThat(repository.getSettings().hasPendingRebootChanges).isFalse()
        assertThat(repository.getSettings().editableSnapshot.general.showRest).isTrue()
    }

    @Test
    fun requireRebootForSettingsChange_bootCountIncrementAutoPromotesShadow() = runTest(testDispatcher) {
        repository.updateGeneralSettings(GeneralSettings(requireRebootForSettingsChange = true))
        repository.updateGeneralSettings(
            GeneralSettings(requireRebootForSettingsChange = true, showUnlocks = false),
        )

        // Same boot count (e.g., Force Stop and restart without reboot) -> still staged
        assertThat(repository.getSettings().hasPendingRebootChanges).isTrue()
        assertThat(repository.getSettings().general.showUnlocks).isTrue()

        // Kernel boot count increments -> automatically promoted on read
        currentBootCount = 11
        val afterRebootRead = repository.getSettings()
        assertThat(afterRebootRead.hasPendingRebootChanges).isFalse()
        assertThat(afterRebootRead.general.showUnlocks).isFalse()
    }

    @Test
    fun setSettingsLocked_rejectsAllLiveAndShadowMutationsWhileLocked_butAllowsBootPromotion() =
        runTest(testDispatcher) {
            // 1. Lock settings while in standard mode -> live mutations are rejected
            repository.setSettingsLocked(true)
            assertThat(repository.getSettings().isSettingsLocked).isTrue()

            repository.updateGeneralSettings(GeneralSettings(showUnlocks = false))
            repository.updateBaseProfile(
                BaseProfileSettings(
                    defaultFriction = FrictionChoice.DRAG_SORT,
                    appAssignments = mapOf(socialKey to AppAssignment.Blocked),
                ),
            )
            repository.saveCustomProfile(
                com.garan.drytoast.core.model.CustomProfile(id = "work", name = "Work"),
            )

            val lockedSettings = repository.getSettings()
            assertThat(lockedSettings.general.showUnlocks).isTrue()
            assertThat(lockedSettings.baseProfile.defaultFriction).isEqualTo(FrictionChoice.TYPED_INTENTION)
            assertThat(lockedSettings.baseProfile.appAssignments).isEmpty()
            assertThat(lockedSettings.customProfiles).isEmpty()

            // 2. Unlock, enable requireRebootForSettingsChange, and stage a shadow change
            repository.setSettingsLocked(false)
            assertThat(repository.getSettings().isSettingsLocked).isFalse()

            repository.updateGeneralSettings(GeneralSettings(requireRebootForSettingsChange = true))
            repository.updateGeneralSettings(
                GeneralSettings(requireRebootForSettingsChange = true, showUnlocks = false),
            )
            assertThat(repository.getSettings().hasPendingRebootChanges).isTrue()

            // 3. Re-lock settings while shadow changes are pending:
            //    - Cannot modify or discard the staged shadow
            //    - Rebooting still promotes the existing staged shadow to live
            repository.setSettingsLocked(true)
            repository.updateGeneralSettings(
                GeneralSettings(
                    requireRebootForSettingsChange = true,
                    showUnlocks = false,
                    showRest = false,
                ),
            )
            repository.discardPendingChanges()

            val whileLockedWithShadow = repository.getSettings()
            assertThat(whileLockedWithShadow.hasPendingRebootChanges).isTrue()
            assertThat(whileLockedWithShadow.editableSnapshot.general.showUnlocks).isFalse()
            assertThat(whileLockedWithShadow.editableSnapshot.general.showRest).isTrue()

            // Reboot promotes the pre-existing shadow even while settings remain locked
            val promoted = repository.promotePendingSettingsOnBoot()
            assertThat(promoted).isTrue()
            val afterBoot = repository.getSettings()
            assertThat(afterBoot.isSettingsLocked).isTrue()
            assertThat(afterBoot.hasPendingRebootChanges).isFalse()
            assertThat(afterBoot.general.showUnlocks).isFalse()
        }

    @Test
    fun setHasCompletedOnboarding_persistsFlagIndependentlyOfStagedRebootChanges() = runTest(testDispatcher) {
        assertThat(repository.getSettings().general.hasCompletedOnboarding).isFalse()

        repository.setHasCompletedOnboarding(true)
        assertThat(repository.getSettings().general.hasCompletedOnboarding).isTrue()

        repository.updateGeneralSettings(
            GeneralSettings(
                requireRebootForSettingsChange = true,
                hasCompletedOnboarding = true,
            ),
        )
        repository.updateGeneralSettings(
            GeneralSettings(
                requireRebootForSettingsChange = true,
                showUnlocks = false,
                hasCompletedOnboarding = true,
            ),
        )
        val staged = repository.getSettings()
        assertThat(staged.general.hasCompletedOnboarding).isTrue()
        assertThat(staged.editableSnapshot.general.hasCompletedOnboarding).isTrue()
    }

    @Test
    fun updateDpcSuspendedPackages_persistsAndClearsSuspendedPackages() = runTest(testDispatcher) {
        assertThat(repository.getSettings().dpcSuspendedPackages).isEmpty()

        repository.updateDpcSuspendedPackages(setOf("com.instagram.android", "com.zhiliaoapp.musically"))
        assertThat(repository.getSettings().dpcSuspendedPackages)
            .containsExactly("com.instagram.android", "com.zhiliaoapp.musically")

        repository.updateDpcSuspendedPackages(emptySet())
        assertThat(repository.getSettings().dpcSuspendedPackages).isEmpty()
    }

    @Test
    fun guardedSessionGraceMinutes_persistsInLiveAndStagedRebootShadow() = runTest(testDispatcher) {
        assertThat(repository.getSettings().general.guardedSessionGraceMinutes).isEqualTo(5)

        repository.updateGeneralSettings(
            GeneralSettings(
                guardedSessionGraceMinutes = 15,
                requireRebootForSettingsChange = true,
            ),
        )
        assertThat(repository.getSettings().general.guardedSessionGraceMinutes).isEqualTo(15)

        // Stage change to 0 minutes while requireRebootForSettingsChange is active
        repository.updateGeneralSettings(
            GeneralSettings(
                guardedSessionGraceMinutes = 0,
                requireRebootForSettingsChange = true,
            ),
        )
        val staged = repository.getSettings()
        assertThat(staged.hasPendingRebootChanges).isTrue()
        assertThat(staged.general.guardedSessionGraceMinutes).isEqualTo(15)
        assertThat(staged.editableSnapshot.general.guardedSessionGraceMinutes).isEqualTo(0)

        // Promote on boot
        repository.promotePendingSettingsOnBoot()
        assertThat(repository.getSettings().general.guardedSessionGraceMinutes).isEqualTo(0)
    }

    @Test
    fun breakGlass_persistsConfigAndRuntimeExpiry_respectsSettingsLockAndRebootStaging() = runTest(testDispatcher) {
        // 1. Save normalized Break-Glass card UID & custom duration
        repository.updateGeneralSettings(
            GeneralSettings(
                breakGlassTagUid = "04a1b2c3",
                breakGlassDurationMinutes = 60,
            ),
        )
        val saved = repository.getSettings()
        assertThat(saved.general.breakGlassTagUid).isEqualTo("04:A1:B2:C3")
        assertThat(saved.general.breakGlassDurationMinutes).isEqualTo(60)
        assertThat(saved.breakGlassUntilEpochMillis).isNull()

        // 2. Lock Settings -> card UID & duration mutations are rejected,
        //    but runtime updateBreakGlassUntil() STILL works so tapping the physical card works while locked!
        repository.setSettingsLocked(true)
        repository.updateGeneralSettings(
            saved.general.copy(breakGlassTagUid = "04:FF:EE:DD", breakGlassDurationMinutes = 15),
        )
        assertThat(repository.getSettings().general.breakGlassTagUid).isEqualTo("04:A1:B2:C3")
        assertThat(repository.getSettings().general.breakGlassDurationMinutes).isEqualTo(60)

        val expiryMillis = 1_750_000_000_000L
        repository.updateBreakGlassUntil(expiryMillis)
        assertThat(repository.getSettings().breakGlassUntilEpochMillis).isEqualTo(expiryMillis)

        repository.updateBreakGlassUntil(null)
        assertThat(repository.getSettings().breakGlassUntilEpochMillis).isNull()

        // 3. Unlock Settings & enable requireRebootForSettingsChange -> card replacement is staged until reboot,
        //    while runtime updateBreakGlassUntil() still applies immediately without touching stagedShadow.
        repository.setSettingsLocked(false)
        repository.updateGeneralSettings(
            repository.getSettings().general.copy(requireRebootForSettingsChange = true),
        )
        repository.updateGeneralSettings(
            repository.getSettings().editableSnapshot.general.copy(
                breakGlassTagUid = "04:99:88:77",
                breakGlassDurationMinutes = 15,
            ),
        )
        val staged = repository.getSettings()
        assertThat(staged.hasPendingRebootChanges).isTrue()
        assertThat(staged.general.breakGlassTagUid).isEqualTo("04:A1:B2:C3")
        assertThat(staged.editableSnapshot.general.breakGlassTagUid).isEqualTo("04:99:88:77")
        assertThat(staged.editableSnapshot.general.breakGlassDurationMinutes).isEqualTo(15)

        repository.updateBreakGlassUntil(expiryMillis)
        assertThat(repository.getSettings().breakGlassUntilEpochMillis).isEqualTo(expiryMillis)
        assertThat(repository.getSettings().hasPendingRebootChanges).isTrue()

        repository.promotePendingSettingsOnBoot()
        val promoted = repository.getSettings()
        assertThat(promoted.general.breakGlassTagUid).isEqualTo("04:99:88:77")
        assertThat(promoted.general.breakGlassDurationMinutes).isEqualTo(15)
        assertThat(promoted.breakGlassUntilEpochMillis).isEqualTo(expiryMillis)
    }

    @Test
    fun customProfile_defaultRule_persistsAcrossReloadsAndRebootStaging() = runTest(testDispatcher) {
        val strictProfile = com.garan.drytoast.core.model.CustomProfile(
            id = "strict",
            name = "Strict",
            priority = 0,
            enabled = true,
            defaultRule = com.garan.drytoast.core.model.ProfileDefaultRule.BLOCK_ALL,
        )
        repository.saveCustomProfile(strictProfile)

        val loaded = repository.getSettings().customProfiles.first { it.id == "strict" }
        assertThat(loaded.defaultRule).isEqualTo(com.garan.drytoast.core.model.ProfileDefaultRule.BLOCK_ALL)

        // Stage change to ALLOW_ALL while requireRebootForSettingsChange is active
        repository.updateGeneralSettings(GeneralSettings(requireRebootForSettingsChange = true))
        repository.saveCustomProfile(
            strictProfile.copy(defaultRule = com.garan.drytoast.core.model.ProfileDefaultRule.ALLOW_ALL),
        )
        val staged = repository.getSettings()
        assertThat(staged.customProfiles.first { it.id == "strict" }.defaultRule)
            .isEqualTo(com.garan.drytoast.core.model.ProfileDefaultRule.BLOCK_ALL)
        assertThat(staged.editableSnapshot.customProfiles.first { it.id == "strict" }.defaultRule)
            .isEqualTo(com.garan.drytoast.core.model.ProfileDefaultRule.ALLOW_ALL)

        repository.promotePendingSettingsOnBoot()
        assertThat(repository.getSettings().customProfiles.first { it.id == "strict" }.defaultRule)
            .isEqualTo(com.garan.drytoast.core.model.ProfileDefaultRule.ALLOW_ALL)
    }
}
