package com.hsm.beardylog

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.hsm.beardylog.data.AppDatabase
import com.hsm.beardylog.data.Reptile
import com.hsm.beardylog.data.ReptileRepository
import com.hsm.beardylog.data.WeightChartPreferences
import com.hsm.beardylog.databinding.ActivityMainBinding
import com.hsm.beardylog.viewmodel.ReptileViewModel
import com.hsm.beardylog.viewmodel.ReptileViewModelFactory

class MainActivity : AppBaseActivity() {
    internal lateinit var binding: ActivityMainBinding
    override val bottomInsetTarget: View?
        get() = if (::binding.isInitialized) binding.bottomNavigation else null
    private lateinit var viewModel: ReptileViewModel
    internal var reptiles: List<Reptile> = emptyList()
    internal var allReptiles: List<Reptile> = emptyList()
    private val homeSection = HomeSection(this)
    private val memorialSection = MemorialSection(this)
    private val breedingSection = BreedingSection(this)
    private val settingsSection = SettingsSection(this)
    internal var selectedReptileId: Long? = null
    internal lateinit var database: AppDatabase
    internal lateinit var appSettings: AppSettings
    private lateinit var mainColumn: LinearLayout
    private lateinit var homeContent: View
    internal var currentTopContent: View? = null
    internal var currentSection = MainSection.HOME
    private val calendarSection = CalendarSection(this)
    internal var settingsScrollY = 0
    private var isNavigationHapticsReady = false
    internal val driveAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        settingsSection.handleDriveAuthorizationResult(result)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRootContent()
        registerBackHandler()
        restoreMainState(savedInstanceState)
        setupDataLayer()
        homeSection.setup()
        setupBottomNavigation(savedInstanceState)
        if (isUpdateCheckEnabled()) settingsSection.checkForUpdate()
        settingsSection.showPendingThemeTransition()
        observeProfiles()
    }

    private fun setupRootContent() {
        mainColumn = binding.root.getChildAt(0) as LinearLayout
        homeContent = mainColumn.getChildAt(0)
        currentTopContent = homeContent
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleMainBackPressed()
            }
        })
    }

    private fun restoreMainState(savedInstanceState: Bundle?) {
        calendarSection.restore(savedInstanceState)
        settingsScrollY = savedInstanceState?.getInt(KEY_SETTINGS_SCROLL_Y, 0) ?: 0
    }

    private fun setupDataLayer() {
        database = AppDatabase.getInstance(applicationContext)
        calendarSection.setup()
        breedingSection.setup()
        settingsSection.setup()
        appSettings = AppSettings(this)
        viewModel = ViewModelProvider(this, ReptileViewModelFactory(ReptileRepository(database.reptileDao())))[ReptileViewModel::class.java]
    }

    private fun setupBottomNavigation(savedInstanceState: Bundle?) {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val section = sectionForMenuItem(item.itemId)
            if (section == null) {
                false
            } else {
                if (isNavigationHapticsReady && currentSection != section) {
                    binding.bottomNavigation.selectionHaptic()
                }
                showMainSection(section)
                true
            }
        }
        val restoredSection = savedInstanceState?.getString(KEY_CURRENT_SECTION)?.let { value ->
            runCatching { MainSection.valueOf(value) }.getOrNull()
        } ?: MainSection.HOME
        binding.bottomNavigation.selectedItemId = menuIdForSection(restoredSection)
        showMainSection(restoredSection)
        isNavigationHapticsReady = true
    }

    private fun sectionForMenuItem(itemId: Int): MainSection? = when (itemId) {
        R.id.nav_home -> MainSection.HOME
        R.id.nav_breeding -> MainSection.BREEDING
        R.id.nav_calendar -> MainSection.CALENDAR
        R.id.nav_memorial -> MainSection.MEMORIAL
        R.id.nav_settings -> MainSection.SETTINGS
        else -> null
    }

    private fun observeProfiles() {
        viewModel.reptiles.observe(this) {
            allReptiles = it
            reptiles = it.filter { reptile -> reptile.deathDate == null }
            settingsSection.updateDriveActionAvailability()
            homeSection.resolveInitialProfileSelection()
            if (selectedReptileId != null && reptiles.none { reptile -> reptile.id == selectedReptileId }) selectedReptileId = null
            renderProfiles()
            renderDashboard()
            if (currentSection == MainSection.MEMORIAL) memorialSection.refresh()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_CURRENT_SECTION, currentSection.name)
        calendarSection.save(outState)
        outState.putInt(KEY_SETTINGS_SCROLL_Y, settingsScrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        binding.weightPeriodLabel.text = WeightChartPreferences.homePeriod(this).displayName
        selectedReptileId?.let(::loadWeightHistory)
        if (currentSection == MainSection.MEMORIAL && memorialSection.hasOpenDetail()) {
            replaceTopContent(memorialSection.createContentView())
        }
        if (currentSection == MainSection.BREEDING && breedingSection.hasOpenDetail()) {
            replaceTopContent(breedingSection.createContentView())
        }
    }

    internal fun isUpdateCheckEnabled(): Boolean =
        appSettings.checkUpdatesOnStart

    internal fun isAutoSelectLastProfileEnabled(): Boolean =
        appSettings.autoSelectLastProfile

    private fun showMainSection(section: MainSection) {
        if (currentSection == section) return
        currentSection = section
        if (section != MainSection.CALENDAR) {
            calendarSection.leave()
        }
        if (section != MainSection.MEMORIAL) {
            memorialSection.leave()
        }
        if (section != MainSection.BREEDING) {
            breedingSection.leave()
        }
        replaceTopContent(when (section) {
            MainSection.HOME -> homeContent
            MainSection.BREEDING -> breedingSection.createContentView()
            MainSection.CALENDAR -> calendarSection.createCalendarContentView()
            MainSection.MEMORIAL -> memorialSection.createContentView()
            MainSection.SETTINGS -> settingsSection.createContentView()
        })
    }

    private fun handleMainBackPressed() {
        if (currentSection == MainSection.MEMORIAL && memorialSection.closeDetail()) {
            replaceTopContent(memorialSection.createContentView())
            return
        }
        if (currentSection == MainSection.BREEDING && breedingSection.closeDetail()) {
            replaceTopContent(breedingSection.createContentView())
            return
        }
        if (calendarSection.handleBackPressed()) return
        if (currentSection != MainSection.HOME) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
            return
        }
        finish()
    }

    private fun menuIdForSection(section: MainSection): Int = when (section) {
        MainSection.HOME -> R.id.nav_home
        MainSection.BREEDING -> R.id.nav_breeding
        MainSection.CALENDAR -> R.id.nav_calendar
        MainSection.MEMORIAL -> R.id.nav_memorial
        MainSection.SETTINGS -> R.id.nav_settings
    }

    internal fun replaceTopContent(content: View) {
        if (currentTopContent === content) return
        mainColumn.removeViewAt(0)
        mainColumn.addView(content, 0, LinearLayout.LayoutParams(match, 0, 1f))
        currentTopContent = content
    }

    // ---- 다른 섹션이 호출하는 Home 진입점 (실제 구현은 HomeSection) ----
    internal fun renderDashboard() = homeSection.renderDashboard()
    internal fun renderProfiles() = homeSection.renderProfiles()
    internal fun restoreLastSelectedProfile() = homeSection.restoreLastSelectedProfile()
    internal fun loadWeightHistory(reptileId: Long) = homeSection.loadWeightHistory(reptileId)

    private val match: Int get() = ViewGroup.LayoutParams.MATCH_PARENT

    internal enum class MainSection {
        HOME,
        BREEDING,
        CALENDAR,
        MEMORIAL,
        SETTINGS
    }

    companion object {
        private const val KEY_CURRENT_SECTION = "current_section"
        private const val KEY_SETTINGS_SCROLL_Y = "settings_scroll_y"
    }
}
