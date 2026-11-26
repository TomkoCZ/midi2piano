@file:OptIn(ExperimentalMaterial3Api::class)

package cz.midi2piano

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppRoot(vm: AppVM, onPickMidi: () -> Unit, onPickDir: () -> Unit) {
    BackHandler(enabled = vm.inResult) { vm.back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        if (vm.inResult && vm.score != null) ResultScreen(vm, onPickDir) else HomeScreen(vm, onPickMidi)
    }
}

// ============================================================================ HOME
@Composable
fun HeroKeys(modifier: Modifier) {
    val tr = rememberInfiniteTransition(label = "hero")
    val ph by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart), label = "ph")
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.secondary
    val keyC = MaterialTheme.colorScheme.surface
    val blackC = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val keysH = size.height * 0.30f
        val n = 9
        val kw = size.width / n
        val notes = listOf(Triple(1, 0.00f, 0), Triple(3, 0.25f, 1), Triple(4, 0.55f, 0), Triple(6, 0.15f, 1), Triple(7, 0.70f, 0), Triple(2, 0.42f, 1))
        val fallH = size.height - keysH
        for ((col, off, hand) in notes) {
            val y = (((ph + off) % 1f)) * (fallH + 60f) - 60f
            drawRoundRect(if (hand == 0) c1 else c2, Offset(col * kw + 6f, y), Size(kw - 12f, 56f + off * 40f), CornerRadius(14f, 14f))
        }
        for (i in 0 until n) drawRoundRect(keyC, Offset(i * kw + 2f, fallH), Size(kw - 4f, keysH), CornerRadius(10f, 10f))
        for (i in listOf(0, 1, 3, 4, 5, 7)) drawRoundRect(blackC, Offset((i + 1) * kw - kw * 0.28f, fallH), Size(kw * 0.56f, keysH * 0.6f), CornerRadius(8f, 8f))
    }
}

@Composable
fun HomeScreen(vm: AppVM, onPickMidi: () -> Unit) {
    val sb = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(sb.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("MIDI → Piano", fontWeight = FontWeight.ExtraBold) },
                scrollBehavior = sb,
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HeroKeys(Modifier.fillMaxWidth().height(150.dp).clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.primaryContainer))
                    Text("Z libovolného MIDI klavírní noty, zvuk a video s padajícími notami.",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Button(onClick = onPickMidi, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                        Text(if (vm.midi == null) "Vybrat MIDI soubor" else "Vybrat jiný soubor", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            vm.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            val m = vm.midi
            if (m == null) {
                InfoRow("Noty", "Dvě osnovy, tónina, triol, vazby – jako z LilyPondu. Export do PDF.")
                InfoRow("Zvuk", "Přehrání přímo z notového zápisu (vlastní klavírní syntezátor), export M4A / WAV.")
                InfoRow("Video", "Padající noty ve stylu Synthesia v rychlostech 1× / 0,55× / 0,25×, export MP4.")
            } else {
                OptionsCard(vm, m)
                Button(
                    onClick = { vm.build() }, enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().height(64.dp), shape = MaterialTheme.shapes.extraLarge,
                ) {
                    if (vm.busy) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(12.dp)) }
                    Text(if (vm.busy) "Počítám…" else "Vytvořit noty, zvuk a video", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun InfoRow(title: String, text: String) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val KEY_NAMES = listOf("c", "c#", "d", "eb", "e", "f", "f#", "g", "ab", "a", "bb", "b")
private fun keyLabel(code: String): String {
    val (n, m) = code.split("-")
    return n.replaceFirstChar { it.uppercase() }.replace("b", "♭").replace("#", "♯") + if (m == "minor") " moll" else " dur"
}

@Composable
fun OptionsCard(vm: AppVM, m: MidiData) {
    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(vm.fileName, style = MaterialTheme.typography.titleLarge)
            Text("tempo ≈ ${Math.round(m.bpm)} BPM · takt ${m.ts.first}/${m.ts.second} · stop s notami: ${m.tracks.size} · not: ${m.tracks.sumOf { it.size }}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(vm.title, { vm.title = it }, label = { Text("Název skladby") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)

            Text("Rozdělení rukou", style = MaterialTheme.typography.titleSmall)
            val hOpts = listOf("auto" to "Auto", "tracks" to "Podle stop", "pitch" to "Podle výšky")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                hOpts.forEachIndexed { i, (k, l) ->
                    SegmentedButton(vm.hands == k, { vm.hands = k }, SegmentedButtonDefaults.itemShape(i, hOpts.size)) { Text(l, maxLines = 1) }
                }
            }
            if (vm.hands != "tracks") {
                Text("Dělicí tón: ${vm.split}  (60 = střední C)", style = MaterialTheme.typography.titleSmall)
                Slider(vm.split.toFloat(), { vm.split = it.toInt() }, valueRange = 36f..84f)
            }

            Text("Tónina", style = MaterialTheme.typography.titleSmall)
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.large) { Text(vm.keyChoice?.let { keyLabel(it) } ?: "Automaticky") }
                DropdownMenu(open, { open = false }) {
                    DropdownMenuItem(text = { Text("Automaticky") }, onClick = { vm.keyChoice = null; open = false })
                    for (mode in listOf("major", "minor")) for (k in KEY_NAMES) {
                        val code = "$k-$mode"
                        DropdownMenuItem(text = { Text(keyLabel(code)) }, onClick = { vm.keyChoice = code; open = false })
                    }
                }
            }

            Text("Taktů na řádek: ${vm.perLine}", style = MaterialTheme.typography.titleSmall)
            Slider(vm.perLine.toFloat(), { vm.perLine = it.toInt() }, valueRange = 2f..8f, steps = 5)

            Text("Názvy not v padajících notách", style = MaterialTheme.typography.titleSmall)
            val nOpts = listOf("cz" to "CZ (H)", "en" to "EN (B)", "none" to "Žádné")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                nOpts.forEachIndexed { i, (k, l) ->
                    SegmentedButton(vm.names == k, { vm.names = k }, SegmentedButtonDefaults.itemShape(i, nOpts.size)) { Text(l, maxLines = 1) }
                }
            }

            Text("Rychlosti videa", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in vm.speedChoices) FilterChip(
                    selected = s in vm.speeds,
                    onClick = { if (s in vm.speeds) { if (vm.speeds.size > 1) vm.speeds.remove(s) } else vm.speeds.add(s) },
                    label = { Text("${if (s == 1.0) "1" else s.toString().replace('.', ',')}×") },
                )
            }
        }
    }
}

// ============================================================================ RESULT
@Composable
fun ResultScreen(vm: AppVM, onPickDir: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sc = vm.score!!
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sc.title, maxLines = 1, fontWeight = FontWeight.Bold)
                        Text("${sc.key.label} · ${sc.ts.first}/${sc.ts.second} · ${Math.round(sc.bpm)} BPM · ${sc.nb} taktů",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.Rounded.ArrowBack, "Zpět") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Rounded.Create, null) }, label = { Text("Noty") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Rounded.PlayArrow, null) }, label = { Text("Přehrát") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Rounded.Share, null) }, label = { Text("Export") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> NotesTab(vm)
                1 -> PlayerTab(vm)
                else -> ExportTab(vm, onPickDir)
            }
        }
    }
}

@Composable
fun NotesTab(vm: AppVM) {
    val eg = vm.engraver ?: return
    var zoom by remember { mutableFloatStateOf(1f) }
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val w = (screenW - 24.dp) * zoom
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(List(eg.pageCount) { it }) { _, page ->
                    Canvas(Modifier.width(w).aspectRatio(Engraver.PW / Engraver.PH).clip(RoundedCornerShape(10.dp)).background(Color.White)) {
                        drawIntoCanvas { c ->
                            val nc = c.nativeCanvas
                            nc.save()
                            val s = size.width / Engraver.PW
                            nc.scale(s, s)
                            eg.draw(nc, page)
                            nc.restore()
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton({ zoom = (zoom - 0.5f).coerceAtLeast(1f) }, Modifier.size(52.dp)) { Text("−", fontSize = 24.sp) }
            FilledTonalIconButton({ zoom = (zoom + 0.5f).coerceAtMost(4f) }, Modifier.size(52.dp)) { Text("+", fontSize = 24.sp) }
        }
    }
}

@Composable
fun PlayerTab(vm: AppVM) {
    val scene = vm.scene ?: return
    var t by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(false) }
    val player = vm.player

    LaunchedEffect(Unit) { if (vm.player == null && !vm.audioBusy) vm.selectSpeed(vm.speed) }
    LaunchedEffect(player) { t = 0.0; playing = false }
    DisposableEffect(Unit) { onDispose { vm.player?.pause() } }
    LaunchedEffect(playing, player) {
        while (playing && player != null) {
            androidx.compose.runtime.withFrameNanos { }
            t = player.position()
            if (!player.playing) playing = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(Color.Black)) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                val s = size.width / Scene.W
                nc.scale(s, s)
                nc.clipRect(0f, 0f, Scene.W, Scene.H)
                scene.draw(nc, t, vm.speed)
                nc.restore()
            }
        }

        if (vm.audioBusy) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Generuji zvuk z notového zápisu…", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(progress = { vm.audioProgress }, Modifier.fillMaxWidth())
            }
        }

        val dur = player?.duration ?: 1.0
        Slider(
            value = t.toFloat().coerceIn(0f, dur.toFloat()),
            onValueChange = { t = it.toDouble() },
            onValueChangeFinished = { if (playing) player?.play(t) },
            valueRange = 0f..dur.toFloat(),
            enabled = player != null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtTime(t), style = MaterialTheme.typography.labelLarge)
            Text(fmtTime(dur), style = MaterialTheme.typography.labelLarge)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton({ t = 0.0; if (playing) player?.play(0.0) }, Modifier.size(56.dp), enabled = player != null) {
                Icon(Icons.Rounded.Refresh, "Od začátku")
            }
            Spacer(Modifier.width(20.dp))
            Box(
                Modifier.size(84.dp).clip(RoundedCornerShape(if (playing) 28.dp else 42.dp)).background(MaterialTheme.colorScheme.primary)
                    .let { m -> if (player != null) m.clickableNoRipple {
                        if (playing) { player.pause(); playing = false }
                        else { if (t >= player.duration - 0.2) t = 0.0; player.play(t); playing = true }
                    } else m },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(2) { Box(Modifier.size(width = 9.dp, height = 30.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.onPrimary)) }
                } else Icon(Icons.Rounded.PlayArrow, "Přehrát", Modifier.size(46.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }

        Text("Rychlost", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in vm.speeds.sortedDescending()) FilterChip(
                selected = vm.speed == s,
                onClick = { if (vm.speed != s) { playing = false; vm.selectSpeed(s) } },
                label = { Text("${if (s == 1.0) "1" else s.toString().replace('.', ',')}×") },
                enabled = !vm.audioBusy,
            )
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(indication = null, interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(), onClick = onClick))

private fun fmtTime(s: Double): String { val x = s.toInt().coerceAtLeast(0); return "%d:%02d".format(x / 60, x % 60) }

@Composable
fun ExportTab(vm: AppVM, onPickDir: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Výstupní složka", style = MaterialTheme.typography.titleMedium)
                Text(vm.outDir?.lastPathSegment?.substringAfter(':') ?: "Zatím nevybrána – soubory se ukládají sem.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                FilledTonalButton(onPickDir) { Text(if (vm.outDir == null) "Vybrat složku" else "Změnit složku") }
            }
        }
        Button(
            onClick = { vm.exportAll() }, enabled = vm.outDir != null,
            modifier = Modifier.fillMaxWidth().height(58.dp), shape = MaterialTheme.shapes.extraLarge,
        ) { Text("Exportovat vše", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

        for (j in vm.jobs) {
            Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(j.label, style = MaterialTheme.typography.titleMedium)
                        Text(j.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (j.status == 1) LinearProgressIndicator(progress = { j.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
                        if (j.status == 3) Text(j.msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    when (j.status) {
                        2 -> Icon(Icons.Rounded.Check, "Hotovo", tint = MaterialTheme.colorScheme.primary)
                        1 -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                        else -> FilledTonalButton({ vm.start(j) }, enabled = vm.outDir != null) { Text(if (j.status == 3) "Znovu" else "Uložit") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
