package com.hsm.beardylog

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Common edge-to-edge behavior for every app screen. */
abstract class AppBaseActivity : AppCompatActivity() {
    protected open val useEdgeToEdge: Boolean = true
    /** 화면에 하단 고정 바(예: BottomNavigationView)가 있으면 이 뷰를 반환해서
     *  내비게이션 바 인셋(3버튼/제스처)을 루트가 아니라 그 바 자체에 패딩으로 적용한다.
     *  그래야 인셋만큼의 빈 여백이 배경색 위에 따로 생기지 않고, 바 배경이 그대로 이어져
     *  실제 바 높이 + 인셋만큼만 커져서 3버튼/제스처 모드 모두에서 자연스러운 높이가 된다. */
    protected open val bottomInsetTarget: View? = null
    private var briefToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(AppThemePreferences.selected(this).styleRes)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, !useEdgeToEdge)
        window.statusBarColor = appColor(R.color.surface_alt)
        window.navigationBarColor = appColor(R.color.surface_alt)
        // 뒤로 갈 때 화면이 옆으로 살짝 밀려나며 사라지는 가벼운 전환 애니메이션.
        // translate/alpha만 쓰는 윈도우 애니메이션이라 뷰 트리를 다시 그리지 않고 GPU 합성만으로 처리됨.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.activity_pop_enter, R.anim.activity_pop_exit)
        }
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        if (useEdgeToEdge) view?.let(::installSystemBarInsets)
    }

    // API 34 미만은 overrideActivityTransition이 없어서, finish() 직후 레거시 방식으로 같은 애니메이션을 적용한다.
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.activity_pop_enter, R.anim.activity_pop_exit)
        }
    }

    private fun installSystemBarInsets(root: View) {
        val initialTop = root.paddingTop
        val initialBottom = root.paddingBottom
        val bottomBar = bottomInsetTarget
        val bottomBarInitialPadding = bottomBar?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (bottomBar != null) {
                view.setPadding(view.paddingLeft, initialTop + bars.top, view.paddingRight, initialBottom)
                bottomBar.setPadding(
                    bottomBar.paddingLeft,
                    bottomBar.paddingTop,
                    bottomBar.paddingRight,
                    bottomBarInitialPadding + bars.bottom,
                )
            } else {
                view.setPadding(view.paddingLeft, initialTop + bars.top, view.paddingRight, initialBottom + bars.bottom)
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** 입력 중인 EditText 바깥을 탭하면 포커스를 해제하고 키보드를 자연스럽게 내린다. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let { focused ->
                if (focused is EditText) {
                    val touchedRect = Rect()
                    focused.getGlobalVisibleRect(touchedRect)
                    if (!touchedRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        focused.clearFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(focused.windowToken, 0)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    internal fun showBriefToast(message: String) {
        briefToast?.cancel()
        briefToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { toast ->
            toast.show()
            window.decorView.postDelayed({ toast.cancel() }, 1500L)
        }
    }
}

// 화면(Activity)이 아닌 섹션 전용 컨트롤러(예: CalendarSection)에서도 공통 햅틱을 쓸 수 있도록
// top-level internal 확장함수로 둔다. Context 의존이 없는 순수 View 동작이라 Activity 상속과 무관하다.
internal fun View.clickHaptic() {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

internal fun View.selectionHaptic() {
    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

internal fun View.confirmHaptic() {
    val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }
    performHapticFeedback(feedback)
}

internal fun View.rejectHaptic() {
    val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.REJECT
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(feedback)
}

/** 다이얼로그/바텀시트 안에서 EditText가 아닌 빈 공간을 탭하면 포커스된 입력의 키보드를 내린다.
 *  Activity의 dispatchTouchEvent 처리는 별도 Window로 뜨는 다이얼로그에는 적용되지 않아서
 *  다이얼로그 루트 뷰에 개별적으로 걸어준다. */
internal fun View.dismissKeyboardOnOutsideTouch(vararg inputs: EditText) {
    setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_DOWN) {
            inputs.firstOrNull { it.hasFocus() }?.let { focused ->
                focused.clearFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(focused.windowToken, 0)
            }
        }
        false
    }
}
