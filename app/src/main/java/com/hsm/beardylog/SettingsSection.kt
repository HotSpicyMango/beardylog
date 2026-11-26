package com.hsm.beardylog

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import com.bumptech.glide.Glide
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hsm.beardylog.data.GitHubUpdateChecker
import com.hsm.beardylog.data.ProfileBackupManager
import com.hsm.beardylog.data.WeightChartPeriod
import com.hsm.beardylog.data.WeightChartPreferences
import com.hsm.beardylog.notification.NotificationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import java.net.HttpURLConnection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MainActivity의 "설정" 섹션(테마/홈/프로필 설정 + Google Drive 백업·복원 + 앱 업데이트 체크 + 앱 정보) 전용
 * 뷰 빌드와 상태를 담당한다. MainActivity가 소유하며, 화면 전환 같은 액티비티 공통 동작은 [activity]에 위임한다.
 */
internal class SettingsSection(private val activity: MainActivity) {

    private lateinit var profileBackupManager: ProfileBackupManager
    private var pendingDriveAction: DriveAction? = null
    private var driveActionInProgress = false
    private var driveBackupButton: MaterialButton? = null
    private var driveRestoreButton: MaterialButton? = null
    private var driveBackupStatusText: TextView? = null
    private var driveEmptyStateHintText: TextView? = null
    private var themeTransitionInProgress = false

    private val gitHubUpdateChecker = GitHubUpdateChecker()
    private var pendingApkDownloadId: Long? = null
    /** 설치 권한 설정 화면으로 보낸 동안 기다리는 APK. 돌아왔을 때([onResume]) 권한이 켜졌으면 바로 설치를 이어간다. */
    private var apkAwaitingInstallPermission: File? = null
    private var apkReceiverRegistered = false
    /** 복원 확인 다이얼로그가 떠 있는 동안 백업 임시 파일과 ZipFile 핸들을 들고 있는 미리보기.
     *  다이얼로그로 결론이 나면 그 자리에서 닫지만, 화면이 죽어 다이얼로그가 사라지는 경로는
     *  어느 콜백도 타지 않으므로 여기에 붙잡아 두고 release에서 정리한다. */
    private var pendingRestorePreview: ProfileBackupManager.RestorePreview? = null
    private var restoreInProgress = false
    private val apkDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (completedId == -1L || completedId != pendingApkDownloadId) return
            unregisterApkReceiver()
            handleApkDownloadComplete(completedId)
        }
    }

    private fun unregisterApkReceiver() {
        if (!apkReceiverRegistered) return
        apkReceiverRegistered = false
        runCatching { activity.unregisterReceiver(apkDownloadReceiver) }
    }

    /** MainActivity.onDestroy에서 호출. 다운로드가 끝나기 전에 화면이 죽으면 해제할 곳이 여기뿐이라
     *  없으면 수신기가 액티비티를 붙잡은 채 누수된다. 다운로드 자체는 계속되고, 완료 알림을
     *  탭하면 설치로 이어지므로 여기서 취소하지는 않는다. */
    fun release() {
        unregisterApkReceiver()
        // 복원 중에 닫으면 사진을 꺼내 쓰던 ZIP이 사라져 복원이 조용히 실패한다. 복원이 끝나면 아래 also 블록이 닫는다.
        if (!restoreInProgress) closePendingRestorePreview()
    }

    private fun closePendingRestorePreview() {
        pendingRestorePreview?.close()
        pendingRestorePreview = null
    }

    private enum class DriveAction {
        BACKUP,
        RESTORE
    }

    // ---- MainActivity가 호출하는 진입점 ----

    fun setup() {
        profileBackupManager = ProfileBackupManager(activity, activity.database)
    }

    /** MainActivity의 driveAuthorizationLauncher 콜백에서 위임 호출된다. registerForActivityResult는
     * ComponentActivity에서만 등록할 수 있어 런처 자체는 MainActivity에 남겨두고, 결과 처리만 이곳에서 한다. */
    fun handleDriveAuthorizationResult(result: ActivityResult) {
        val action = pendingDriveAction ?: return
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            finishDriveAction("Google Drive 연결을 취소했습니다")
            return
        }
        try {
            val authorizationResult = Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(data)
            val accessToken = authorizationResult.accessToken
            if (accessToken == null) {
                failDriveAction(IllegalStateException("Google Drive 접근 토큰을 받지 못했습니다"))
                return
            }
            runDriveAction(action, accessToken)
        } catch (error: ApiException) {
            failDriveAction(error)
        }
    }

    /** 설정 메뉴에서 들어가는 하위 화면. 좁은 화면에서는 null이면 메뉴 목록을 보여준다. */
    private enum class SettingsPage(val title: String, val summary: String) {
        GENERAL("일반", "프로필 자동 선택, 홈 그래프 기간"),
        THEME("테마", "화면 모드, 앱 테마색"),
        NOTIFICATION("알림", "일정, 식단, 부화 알림"),
        BACKUP("백업 및 복원", "Google Drive 백업과 복원"),
        UPDATE("업데이트", "자동 확인, 베타 채널"),
        APP_INFO("앱 정보", "버전, 제작")
    }

    private var openPage: SettingsPage? = null

    fun save(outState: Bundle) {
        openPage?.let { outState.putString(KEY_SETTINGS_PAGE, it.name) }
    }

    /** 테마 변경은 recreate()를 거치므로, 보던 하위 화면으로 돌아오려면 상태를 저장해 두어야 한다. */
    fun restore(savedInstanceState: Bundle?) {
        openPage = savedInstanceState?.getString(KEY_SETTINGS_PAGE)
            ?.let { name -> runCatching { SettingsPage.valueOf(name) }.getOrNull() }
    }

    fun leave() {
        openPage = null
        activity.settingsScrollY = 0
    }

    /** 넓은 화면은 메뉴와 하위 화면이 함께 보이므로 뒤로가기로 닫을 하위 화면이 없다. */
    fun closeDetail(): Boolean {
        if (activity.isWide || openPage == null) return false
        val menu = backPreview
        val page = activity.currentTopContent
        if (menu == null || page == null) {
            openPage = null
            activity.settingsScrollY = 0
            activity.replaceTopContent(createContentView())
            return true
        }
        // 예측 뒤로가기 제스처를 끝까지 한 경우: 남은 이동을 마저 보여준 뒤, 뒤에 깔아 둔 메뉴를 그대로 본 화면으로 쓴다
        backPreview = null
        page.animate().translationX(page.width.toFloat()).setDuration(BACK_FINISH_DURATION_MS).start()
        menu.animate().translationX(0f).setDuration(BACK_FINISH_DURATION_MS).withEndAction {
            (menu.parent as? ViewGroup)?.removeView(menu)
            page.translationX = 0f
            // 애니메이션 사이에 다른 탭으로 옮겼거나 이미 닫혔으면 손대지 않는다
            if (activity.currentTopContent !== page || openPage == null) return@withEndAction
            openPage = null
            activity.settingsScrollY = 0
            activity.replaceTopContent(menu)
        }.start()
        return true
    }

    /** 예측 뒤로가기 제스처 동안 하위 화면 뒤에 깔아 두는 메뉴 화면. 제스처가 끝나거나 취소되면 null. */
    private var backPreview: View? = null

    fun onBackStarted() {
        if (activity.isWide || openPage == null || backPreview != null) return
        val page = activity.currentTopContent ?: return
        val host = activity.binding.blurTarget ?: return
        if (page.width == 0) return
        val savedPage = openPage
        val pageScrollY = activity.settingsScrollY
        openPage = null
        activity.settingsScrollY = 0
        val menu = createContentView()
        openPage = savedPage
        activity.settingsScrollY = pageScrollY
        // 메뉴가 하위 화면과 정확히 같은 자리에 오도록, 하위 화면의 위치를 host 기준 좌표로 옮겨 쓴다
        val bounds = Rect(0, 0, page.width, page.height)
        host.offsetDescendantRectToMyCoords(page, bounds)
        host.addView(menu, 0, FrameLayout.LayoutParams(page.width, page.height).apply {
            leftMargin = bounds.left
            topMargin = bounds.top
        })
        backPreview = menu
        onBackProgressed(0f)
    }

    fun onBackProgressed(progress: Float) {
        val menu = backPreview ?: return
        val page = activity.currentTopContent ?: return
        val width = page.width.toFloat()
        page.translationX = width * progress
        menu.translationX = -width * BACK_PARALLAX * (1f - progress)
    }

    fun onBackCancelled() {
        val menu = backPreview ?: return
        backPreview = null
        val page = activity.currentTopContent
        val width = (page?.width ?: menu.width).toFloat()
        page?.animate()?.translationX(0f)?.setDuration(BACK_FINISH_DURATION_MS)?.start()
        menu.animate().translationX(-width * BACK_PARALLAX).setDuration(BACK_FINISH_DURATION_MS).withEndAction {
            (menu.parent as? ViewGroup)?.removeView(menu)
        }.start()
    }

    private fun openSettingsPage(page: SettingsPage) {
        openPage = page
        activity.settingsScrollY = 0
        activity.replaceTopContent(createContentView())
    }

    fun createContentView(): View {
        val scrollView = ScrollView(activity).apply {
            isFillViewport = true
            setBackgroundColor(resColor(R.color.surface_alt))
            setOnScrollChangeListener { _, _, scrollY, _, _ -> activity.settingsScrollY = scrollY }
            val initialScrollY = activity.settingsScrollY
            post { scrollTo(0, initialScrollY) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        scrollView.addView(content, ViewGroup.LayoutParams(match, wrap))
        if (activity.isWide) {
            // 넓은 화면: 왼쪽 칸에 메뉴, 오른쪽 칸에 선택한 하위 화면
            val page = openPage ?: SettingsPage.GENERAL
            scrollView.setTag(R.id.wide_two_pane, true)
            // 두 헤더를 한 줄에 두어, 한쪽 설명이 두 줄로 넘어가도 아래 카드들의 윗선이 맞게 한다
            content.addView(LinearLayout(activity).apply {
                addView(settingsHeader(), LinearLayout.LayoutParams(0, wrap, 1f))
                addView(settingsHeader(page.title, page.summary),
                    LinearLayout.LayoutParams(0, wrap, 1.4f).apply { leftMargin = dp(28) })
            })
            content.addView(LinearLayout(activity).apply {
                addView(settingsMenuCard(selected = page), LinearLayout.LayoutParams(0, wrap, 1f))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addPageContent(page, showBack = false, showHeader = false)
                }, LinearLayout.LayoutParams(0, wrap, 1.4f).apply { leftMargin = dp(28) })
            })
        } else {
            val page = openPage
            if (page == null) {
                content.addView(settingsHeader())
                content.addView(settingsMenuCard(selected = null))
            } else {
                content.addPageContent(page, showBack = true)
            }
        }
        return scrollView
    }

    private fun LinearLayout.addPageContent(page: SettingsPage, showBack: Boolean, showHeader: Boolean = true) {
        if (showBack) {
            addView(TextView(context).apply {
                text = "← 설정"
                textSize = 14f
                setTextColor(resColor(R.color.forest))
                typeface = resources.pretendardBold()
                setPadding(0, 0, 0, dp(12))
                setOnClickListener { view ->
                    view.clickHaptic()
                    closeDetail()
                }
            })
        }
        if (showHeader) addView(settingsHeader(page.title, page.summary))
        when (page) {
            SettingsPage.GENERAL -> {
                addView(settingsSectionTitle("앱 동작", first = true))
                addView(profileSettingsCard())
                addView(settingsSectionTitle("홈 화면"))
                addView(homeSettingsCard())
            }
            SettingsPage.THEME -> addView(themeSettingsCard())
            SettingsPage.NOTIFICATION -> addView(notificationSettingsCard())
            SettingsPage.BACKUP -> addView(backupSettingsCard())
            SettingsPage.UPDATE -> addView(updateSettingsCard())
            SettingsPage.APP_INFO -> addView(appInfoCard())
        }
    }

    private fun settingsMenuCard(selected: SettingsPage?): View = settingsCard(verticalPaddingDp = 6) {
        SettingsPage.entries.forEachIndexed { index, page ->
            if (index > 0) addAppInfoDivider(marginDp = 0)
            addView(settingsMenuRow(page, page == selected))
        }
    }

    private fun settingsMenuRow(page: SettingsPage, selected: Boolean): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        // 넓은 화면은 선택 강조 배경이 글자에 붙지 않도록 좌우 여백을 준다
        val sidePadding = if (activity.isWide) dp(12) else 0
        setPadding(sidePadding, dp(10), sidePadding, dp(10))
        isClickable = true
        isFocusable = true
        if (selected) {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(resColor(R.color.forest_light))
            }
        } else {
            val selectableBackground = TypedValue()
            if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableBackground, true)) {
                setBackgroundResource(selectableBackground.resourceId)
            }
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = page.title
                textSize = 16f
                typeface = resources.pretendardBold()
                setTextColor(resColor(if (selected) R.color.forest else R.color.text_primary))
            })
            addView(TextView(context).apply {
                text = page.summary
                textSize = 13f
                setTextColor(resColor(R.color.text_secondary))
                setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        addView(TextView(context).apply {
            text = "›"
            textSize = 20f
            setTextColor(resColor(R.color.text_secondary))
        })
        if (selected) contentDescription = "${page.title}, ${page.summary}, 선택됨"
        setOnClickListener { view ->
            if (selected) return@setOnClickListener
            view.clickHaptic()
            openSettingsPage(page)
        }
    }

    private fun settingsHeader(
        title: String = "설정",
        subtitle: String = "앱 동작, 테마, 알림과 데이터 백업을 관리합니다"
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(12))
        addView(TextView(context).apply {
            text = title
            textSize = 28f
            typeface = resources.pretendardBold()
            setTextColor(resColor(R.color.text_primary))
        })
        addView(TextView(context).apply {
            text = subtitle
            textSize = 14f
            setTextColor(resColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
        })
    }

    /** first: 헤더 바로 아래 첫 묶음은 헤더 아래 여백(12dp)이 이미 있어 위 여백을 줄인다. */
    private fun settingsSectionTitle(title: String, first: Boolean = false): View = TextView(activity).apply {
        text = title
        textSize = 17f
        typeface = resources.pretendardBold()
        setTextColor(resColor(R.color.text_primary))
        setPadding(0, dp(if (first) 4 else 18), 0, dp(10))
    }

    private fun themeSettingsCard(): View = settingsCard(verticalPaddingDp = 12) {
        addView(TextView(context).apply {
            text = "화면 모드"
            textSize = 16f
            typeface = resources.pretendardBold()
            setTextColor(resColor(R.color.text_primary))
        })
        addView(displayModeToggle(), LinearLayout.LayoutParams(match, wrap).apply {
            topMargin = dp(8)
            bottomMargin = dp(18)
        })
        addView(TextView(context).apply {
            text = "앱 테마색"
            textSize = 16f
            typeface = resources.pretendardBold()
            setTextColor(resColor(R.color.text_primary))
        })
        addView(TextView(context).apply {
            text = "취향에 맞는 색을 선택하세요"
            textSize = 13f
            setTextColor(resColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(8))
        })

        val selectedPalette = AppThemePreferences.selected(context)
        AppThemePalette.entries.forEachIndexed { index, palette ->
            if (index > 0) addView(themeOptionDivider())
            addView(themeOptionRow(palette, palette == selectedPalette))
        }
    }

    private fun displayModeToggle(): View {
        val modes = listOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to "시스템",
            AppCompatDelegate.MODE_NIGHT_NO to "라이트",
            AppCompatDelegate.MODE_NIGHT_YES to "다크",
        )
        val currentMode = AppThemePreferences.nightMode(activity)
        val buttons = modes.map { (mode, label) ->
            MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = mode == currentMode
            }
        }
        return MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, wrap, 1f)) }
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked || themeTransitionInProgress) return@addOnButtonCheckedListener
                val picked = buttons.indexOfFirst { it.id == checkedId }.takeIf { it >= 0 } ?: return@addOnButtonCheckedListener
                beginThemeTransition()
                // setDefaultNightMode는 실제로 적용되는 명암(uiMode)이 바뀔 때만 액티비티를 recreate()한다.
                // 예를 들어 시스템이 이미 다크인데 "시스템"에서 "다크"로 바꾸면 화면상 변화가 없어
                // recreate가 아예 일어나지 않으므로, themeTransitionInProgress 리셋과 캡처한 비트맵 소비를
                // 보장하기 위해 여기서 항상 명시적으로 recreate()한다.
                AppThemePreferences.setNightMode(activity, modes[picked].first)
                activity.recreate()
            }
        }
    }

    private fun themeOptionRow(palette: AppThemePalette, selected: Boolean): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            isClickable = true
            isFocusable = true
            contentDescription = buildString {
                append("앱 테마색, ${palette.displayName}, ${palette.description}")
                if (selected) append(", 선택됨")
            }

            val selectableBackground = TypedValue()
            if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableBackground, true)) {
                setBackgroundResource(selectableBackground.resourceId)
            }

            addView(View(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(context, palette.previewColorRes))
                    setStroke(
                        dp(if (selected) 3 else 1),
                        resColor(if (selected) R.color.text_primary else R.color.surface_card)
                    )
                }
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = palette.displayName
                    textSize = 15f
                    typeface = if (selected) resources.pretendardBold() else resources.getFont(R.font.pretendard)
                    setTextColor(resColor(R.color.text_primary))
                })
                addView(TextView(context).apply {
                    text = palette.description
                    textSize = 12f
                    setTextColor(resColor(R.color.text_secondary))
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, wrap, 1f))

            addView(TextView(context).apply {
                text = if (selected) "✓" else ""
                textSize = 20f
                gravity = Gravity.CENTER
                typeface = resources.pretendardBold()
                setTextColor(resColor(R.color.forest))
            }, LinearLayout.LayoutParams(dp(32), dp(40)))

            setOnClickListener { view ->
                if (selected || themeTransitionInProgress) return@setOnClickListener
                view.selectionHaptic()
                beginThemeTransition()
                AppThemePreferences.select(context, palette)
                activity.recreate()
            }
        }

    /** 테마색 전환과 화면 모드(라이트/다크/시스템) 전환 둘 다, 리크리에이트 직전 화면을 찍어뒀다가
     *  [showPendingThemeTransition]에서 새 테마 위로 크로스페이드시키는 데 쓴다. */
    private fun beginThemeTransition() {
        activity.settingsScrollY = (activity.currentTopContent as? ScrollView)?.scrollY ?: activity.settingsScrollY
        themeTransitionInProgress = true
        pendingThemeTransitionBitmap?.recycle()
        pendingThemeTransitionBitmap = captureThemeTransitionBitmap()
    }

    private fun captureThemeTransitionBitmap(): Bitmap? {
        val decorView = activity.window.decorView
        if (decorView.width <= 0 || decorView.height <= 0) return null
        var bitmap: Bitmap? = null
        return try {
            bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
            decorView.draw(Canvas(bitmap))
            bitmap
        } catch (_: Throwable) {
            bitmap?.recycle()
            null
        }
    }

    fun showPendingThemeTransition() {
        val bitmap = pendingThemeTransitionBitmap ?: return
        pendingThemeTransitionBitmap = null
        val decorView = activity.window.decorView as? ViewGroup ?: run {
            bitmap.recycle()
            return
        }
        val overlay = ImageView(activity).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        decorView.addView(overlay, ViewGroup.LayoutParams(match, match))
        overlay.post {
            overlay.animate()
                .alpha(0f)
                .setDuration(THEME_TRANSITION_DURATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        decorView.removeView(overlay)
                        bitmap.recycle()
                    }
                })
                .start()
        }
    }

    private fun themeOptionDivider(): View = View(activity).apply {
        setBackgroundColor(resColor(R.color.forest_light))
        layoutParams = LinearLayout.LayoutParams(match, dp(1)).apply { leftMargin = dp(40) }
    }

    private fun homeSettingsCard(): View = settingsCard {
        val selectedPeriodText = TextView(context).apply {
            textSize = 14f
            gravity = Gravity.END
            setTextColor(resColor(R.color.forest))
            text = "${WeightChartPreferences.homePeriod(context).displayName}  ›"
        }
        addView(settingRow(
            title = "홈 무게 그래프 기간",
            subtitle = "홈에서는 선택한 기간만 표시하며 전체 기록은 상세 화면에서 확인합니다",
            trailing = selectedPeriodText
        ).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "홈 무게 그래프 기간, ${WeightChartPreferences.homePeriod(context).displayName}"
            setOnClickListener { view ->
                view.clickHaptic()
                showHomeWeightPeriodDialog(selectedPeriodText, this)
            }
        })
    }

    private fun showHomeWeightPeriodDialog(valueText: TextView, settingView: View) {
        val periods = arrayOf(
            WeightChartPeriod.ONE_WEEK,
            WeightChartPeriod.ONE_MONTH,
            WeightChartPeriod.THREE_MONTHS
        )
        val currentPeriod = WeightChartPreferences.homePeriod(activity)
        MaterialAlertDialogBuilder(activity)
            .setTitle("홈 무게 그래프 기간")
            .setSingleChoiceItems(
                periods.map(WeightChartPeriod::displayName).toTypedArray(),
                periods.indexOf(currentPeriod).coerceAtLeast(0)
            ) { dialog, index ->
                val selectedPeriod = periods[index]
                WeightChartPreferences.setHomePeriod(activity, selectedPeriod)
                valueText.text = "${selectedPeriod.displayName}  ›"
                settingView.contentDescription = "홈 무게 그래프 기간, ${selectedPeriod.displayName}"
                activity.binding.weightPeriodLabel.text = selectedPeriod.displayName
                activity.selectedReptileId?.let(activity::loadWeightHistory)
                activity.showBriefToast("홈 그래프를 ${selectedPeriod.displayName}로 표시합니다")
                dialog.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun profileSettingsCard(): View = settingsCard {
        addView(settingRow(
            title = "앱 시작 시 마지막 선택한 프로필 선택",
            subtitle = "앱 실행 시 마지막으로 선택했던 개체가 선택됩니다.",
            trailing = SwitchMaterial(context).apply {
                applyThemeColors()
                isChecked = activity.isAutoSelectLastProfileEnabled()
                setOnCheckedChangeListener { button, checked ->
                    button.selectionHaptic()
                    activity.appSettings.autoSelectLastProfile = checked
                    if (checked) {
                        activity.restoreLastSelectedProfile()
                    } else {
                        activity.selectedReptileId = null
                        activity.renderProfiles()
                        activity.renderDashboard()
                    }
                    activity.showBriefToast(if (checked) "마지막 프로필 자동 표시를 켰습니다" else "마지막 프로필 자동 표시를 껐습니다")
                }
            }
        ))
    }

    private val notificationSettings: NotificationSettings
        get() = activity.notificationSettings

    private fun notificationSettingsCard(): View = settingsCard {
        addView(settingRow(
            title = "오늘의 일정 알림",
            subtitle = "캘린더에 등록된 당일 관리 일정을 하루 한 번 알려줍니다",
            trailing = SwitchMaterial(context).apply {
                applyThemeColors()
                isChecked = notificationSettings.dailyScheduleEnabled
                setOnCheckedChangeListener { button, checked ->
                    button.selectionHaptic()
                    notificationSettings.dailyScheduleEnabled = checked
                    activity.showBriefToast(if (checked) "일정 알림을 켰습니다" else "일정 알림을 껐습니다")
                }
            }
        ))
        addAppInfoDivider()
        addView(settingRow(
            title = "오늘의 식단 알림",
            subtitle = "개체별 당일 식단을 하루 한 번 알려줍니다",
            trailing = SwitchMaterial(context).apply {
                applyThemeColors()
                isChecked = notificationSettings.dailyDietEnabled
                setOnCheckedChangeListener { button, checked ->
                    button.selectionHaptic()
                    notificationSettings.dailyDietEnabled = checked
                    activity.showBriefToast(if (checked) "식단 알림을 켰습니다" else "식단 알림을 껐습니다")
                }
            }
        ))
        addAppInfoDivider()
        addView(settingRow(
            title = "부화 임박 알림",
            subtitle = "알(클러치)의 부화 예정일이 다가오면 알려줍니다",
            trailing = SwitchMaterial(context).apply {
                applyThemeColors()
                isChecked = notificationSettings.breedingHatchDueEnabled
                setOnCheckedChangeListener { button, checked ->
                    button.selectionHaptic()
                    notificationSettings.breedingHatchDueEnabled = checked
                    activity.showBriefToast(if (checked) "부화 임박 알림을 켰습니다" else "부화 임박 알림을 껐습니다")
                }
            }
        ))
    }

    private fun backupSettingsCard(): View = settingsCard {
        addView(TextView(context).apply {
            text = "Google Drive"
            textSize = 16f
            typeface = resources.pretendardBold()
            setTextColor(resColor(R.color.text_primary))
        })
        addView(TextView(context).apply {
            text = "프로필, 사진, 무게와 관리 기록을 앱 전용 숨김 폴더에 저장합니다. " +
                "최근 백업 ${ProfileBackupManager.MAX_BACKUPS}개를 보관하며, Google 계정 저장공간을 사용합니다"
            textSize = 13f
            setTextColor(resColor(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
        })
        driveBackupStatusText = TextView(context).apply {
            text = lastDriveBackupStatus()
            textSize = 12f
            setTextColor(resColor(R.color.forest))
            setPadding(0, dp(8), 0, dp(12))
        }
        addView(driveBackupStatusText)
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(match, dp(4))
        })
        addView(settingRow(
            title = "백업 경과 알림받기",
            subtitle = "마지막 백업 후 오래 지나면 알려줍니다",
            trailing = SwitchMaterial(context).apply {
                applyThemeColors()
                isChecked = notificationSettings.driveBackupOverdueEnabled
                setOnCheckedChangeListener { button, checked ->
                    button.selectionHaptic()
                    notificationSettings.driveBackupOverdueEnabled = checked
                    activity.showBriefToast(if (checked) "백업 경과 알림을 켰습니다" else "백업 경과 알림을 껐습니다")
                }
            }
        ))
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(match, dp(8))
        })
        driveEmptyStateHintText = TextView(context).apply {
            text = "재설치한 경우 백업을 누르지 말고 먼저 Google Drive에서 복원하세요"
            textSize = 13f
            typeface = resources.pretendardBold()
            setTextColor(resColor(R.color.forest))
            setPadding(0, 0, 0, dp(12))
            visibility = if (activity.allReptiles.isEmpty()) View.VISIBLE else View.GONE
        }
        addView(driveEmptyStateHintText)

        driveBackupButton = MaterialButton(context).apply {
            text = "Google Drive에 백업"
            setTextColor(resColor(R.color.button_on_primary))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.button_primary))
            isEnabled = !driveActionInProgress && activity.allReptiles.isNotEmpty()
            setOnClickListener { view ->
                view.clickHaptic()
                requestDriveAuthorization(DriveAction.BACKUP)
            }
            applyButtonHeight(BUTTON_HEIGHT_LARGE_DP)
            layoutParams = LinearLayout.LayoutParams(match, wrap)
        }
        addView(driveBackupButton)
        addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(match, dp(8))
        })
        driveRestoreButton = MaterialButton(context).apply {
            text = "Google Drive에서 복원"
            setTextColor(resColor(R.color.forest))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.forest_light))
            isEnabled = !driveActionInProgress
            setOnClickListener { view ->
                view.clickHaptic()
                requestDriveAuthorization(DriveAction.RESTORE)
            }
            applyButtonHeight(BUTTON_HEIGHT_LARGE_DP)
            layoutParams = LinearLayout.LayoutParams(match, wrap)
        }
        addView(driveRestoreButton)
        updateDriveActionAvailability()
    }

    private fun requestDriveAuthorization(action: DriveAction) {
        if (driveActionInProgress) return
        if (action == DriveAction.BACKUP && activity.allReptiles.isEmpty()) {
            val message = "백업할 프로필이 없습니다. 재설치했다면 먼저 복원하세요"
            driveBackupStatusText?.text = message
            driveBackupButton?.rejectHaptic()
            activity.showBriefToast(message)
            return
        }
        pendingDriveAction = action
        setDriveActionInProgress(true)
        driveBackupStatusText?.text = "Google 계정을 확인하는 중입니다…"

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        failDriveAction(IllegalStateException("Google 계정 선택 화면을 열지 못했습니다"))
                    } else {
                        activity.driveAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    }
                } else {
                    val accessToken = result.accessToken
                    if (accessToken == null) {
                        failDriveAction(IllegalStateException("Google Drive 접근 토큰을 받지 못했습니다"))
                    } else {
                        runDriveAction(action, accessToken)
                    }
                }
            }
            .addOnFailureListener(::failDriveAction)
    }

    private fun runDriveAction(action: DriveAction, accessToken: String) {
        when (action) {
            DriveAction.BACKUP -> uploadProfileBackup(accessToken)
            DriveAction.RESTORE -> loadRestoreChoices(accessToken)
        }
    }

    private fun uploadProfileBackup(accessToken: String) {
        driveBackupStatusText?.text = "기존 백업이 있는지 확인하는 중입니다…"
        activity.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { profileBackupManager.listBackups(accessToken) }
            }.onSuccess { backups ->
                if (backups.size >= ProfileBackupManager.MAX_BACKUPS) {
                    // 다이얼로그가 떠 있는 동안은 진행 중 상태를 풀어서, 사용자가 취소해도 버튼이 계속 잠겨 있지 않게 한다.
                    pendingDriveAction = null
                    setDriveActionInProgress(false)
                    showOverwriteBackupConfirmation(accessToken, oldest = backups.last())
                } else {
                    performBackupUpload(accessToken)
                }
            }.onFailure(::failDriveAction)
        }
    }

    private fun showOverwriteBackupConfirmation(accessToken: String, oldest: ProfileBackupManager.RemoteBackup) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("가장 오래된 백업 삭제")
            .setMessage(
                "Google Drive에는 백업을 최대 ${ProfileBackupManager.MAX_BACKUPS}개까지 보관합니다. " +
                    "지금 백업하면 가장 오래된 백업(${formatBackupTime(oldest.modifiedAt)})이 삭제되고 되돌릴 수 없습니다. 계속할까요?"
            )
            .setNegativeButton("취소") { _, _ -> cancelBackupOverwrite() }
            .setPositiveButton("백업") { _, _ -> performBackupUpload(accessToken) }
            .setOnCancelListener { cancelBackupOverwrite() }
            .show()
    }

    private fun cancelBackupOverwrite() {
        pendingDriveAction = null
        setDriveActionInProgress(false)
        driveBackupStatusText?.text = "백업을 취소했습니다"
    }

    private fun performBackupUpload(accessToken: String) {
        setDriveActionInProgress(true)
        driveBackupStatusText?.text = "프로필 데이터를 백업하는 중입니다…"
        activity.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { profileBackupManager.upload(accessToken) }
            }.onSuccess { result ->
                activity.appSettings.lastDriveBackupAt = result.createdAt
                val photoMessage = when {
                    result.skippedPhotoCount > 0 -> ", 사진 ${result.skippedPhotoCount}개 제외"
                    result.photoCount > 0 -> ", 사진 ${result.photoCount}개"
                    else -> ""
                }
                driveBackupStatusText?.text = "마지막 백업: ${formatBackupTime(result.createdAt)}"
                driveBackupButton?.confirmHaptic()
                activity.showBriefToast("프로필 ${result.profileCount}개를 백업했습니다$photoMessage")
                finishDriveAction()
            }.onFailure(::failDriveAction)
        }
    }

    private fun loadRestoreChoices(accessToken: String) {
        driveBackupStatusText?.text = "Google Drive 백업을 확인하는 중입니다…"
        activity.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { profileBackupManager.listBackups(accessToken) }
            }.onSuccess { backups ->
                when (backups.size) {
                    0 -> failDriveAction(ProfileBackupManager.NoBackupFoundException())
                    1 -> loadRestorePreview(accessToken, backups.single())
                    else -> {
                        // 선택 창이 떠 있는 동안은 진행 중 상태를 풀어 둔다(취소해도 버튼이 잠기지 않게).
                        pendingDriveAction = null
                        setDriveActionInProgress(false)
                        showRestoreChooser(accessToken, backups)
                    }
                }
            }.onFailure(::failDriveAction)
        }
    }

    private fun showRestoreChooser(accessToken: String, backups: List<ProfileBackupManager.RemoteBackup>) {
        // 같은 분에 두 번 백업하면 시각이 똑같이 보이므로, 그때만 초까지 표시한다.
        val minuteLabels = backups.map { formatBackupTime(it.modifiedAt) }
        val showSeconds = minuteLabels.toSet().size != minuteLabels.size
        val labels = backups.mapIndexed { index, backup ->
            buildString {
                append(if (showSeconds) formatBackupTime(backup.modifiedAt, withSeconds = true) else minuteLabels[index])
                if (index == 0) append(" (최신)")
                backup.sizeBytes?.let { append(" · ${formatBackupSize(it)}") }
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle("복원할 백업 선택")
            .setItems(labels) { _, which -> loadRestorePreview(accessToken, backups[which]) }
            .setNegativeButton("취소") { _, _ -> cancelRestorePreview() }
            .setOnCancelListener { cancelRestorePreview() }
            .show()
    }

    private fun formatBackupSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.KOREA, "%.1fMB", bytes / (1024.0 * 1024))
        else -> "${(bytes / 1024).coerceAtLeast(1)}KB"
    }

    private fun loadRestorePreview(accessToken: String, backup: ProfileBackupManager.RemoteBackup) {
        setDriveActionInProgress(true)
        driveBackupStatusText?.text = "선택한 백업을 내려받는 중입니다…"
        activity.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { profileBackupManager.download(accessToken, backup) }
            }.onSuccess { preview ->
                pendingDriveAction = null
                setDriveActionInProgress(false)
                driveBackupStatusText?.text = "Google Drive 백업을 찾았습니다"
                showRestoreConfirmation(preview)
            }.onFailure(::failDriveAction)
        }
    }

    private fun showRestoreConfirmation(preview: ProfileBackupManager.RestorePreview) {
        pendingRestorePreview = preview
        MaterialAlertDialogBuilder(activity)
            .setTitle("Google Drive 백업 복원")
            .setMessage(
                "백업 일시: ${formatBackupTime(preview.createdAt)}\n" +
                    "프로필: ${preview.profileCount}개\n" +
                    "사진: ${preview.photoCount}개\n" +
                    "브리딩 기록: ${preview.breedingPairCount}쌍\n" +
                    "캘린더 기록: ${preview.calendarDatesCount}일\n\n" +
                    "현재 앱의 프로필과 관련 기록, 캘린더 기록을 모두 이 백업으로 교체합니다. 계속할까요?"
            )
            .setNegativeButton("취소") { _, _ -> cancelRestorePreview() }
            .setPositiveButton("복원") { _, _ -> restoreProfileBackup(preview) }
            .setOnCancelListener { cancelRestorePreview() }
            .show()
    }

    /** 백업 파일은 임시 파일로 받아 두고 사진을 복원 시점에 꺼내 쓰므로, 취소했으면 여기서 정리해야 한다. */
    private fun cancelRestorePreview() {
        closePendingRestorePreview()
        pendingDriveAction = null
        setDriveActionInProgress(false)
        driveBackupStatusText?.text = "복원을 취소했습니다"
    }

    private fun restoreProfileBackup(preview: ProfileBackupManager.RestorePreview) {
        restoreInProgress = true
        setDriveActionInProgress(true)
        driveBackupStatusText?.text = "프로필 데이터를 복원하는 중입니다…"
        activity.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    profileBackupManager.restore(preview).also {
                        // 복원은 모든 사진 경로를 새 파일명으로 갈아치우므로 기존 썸네일 캐시는 전부 무효다.
                        // 그대로 두면 무효한 캐시가 Glide 상한(기본 250MB)까지 남는다.
                        runCatching { Glide.get(activity).clearDiskCache() }
                    }
                }
            }.also {
                restoreInProgress = false
                closePendingRestorePreview()
            }.onSuccess { result ->
                activity.selectedReptileId = null
                activity.appSettings.lastSelectedProfileId = null
                driveBackupStatusText?.text = "마지막 복원: ${formatBackupTime(System.currentTimeMillis())}"
                driveRestoreButton?.confirmHaptic()
                activity.showBriefToast("프로필 ${result.profileCount}개를 복원했습니다")
                finishDriveAction()
            }.onFailure(::failDriveAction)
        }
    }

    private fun setDriveActionInProgress(inProgress: Boolean) {
        driveActionInProgress = inProgress
        updateDriveActionAvailability()
    }

    fun updateDriveActionAvailability() {
        val canBackup = !driveActionInProgress && activity.allReptiles.isNotEmpty()
        driveBackupButton?.apply {
            isEnabled = canBackup
            isClickable = canBackup
            alpha = if (canBackup) 1f else DISABLED_BUTTON_ALPHA
            text = if (activity.allReptiles.isEmpty()) "백업할 프로필이 없습니다" else "Google Drive에 백업"
        }
        val canRestore = !driveActionInProgress
        driveRestoreButton?.apply {
            isEnabled = canRestore
            isClickable = canRestore
            alpha = if (canRestore) 1f else DISABLED_BUTTON_ALPHA
        }
        driveEmptyStateHintText?.visibility = if (activity.allReptiles.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun finishDriveAction(status: String? = null) {
        pendingDriveAction = null
        setDriveActionInProgress(false)
        status?.let {
            driveBackupStatusText?.text = it
            activity.showBriefToast(it)
        }
    }

    private fun failDriveAction(error: Throwable) {
        // 화면 회전 등으로 액티비티가 다시 만들어지면 코루틴이 취소된다. runCatching이 그 취소까지 잡아서
        // "StandaloneCoroutine was cancelled" 같은 실패 토스트가 뜨지 않도록 무시한다.
        if (error is CancellationException) return
        val message = when (error) {
            is ProfileBackupManager.NoBackupFoundException -> error.message.orEmpty()
            is ProfileBackupManager.NoProfilesToBackupException -> error.message.orEmpty()
            is ProfileBackupManager.InvalidBackupException -> error.message.orEmpty()
            is ProfileBackupManager.DriveBackupException -> error.message.orEmpty()
            is ApiException -> driveAuthorizationFailureMessage(error)
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Google Drive 작업에 실패했습니다"
        }
        driveBackupButton?.rejectHaptic()
        driveBackupStatusText?.text = message
        finishDriveAction()
        activity.showBriefToast(message)
    }

    private fun driveAuthorizationFailureMessage(error: ApiException): String =
        if (error.statusCode == GOOGLE_DEVELOPER_ERROR_STATUS_CODE) {
            "Google Drive 로그인 설정이 현재 앱 서명과 맞지 않습니다 (${error.statusCode})"
        } else {
            "Google 계정 연결에 실패했습니다 (${error.statusCode})"
        }

    private fun lastDriveBackupStatus(): String {
        val savedAt = activity.appSettings.lastDriveBackupAt
        return if (savedAt != null) {
            "마지막 백업: ${formatBackupTime(savedAt)}"
        } else {
            "아직 이 기기에서 백업하지 않았습니다"
        }
    }

    private fun formatBackupTime(epochMillis: Long, withSeconds: Boolean = false): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(if (withSeconds) "yyyy년 M월 d일 HH:mm:ss" else "yyyy년 M월 d일 HH:mm"))

    private fun updateSettingsCard(): View = settingsCard {
        addView(settingRow(
            title = "앱 시작 시 업데이트 확인",
            subtitle = "새 버전이 있으면 안내합니다",
            trailing = SwitchMaterial(context).apply {
                applyThemeColors()
                isChecked = activity.isUpdateCheckEnabled()
                setOnCheckedChangeListener { button, checked ->
                    button.selectionHaptic()
                    activity.appSettings.checkUpdatesOnStart = checked
                    activity.showBriefToast(if (checked) "자동 업데이트 확인을 켰습니다" else "자동 업데이트 확인을 껐습니다")
                }
            }
        ))
        addAppInfoDivider()
        addView(settingRow(
            title = "베타 업데이트 받기",
            subtitle = "베타 테스트 버전을 업데이트 대상에 포함합니다",
            trailing = SwitchMaterial(context).apply {
                applyThemeColors()
                isChecked = activity.appSettings.updateChannelIsBeta
                setOnCheckedChangeListener { button, checked ->
                    button.selectionHaptic()
                    activity.appSettings.updateChannelIsBeta = checked
                    activity.showBriefToast(if (checked) "베타 채널로 전환했습니다" else "안정 채널로 전환했습니다")
                }
            }
        ))
        addUpdateActionDivider()
        addView(MaterialButton(context).apply {
            text = "지금 업데이트 확인"
            icon = ContextCompat.getDrawable(context, android.R.drawable.stat_sys_download_done)
            iconTint = ColorStateList.valueOf(resColor(R.color.button_on_primary))
            setTextColor(resColor(R.color.button_on_primary))
            backgroundTintList = ColorStateList.valueOf(resColor(R.color.button_primary))
            setOnClickListener { view ->
                view.clickHaptic()
                checkForUpdate(showNoUpdateToast = true)
            }
            applyButtonHeight(BUTTON_HEIGHT_LARGE_DP)
            layoutParams = LinearLayout.LayoutParams(match, wrap)
        })
    }

    private fun appInfoCard(): View = settingsCard(verticalPaddingDp = 12) {
        val info = packageInfo()
        addView(infoRow("앱 이름", activity.getString(R.string.app_name), compact = true))
        addAppInfoDivider(marginDp = 3)
        addView(infoRow("버전", "${info.versionName ?: "-"} (${versionCode(info)})", compact = true))
        addAppInfoDivider(marginDp = 3)
        addView(infoRow("제작", "M.G OH · J.H BAE", compact = true))
        addAppInfoDivider(marginDp = 3)
        addView(TextView(activity).apply {
            text = "\"우리는 모두 마음 한켠에 조그만 생명이 주고 간 다정함을 품고 살아갑니다.\""
            textSize = 10.5f
            gravity = Gravity.CENTER
            typeface = resources.getFont(R.font.pretendard)
            setTextColor(resColor(R.color.text_secondary))
            setPadding(dp(0), dp(8), dp(0), dp(4))
        })
    }

    private fun settingRow(title: String, subtitle: String, trailing: View): View =
        LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    typeface = resources.pretendardBold()
                    setTextColor(resColor(R.color.text_primary))
                })
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(resColor(R.color.text_secondary))
                    setPadding(0, dp(4), dp(12), 0)
                })
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(trailing)
        }

    private fun infoRow(label: String, value: String, compact: Boolean = false): View =
        LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(if (compact) 30 else 44)
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(resColor(R.color.text_secondary))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                gravity = Gravity.END
                typeface = activity.resources.getFont(R.font.d2)
                setTextColor(resColor(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, wrap, 1.5f))
        }

    private fun settingsCard(verticalPaddingDp: Int = 16, content: LinearLayout.() -> Unit): View =
        activity.settingsCard(verticalPaddingDp, content)

    private fun LinearLayout.addAppInfoDivider(marginDp: Int = 6) {
        addView(View(context).apply {
            setBackgroundColor(resColor(R.color.forest_light))
            layoutParams = LinearLayout.LayoutParams(match, dp(1)).apply {
                topMargin = dp(marginDp)
                bottomMargin = dp(marginDp)
            }
        })
    }

    private fun LinearLayout.addUpdateActionDivider() {
        addView(View(context).apply {
            setBackgroundColor(resColor(R.color.forest_light))
            layoutParams = LinearLayout.LayoutParams(match, dp(1)).apply {
                topMargin = dp(14)
                // 버튼 아래 카드 여백(16dp)과 같게 해서, 구분선과 카드 끝 사이 가운데에 버튼이 오게 한다
                bottomMargin = dp(16)
            }
        })
    }

    private fun packageInfo(): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.getPackageInfo(activity.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }

    private fun versionCode(info: PackageInfo): Long = info.longVersionCode

    /** GitHub Releases는 공개 저장소라 로그인 없이 바로 확인할 수 있어서, 자동 시작 체크(silent)와
     *  수동 "지금 업데이트 확인" 버튼이 똑같은 함수를 쓴다 — showNoUpdateToast로 결과 알림 여부만 다르다. */
    fun checkForUpdate(showNoUpdateToast: Boolean = false) {
        activity.lifecycleScope.launch {
            val includePrereleases = activity.appSettings.updateChannelIsBeta
            val release = runCatching {
                withContext(Dispatchers.IO) { gitHubUpdateChecker.fetchLatestRelease(includePrereleases) }
            }.getOrElse { error ->
                if (showNoUpdateToast) activity.showBriefToast(updateFailureMessage(error))
                return@launch
            }
            val currentVersion = packageInfo().versionName.orEmpty()
            when {
                release != null && GitHubUpdateChecker.isNewerVersion(release.versionName, currentVersion) ->
                    showUpdateDialog(release)
                showNoUpdateToast -> activity.showBriefToast("사용 가능한 새 버전이 없습니다")
            }
        }
    }

    private fun showUpdateDialog(release: GitHubUpdateChecker.ReleaseInfo) {
        val message = buildString {
            append("새 버전 ${release.versionName}을 사용할 수 있습니다.")
            if (release.notes.isNotBlank()) {
                append("\n\n")
                append(release.notes)
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("업데이트 가능")
            .setMessage(Markwon.create(activity).toMarkdown(message))
            .setPositiveButton("다운로드") { _, _ -> startApkDownload(release) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun updateFailureMessage(error: Throwable): String =
        if (error is GitHubUpdateChecker.GitHubUpdateException && error.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            "업데이트 저장소를 찾을 수 없습니다. GitHub 공개 설정을 확인하세요"
        } else {
            error.message?.takeIf { it.isNotBlank() } ?: "업데이트 확인에 실패했습니다"
        }

    /** DownloadManager를 사용자가 비활성화했거나 외장 저장소가 없으면 enqueue가 예외를 던진다.
     *  그 경우 브라우저로 릴리즈 APK를 직접 받게 넘긴다. */
    private fun startApkDownload(release: GitHubUpdateChecker.ReleaseInfo) {
        runCatching {
            val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
                .setTitle("BeardyLog 업데이트")
                .setDescription("${release.versionName} 다운로드 중")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(activity, null, release.assetName)
                .setMimeType("application/vnd.android.package-archive")
            // 릴리즈 APK 이름이 매번 같아서(app-release.apk), 지난 업데이트 때 받은 파일이 남아 있으면
            // DownloadManager가 새 파일을 app-release-1.apk로 저장하고 우리는 옛 파일을 설치하려다 실패했다.
            // 받기 전에 남은 APK를 지워 이름이 겹치지 않게 한다.
            deleteDownloadedApks()
            pendingApkDownloadId = downloadManager.enqueue(request)
            if (!apkReceiverRegistered) {
                ContextCompat.registerReceiver(
                    activity,
                    apkDownloadReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    ContextCompat.RECEIVER_EXPORTED
                )
                apkReceiverRegistered = true
            }
        }.onSuccess {
            activity.showBriefToast("업데이트 다운로드를 시작합니다")
        }.onFailure {
            pendingApkDownloadId = null
            runCatching {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
            }.onFailure {
                activity.showBriefToast("다운로드를 시작하지 못했습니다")
            }
        }
    }

    private fun handleApkDownloadComplete(downloadId: Long) {
        pendingApkDownloadId = null
        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // 파일 경로는 짐작하지 않고 DownloadManager가 실제로 저장한 위치를 읽는다.
        val (status, file) = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val status = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS).takeIf { it >= 0 }?.let(cursor::getInt)
            val file = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI).takeIf { it >= 0 }
                ?.let(cursor::getString)
                ?.let { Uri.parse(it).path }
                ?.let(::File)
            status to file
        } ?: (null to null)
        if (status != DownloadManager.STATUS_SUCCESSFUL || file == null || !file.exists()) {
            activity.showBriefToast("업데이트 다운로드에 실패했습니다")
            return
        }
        installApk(file)
    }

    private fun installApk(file: File) {
        // Android 8부터 앱마다 '알 수 없는 앱 설치'를 사용자가 켜줘야 한다. 확인 없이 설치 화면을
        // 던지면 아무 설명 없이 막히기만 하므로, 꺼져 있으면 해당 설정으로 안내한다.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            apkAwaitingInstallPermission = file
            MaterialAlertDialogBuilder(activity)
                .setTitle("설치 권한이 필요합니다")
                .setMessage("업데이트를 설치하려면 BeardyLog에 '알 수 없는 앱 설치'를 허용해 주세요.")
                .setNegativeButton("나중에") { _, _ -> apkAwaitingInstallPermission = null }
                .setPositiveButton("설정 열기") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    }.onFailure { activity.showBriefToast("설정 화면을 열지 못했습니다") }
                }
                .show()
            return
        }
        val contentUri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(installIntent) }
            .onFailure { activity.showBriefToast("설치 화면을 열지 못했습니다") }
    }

    /** MainActivity.onResume에서 호출. '알 수 없는 앱 설치'를 허용하고 돌아오면 다시 업데이트를 누르지 않아도 설치가 이어진다.
     *  (예전에는 여기서 흐름이 끊겨, 처음 업데이트할 때는 항상 한 번 더 시도해야 했다) */
    fun onResume() {
        val file = apkAwaitingInstallPermission ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) return
        apkAwaitingInstallPermission = null
        if (file.exists()) installApk(file)
    }

    private fun deleteDownloadedApks() {
        activity.getExternalFilesDir(null)
            ?.listFiles { file -> file.extension.equals("apk", ignoreCase = true) }
            ?.forEach { it.delete() }
    }

    // ---- 이 섹션 전용 소품 헬퍼 (MainActivity의 것과 동일한 구현을 그대로 둔다) ----

    private fun dp(value: Int): Int = activity.dp(value)
    private fun resColor(resId: Int): Int = activity.appColor(resId)

    /** SwitchMaterial 기본 색이 테마를 안 따라가서, 켜짐/꺼짐 색을 현재 앱 테마에 맞춰 직접 지정한다. */
    private fun SwitchMaterial.applyThemeColors() {
        trackTintList = ContextCompat.getColorStateList(context, R.color.switch_track_tint)
        thumbTintList = ContextCompat.getColorStateList(context, R.color.switch_thumb_tint)
    }

    private val match: Int get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap: Int get() = ViewGroup.LayoutParams.WRAP_CONTENT

    private companion object {
        const val KEY_SETTINGS_PAGE = "settings_page"
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val GOOGLE_DEVELOPER_ERROR_STATUS_CODE = 10
        const val DISABLED_BUTTON_ALPHA = 0.45f
        const val THEME_TRANSITION_DURATION_MS = 220L
        const val BACK_FINISH_DURATION_MS = 160L
        /** 뒤에서 들어오는 메뉴가 시작하는 위치(화면 폭 대비). 하위 화면보다 덜 움직여 깊이감을 준다. */
        const val BACK_PARALLAX = 0.25f
        var pendingThemeTransitionBitmap: Bitmap? = null
    }
}
