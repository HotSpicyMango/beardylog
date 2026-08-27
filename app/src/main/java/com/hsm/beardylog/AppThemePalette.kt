package com.hsm.beardylog

import android.content.Context
import android.util.TypedValue
import androidx.annotation.ColorRes
import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat

enum class AppThemePalette(
    val preferenceKey: String,
    val displayName: String,
    val description: String,
    @param:StyleRes val styleRes: Int,
    @param:ColorRes val previewColorRes: Int
) {
    OCEAN(
        preferenceKey = "ocean",
        displayName = "블루",
        description = "블루트리모니터",
        styleRes = R.style.Theme_Beardylog,
        previewColorRes = R.color.palette_ocean_accent
    ),
    FOREST(
        preferenceKey = "forest",
        displayName = "그린",
        description = "데이게코",
        styleRes = R.style.Theme_Beardylog_Forest,
        previewColorRes = R.color.palette_forest_accent
    ),
    LAVENDER(
        preferenceKey = "lavender",
        displayName = "퍼플",
        description = "그레이프",
        styleRes = R.style.Theme_Beardylog_Lavender,
        previewColorRes = R.color.palette_lavender_accent
    ),
    ROSE(
        preferenceKey = "rose",
        displayName = "핑크",
        description = "로즈",
        styleRes = R.style.Theme_Beardylog_Rose,
        previewColorRes = R.color.palette_rose_accent
    ),
    CORAL(
        preferenceKey = "coral",
        displayName = "오렌지",
        description = "레오파드게코",
        styleRes = R.style.Theme_Beardylog_Coral,
        previewColorRes = R.color.palette_coral_accent
    ),
    SLATE(
        preferenceKey = "slate",
        displayName = "그레이",
        description = "실버텅스킨크",
        styleRes = R.style.Theme_Beardylog_Slate,
        previewColorRes = R.color.palette_slate_accent
    );

    companion object {
        fun fromPreferenceKey(key: String?): AppThemePalette =
            entries.firstOrNull { it.preferenceKey == key } ?: OCEAN
    }
}

object AppThemePreferences {
    private const val PREFERENCES_NAME = "app_settings"
    private const val KEY_APP_THEME = "app_theme"

    fun selected(context: Context): AppThemePalette = AppThemePalette.fromPreferenceKey(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_THEME, null)
    )

    fun select(context: Context, palette: AppThemePalette) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_THEME, palette.preferenceKey)
            .apply()
    }
}

fun Context.appColor(@ColorRes colorRes: Int): Int {
    val themeAttribute = when (colorRes) {
        R.color.forest -> R.attr.beardyAccent
        R.color.button_primary -> R.attr.beardyButtonPrimary
        R.color.button_on_primary -> R.attr.beardyOnButtonPrimary
        R.color.forest_light -> R.attr.beardyAccentContainer
        R.color.surface_alt -> R.attr.beardyBackground
        R.color.surface_card -> R.attr.beardySurface
        R.color.text_primary -> R.attr.beardyTextPrimary
        R.color.text_secondary -> R.attr.beardyTextSecondary
        R.color.danger -> R.attr.beardyDanger
        else -> return ContextCompat.getColor(this, colorRes)
    }
    return themeColor(themeAttribute)
}

private fun Context.themeColor(@AttrRes attribute: Int): Int {
    val value = TypedValue()
    check(theme.resolveAttribute(attribute, value, true)) {
        "Theme attribute 0x${attribute.toString(16)} is not defined"
    }
    return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
}
