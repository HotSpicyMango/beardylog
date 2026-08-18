package com.hsm.beardylog

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Common edge-to-edge behavior for every app screen. */
abstract class AppBaseActivity : AppCompatActivity() {
    protected open val useEdgeToEdge: Boolean = true
    private var briefToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, !useEdgeToEdge)
        window.statusBarColor = ContextCompat.getColor(this, R.color.surface_alt)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.surface_alt)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        if (useEdgeToEdge) view?.let(::installSystemBarInsets)
    }

    private fun installSystemBarInsets(root: View) {
        val initialTop = root.paddingTop
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, initialTop + bars.top, view.paddingRight, initialBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    protected fun showBriefToast(message: String) {
        briefToast?.cancel()
        briefToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { toast ->
            toast.show()
            window.decorView.postDelayed({ toast.cancel() }, 800L)
        }
    }
}
