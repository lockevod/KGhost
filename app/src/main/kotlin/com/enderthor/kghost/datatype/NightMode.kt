package com.enderthor.kghost.datatype

import android.content.Context
import android.content.res.Configuration

/**
 * True when the Karoo's system-wide UI mode is set to night.
 *
 * Adapted from KSafe's `Context.isKarooNightMode()`. We read
 * `Configuration.UI_MODE_NIGHT_MASK` from the extension's context rather than relying on a
 * theme attribute, because the theme a host inflates RemoteViews with did not match the
 * actual rendered background on real Karoo hardware (riders saw white text on a white
 * field). Reading the system UI mode is system-wide and matches what the Karoo OS draws
 * underneath the field. Used to pick the neutral text colour for the `---` / normal state.
 */
internal fun Context.isKarooNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
