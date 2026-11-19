/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.player

import androidx.media3.common.Player
import kotlinx.serialization.Serializable

/** [savedCode] is how a saved queue writes the mode; [playerMode] is Media3's own value for it. */
@Serializable
enum class RepeatMode(
    val savedCode: Int,
    val playerMode: Int,
) {
    OFF(0, Player.REPEAT_MODE_OFF),
    ALL(1, Player.REPEAT_MODE_ALL),
    ONE(2, Player.REPEAT_MODE_ONE),
    ;

    companion object {
        fun fromPlayer(mode: Int): RepeatMode = entries.firstOrNull { it.playerMode == mode } ?: OFF

        fun fromCode(code: Int): RepeatMode = entries.firstOrNull { it.savedCode == code } ?: OFF
    }
}
