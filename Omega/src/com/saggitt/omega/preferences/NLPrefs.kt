/*
 * This file is part of Neo Launcher
 * Copyright (c) 2023   Neo Launcher Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.saggitt.omega.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.android.launcher3.R
import com.android.launcher3.util.MainThreadInitializedObject
import com.android.launcher3.util.Themes
import com.saggitt.omega.util.Config
import kotlin.math.roundToInt

private const val USER_PREFERENCES_NAME = "neo_launcher"

class NLPrefs private constructor(private val context: Context) {
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = USER_PREFERENCES_NAME)
    private val dataStore: DataStore<Preferences> = context.dataStore

    // Profile
    var themeCornerRadius = FloatPref(
        titleId = R.string.title_override_corner_radius_value,
        dataStore = dataStore,
        key = PrefKey.PROFILE_WINDOW_CORNER_RADIUS,
        defaultValue = 8f,
        maxValue = 24f,
        minValue = -1f,
        steps = 24,
        specialOutputs = {
            when {
                it < 0f -> context.getString(R.string.automatic_short)
                else    -> "${it.roundToInt()}dp"
            }
        }
    )

    val profileShowTopShadow = BooleanPref(
        dataStore = dataStore,
        key = PrefKey.PROFILE_STATUSBAR_SHADOW,
        titleId = R.string.show_top_shadow,
        defaultValue = true,
    )

    // Desktop
    // TODO desktop_rows, desktop_columns,
    val desktopIconAddInstalled = BooleanPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_ICON_ADD_INSTALLED,
        titleId = R.string.auto_add_shortcuts_label,
        summaryId = R.string.auto_add_shortcuts_description,
        defaultValue = false,
    )

    val desktopIconScale = FloatPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_ICON_SCALE,
        titleId = R.string.title__desktop_icon_size,
        defaultValue = 1f,
        maxValue = 2f,
        minValue = 0.5f,
        steps = 150,
        specialOutputs = { "${(it * 100).roundToInt()}%" },
    )

    val desktopLock = BooleanPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_LOCK_CHANGES,
        titleId = R.string.title_desktop_lock_desktop,
        defaultValue = false,
    )

    val desktopHideStatusBar = BooleanPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_STATUS_BAR_HIDE,
        titleId = R.string.title_desktop_hide_statusbar,
        defaultValue = false,
    )

    var desktopAllowEmptyScreens = BooleanPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_EMPTY_SCREENS_ALLOW,
        titleId = R.string.title_desktop_keep_empty,
        defaultValue = false
    )

    val desktopHideAppLabels = BooleanPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_LABELS_HIDE,
        titleId = R.string.title__desktop_hide_icon_labels,
        defaultValue = false,
    )

    val desktopLabelsScale = FloatPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_LABELS_SCALE,
        titleId = R.string.title_desktop_text_size,
        defaultValue = 1f,
        maxValue = 2f,
        minValue = 0.5f,
        steps = 150,
        specialOutputs = { "${(it * 100).roundToInt()}%" },
    )

    val desktopAllowFullWidthWidgets = BooleanPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_WIDGETS_FULL_WIDTH,
        titleId = R.string.title_desktop_full_width_widgets,
        summaryId = R.string.summary_full_width_widgets,
        defaultValue = false,
    )

    // TODO DimensionPref?
    var desktopWidgetCornerRadius = FloatPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_WIDGETS_CORNER_RADIUS,
        titleId = R.string.title_desktop_widget_corner_radius,
        defaultValue = 16f,
        maxValue = 24f,
        minValue = 1f,
        steps = 22,
        specialOutputs = { "${it.roundToInt()}dp" },
    )

    var desktopPopup = StringMultiSelectionPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_POPUP_OPTIONS,
        titleId = R.string.title_desktop_icon_popup_menu,
        defaultValue = setOf(PREFS_DESKTOP_POPUP_EDIT),
        entries = desktopPopupOptions,
        //withIcons = true,
    )

    // TODO DimensionPref?
    val desktopGridColumns = IntPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_GRID_COLUMNS,
        titleId = R.string.title__drawer_columns,
        defaultValue = 5, // TODO get from profile
        minValue = 2,
        maxValue = 16,
        steps = 15,
    )
    val desktopGridRows = IntPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_GRID_ROWS,
        titleId = R.string.title__drawer_rows,
        defaultValue = 5, // TODO get from profile
        minValue = 2,
        maxValue = 16,
        steps = 15,
    )
    var desktopFolderCornerRadius = FloatPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_FOLDER_CORNER_RADIUS,
        titleId = R.string.folder_radius,
        defaultValue = -1f,
        maxValue = 24f,
        minValue = -1f,
        steps = 24,
        specialOutputs = {
            when {
                it < 0f -> context.getString(R.string.automatic_short)
                else    -> "${it.roundToInt()}dp"
            }
        },
    )

    val desktopCustomFolderBackground = BooleanPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_FOLDER_BG_CUSTOM,
        titleId = R.string.folder_custom_background,
        defaultValue = false
    )

    // TODO ColorPref?
    val desktopFolderBackgroundColor = IntPref(
        titleId = R.string.folder_background,
        dataStore = dataStore,
        key = PrefKey.DESKTOP_FOLDER_BG_COLOR,
        defaultValue = Themes.getAttrColor(context, R.attr.colorSurface),
        //withAlpha = true,
    )

    val desktopFolderColumns = IntPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_FOLDER_COLUMNS,
        titleId = R.string.folder_columns,
        defaultValue = 4,
        minValue = 2,
        maxValue = 5,
        steps = 2,
    )

    val desktopFolderRows = IntPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_FOLDER_ROWS,
        titleId = R.string.folder_rows,
        defaultValue = 4,
        minValue = 2,
        maxValue = 5,
        steps = 2,
    )

    val desktopFolderOpacity = FloatPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_FOLDER_BG_OPACITY,
        titleId = R.string.folder_opacity,
        defaultValue = 1f,
        maxValue = 1f,
        minValue = 0f,
        steps = 10,
        specialOutputs = { "${(it * 100).roundToInt()}%" },
    )

    val desktopMultilineLabel = BooleanPref(
        dataStore = dataStore,
        key = PrefKey.DESKTOP_LABELS_MULTILINE,
        titleId = R.string.title__multiline_labels,
        defaultValue = false,
    )

    // Drawer
    var drawerSortMode = IntSelectionPref(
        titleId = R.string.title__sort_mode,
        dataStore = dataStore,
        key = PrefKey.DRAWER_SORT_MODE,
        defaultValue = Config.SORT_AZ,
        entries = Config.drawerSortOptions,
    )


    companion object {
        private val INSTANCE = MainThreadInitializedObject(::NLPrefs)

        @JvmStatic
        fun getInstance(context: Context) = INSTANCE.get(context)!!

    }
}