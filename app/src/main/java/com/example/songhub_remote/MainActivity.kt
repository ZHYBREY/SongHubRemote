package com.example.songhub_remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val CATALOG_URL =
    "https://raw.githubusercontent.com/ZHYBREY/SongHub-Catalog/main/app/src/main/assets/songs_catalog.json"

private const val APP_UPDATE_URL =
    "https://raw.githubusercontent.com/ZHYBREY/SongHub-Catalog/main/app_update.json"

private const val RELEASE_URL =
    "https://github.com/ZHYBREY/SongHub-Catalog/releases/latest"

private const val PREF_KNOWN_CATALOG_CODES = "known_catalog_codes"
private const val PREF_PENDING_NEW_SONG_COUNT = "pending_new_song_count"
private const val PREF_PENDING_APP_VERSION_CODE = "pending_app_version_code"
private const val PREF_PENDING_APP_VERSION_NAME = "pending_app_version_name"

data class Song(
    val code: String,
    val title: String,
    val artist: String
)

data class ScoreEntry(
    val id: String,
    val code: String,
    val title: String,
    val artist: String,
    val score: Int,
    val playedAt: Long
)

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String
)

private val Navy = Color(0xFF101A32)
private val TextWhite = Color(0xFFF4F6FF)
private val Muted = Color(0xFF9EACCC)
private val Accent = Color(0xFF63E6D1)
private val AlertRed = Color(0xFFE65C70)

private val songColors = listOf(
    Color(0xFF5865D9),
    Color(0xFF8B5CC7),
    Color(0xFF287FA8),
    Color(0xFFB66B43),
    Color(0xFF398C78),
    Color(0xFF9B567F),
    Color(0xFF687DB8)
)

private val catalogHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private val appUpdateHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

private fun getInstalledVersionCode(context: Context): Long {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    return if (Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
}

private fun getInstalledVersionName(context: Context): String {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    return info.versionName ?: "Unknown"
}

private fun formatSongNumber(code: String): String {
    return code.trim().ifBlank { "----" }
}

private fun sortSongsAlphabetically(songs: List<Song>): List<Song> {
    return songs.sortedWith(
        compareBy<Song> { it.title.lowercase(Locale.ROOT) }
            .thenBy { it.code }
    )
}

private fun parseSongs(json: String): List<Song> {
    val root = org.json.JSONTokener(json).nextValue()
    val array = when (root) {
        is JSONArray -> root
        is JSONObject -> {
            when {
                root.optJSONArray("songs") != null -> root.getJSONArray("songs")
                root.optJSONArray("data") != null -> root.getJSONArray("data")
                root.optJSONArray("value") != null -> root.getJSONArray("value")
                else -> JSONArray()
            }
        }
        else -> JSONArray()
    }

    val result = LinkedHashMap<String, Song>()

    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue

        val code = (
                item.optString("code")
                    .ifBlank { item.optString("number") }
                    .ifBlank { item.optString("songNumber") }
                ).trim()

        val title = (
                item.optString("title")
                    .ifBlank { item.optString("name") }
                ).trim()

        val artist = (
                item.optString("artist")
                    .ifBlank { item.optString("singer") }
                ).trim()

        if (code.isNotBlank() && title.isNotBlank()) {
            result[code] = Song(
                code = code,
                title = title,
                artist = artist
            )
        }
    }

    return result.values.toList()
}

private fun saveInternalCatalog(context: Context, json: String) {
    val parsed = parseSongs(json)
    require(parsed.isNotEmpty()) {
        "Walang valid na kanta sa catalog."
    }

    val file = File(context.filesDir, "songs_catalog.json")
    val backup = File(context.filesDir, "songs_catalog_backup.json")

    if (file.exists()) {
        file.copyTo(backup, overwrite = true)
    }

    file.writeText(json)
}

private fun loadSongs(
    context: Context,
    folderUri: Uri?
): List<Song> {
    val candidates = mutableListOf<List<Song>>()

    if (folderUri != null) {
        try {
            val directory = getCatalogDirectory(context, folderUri)
            val file = directory?.findFile("songs_catalog.json")
                ?: directory?.findFile("catalog.json")

            if (file != null) {
                context.contentResolver.openInputStream(file.uri)?.use {
                    candidates.add(parseSongs(it.bufferedReader().readText()))
                }
            }
        } catch (_: Exception) {
        }
    }

    try {
        val internal = File(context.filesDir, "songs_catalog.json")
        if (internal.exists()) {
            candidates.add(parseSongs(internal.readText()))
        }
    } catch (_: Exception) {
    }

    try {
        context.assets.open("songs_catalog.json").use {
            candidates.add(parseSongs(it.bufferedReader().readText()))
        }
    } catch (_: Exception) {
    }

    return candidates
        .filter { it.isNotEmpty() }
        .maxByOrNull { it.size }
        ?: emptyList()
}

private fun getCatalogDirectory(
    context: Context,
    treeUri: Uri,
    create: Boolean = false
): DocumentFile? {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null

    if (tree.name.equals("SongHub", true)) {
        return tree
    }

    return tree.findFile("SongHub")
        ?: if (create) tree.createDirectory("SongHub") else null
}

private fun ensureCatalogFile(
    context: Context,
    parentUri: Uri
) {
    val directory = getCatalogDirectory(
        context,
        parentUri,
        create = true
    ) ?: throw Exception("Hindi mabuksan ang SongHub folder.")

    if (
        directory.findFile("songs_catalog.json") == null &&
        directory.findFile("catalog.json") == null
    ) {
        val file = directory.createFile(
            "application/json",
            "songs_catalog.json"
        ) ?: throw Exception("Hindi makagawa ng catalog file.")

        context.contentResolver.openOutputStream(file.uri)?.use {
            it.write("[]".toByteArray())
        }
    }
}

private fun writeDocumentFile(
    context: Context,
    file: DocumentFile,
    content: String
) {
    context.contentResolver.openOutputStream(file.uri, "wt")?.use {
        it.write(content.toByteArray())
    } ?: throw Exception("Hindi maisulat ang file.")
}

private fun replaceCatalog(
    context: Context,
    folderUri: Uri,
    json: String
) {
    val parsed = parseSongs(json)
    require(parsed.isNotEmpty()) {
        "Walang valid na kanta sa JSON."
    }

    val directory = getCatalogDirectory(
        context,
        folderUri,
        create = true
    ) ?: throw Exception("Hindi makita ang SongHub folder.")

    val current = directory.findFile("songs_catalog.json")
    val backup = directory.findFile("songs_catalog_backup.json")

    if (current != null) {
        val oldText = context.contentResolver
            .openInputStream(current.uri)
            ?.bufferedReader()
            ?.readText()

        if (oldText != null) {
            val backupFile = backup
                ?: directory.createFile(
                    "application/json",
                    "songs_catalog_backup.json"
                )

            if (backupFile != null) {
                writeDocumentFile(context, backupFile, oldText)
            }
        }

        writeDocumentFile(context, current, json)
    } else {
        val newFile = directory.createFile(
            "application/json",
            "songs_catalog.json"
        ) ?: throw Exception("Hindi makagawa ng catalog file.")

        writeDocumentFile(context, newFile, json)
    }

    saveInternalCatalog(context, json)
}

private suspend fun updateOnlineCatalog(
    context: Context,
    folderUri: Uri?
): List<Song> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url("$CATALOG_URL?updated=${System.currentTimeMillis()}")
        .header("Cache-Control", "no-cache")
        .build()

    catalogHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("Catalog server error: ${response.code}")
        }

        val json = response.body?.string()
            ?: throw Exception("Walang natanggap na catalog.")

        val parsed = parseSongs(json)

        if (parsed.isEmpty()) {
            throw Exception("Walang valid na kanta sa online catalog.")
        }

        if (folderUri != null) {
            replaceCatalog(context, folderUri, json)
        } else {
            saveInternalCatalog(context, json)
        }

        parsed
    }
}

private fun recordCatalogChanges(
    context: Context,
    songs: List<Song>
): Int {
    val prefs = context.getSharedPreferences("songhub_settings", 0)
    val oldCodes = prefs.getStringSet(
        PREF_KNOWN_CATALOG_CODES,
        emptySet()
    ) ?: emptySet()

    val newCodes = songs.map { it.code }.toSet()
    val added = if (oldCodes.isEmpty()) {
        0
    } else {
        (newCodes - oldCodes).size
    }

    prefs.edit()
        .putStringSet(PREF_KNOWN_CATALOG_CODES, newCodes)
        .putInt(
            PREF_PENDING_NEW_SONG_COUNT,
            prefs.getInt(PREF_PENDING_NEW_SONG_COUNT, 0) + added
        )
        .apply()

    return added
}

private suspend fun fetchAppUpdateInfo(): AppUpdateInfo? =
    withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$APP_UPDATE_URL?updated=${System.currentTimeMillis()}")
            .header("Cache-Control", "no-cache")
            .build()

        appUpdateHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Update server error: ${response.code}")
            }

            val json = JSONObject(
                response.body?.string()
                    ?: throw Exception("Walang update information.")
            )

            val versionCode = json.optLong("versionCode", 0L)
            val versionName = json.optString("versionName", "Unknown")
            val apkUrl = json.optString("apkUrl", "")
            val releaseNotes = json.optString("releaseNotes", "No release notes.")

            if (versionCode <= 0L || !apkUrl.startsWith("https://")) {
                throw Exception("Invalid app update information.")
            }

            AppUpdateInfo(
                versionCode,
                versionName,
                apkUrl,
                releaseNotes
            )
        }
    }

private suspend fun downloadAppUpdate(
    context: Context,
    updateInfo: AppUpdateInfo
): File = withContext(Dispatchers.IO) {
    require(updateInfo.apkUrl.startsWith("https://")) {
        "Invalid APK download URL."
    }

    val directory = File(context.cacheDir, "updates")
    if (!directory.exists()) directory.mkdirs()

    val temporaryFile = File(directory, "SongHubRemote.apk.tmp")
    val apkFile = File(directory, "SongHubRemote.apk")

    val request = Request.Builder()
        .url(updateInfo.apkUrl)
        .build()

    appUpdateHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("APK download failed: ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty APK file.")

        body.byteStream().use { input ->
            temporaryFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    if (!temporaryFile.exists() || temporaryFile.length() < 100_000L) {
        temporaryFile.delete()
        throw Exception("Invalid downloaded APK.")
    }

    if (apkFile.exists()) apkFile.delete()

    if (!temporaryFile.renameTo(apkFile)) {
        temporaryFile.copyTo(apkFile, overwrite = true)
        temporaryFile.delete()
    }

    apkFile
}

private fun installDownloadedApk(
    context: Context,
    apkFile: File
) {
    if (!apkFile.exists()) {
        throw Exception("Walang downloaded APK.")
    }

    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        throw Exception("UNKNOWN_SOURCE_PERMISSION")
    }

    val apkUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(
            apkUri,
            "application/vnd.android.package-archive"
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    context.startActivity(intent)
}

private fun loadScoreHistory(prefs: android.content.SharedPreferences): List<ScoreEntry> {
    val raw = prefs.getString("score_history", "[]") ?: "[]"
    val array = try {
        JSONArray(raw)
    } catch (_: Exception) {
        JSONArray()
    }

    val result = mutableListOf<ScoreEntry>()

    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        result.add(
            ScoreEntry(
                id = item.optString("id", UUID.randomUUID().toString()),
                code = item.optString("code"),
                title = item.optString("title"),
                artist = item.optString("artist"),
                score = item.optInt("score"),
                playedAt = item.optLong("playedAt")
            )
        )
    }

    return result
}

private fun saveScoreHistory(
    prefs: android.content.SharedPreferences,
    entries: List<ScoreEntry>
) {
    val array = JSONArray()

    entries.forEach { entry ->
        array.put(
            JSONObject().apply {
                put("id", entry.id)
                put("code", entry.code)
                put("title", entry.title)
                put("artist", entry.artist)
                put("score", entry.score)
                put("playedAt", entry.playedAt)
            }
        )
    }

    prefs.edit()
        .putString("score_history", array.toString())
        .apply()
}

private fun createQrBitmap(
    content: String,
    size: Int = 720
): Bitmap {
    val hints = hashMapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2
    )

    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        hints
    )

    return Bitmap.createBitmap(
        size,
        size,
        Bitmap.Config.ARGB_8888
    ).apply {
        for (x in 0 until size) {
            for (y in 0 until size) {
                setPixel(
                    x,
                    y,
                    if (matrix[x, y]) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Navy,
                    surface = Color(0xFF222E50)
                )
            ) {
                SongHubApp()
            }
        }
    }
}

@Composable
fun SongHubApp() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val scope = rememberCoroutineScope()

    val prefs = remember {
        context.getSharedPreferences("songhub_settings", 0)
    }

    var ip by remember {
        mutableStateOf(prefs.getString("pc_ip", "") ?: "")
    }

    var port by remember {
        mutableStateOf(prefs.getString("pc_port", "8080") ?: "8080")
    }

    var folderUri by remember {
        mutableStateOf(
            prefs.getString("catalog_folder_uri", null)
                ?.let { Uri.parse(it) }
        )
    }

    var songs by remember { mutableStateOf(emptyList<Song>()) }
    var searchText by remember { mutableStateOf("") }
    var selectedLetter by remember { mutableStateOf<String?>(null) }
    var selectedSong by remember { mutableStateOf<Song?>(null) }

    var connected by remember { mutableStateOf(false) }
    var connectionMessage by remember {
        mutableStateOf("Not connected to SongHub PC.")
    }

    var socket by remember { mutableStateOf<WebSocket?>(null) }

    var showSettingsPage by remember { mutableStateOf(false) }
    var showSharePage by remember { mutableStateOf(false) }
    var showPcSettings by remember { mutableStateOf(false) }
    var showCatalogSettings by remember { mutableStateOf(false) }
    var showScoresPage by remember { mutableStateOf(false) }

    var scoreHistory by remember {
        mutableStateOf(loadScoreHistory(prefs))
    }

    var pendingNewSongCount by remember {
        mutableIntStateOf(
            prefs.getInt(PREF_PENDING_NEW_SONG_COUNT, 0)
        )
    }

    var showAppUpdateDialog by remember { mutableStateOf(false) }
    var checkingAppUpdate by remember { mutableStateOf(false) }
    var downloadingAppUpdate by remember { mutableStateOf(false) }
    var appUpdateMessage by remember { mutableStateOf("Checking for updates...") }
    var appUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }

    var showCatalogStatus by remember { mutableStateOf(false) }
    var catalogStatus by remember { mutableStateOf("") }
    var catalogBusy by remember { mutableStateOf(false) }

    val wsClient = remember {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    BackHandler(
        enabled = showSharePage ||
                showSettingsPage ||
                showScoresPage ||
                showPcSettings ||
                showCatalogSettings
    ) {
        when {
            showSharePage -> showSharePage = false
            showSettingsPage -> showSettingsPage = false
            showScoresPage -> showScoresPage = false
            showPcSettings -> showPcSettings = false
            showCatalogSettings -> showCatalogSettings = false
        }
    }

    fun checkForAppUpdate() {
        checkingAppUpdate = true
        showAppUpdateDialog = true
        appUpdateInfo = null
        appUpdateMessage = "Checking for updates..."

        scope.launch {
            try {
                val info = fetchAppUpdateInfo()
                val installed = getInstalledVersionCode(context)

                if (info == null || info.versionCode <= installed) {
                    appUpdateMessage = "Your SongHub app is already up to date."
                    appUpdateInfo = null
                } else {
                    appUpdateInfo = info
                    appUpdateMessage = "A new version is available."
                    prefs.edit()
                        .putLong(
                            PREF_PENDING_APP_VERSION_CODE,
                            info.versionCode
                        )
                        .putString(
                            PREF_PENDING_APP_VERSION_NAME,
                            info.versionName
                        )
                        .apply()
                }
            } catch (e: Exception) {
                appUpdateMessage = e.message ?: "Unable to check for updates."
            } finally {
                checkingAppUpdate = false
            }
        }
    }

    fun downloadUpdate() {
        val info = appUpdateInfo ?: return

        downloadingAppUpdate = true
        appUpdateMessage = "Downloading update..."

        scope.launch {
            try {
                downloadedApk = downloadAppUpdate(context, info)
                appUpdateMessage = "Download complete. Ready to install."
            } catch (e: Exception) {
                appUpdateMessage = e.message ?: "Download failed."
            } finally {
                downloadingAppUpdate = false
            }
        }
    }

    fun installUpdate() {
        val apk = downloadedApk ?: return

        try {
            installDownloadedApk(context, apk)
        } catch (e: Exception) {
            if (e.message == "UNKNOWN_SOURCE_PERMISSION") {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                )
                activity.startActivity(intent)
            } else {
                appUpdateMessage = e.message ?: "Unable to install update."
            }
        }
    }

    fun refreshCatalog() {
        if (catalogBusy) return

        catalogBusy = true
        catalogStatus = "Refreshing song catalog..."

        scope.launch {
            try {
                val updatedSongs = updateOnlineCatalog(context, folderUri)
                val added = recordCatalogChanges(context, updatedSongs)

                songs = sortSongsAlphabetically(updatedSongs)
                pendingNewSongCount = prefs.getInt(
                    PREF_PENDING_NEW_SONG_COUNT,
                    0
                )

                catalogStatus = if (added > 0) {
                    "Catalog updated. $added new song(s) added."
                } else {
                    "Catalog is up to date."
                }
            } catch (e: Exception) {
                catalogStatus = e.message ?: "Catalog refresh failed."
                songs = sortSongsAlphabetically(
                    loadSongs(context, folderUri)
                )
            } finally {
                catalogBusy = false
                showCatalogStatus = true
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                folderUri = uri
                prefs.edit()
                    .putString("catalog_folder_uri", uri.toString())
                    .apply()

                ensureCatalogFile(context, uri)
                songs = sortSongsAlphabetically(
                    loadSongs(context, uri)
                )

                catalogStatus = "SongHub folder selected successfully."
            } catch (e: Exception) {
                catalogStatus = e.message ?: "Unable to select folder."
            }

            showCatalogStatus = true
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.readText()
                            ?: throw Exception("Unable to read JSON file.")
                    }

                    val parsed = parseSongs(json)
                    if (parsed.isEmpty()) {
                        throw Exception("Walang valid na kanta sa JSON.")
                    }

                    withContext(Dispatchers.IO) {
                        if (folderUri != null) {
                            replaceCatalog(context, folderUri!!, json)
                        } else {
                            saveInternalCatalog(context, json)
                        }
                    }

                    songs = sortSongsAlphabetically(parsed)
                    catalogStatus = "Catalog imported successfully."
                } catch (e: Exception) {
                    catalogStatus = e.message ?: "Import failed."
                }

                showCatalogStatus = true
            }
        }
    }

    fun connectToPC() {
        val cleanIp = ip.trim()
        val cleanPort = port.trim().toIntOrNull()

        if (cleanIp.isBlank() || cleanPort == null ||
            cleanPort !in 1..65535
        ) {
            connectionMessage = "Enter a valid IP address and port."
            return
        }

        prefs.edit()
            .putString("pc_ip", cleanIp)
            .putString("pc_port", cleanPort.toString())
            .apply()

        socket?.close(1000, "Reconnect")
        connected = false
        connectionMessage = "Connecting..."

        val request = Request.Builder()
            .url("ws://$cleanIp:$cleanPort/")
            .header("Origin", "https://songbook.songhub.space")
            .build()

        socket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    connected = true
                    connectionMessage = "Connected to SongHub PC."
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    try {
                        val json = JSONObject(text)
                        val action = json.optString("action")
                        val type = json.optString("type")

                        if (
                            action == "score_update" ||
                            type == "score_update"
                        ) {
                            val score = json.optInt(
                                "score",
                                json.optInt("value", 0)
                            )

                            val code = json.optString("code")
                            val title = json.optString("title", "Unknown song")
                            val artist = json.optString("artist", "")

                            val entry = ScoreEntry(
                                id = UUID.randomUUID().toString(),
                                code = code,
                                title = title,
                                artist = artist,
                                score = score,
                                playedAt = System.currentTimeMillis()
                            )

                            scoreHistory = listOf(entry) + scoreHistory
                            saveScoreHistory(prefs, scoreHistory)
                        }

                        if (
                            action == "state_update" ||
                            type == "state_update"
                        ) {
                            connectionMessage = json.optString(
                                "message",
                                "Connected to SongHub PC."
                            )
                        }
                    } catch (_: Exception) {
                    }
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    connected = false
                    connectionMessage = "Connection closing..."
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    connected = false
                    connectionMessage = "Disconnected from SongHub PC."
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    connected = false
                    connectionMessage = t.message ?: "Connection failed."
                }
            }
        )
    }

    fun disconnectFromPC() {
        socket?.close(1000, "Disconnected by user")
        socket = null
        connected = false
        connectionMessage = "Disconnected."
    }

    fun sendCommand(action: String, payload: String = "") {
        if (!connected) {
            connectionMessage = "Connect to SongHub PC first."
            return
        }

        val message = JSONObject().apply {
            put("action", action)
            put("payload", payload)
        }

        socket?.send(message.toString())
    }

    DisposableEffect(Unit) {
        onDispose {
            socket?.close(1000, "App closed")
            wsClient.dispatcher.executorService.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        songs = sortSongsAlphabetically(
            loadSongs(context, folderUri)
        )

        try {
            val updatedSongs = updateOnlineCatalog(context, folderUri)
            recordCatalogChanges(context, updatedSongs)
            songs = sortSongsAlphabetically(updatedSongs)
            pendingNewSongCount = prefs.getInt(
                PREF_PENDING_NEW_SONG_COUNT,
                0
            )
        } catch (_: Exception) {
        }
    }

    when {
        showSharePage -> {
            ShareAppPage(
                onBack = { showSharePage = false }
            )
        }

        showSettingsPage -> {
            SettingsPage(
                onBack = { showSettingsPage = false },
                onShareApp = {
                    showSettingsPage = false
                    showSharePage = true
                },
                onCatalogSettings = {
                    showSettingsPage = false
                    showCatalogSettings = true
                },
                onPCSettings = {
                    showSettingsPage = false
                    showPcSettings = true
                },
                onCheckUpdate = {
                    checkForAppUpdate()
                }
            )
        }

        showScoresPage -> {
            ScoresPage(
                entries = scoreHistory,
                onBack = { showScoresPage = false },
                onRefresh = {
                    scoreHistory = loadScoreHistory(prefs)
                },
                onClear = {
                    scoreHistory = emptyList()
                    saveScoreHistory(prefs, emptyList())
                }
            )
        }

        else -> {
            SongbookPage(
                songs = songs,
                connected = connected,
                connectionMessage = connectionMessage,
                searchText = searchText,
                onSearchChange = { searchText = it },
                selectedLetter = selectedLetter,
                onLetterChange = { selectedLetter = it },
                onSongClick = { selectedSong = it },
                onSettingsClick = { showSettingsPage = true },
                onScoresClick = { showScoresPage = true },
                onPCClick = { showPcSettings = true },
                pendingNewSongCount = pendingNewSongCount,
                onRefreshCatalog = {
                    prefs.edit()
                        .putInt(PREF_PENDING_NEW_SONG_COUNT, 0)
                        .apply()
                    pendingNewSongCount = 0
                    refreshCatalog()
                },
                onPlay = { sendCommand("play") },
                onPause = { sendCommand("pause") },
                onNext = { sendCommand("next") },
                onStop = { sendCommand("stop") }
            )
        }
    }

    if (showPcSettings) {
        AlertDialog(
            onDismissRequest = { showPcSettings = false },
            containerColor = Color(0xFF222E50),
            title = {
                Text(
                    "PC CONNECTION",
                    color = Accent,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("PC IP ADDRESS") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("PORT") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        singleLine = true
                    )

                    Text(
                        connectionMessage,
                        color = if (connected) Accent else Muted,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (connected) disconnectFromPC()
                        else connectToPC()
                    }
                ) {
                    Text(
                        if (connected) "DISCONNECT" else "CONNECT",
                        color = Accent
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPcSettings = false }) {
                    Text("CLOSE", color = Muted)
                }
            }
        )
    }

    if (showCatalogSettings) {
        AlertDialog(
            onDismissRequest = { showCatalogSettings = false },
            containerColor = Color(0xFF222E50),
            title = {
                Text(
                    "CATALOG MANAGER",
                    color = Accent,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text(
                        "Songs loaded: ${songs.size}",
                        color = TextWhite
                    )

                    Text(
                        "Choose a folder where SongHub can store its catalog, or import a JSON catalog.",
                        color = Muted,
                        fontSize = 12.sp
                    )

                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("SELECT SONGHUB FOLDER")
                    }

                    Button(
                        onClick = {
                            importPicker.launch(
                                arrayOf(
                                    "application/json",
                                    "text/plain"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("IMPORT / REPLACE JSON")
                    }

                    Button(
                        onClick = { refreshCatalog() },
                        enabled = !catalogBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (catalogBusy) {
                                "REFRESHING..."
                            } else {
                                "REFRESH ONLINE CATALOG"
                            }
                        )
                    }

                    Text(
                        catalogStatus,
                        color = Accent,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showCatalogSettings = false }
                ) {
                    Text("DONE", color = Accent)
                }
            }
        )
    }

    if (showAppUpdateDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!checkingAppUpdate) {
                    showAppUpdateDialog = false
                }
            },
            containerColor = Color(0xFF222E50),
            title = {
                Text(
                    "SONGHUB APP UPDATE",
                    color = Accent,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    if (checkingAppUpdate) {
                        CircularProgressIndicator(
                            color = Accent,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Text(
                        appUpdateMessage,
                        color = TextWhite,
                        fontSize = 14.sp
                    )

                    appUpdateInfo?.let { info ->
                        Text(
                            "Version ${info.versionName}",
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            info.releaseNotes,
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }

                    if (appUpdateInfo != null) {
                        Button(
                            onClick = { downloadUpdate() },
                            enabled = !downloadingAppUpdate,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Navy
                            )
                        ) {
                            Text(
                                if (downloadingAppUpdate) {
                                    "DOWNLOADING..."
                                } else {
                                    "DOWNLOAD UPDATE"
                                }
                            )
                        }
                    }

                    if (downloadedApk != null) {
                        Button(
                            onClick = { installUpdate() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF36B879)
                            )
                        ) {
                            Text("INSTALL UPDATE")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showAppUpdateDialog = false },
                    enabled = !checkingAppUpdate
                ) {
                    Text("CLOSE", color = Accent)
                }
            }
        )
    }

    if (showCatalogStatus) {
        AlertDialog(
            onDismissRequest = { showCatalogStatus = false },
            containerColor = Color(0xFF222E50),
            title = {
                Text("SONGHUB", color = Accent)
            },
            text = {
                Text(catalogStatus, color = TextWhite)
            },
            confirmButton = {
                TextButton(
                    onClick = { showCatalogStatus = false }
                ) {
                    Text("OK", color = Accent)
                }
            }
        )
    }

    selectedSong?.let { song ->
        AlertDialog(
            onDismissRequest = { selectedSong = null },
            containerColor = Color(0xFF222E50),
            title = {
                Text(
                    song.title,
                    color = TextWhite,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Column {
                    Text(
                        song.artist,
                        color = Color(0xFFB9C6E8),
                        fontSize = 16.sp
                    )

                    Spacer(Modifier.height(9.dp))

                    Text(
                        "SONG NUMBER: ${formatSongNumber(song.code)}",
                        color = Accent,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        sendCommand("play", song.code)
                        selectedSong = null
                    },
                    enabled = connected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF36CFC1),
                        contentColor = Navy
                    )
                ) {
                    Text("PLAY NOW", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            sendCommand("reserve", song.code)
                            selectedSong = null
                        },
                        enabled = connected
                    ) {
                        Text("RESERVE", color = Color(0xFFB58BFF))
                    }

                    TextButton(
                        onClick = { selectedSong = null }
                    ) {
                        Text("CANCEL", color = Muted)
                    }
                }
            }
        )
    }
}

@Composable
private fun SongbookPage(
    songs: List<Song>,
    connected: Boolean,
    connectionMessage: String,
    searchText: String,
    onSearchChange: (String) -> Unit,
    selectedLetter: String?,
    onLetterChange: (String?) -> Unit,
    onSongClick: (Song) -> Unit,
    onSettingsClick: () -> Unit,
    onScoresClick: () -> Unit,
    onPCClick: () -> Unit,
    pendingNewSongCount: Int,
    onRefreshCatalog: () -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit
) {
    val filteredSongs = remember(
        songs,
        searchText,
        selectedLetter
    ) {
        songs.filter { song ->
            val matchesSearch =
                searchText.isBlank() ||
                        song.title.contains(searchText, true) ||
                        song.artist.contains(searchText, true) ||
                        song.code.contains(searchText, true)

            val matchesLetter =
                selectedLetter == null ||
                        song.title.trim().firstOrNull()
                            ?.uppercaseChar()
                            ?.toString() == selectedLetter

            matchesSearch && matchesLetter
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF171B3D),
                        Navy,
                        Color(0xFF101D36)
                    )
                )
            )
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SONGHUB",
                    color = Accent,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )

                Text(
                    "SONGBOOK REMOTE",
                    color = Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }

            TextButton(onClick = onScoresClick) {
                Text(
                    "SCORES",
                    color = Accent,
                    fontWeight = FontWeight.Bold
                )
            }

            Box {
                TextButton(onClick = onSettingsClick) {
                    Text(
                        "⚙",
                        color = TextWhite,
                        fontSize = 25.sp
                    )
                }

                if (pendingNewSongCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(9.dp)
                            .background(AlertRed, RoundedCornerShape(50))
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color(0xFF202944),
                    RoundedCornerShape(12.dp)
                )
                .clickable { onPCClick() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (connected) Color(0xFF36D78B) else AlertRed,
                        RoundedCornerShape(50)
                    )
            )

            Spacer(Modifier.width(9.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (connected) "PC CONNECTED" else "PC CONNECTION",
                    color = TextWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )

                Text(
                    connectionMessage,
                    color = Muted,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }

            Text(
                "OPEN ›",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = searchText,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search song, artist or number...") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(9.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            AlphabetButton(
                text = "ALL",
                selected = selectedLetter == null,
                onClick = { onLetterChange(null) }
            )

            ('A'..'Z').forEach { letter ->
                val value = letter.toString()

                AlphabetButton(
                    text = value,
                    selected = selectedLetter == value,
                    onClick = { onLetterChange(value) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${filteredSongs.size} SONGS",
                color = Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            TextButton(onClick = onRefreshCatalog) {
                Text(
                    if (pendingNewSongCount > 0) {
                        "↻ UPDATE ($pendingNewSongCount)"
                    } else {
                        "↻ REFRESH"
                    },
                    color = Accent,
                    fontSize = 11.sp
                )
            }
        }

        if (filteredSongs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (songs.isEmpty()) {
                        "No songs loaded. Open Settings to manage catalog."
                    } else {
                        "No songs found."
                    },
                    color = Muted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 10.dp)
            ) {
                items(
                    filteredSongs,
                    key = { it.code }
                ) { song ->
                    SongCard(
                        song = song,
                        onClick = { onSongClick(song) }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            PlaybackButton(
                label = "PLAY",
                modifier = Modifier.weight(1f),
                onClick = onPlay
            )

            PlaybackButton(
                label = "PAUSE",
                modifier = Modifier.weight(1f),
                onClick = onPause
            )

            PlaybackButton(
                label = "NEXT",
                modifier = Modifier.weight(1f),
                onClick = onNext
            )

            PlaybackButton(
                label = "STOP",
                modifier = Modifier.weight(1f),
                onClick = onStop
            )
        }
    }
}

@Composable
private fun PlaybackButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(43.dp),
        contentPadding = PaddingValues(3.dp),
        shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF293653),
            contentColor = Accent
        )
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun AlphabetButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) Accent else Color(0xFF293653),
                shape = RoundedCornerShape(9.dp)
            )
            .clickable { onClick() }
            .padding(
                horizontal = 12.dp,
                vertical = 9.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Navy else TextWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun SongCard(
    song: Song,
    onClick: () -> Unit
) {
    val color = remember(song.code) {
        songColors[
            Math.floorMod(
                song.code.hashCode(),
                songColors.size
            )
        ]
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color(0xFF202944),
                RoundedCornerShape(13.dp)
            )
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(82.dp)
                .height(62.dp)
                .background(
                    color,
                    RoundedCornerShape(11.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formatSongNumber(song.code),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                softWrap = false
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                song.title,
                color = TextWhite,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )

            Spacer(Modifier.height(3.dp))

            Text(
                song.artist,
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }

        Text(
            "›",
            color = color,
            fontSize = 28.sp
        )
    }
}

@Composable
private fun SettingsPage(
    onBack: () -> Unit,
    onShareApp: () -> Unit,
    onCatalogSettings: () -> Unit,
    onPCSettings: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
            .padding(20.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("← BACK", color = Accent)
        }

        Spacer(Modifier.height(15.dp))

        Text(
            "SETTINGS",
            color = TextWhite,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(25.dp))

        SettingsItem(
            title = "PC CONNECTION",
            description = "Connect SongHub Remote to your PC",
            onClick = onPCSettings
        )

        SettingsItem(
            title = "CATALOG MANAGER",
            description = "Select folder, import or refresh songs",
            onClick = onCatalogSettings
        )

        SettingsItem(
            title = "CHECK APP UPDATE",
            description = "Check for the latest SongHub version",
            onClick = onCheckUpdate
        )

        SettingsItem(
            title = "SHARE APP",
            description = "QR code and download link",
            onClick = onShareApp
        )
    }
}

@Composable
private fun SettingsItem(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 11.dp)
            .background(
                Color(0xFF202944),
                RoundedCornerShape(13.dp)
            )
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Text(
            title,
            color = Accent,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(4.dp))

        Text(
            description,
            color = Muted,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ShareAppPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val qrBitmap = remember {
        createQrBitmap(RELEASE_URL).asImageBitmap()
    }

    val versionName = remember {
        getInstalledVersionName(context)
    }

    val versionCode = remember {
        getInstalledVersionCode(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF171B3D),
                        Navy,
                        Color(0xFF101D36)
                    )
                )
            )
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← BACK", color = Accent)
            }

            Spacer(Modifier.weight(1f))

            Text(
                "SHARE APP",
                color = Accent,
                fontSize = 21.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(55.dp))
        }

        Spacer(Modifier.height(22.dp))

        Text(
            "SONGHUB REMOTE",
            color = TextWhite,
            fontSize = 23.sp,
            fontWeight = FontWeight.Black
        )

        Spacer(Modifier.height(15.dp))

        Image(
            bitmap = qrBitmap,
            contentDescription = "SongHub download QR code",
            modifier = Modifier
                .size(230.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(8.dp)
        )

        Spacer(Modifier.height(15.dp))

        Text(
            "SCAN TO DOWNLOAD",
            color = Accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.4.sp
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "SongHub Remote",
            color = TextWhite,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Version $versionName  •  Build $versionCode",
            color = Muted,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(14.dp))

        Text(
            RELEASE_URL,
            color = Color(0xFF8CA8FF),
            fontSize = 11.sp
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Download SongHub Remote:\n$RELEASE_URL"
                    )
                }

                context.startActivity(
                    Intent.createChooser(
                        sendIntent,
                        "Share SongHub Remote"
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = Navy
            )
        ) {
            Text(
                "SHARE LINK",
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun ScoresPage(
    entries: List<ScoreEntry>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
    var selectedLetter by remember {
        mutableStateOf<String?>(null)
    }

    var sortMode by remember {
        mutableStateOf("HIGH")
    }

    var showClearConfirmation by remember {
        mutableStateOf(false)
    }

    val letters = ('A'..'Z').map { it.toString() }

    val displayedEntries = remember(
        entries,
        selectedLetter,
        sortMode
    ) {
        val filtered = entries.filter { entry ->
            selectedLetter == null ||
                    entry.title.trim()
                        .firstOrNull()
                        ?.uppercaseChar()
                        ?.toString() == selectedLetter
        }

        when (sortMode) {
            "LOW" -> filtered.sortedWith(
                compareBy<ScoreEntry> { it.score }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
            )

            "AZ" -> filtered.sortedBy {
                it.title.lowercase(Locale.ROOT)
            }

            else -> filtered.sortedWith(
                compareByDescending<ScoreEntry> { it.score }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF171B3D),
                        Navy,
                        Color(0xFF101D36)
                    )
                )
            )
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(
                    "‹ BACK",
                    color = Accent,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                "SCORES",
                color = Accent,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.weight(1f))

            Text(
                "${entries.size}",
                color = Color(0xFFFFC66D),
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onRefresh,
                contentPadding = PaddingValues(
                    horizontal = 10.dp,
                    vertical = 5.dp
                ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF293653),
                    contentColor = Accent
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "↻ REFRESH",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Text(
            "YOUR SONG PERFORMANCE HISTORY",
            color = Muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp
        )

        Spacer(Modifier.height(14.dp))

        Text(
            "SORT BY",
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ScoreSortButton(
                "A–Z",
                sortMode == "AZ",
                Modifier.weight(1f)
            ) {
                sortMode = "AZ"
            }

            ScoreSortButton(
                "LOW → HIGH",
                sortMode == "LOW",
                Modifier.weight(1.2f)
            ) {
                sortMode = "LOW"
            }

            ScoreSortButton(
                "HIGH → LOW",
                sortMode == "HIGH",
                Modifier.weight(1.2f)
            ) {
                sortMode = "HIGH"
            }
        }

        Spacer(Modifier.height(13.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "FILTER",
                color = Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.width(10.dp))

            Text(
                selectedLetter ?: "ALL SONGS",
                color = Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            AlphabetButton(
                "ALL",
                selectedLetter == null
            ) {
                selectedLetter = null
            }

            letters.forEach { letter ->
                AlphabetButton(
                    letter,
                    selectedLetter == letter
                ) {
                    selectedLetter = letter
                }
            }
        }

        Spacer(Modifier.height(13.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color(0xFF293653),
                    RoundedCornerShape(10.dp)
                )
                .padding(
                    horizontal = 12.dp,
                    vertical = 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SONG",
                color = Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            Text(
                "SCORE",
                color = Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (displayedEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "♪",
                        color = Color(0xFF9168F5),
                        fontSize = 48.sp
                    )

                    Text(
                        if (entries.isEmpty()) {
                            "No scores recorded yet."
                        } else {
                            "No songs found for this letter."
                        },
                        color = Muted,
                        fontSize = 14.sp
                    )

                    Text(
                        "Finish a song on SongHub PC to save its score.",
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(
                    bottom = 12.dp,
                    top = 2.dp
                )
            ) {
                items(
                    displayedEntries,
                    key = { it.id }
                ) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFF202944),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(
                                horizontal = 12.dp,
                                vertical = 12.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                entry.title,
                                color = TextWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2
                            )

                            Text(
                                entry.artist.ifBlank {
                                    "Unknown artist"
                                },
                                color = Color(0xFFB9C6E8),
                                fontSize = 11.sp,
                                maxLines = 1
                            )

                            Text(
                                "NO. ${formatSongNumber(entry.code)}",
                                color = Muted,
                                fontSize = 10.sp
                            )
                        }

                        Text(
                            entry.score.toString(),
                            color = Accent,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                showClearConfirmation = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFB93D55),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                "CLEAR SCORES",
                fontWeight = FontWeight.ExtraBold
            )
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showClearConfirmation = false
            },
            containerColor = Color(0xFF222E50),
            title = {
                Text(
                    "CLEAR ALL SCORES?",
                    color = TextWhite,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Text(
                    "Mabubura ang lahat ng saved score history. Hindi maaapektuhan ang song catalog at PC settings.",
                    color = Muted
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        showClearConfirmation = false
                    }
                ) {
                    Text(
                        "CLEAR",
                        color = Color(0xFFFF7184),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                    }
                ) {
                    Text("CANCEL", color = Accent)
                }
            }
        )
    }
}

@Composable
private fun ScoreSortButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.background(
            if (selected) {
                Accent.copy(alpha = 0.18f)
            } else {
                Color(0xFF293653)
            },
            RoundedCornerShape(9.dp)
        ),
        contentPadding = PaddingValues(
            horizontal = 5.dp,
            vertical = 7.dp
        )
    ) {
        Text(
            label,
            color = if (selected) Accent else TextWhite,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}