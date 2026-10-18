package com.jackdengler.quiet.home

/** One of the 8 home tiles. */
sealed interface Slot {
    /** An app; the first installed package from [packages] is used. */
    data class App(val label: String, val packages: List<String>) : Slot
    data object AllApps : Slot
    data object Settings : Slot
}

val DefaultSlots: List<Slot> = listOf(
    Slot.App("YouTube TV", listOf("com.google.android.youtube.tvunplugged")),
    Slot.App("Paramount+", listOf("com.cbs.ott", "com.cbs.app")),
    Slot.App("HBO Max", listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.max")),
    Slot.App("Disney+", listOf("com.disney.disneyplus")),
    Slot.App("Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
    Slot.App("YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube")),
    Slot.AllApps,
    Slot.Settings,
)

/** The home slots with any long-press overrides from settings applied (app slots only). */
fun effectiveSlots(override: (Int) -> String?): List<Slot> = DefaultSlots.mapIndexed { i, slot ->
    val pkg = override(i)
    if (slot is Slot.App && pkg != null) Slot.App(pkg, listOf(pkg)) else slot
}
