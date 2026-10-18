package com.jackdengler.quiet.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.jackdengler.quiet.data.OpenMeteo
import com.jackdengler.quiet.data.Place
import com.jackdengler.quiet.data.Prefs
import com.jackdengler.quiet.ui.LocalAccent
import com.jackdengler.quiet.ui.QuietFonts
import com.jackdengler.quiet.ui.tvClickable
import com.jackdengler.quiet.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A text value being edited in the full-screen editor. */
private data class Editor(
    val title: String,
    val hint: String,
    val initial: String,
    val keyboard: KeyboardType = KeyboardType.Text,
    val secret: Boolean = false,
    val save: (String) -> Unit,
)

@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit, onHomeSetup: () -> Unit) {
    var editor by remember { mutableStateOf<Editor?>(null) }
    var locating by remember { mutableStateOf(false) }
    BackHandler(enabled = editor == null && !locating, onBack = onBack)
    // Re-read prefs whenever they change.
    prefs.changes.collectAsState().value

    val first = remember { FocusRequester() }
    LaunchedEffect(editor == null && !locating) {
        if (editor == null && !locating) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).padding(start = 140.dp, end = 140.dp, top = 56.dp)) {
        Title("Quiet settings")
        Spacer(Modifier.height(20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            item { Section("Quiet") }
            item { UpdateRow() }
            item { Section("Home screen") }
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                SettingRow("Background", if (prefs.santaMonica) "Santa Monica: live time of day & weather" else "Photo mix") {
                    prefs.santaMonica = !prefs.santaMonica
                    com.jackdengler.quiet.photos.PhotoSyncWorker.syncNow(context)
                }
            }
            item {
                SettingRow("Weather location", prefs.placeName, Modifier.focusRequester(first)) { locating = true }
            }
            item {
                SettingRow("Temperature", if (prefs.fahrenheit) "°F" else "°C") { prefs.fahrenheit = !prefs.fahrenheit }
            }
            item {
                SettingRow("Headline feeds", "${prefs.feeds.size} feed(s): " + prefs.feeds.joinToString(", ") { it.substringAfter("//").substringBefore("/") }) {
                    editor = Editor("Headline feeds", "One or more RSS/Atom URLs separated by spaces. Leave empty for WSJ + Variety.", prefs.rssUrl, KeyboardType.Uri) {
                        prefs.rssUrl = it
                    }
                }
            }
            item { SettingRow("Home button", "Set up the Home button takeover", onClick = onHomeSetup) }

            item { Section("ESPN fantasy overlay") }
            item {
                SettingRow("Overlay", if (prefs.espnEnabled) "On" else "Off") { prefs.espnEnabled = !prefs.espnEnabled }
            }
            item {
                // The service updates this in the background; re-read it every few seconds.
                var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(3_000)
                        tick++
                    }
                }
                tick
                val status = if (!prefs.espnEnabled) "Off" else prefs.cached(com.jackdengler.quiet.data.Espn.STATUS)
                    ?: "No update yet. Needs the Quiet Home accessibility service on"
                SettingRow("Overlay status", status) { }
            }
            item {
                SettingRow("When to show", if (prefs.espnAlways) "Always" else "During NFL game windows") {
                    prefs.espnAlways = !prefs.espnAlways
                }
            }
            item {
                SettingRow("League ID", prefs.espnLeague.ifBlank { "Not set" }) {
                    editor = Editor("League ID", "The number after leagueId= in your ESPN league URL.", prefs.espnLeague, KeyboardType.Number) {
                        prefs.espnLeague = it
                    }
                }
            }
            item {
                SettingRow("Team ID", prefs.espnTeam.ifBlank { "Not set" }) {
                    editor = Editor("Team ID", "The number after teamId= on your team page.", prefs.espnTeam, KeyboardType.Number) {
                        prefs.espnTeam = it
                    }
                }
            }
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                SettingRow(
                    "Sign in to ESPN",
                    if (prefs.espnS2.isBlank()) "Private leagues: sign in once to save access" else "Signed in",
                ) {
                    context.startActivity(android.content.Intent(context, EspnLoginActivity::class.java))
                }
            }
            item {
                SettingRow("espn_s2 cookie", if (prefs.espnS2.isBlank()) "Only for private leagues" else "Saved") {
                    editor = Editor("espn_s2", "Paste from your phone using the Google Home app's remote keyboard.", prefs.espnS2, secret = true) {
                        prefs.espnS2 = it
                    }
                }
            }
            item {
                SettingRow("SWID cookie", if (prefs.espnSwid.isBlank()) "Only for private leagues" else "Saved") {
                    editor = Editor("SWID", "Looks like {XXXXXXXX-XXXX-…}.", prefs.espnSwid, secret = true) {
                        prefs.espnSwid = it
                    }
                }
            }
        }
    }

    editor?.let { e -> TextEditor(e) { editor = null } }
    if (locating) LocationEditor(prefs) { locating = false }
}

@Composable
private fun Title(text: String) = Text(
    text,
    style = TextStyle(fontFamily = QuietFonts.InstrumentSerif, fontStyle = FontStyle.Italic, fontSize = 48.sp, color = Color.White),
)

@Composable
private fun Section(text: String) {
    val accent by LocalAccent.current
    Text(
        text.uppercase(),
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
        style = TextStyle(
            fontFamily = QuietFonts.Manrope, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
            letterSpacing = 1.8.sp, color = accent,
        ),
    )
}

@Composable
fun SettingRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val f by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "row")
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { val s = 1f + 0.015f * f; scaleX = s; scaleY = s }
            .background(Color.White.copy(alpha = 0.06f + 0.84f * f), RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .tvClickable(onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (focused) Color(0xFF151515) else Color.White
        Text(
            label,
            modifier = Modifier.width(360.dp),
            style = TextStyle(fontFamily = QuietFonts.Manrope, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, color = ink),
        )
        Text(
            value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 21.sp, color = ink.copy(alpha = 0.7f)),
        )
    }
}

@Composable
private fun EditorFrame(title: String, hint: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.width(1100.dp).padding(top = 120.dp)) {
            Title(title)
            Spacer(Modifier.height(10.dp))
            Text(hint, style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 20.sp, color = Color.White.copy(alpha = 0.7f)))
            Spacer(Modifier.height(28.dp))
            content()
        }
    }
}

@Composable
private fun InputField(
    initial: String,
    keyboard: KeyboardType,
    ime: ImeAction,
    onSubmit: (String) -> Unit,
    secret: Boolean = false,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
        keyboardController?.show()
    }
    var focused by remember { mutableStateOf(false) }
    val accent by LocalAccent.current
    BasicTextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 26.sp, color = Color.White),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ime),
        keyboardActions = KeyboardActions(onAny = { onSubmit(value.text) }),
        visualTransformation = if (secret && !focused) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .border(2.dp, if (focused) accent else Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 18.dp),
    )
    Spacer(Modifier.height(22.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingRow(if (ime == ImeAction.Search) "Search" else "Save", "", Modifier.width(260.dp)) { onSubmit(value.text) }
    }
}

@Composable
private fun TextEditor(e: Editor, onClose: () -> Unit) = EditorFrame(e.title, e.hint, onClose) {
    InputField(e.initial, e.keyboard, ImeAction.Done, onSubmit = { e.save(it); onClose() }, secret = e.secret)
}

private val LatLon = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$""")

@Composable
private fun LocationEditor(prefs: Prefs, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var status by remember { mutableStateOf("") }
    fun choose(p: Place) {
        prefs.latitude = p.latitude
        prefs.longitude = p.longitude
        prefs.placeName = p.name
        onClose()
    }
    EditorFrame("Weather location", "Type a city, or latitude,longitude (e.g. 40.71,-74.01).", onClose) {
        InputField("", KeyboardType.Text, ImeAction.Search, onSubmit = { q ->
            LatLon.matchEntire(q)?.let { m ->
                choose(Place(q.trim(), m.groupValues[1].toDouble(), m.groupValues[2].toDouble()))
                return@InputField
            }
            if (q.isBlank()) return@InputField
            status = "Searching…"
            scope.launch {
                val found = withContext(Dispatchers.IO) { runCatching { OpenMeteo.search(q) }.getOrDefault(emptyList()) }
                results = found
                status = if (found.isEmpty()) "No matches." else ""
            }
        })
        Spacer(Modifier.height(16.dp))
        if (status.isNotEmpty()) {
            Text(status, style = TextStyle(fontFamily = QuietFonts.Manrope, fontSize = 20.sp, color = Color.White.copy(alpha = 0.7f)))
        }
        results?.let { list ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEach { p -> SettingRow(p.name, "%.2f, %.2f".format(p.latitude, p.longitude)) { choose(p) } }
            }
        }
    }
}

/** One-click update: checks GitHub when shown; selecting it installs the newer build. */
@Composable
fun UpdateRow(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Updater.check() }
    val current = com.jackdengler.quiet.BuildConfig.VERSION_CODE
    val value = when (val st = Updater.state) {
        Updater.State.Idle, Updater.State.Checking -> "Checking… (build $current)"
        Updater.State.UpToDate -> "Up to date (build $current)"
        is Updater.State.Available -> "Update to build ${st.build}: select to install"
        is Updater.State.Downloading -> "Downloading… ${st.percent}%"
        Updater.State.Installing -> "Confirm the install on screen"
        is Updater.State.Failed -> "${st.message}. Select to retry"
    }
    SettingRow("Update Quiet", value, modifier) {
        scope.launch {
            when (Updater.state) {
                is Updater.State.Available -> Updater.install(context)
                is Updater.State.Downloading, Updater.State.Installing -> Unit
                else -> {
                    Updater.check(force = true)
                    if (Updater.state is Updater.State.Available) Updater.install(context)
                }
            }
        }
    }
}
