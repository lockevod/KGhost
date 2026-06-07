package com.enderthor.kvpartner.map

import androidx.annotation.DrawableRes
import com.enderthor.kvpartner.R
import com.enderthor.kvpartner.data.GhostIcon
import com.enderthor.kvpartner.data.GhostSize

/**
 * Resolves the rider's [GhostIcon] + [GhostSize] choice to a concrete drawable for the map symbol.
 *
 * The Karoo `Symbol.Icon` API carries no size field, so each size is a distinct drawable that differs
 * only in its intrinsic width/height (S=32dp, M=48dp, L=66dp over a shared 48-unit viewport). Every
 * icon paints a white halo behind a dark glyph so it stays legible on any map background.
 */
@DrawableRes
fun ghostIconRes(icon: GhostIcon, size: GhostSize): Int = when (icon) {
    GhostIcon.GHOST -> when (size) {
        GhostSize.SMALL -> R.drawable.ic_ghost_s
        GhostSize.MEDIUM -> R.drawable.ic_ghost_m
        GhostSize.LARGE -> R.drawable.ic_ghost_l
    }
    GhostIcon.CYCLIST -> when (size) {
        GhostSize.SMALL -> R.drawable.ic_cyclist_s
        GhostSize.MEDIUM -> R.drawable.ic_cyclist_m
        GhostSize.LARGE -> R.drawable.ic_cyclist_l
    }
    GhostIcon.ARROW -> when (size) {
        GhostSize.SMALL -> R.drawable.ic_arrow_s
        GhostSize.MEDIUM -> R.drawable.ic_arrow_m
        GhostSize.LARGE -> R.drawable.ic_arrow_l
    }
    GhostIcon.DOT -> when (size) {
        GhostSize.SMALL -> R.drawable.ic_dot_s
        GhostSize.MEDIUM -> R.drawable.ic_dot_m
        GhostSize.LARGE -> R.drawable.ic_dot_l
    }
}
