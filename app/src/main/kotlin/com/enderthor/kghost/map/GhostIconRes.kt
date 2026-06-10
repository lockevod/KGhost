package com.enderthor.kghost.map

import androidx.annotation.DrawableRes
import com.enderthor.kghost.R
import com.enderthor.kghost.data.GhostIcon
import com.enderthor.kghost.data.GhostSize

/**
 * Resolves the rider's [GhostIcon] + [GhostSize] choice to a concrete drawable for the map symbol.
 *
 * The Karoo `Symbol.Icon` API carries no size field, so each size is a distinct drawable that differs
 * only in its intrinsic width/height (S=22dp, M=32dp, L=45dp over a shared 48-unit viewport). Every
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

/**
 * Whether [icon] should be rotated to the route heading when placed via `Symbol.Icon`.
 *
 * ARROW, GHOST and CYCLIST all rotate: the arrow is explicitly directional, and the ghost/cyclist
 * glyphs face upward (north) at 0° so rotating them to the route tangent makes the marker visually
 * follow the direction of travel. DOT is rotationally symmetric and needs no rotation.
 */
fun ghostIconRotates(icon: GhostIcon): Boolean = icon != GhostIcon.DOT

/**
 * Automatic ghost icon size from the map zoom level (`[8.0, 18.0]`, smaller = more zoomed out; the
 * map page's default cycle is `[13, 15, 16]`). `Symbol.Icon` has no size field, so the only way to
 * scale the icon is to swap the S/M/L drawable — and since the SDK exposes the zoom (`OnMapZoomLevel`)
 * we do it automatically (no manual size setting): smaller when zoomed out, larger when zoomed in,
 * so the marker stays visually proportionate to the map.
 */
fun ghostSizeForZoom(zoom: Double): GhostSize = when {
    zoom < 13.0 -> GhostSize.SMALL
    zoom < 15.5 -> GhostSize.MEDIUM
    else -> GhostSize.LARGE
}
