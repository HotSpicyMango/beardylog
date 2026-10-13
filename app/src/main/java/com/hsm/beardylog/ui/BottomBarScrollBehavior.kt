package com.hsm.beardylog.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import java.lang.ref.WeakReference

/** 떠 있는 하단바를 아래로 스크롤하면 숨기고 위로 스크롤하면 다시 보여 준다.
 *  손가락 스크롤(중첩 스크롤)에만 반응하므로 코드로 스크롤 위치를 복원할 때는 바가 움직이지 않는다.
 *  손떨림 수준의 작은 움직임은 무시하고, 목록 끝에 닿으면 다시 보여 준다. */
class BottomBarScrollBehavior(context: Context, attrs: AttributeSet?) :
    HideBottomViewOnScrollBehavior<View>(context, attrs) {

    private val threshold = THRESHOLD_DP * context.resources.displayMetrics.density
    private var accumulatedDy = 0
    private var lastTarget = WeakReference<View>(null)
    private var childRef = WeakReference<View>(null)
    private var observedTree: ViewTreeObserver? = null

    // 플링은 중첩 스크롤 이벤트 없이 굴러가므로, 끝에 닿았는지는 스크롤 변화 자체를 지켜본다
    private val reachedEndWatcher = ViewTreeObserver.OnScrollChangedListener {
        val target = lastTarget.get() ?: return@OnScrollChangedListener
        val child = childRef.get() ?: return@OnScrollChangedListener
        if (isScrolledDown && target.isAttachedToWindow && !target.canScrollVertically(1)) slideUp(child)
    }

    override fun onStartNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        directTargetChild: View,
        target: View,
        nestedScrollAxes: Int,
        type: Int
    ): Boolean {
        val started = super.onStartNestedScroll(coordinatorLayout, child, directTargetChild, target, nestedScrollAxes, type)
        if (started) {
            lastTarget = WeakReference(target)
            childRef = WeakReference(child)
            val tree = coordinatorLayout.viewTreeObserver
            if (tree !== observedTree && tree.isAlive) {
                tree.addOnScrollChangedListener(reachedEndWatcher)
                observedTree = tree
            }
        }
        return started
    }

    override fun onNestedScroll(
        coordinatorLayout: CoordinatorLayout,
        child: View,
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        if (dyConsumed == 0) return
        // 방향이 바뀌면 누적값을 새로 센다
        if ((dyConsumed > 0) != (accumulatedDy > 0)) accumulatedDy = 0
        accumulatedDy += dyConsumed
        when {
            dyConsumed > 0 && !target.canScrollVertically(1) -> slideUp(child)
            accumulatedDy > threshold -> slideDown(child)
            accumulatedDy < -threshold -> slideUp(child)
        }
    }

    private companion object {
        const val THRESHOLD_DP = 12
    }
}
