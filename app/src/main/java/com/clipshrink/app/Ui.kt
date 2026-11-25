package com.clipshrink.app

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

val Orange = Color(0xFFFF8A1F)
val Green = Color(0xFF3DDC84)
val Yellow = Color(0xFFFFD23F)
val Card1 = Color(0xFF0E0E0E)
val Card2 = Color(0xFF161616)
val Line = Color(0xFF2A2A2A)
val Txt = Color(0xFFF2F2F2)
val Dim = Color(0xFF9A9A9A)

@Composable
fun ClipShrinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orange, onPrimary = Color.Black,
            secondary = Green, onSecondary = Color.Black,
            tertiary = Yellow, onTertiary = Color.Black,
            background = Color.Black, onBackground = Txt,
            surface = Card1, onSurface = Txt,
            surfaceVariant = Card2, onSurfaceVariant = Dim,
            outline = Line
        ),
        content = content
    )
}

@Composable
private fun T(
    s: String,
    color: Color = Txt,
    size: Int = 14,
    bold: Boolean = false,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) = Text(
    text = s,
    color = color,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier
)

@Composable
private fun Panel(border: Color = Line, content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(Card1)
        .border(1.dp, border, RoundedCornerShape(20.dp))
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    content = content
)

@Composable
private fun Btn(label: String, bg: Color, enabled: Boolean = true, onClick: () -> Unit) = Box(
    Modifier
        .fillMaxWidth()
        .height(56.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(if (enabled) bg else Line)
        .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center
) { T(label, if (enabled) Color.Black else Dim, 17, true) }

@Composable
private fun Opt(title: String, sub: String, on: Boolean, enabled: Boolean, set: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
) {
    Column(Modifier.weight(1f)) {
        T(title, Txt, 15, true)
        T(sub, Dim, 12)
    }
    Switch(
        checked = on,
        onCheckedChange = set,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.Black,
            checkedTrackColor = Orange,
            uncheckedThumbColor = Dim,
            uncheckedTrackColor = Color.Black,
            uncheckedBorderColor = Line
        )
    )
}

private fun fmtMb(b: Long) = String.format(Locale.US, "%.1f MB", b / 1048576.0)
private fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

@Composable
fun ClipShrinkApp(
    vm: ConverterViewModel,
    onPick: () -> Unit,
    onPickFolder: () -> Unit,
    onShare: (File) -> Unit,
    onShareOriginal: (Uri) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    LaunchedEffect(vm.busy) { view.keepScreenOn = vm.busy }
    LaunchedEffect(vm.resultFile) {
        if (vm.resultFile != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val c = vm.clip

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Clip", Txt, 28, true)
            T("Shrink", Orange, 28, true)
        }
        T("Offline · hardware accelerated", Dim, 13)

        if (c == null) {
            Panel {
                T("No clip yet", Txt, 16, true)
                T("Pick a video, or share one to ClipShrink from your gallery or Discord.", Dim, 13)
                Btn("Pick a clip", Orange, !vm.busy, onPick)
            }
        } else {
            val limit = vm.targetMb * 1048576L
            val plan = Planner.plan(c, vm.targetMb)
            val effectOn = if (c.hdr) vm.toneMap else vm.enhance
            val small = c.sizeBytes in 1..limit && !effectOn

            Panel {
                val bmp = vm.thumb
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Card2)
                ) {
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (effectOn) Yellow else Line)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        val badge = when {
                            c.hdr && vm.toneMap -> "HDR → SDR"
                            c.hdr -> "HDR"
                            vm.enhance -> "SDR · enhance"
                            else -> "SDR"
                        }
                        T(badge, if (effectOn) Color.Black else Txt, 12, true)
                    }
                }
                T(c.name, Txt, 15, true, maxLines = 1)
                val fpsText = if (c.fps > 0f) " · ${c.fps.roundToInt()}fps" else ""
                T("${fmtDur(c.durationMs)} · ${c.width}×${c.height}$fpsText · ${fmtMb(c.sizeBytes)}", Dim, 12, mono = true)
                if (!vm.busy) {
                    T("Change clip", Orange, 14, true, modifier = Modifier.clickable(onClick = onPick))
                }
            }

            Panel {
                T("Target size", Dim, 12)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 25, 50).forEach { mb ->
                        val sel = vm.targetMb == mb
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Yellow else Color.Transparent)
                                .border(1.dp, if (sel) Yellow else Line, RoundedCornerShape(12.dp))
                                .clickable(enabled = !vm.busy) { vm.targetMb = mb },
                            contentAlignment = Alignment.Center
                        ) { T("$mb MB", if (sel) Color.Black else Txt, 14, true, true) }
                    }
                }
            }

            Panel {
                if (c.hdr) {
                    Opt("Tone-map HDR → SDR", "Converts HDR so it looks right on any screen", vm.toneMap, !vm.busy) { vm.toneMap = it }
                } else {
                    Opt("Fix washed-out look", "Deeper shadows and richer colour, great for PS5 captures", vm.enhance, !vm.busy) { vm.updateEnhance(it) }
                }
                val lvl = vm.brightLevel
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("Brightness", Txt, 15, true)
                        T("Lower it if clips look too bright, raise it if too dark", Dim, 12)
                    }
                    T(
                        if (lvl == 0) "0" else if (lvl > 0) "+$lvl" else "−${-lvl}",
                        if (lvl == 0) Dim else Yellow, 16, true, true
                    )
                }
                Slider(
                    value = lvl.toFloat(),
                    onValueChange = { vm.updateBrightness(it.roundToInt()) },
                    valueRange = -3f..3f,
                    steps = 5,
                    enabled = !vm.busy,
                    colors = SliderDefaults.colors(
                        thumbColor = Orange,
                        activeTrackColor = Orange,
                        inactiveTrackColor = Line,
                        activeTickColor = Color.Black,
                        inactiveTickColor = Dim
                    )
                )
            }

            Panel {
                Opt("Auto-save after convert", "Saves a copy as soon as it's done", vm.autoSave, !vm.busy) { vm.updateAutoSave(it) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("Save to", Dim, 12)
                        T(vm.folderLabel(), Txt, 14, true, true, maxLines = 1)
                    }
                    if (!vm.busy) {
                        T("Change", Orange, 14, true, modifier = Modifier.clickable(onClick = onPickFolder).padding(8.dp))
                        if (vm.folderUri != null) {
                            T("Reset", Yellow, 14, true, modifier = Modifier.clickable { vm.resetFolder() }.padding(8.dp))
                        }
                    }
                }
            }

            val estText: String
            val estColor: Color
            when {
                small -> { estText = "Already under ${vm.targetMb} MB, no need to convert"; estColor = Green }
                plan.tooTight -> {
                    estText = "${plan.h}p${plan.fps} · ~${fmtMb(plan.estBytes)} · very soft, try a bigger target"
                    estColor = Yellow
                }
                else -> { estText = "${plan.h}p${plan.fps} · ~${fmtMb(plan.estBytes)}"; estColor = Green }
            }
            T(estText, estColor, 14, true, true)

            if (!vm.busy) {
                Btn("Convert", Orange) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.convert()
                }
                if (small) Btn("Share original", Green) { onShareOriginal(c.uri) }
            } else {
                Panel(Orange) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF1A1A1A))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(vm.progress.coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(Orange, Yellow)))
                        )
                    }
                    T("Converting… ${(vm.progress * 100).toInt()}%", Txt, 14, true, true)
                    T("Cancel", Yellow, 14, true, modifier = Modifier.clickable { vm.cancel() })
                }
            }
        }

        vm.error?.let { e -> Panel(Yellow) { T(e, Yellow, 14, true) } }

        vm.resultFile?.let { f ->
            val limit = vm.targetMb * 1048576L
            Panel(Green) {
                T("Done", Green, 18, true)
                T(fmtMb(f.length()), Txt, 14, true, true)
                vm.methodLabel?.let { T(it, Dim, 12, mono = true) }
                if (f.length() > limit) T("Slightly over target, try a smaller size.", Yellow, 13)
                val saved = vm.savedTo
                when {
                    saved != null -> T("Saved to $saved", Green, 13)
                    vm.saving -> T("Saving…", Dim, 13)
                    else -> T("Save a copy", Orange, 14, true, modifier = Modifier.clickable { vm.saveResult() })
                }
                Btn("Share", Green) { onShare(f) }
            }
        }
    }
}
