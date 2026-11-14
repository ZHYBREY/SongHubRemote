package com.example.songhub_remote

import android.os.Bundle
import android.graphics.Bitmap
import android.net.Uri
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

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
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String
)

private const val CATALOG_URL =
    "https://raw.githubusercontent.com/ZHYBREY/SongHub-Catalog/main/app/src/main/assets/songs_catalog.json"

private const val APP_UPDATE_URL =
    "https://raw.githubusercontent.com/ZHYBREY/SongHub-Catalog/main/app_update.json"

private const val SHARE_CODE_URL =
    "https://github.com/ZHYBREY/SongHubRemote/releases/latest"

// UPDATE NOTIFICATION PREFERENCES

private const val PREF_KNOWN_CATALOG_CODES = "known_catalog_codes"
private const val PREF_PENDING_NEW_SONG_COUNT = "pending_new_song_count"
private const val PREF_PENDING_APP_VERSION_CODE = "pending_app_version_code"
private const val PREF_PENDING_APP_VERSION_NAME = "pending_app_version_name"

private val catalogHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .build()

private val appUpdateHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(180, TimeUnit.SECONDS)
    .build()

private fun getInstalledVersionCode(
    context: android.content.Context
): Int {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        0
    )

    return if (Build.VERSION.SDK_INT >= 28) {
        info.longVersionCode.toInt()
    } else {
        @Suppress("DEPRECATION")
        info.versionCode
    }
}

private fun getInstalledVersionName(
    context: android.content.Context
): String {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        0
    )
    return info.versionName ?: "Unknown"
}

// COMPARE CATALOG SONG CODES AND SAVE PENDING COUNT

private fun recordCatalogChanges(
    prefs: android.content.SharedPreferences,
    newSongs: List<Song>
): Int {
    val latestCodes = newSongs.map { it.code }.toSet()
    val storedCodes = prefs.getStringSet(
        PREF_KNOWN_CATALOG_CODES,
        null
    )

    // First successful fetch establishes the baseline.
    // Existing songs are not counted as new.
    val newCount = if (storedCodes == null) {
        0
    } else {
        (latestCodes - storedCodes).size
    }

    val currentPending = prefs.getInt(
        PREF_PENDING_NEW_SONG_COUNT,
        0
    )

    val nextPending = currentPending + newCount

    prefs.edit()
        .putStringSet(
            PREF_KNOWN_CATALOG_CODES,
            latestCodes.toMutableSet()
        )
        .putInt(
            PREF_PENDING_NEW_SONG_COUNT,
            nextPending
        )
        .apply()

    return nextPending
}

// GENERATE SHARE CODE QR
private fun createShareCodeQr(content: String, size: Int = 720): Bitmap {
    val hints = hashMapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until size) for (y in 0 until size) {
            setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}

// CHECK GITHUB APP UPDATE MANIFEST

private fun fetchAppUpdateInfo(): AppUpdateInfo {
    val url = "$APP_UPDATE_URL?t=${System.currentTimeMillis()}"

    val request = Request.Builder()
        .url(url)
        .header("Cache-Control", "no-cache, no-store")
        .header("Pragma", "no-cache")
        .header("User-Agent", "SongHub-Remote")
        .build()

    appUpdateHttpClient.newCall(request).execute().use { response ->

        if (!response.isSuccessful) {
            throw Exception("GitHub error: HTTP ${response.code}")
        }

        val body = response.body?.string()
            ?: throw Exception("Walang update information na natanggap.")

        val json = JSONObject(body)

        val versionCode = json.optInt("versionCode", 0)
        val versionName = json.optString("versionName", "")
        val apkUrl = json.optString("apkUrl", "")
        val releaseNotes = json.optString(
            "releaseNotes",
            "SongHub Remote update"
        )

        if (versionCode <= 0 || versionName.isBlank()) {
            throw Exception("Invalid app_update.json.")
        }

        if (
            apkUrl.isBlank() ||
            !apkUrl.startsWith("https://")
        ) {
            throw Exception(
                "Hindi pa valid ang APK link sa app_update.json."
            )
        }

        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            releaseNotes = releaseNotes
        )
    }
}

// DOWNLOAD APK UPDATE

private fun downloadAppUpdate(
    context: android.content.Context,
    updateInfo: AppUpdateInfo
): File {

    if (!updateInfo.apkUrl.startsWith("https://")) {
        throw Exception("Invalid APK download URL.")
    }

    val updateFolder = File(context.cacheDir, "updates")

    if (!updateFolder.exists() && !updateFolder.mkdirs()) {
        throw Exception("Hindi makagawa ng update folder.")
    }

    val apkFile = File(updateFolder, "SongHubRemote.apk")
    val tempFile = File(updateFolder, "SongHubRemote.apk.tmp")

    val request = Request.Builder()
        .url(updateInfo.apkUrl)
        .header("User-Agent", "SongHub-Remote")
        .header("Accept", "application/octet-stream")
        .header("Cache-Control", "no-cache")
        .build()

    appUpdateHttpClient.newCall(request).execute().use { response ->

        if (!response.isSuccessful) {
            throw Exception(
                "APK download error: HTTP ${response.code} | ${response.request.url}"
            )
        }

        val body = response.body
            ?: throw Exception("Walang APK file na natanggap.")

        if (body.contentLength() == 0L) {
            throw Exception("Empty APK file.")
        }

        body.byteStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    if (!tempFile.exists() || tempFile.length() < 100_000L) {
        tempFile.delete()
        throw Exception("Invalid o incomplete APK download.")
    }

    if (apkFile.exists()) {
        apkFile.delete()
    }

    if (!tempFile.renameTo(apkFile)) {
        tempFile.copyTo(apkFile, overwrite = true)
        tempFile.delete()
    }

    if (!apkFile.exists() || apkFile.length() < 100_000L) {
        throw Exception("Hindi na-save nang maayos ang APK.")
    }

    return apkFile
}

// INSTALL DOWNLOADED APK

private fun installDownloadedApk(
    context: android.content.Context,
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

    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(
            apkUri,
            "application/vnd.android.package-archive"
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    context.startActivity(installIntent)
}

private fun loadScoreHistory(
    prefs: android.content.SharedPreferences
): List<ScoreEntry> {
    return try {
        val array = JSONArray(
            prefs.getString("score_history", "[]") ?: "[]"
        )

        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue

                add(
                    ScoreEntry(
                        id = item.optString(
                            "id",
                            UUID.randomUUID().toString()
                        ),
                        code = item.optString("code"),
                        title = item.optString("title", "Unknown"),
                        artist = item.optString("artist"),
                        score = item.optInt("score", 0),
                        playedAt = item.optLong("playedAt", 0L)
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
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

private val Navy = Color(0xFF10152B)
private val TextWhite = Color.White
private val Muted = Color(0xFFB5BED5)
private val Accent = Color(0xFF42E8D4)
private val AlertRed = Color(0xFFE45D68)

private val songColors = listOf(
    Color(0xFF16C7C7),
    Color(0xFF9565F5),
    Color(0xFF438BFA),
    Color(0xFFEC65B5),
    Color(0xFFFFA64D),
    Color(0xFF42B883)
)

private val alphabet = ('A'..'Z').map { it.toString() }

private fun formatSongNumber(code: String): String {
    return code.toIntOrNull()?.toString()?.padStart(6, '0') ?: code
}

private fun sortSongsAlphabetically(songs: List<Song>): List<Song> {
    return songs.sortedWith(
        compareBy<Song> {
            val first = it.title.trim().firstOrNull()?.uppercaseChar()
            if (first != null && first in 'A'..'Z') 0 else 1
        }.thenBy {
            it.title.trim().lowercase(Locale.ROOT)
        }
    )
}

// PARSE NORMAL AND NESTED JSON CATALOGS

private fun parseSongs(json: String): List<Song> {
    val trimmed = json.trim()
    val result = LinkedHashMap<String, Song>()

    fun addSong(item: JSONObject) {
        val code = item.optString("code").trim()
        val title = item.optString("title").trim()
        val artist = item.optString("artist").trim()

        if (code.isNotBlank() && title.isNotBlank()) {
            if (!result.containsKey(code)) {
                result[code] = Song(
                    code = code,
                    title = title,
                    artist = artist
                )
            }
        }
    }

    fun addSongArray(array: JSONArray) {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue

            val nested = item.optJSONArray("value")
                ?: item.optJSONArray("songs")
                ?: item.optJSONArray("data")

            if (nested != null) {
                addSongArray(nested)
            } else {
                addSong(item)
            }
        }
    }

    when {
        trimmed.startsWith("[") -> {
            addSongArray(JSONArray(trimmed))
        }

        trimmed.startsWith("{") -> {
            val root = JSONObject(trimmed)

            val array = root.optJSONArray("value")
                ?: root.optJSONArray("songs")
                ?: root.optJSONArray("data")

            if (array != null) {
                addSongArray(array)
            } else {
                throw Exception(
                    "Hindi makita ang song list sa JSON object."
                )
            }
        }

        else -> {
            throw Exception("Invalid JSON format.")
        }
    }

    require(result.isNotEmpty()) {
        "Walang valid songs sa JSON file."
    }

    return result.values.toList()
}

// SAVE DOWNLOADED CATALOG TO INTERNAL STORAGE

private fun saveInternalCatalog(
    context: android.content.Context,
    json: String
) {
    parseSongs(json)

    val target = File(context.filesDir, "songs_catalog.json")
    val temp = File(context.filesDir, "songs_catalog.json.tmp")
    val backup = File(context.filesDir, "songs_catalog_backup.json")

    try {
        temp.writeText(json, Charsets.UTF_8)
        parseSongs(temp.readText(Charsets.UTF_8))

        if (target.exists()) {
            target.copyTo(backup, overwrite = true)
        }

        temp.copyTo(target, overwrite = true)
        parseSongs(target.readText(Charsets.UTF_8))
    } finally {
        temp.delete()
    }
}

// DOWNLOAD LATEST CATALOG FROM GITHUB

private fun updateOnlineCatalog(
    context: android.content.Context,
    folderUri: String?
): List<Song> {

    val latestUrl = "$CATALOG_URL?t=${System.currentTimeMillis()}"

    val request = Request.Builder()
        .url(latestUrl)
        .header("Cache-Control", "no-cache, no-store")
        .header("Pragma", "no-cache")
        .header("User-Agent", "SongHub-Remote")
        .build()

    catalogHttpClient.newCall(request).execute().use { response ->

        if (!response.isSuccessful) {
            throw Exception("GitHub error: HTTP ${response.code}")
        }

        val json = response.body?.string()
            ?: throw Exception("Walang natanggap na catalog.")

        val updatedSongs = parseSongs(json)

        if (!folderUri.isNullOrBlank()) {
            replaceCatalog(
                context,
                Uri.parse(folderUri),
                json
            )
        } else {
            saveInternalCatalog(context, json)
        }

        return updatedSongs
    }
}

// DOCUMENTS / SONGHUB FOLDER

private fun getCatalogDirectory(
    context: android.content.Context,
    treeUri: Uri,
    create: Boolean = false
): DocumentFile {
    val selectedFolder = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw Exception("Hindi mabuksan ang napiling folder.")

    if (selectedFolder.name.equals("SongHub", ignoreCase = true)) {
        return selectedFolder
    }

    var songHubFolder = selectedFolder.findFile("SongHub")

    if (songHubFolder == null && create) {
        songHubFolder = selectedFolder.createDirectory("SongHub")
    }

    if (songHubFolder == null || !songHubFolder.isDirectory) {
        throw Exception("Hindi makita o magawa ang SongHub folder.")
    }

    return songHubFolder
}

// LOAD ALL AVAILABLE CATALOGS AND USE THE LARGEST VALID ONE

private fun loadSongs(
    context: android.content.Context,
    folderUri: String?
): List<Song> {

    val catalogs = mutableListOf<List<Song>>()

    if (!folderUri.isNullOrBlank()) {
        try {
            val folder = getCatalogDirectory(
                context,
                Uri.parse(folderUri)
            )

            val file = folder.findFile("songs_catalog.json")

            if (file != null && file.exists()) {
                val json = context.contentResolver
                    .openInputStream(file.uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }

                if (!json.isNullOrBlank()) {
                    catalogs.add(parseSongs(json))
                }
            }
        } catch (_: Exception) {
        }
    }

    try {
        val cachedFile = File(
            context.filesDir,
            "songs_catalog.json"
        )

        if (cachedFile.exists()) {
            val json = cachedFile.readText(Charsets.UTF_8)
            catalogs.add(parseSongs(json))
        }
    } catch (_: Exception) {
    }

    try {
        val json = context.assets
            .open("songs_catalog.json")
            .bufferedReader()
            .use { it.readText() }

        catalogs.add(parseSongs(json))
    } catch (_: Exception) {
    }

    return catalogs.maxByOrNull { it.size }
        ?: throw Exception("Walang mabasang valid song catalog.")
}

// CREATE INITIAL DOCUMENTS CATALOG

private fun ensureCatalogFile(
    context: android.content.Context,
    parentUri: Uri
) {
    val folder = getCatalogDirectory(
        context,
        parentUri,
        create = true
    )

    if (folder.findFile("songs_catalog.json")?.exists() == true) return

    val json = context.assets
        .open("songs_catalog.json")
        .bufferedReader()
        .use { it.readText() }

    val file = folder.createFile(
        "application/json",
        "songs_catalog.json"
    ) ?: throw Exception("Hindi makagawa ng catalog file.")

    context.contentResolver.openOutputStream(file.uri, "wt")
        ?.bufferedWriter()
        ?.use { it.write(json) }
        ?: throw Exception("Hindi maisulat ang catalog file.")
}

// WRITE DOCUMENT FILE

private fun writeDocumentFile(
    context: android.content.Context,
    file: DocumentFile,
    content: String
) {
    context.contentResolver.openOutputStream(file.uri, "wt")
        ?.bufferedWriter()
        ?.use { it.write(content) }
        ?: throw Exception("Hindi maisulat ang ${file.name ?: "JSON file"}.")
}

// REPLACE CATALOG WITH BACKUP

private fun replaceCatalog(
    context: android.content.Context,
    parentUri: Uri,
    json: String
) {
    parseSongs(json)

    val folder = getCatalogDirectory(
        context,
        parentUri,
        create = true
    )

    val oldFile = folder.findFile("songs_catalog.json")
    val backupFile = folder.findFile("songs_catalog_backup.json")

    var oldContent: String? = null

    if (oldFile != null && oldFile.exists()) {
        oldContent = context.contentResolver
            .openInputStream(oldFile.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw Exception("Hindi mabasa ang kasalukuyang catalog.")

        val backup = backupFile ?: folder.createFile(
            "application/json",
            "songs_catalog_backup.json"
        ) ?: throw Exception("Hindi makagawa ng backup file.")

        writeDocumentFile(context, backup, oldContent)
    }

    val targetFile = oldFile ?: folder.createFile(
        "application/json",
        "songs_catalog.json"
    ) ?: throw Exception("Hindi makagawa ng bagong catalog file.")

    try {
        writeDocumentFile(context, targetFile, json)

        val verification = context.contentResolver
            .openInputStream(targetFile.uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw Exception("Hindi ma-verify ang bagong catalog.")

        parseSongs(verification)

    } catch (e: Exception) {

        if (oldContent != null) {
            try {
                writeDocumentFile(context, targetFile, oldContent)
            } catch (_: Exception) {
            }
        } else {
            targetFile.delete()
        }

        throw e
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
                    surface = Color(0xFF1B2340),
                    onPrimary = Navy,
                    onBackground = TextWhite,
                    onSurface = TextWhite
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
        mutableStateOf(
            prefs.getString("pc_ip", "192.168.10.4") ?: "192.168.10.4"
        )
    }

    var port by remember {
        mutableStateOf(
            prefs.getString("pc_port", "48080") ?: "48080"
        )
    }

    var showPcSettings by remember { mutableStateOf(false) }
    var showCatalogSettings by remember { mutableStateOf(false) }
    var showShareCodeDialog by remember { mutableStateOf(false) }

    var folderUri by remember {
        mutableStateOf(prefs.getString("catalog_folder", null))
    }

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var filteredSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    var showAlphabet by remember { mutableStateOf(false) }
    var selectedLetter by remember { mutableStateOf<String?>(null) }

    val sortedSongs = remember(songs) {
        sortSongsAlphabetically(songs)
    }

    var searchText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    var loadingRotation by remember {
        mutableFloatStateOf(0f)
    }

    // Drive the loader with a small coroutine tick so it keeps rotating
    // even on devices where system animator duration is disabled.
    LaunchedEffect(loading) {
        while (loading) {
            loadingRotation = (loadingRotation + 12f) % 360f
            delay(16L)
        }
    }

    var socket by remember { mutableStateOf<WebSocket?>(null) }
    var connected by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf("Not connected") }

    var message by remember { mutableStateOf("") }
    var selectedSong by remember { mutableStateOf<Song?>(null) }

    var showScoresPage by remember { mutableStateOf(false) }

    var scoreHistory by remember {
        mutableStateOf(loadScoreHistory(prefs))
    }

    var lastPcStatus by remember { mutableStateOf("") }
    var pendingScore by remember { mutableStateOf(0) }
    var pendingSongCode by remember { mutableStateOf("") }
    var pendingSongTitle by remember { mutableStateOf("") }
    var pendingSongArtist by remember { mutableStateOf("") }

    // APP UPDATE STATES

    var checkingAppUpdate by remember { mutableStateOf(false) }
    var downloadingAppUpdate by remember { mutableStateOf(false) }
    var appUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var appUpdateMessage by remember { mutableStateOf("") }
    var showAppUpdateDialog by remember { mutableStateOf(false) }

    var appUpdateStatus by remember { mutableStateOf("Checking app version...") }

    // PENDING UPDATE INDICATORS

    var pendingNewSongCount by remember {
        mutableIntStateOf(
            prefs.getInt(PREF_PENDING_NEW_SONG_COUNT, 0)
        )
    }

    var pendingAppVersionCode by remember {
        mutableIntStateOf(
            prefs.getInt(PREF_PENDING_APP_VERSION_CODE, 0)
        )
    }

    var pendingAppVersionName by remember {
        mutableStateOf(
            prefs.getString(PREF_PENDING_APP_VERSION_NAME, "") ?: ""
        )
    }

    BackHandler(enabled = showShareCodeDialog || showScoresPage) {
        when {
            showShareCodeDialog -> showShareCodeDialog = false
            showScoresPage -> showScoresPage = false
        }
    }

    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // CHECK APP UPDATE

    fun checkForAppUpdate() {
        scope.launch {
            checkingAppUpdate = true
            appUpdateMessage = "Checking GitHub for app updates..."
            appUpdateInfo = null
            showAppUpdateDialog = true

            try {
                val remoteInfo = withContext(Dispatchers.IO) {
                    fetchAppUpdateInfo()
                }

                val installedVersion = withContext(Dispatchers.IO) {
                    getInstalledVersionCode(context)
                }

                if (remoteInfo.versionCode > installedVersion) {

                    appUpdateInfo = remoteInfo
                    appUpdateStatus = "NEW VERSION AVAILABLE: v${remoteInfo.versionName}"

                    pendingAppVersionCode = remoteInfo.versionCode
                    pendingAppVersionName = remoteInfo.versionName

                    prefs.edit()
                        .putInt(
                            PREF_PENDING_APP_VERSION_CODE,
                            remoteInfo.versionCode
                        )
                        .putString(
                            PREF_PENDING_APP_VERSION_NAME,
                            remoteInfo.versionName
                        )
                        .apply()

                    appUpdateMessage =
                        "New version available: ${remoteInfo.versionName}"

                } else {

                    appUpdateInfo = null

                    // GitHub has no newer build: clear any stale update badge.
                    pendingAppVersionCode = 0
                    pendingAppVersionName = ""

                    prefs.edit()
                        .remove(PREF_PENDING_APP_VERSION_CODE)
                        .remove(PREF_PENDING_APP_VERSION_NAME)
                        .apply()

                    appUpdateStatus = "Your app is up to date (v${getInstalledVersionName(context)})"
                    appUpdateMessage = "Your app is up to date. (Build $installedVersion)"
                }

            } catch (e: Exception) {
                appUpdateInfo = null
                pendingAppVersionCode = 0
                pendingAppVersionName = ""
                prefs.edit()
                    .remove(PREF_PENDING_APP_VERSION_CODE)
                    .remove(PREF_PENDING_APP_VERSION_NAME)
                    .apply()
                appUpdateStatus = "Unable to check updates. Tap RETRY."
                appUpdateMessage = "Update check failed: ${e.message}"

            } finally {
                checkingAppUpdate = false
            }
        }
    }

    // SILENTLY CHECK APP UPDATE WHEN SETTINGS OPENS

    LaunchedEffect(showCatalogSettings) {
        if (showCatalogSettings) {
            appUpdateStatus = "Checking for app updates..."
            try {
                val remoteInfo = withContext(Dispatchers.IO) {
                    fetchAppUpdateInfo()
                }
                val installedVersion = withContext(Dispatchers.IO) {
                    getInstalledVersionCode(context)
                }

                if (remoteInfo.versionCode > installedVersion) {
                    appUpdateInfo = remoteInfo
                    appUpdateMessage = ""
                    pendingAppVersionCode = remoteInfo.versionCode
                    pendingAppVersionName = remoteInfo.versionName
                    prefs.edit()
                        .putInt(PREF_PENDING_APP_VERSION_CODE, remoteInfo.versionCode)
                        .putString(PREF_PENDING_APP_VERSION_NAME, remoteInfo.versionName)
                        .apply()
                    appUpdateStatus = "NEW VERSION AVAILABLE: v${remoteInfo.versionName}"
                } else {
                    appUpdateInfo = null
                    appUpdateMessage = ""
                    pendingAppVersionCode = 0
                    pendingAppVersionName = ""
                    prefs.edit()
                        .remove(PREF_PENDING_APP_VERSION_CODE)
                        .remove(PREF_PENDING_APP_VERSION_NAME)
                        .apply()
                    appUpdateStatus = "Your app is up to date (v${getInstalledVersionName(context)})"
                }
            } catch (e: Exception) {
                // Never leave an old red update badge after a failed check.
                appUpdateInfo = null
                pendingAppVersionCode = 0
                pendingAppVersionName = ""
                prefs.edit()
                    .remove(PREF_PENDING_APP_VERSION_CODE)
                    .remove(PREF_PENDING_APP_VERSION_NAME)
                    .apply()
                appUpdateStatus = "Unable to check updates. Tap RETRY."
            }
        }
    }

    // DOWNLOAD APP UPDATE

    fun downloadUpdate() {
        val update = appUpdateInfo ?: return

        scope.launch {
            downloadingAppUpdate = true
            appUpdateMessage =
                "Downloading SongHub Remote ${update.versionName}..."

            try {
                val apk = withContext(Dispatchers.IO) {
                    downloadAppUpdate(context, update)
                }

                downloadedApk = apk
                appUpdateMessage =
                    "Download complete! Ready to install version ${update.versionName}."

            } catch (e: Exception) {
                appUpdateMessage =
                    "Download failed: ${e.message}"

            } finally {
                downloadingAppUpdate = false
            }
        }
    }

    // INSTALL DOWNLOADED UPDATE

    fun installUpdate() {
        val apk = downloadedApk

        if (apk == null || !apk.exists()) {
            appUpdateMessage = "Download the APK first."
            return
        }

        try {
            installDownloadedApk(context, apk)

        } catch (e: Exception) {

            if (e.message == "UNKNOWN_SOURCE_PERMISSION") {
                try {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )

                    activity.startActivity(settingsIntent)

                    appUpdateMessage =
                        "Enable Allow from this source, then return and tap INSTALL UPDATE again."

                } catch (_: Exception) {
                    appUpdateMessage =
                        "Hindi mabuksan ang installation permission settings."
                }

            } else {
                appUpdateMessage =
                    "Install error: ${e.message}"
            }
        }
    }

    // MANUAL ONLINE CATALOG REFRESH

    fun refreshCatalog() {
        scope.launch {
            loading = true
            message = "Checking GitHub for catalog updates..."

            try {
                val updated = withContext(Dispatchers.IO) {
                    updateOnlineCatalog(context, folderUri)
                }

                // Record the latest codes, then clear the notification
                // because the user manually acknowledged the catalog update.
                recordCatalogChanges(prefs, updated)

                prefs.edit()
                    .putInt(PREF_PENDING_NEW_SONG_COUNT, 0)
                    .apply()

                pendingNewSongCount = 0
                songs = updated

                message = "Online catalog updated: ${updated.size} songs."

            } catch (e: Exception) {

                // Keep the pending badge if the manual update fails.
                try {
                    val savedSongs = withContext(Dispatchers.IO) {
                        loadSongs(context, folderUri)
                    }

                    songs = savedSongs
                    message =
                        "Offline mode: saved catalog (${savedSongs.size} songs)."

                } catch (localError: Exception) {
                    message = "Catalog error: ${localError.message}"
                }

            } finally {
                loading = false
            }
        }
    }

    // AUTOMATIC UPDATE ON APP START

    LaunchedEffect(Unit) {

        // Check whether the pending app update has actually been installed.
        try {
            val installedVersion = withContext(Dispatchers.IO) {
                getInstalledVersionCode(context)
            }

            if (
                pendingAppVersionCode > 0 &&
                installedVersion >= pendingAppVersionCode
            ) {
                pendingAppVersionCode = 0
                pendingAppVersionName = ""

                prefs.edit()
                    .remove(PREF_PENDING_APP_VERSION_CODE)
                    .remove(PREF_PENDING_APP_VERSION_NAME)
                    .apply()
            }
        } catch (_: Exception) {
        }

        // Load local catalog first for offline support.
        try {
            songs = withContext(Dispatchers.IO) {
                loadSongs(context, folderUri)
            }

        } catch (e: Exception) {
            message = "Local catalog error: ${e.message}"

        } finally {
            loading = false
        }

        // Automatically fetch GitHub catalog.
        // Do not clear the pending catalog badge here.
        try {
            val updatedSongs = withContext(Dispatchers.IO) {
                updateOnlineCatalog(context, folderUri)
            }

            val pendingCount = withContext(Dispatchers.IO) {
                recordCatalogChanges(prefs, updatedSongs)
            }

            pendingNewSongCount = pendingCount
            songs = updatedSongs

            message =
                "GitHub catalog updated: ${updatedSongs.size} songs."

        } catch (e: Exception) {

            if (songs.isNotEmpty()) {
                message = "Offline mode: saved catalog is active."
            } else {
                message = "Online catalog unavailable: ${e.message}"
            }
        }
    }

    // DOCUMENTS FOLDER PICKER

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->

        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {

                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )

                        ensureCatalogFile(context, uri)
                    }

                    folderUri = uri.toString()

                    prefs.edit()
                        .putString("catalog_folder", uri.toString())
                        .apply()

                    loading = true

                    val updatedSongs = withContext(Dispatchers.IO) {
                        loadSongs(context, uri.toString())
                    }

                    songs = updatedSongs
                    message =
                        "SongHub folder ready: ${updatedSongs.size} songs."

                } catch (e: Exception) {
                    message = "Folder error: ${e.message}"

                } finally {
                    loading = false
                }
            }
        }
    }

    // JSON IMPORT PICKER

    val jsonPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->

        if (uri != null) {
            scope.launch {
                try {
                    val targetFolder = folderUri

                    if (targetFolder.isNullOrBlank()) {
                        message = "Pumili muna ng SongHub folder."
                        return@launch
                    }

                    val importedJson = withContext(Dispatchers.IO) {

                        val json = context.contentResolver
                            .openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: throw Exception("Hindi mabasa ang JSON file.")

                        replaceCatalog(
                            context,
                            Uri.parse(targetFolder),
                            json
                        )

                        json
                    }

                    val updatedSongs = parseSongs(importedJson)
                    songs = updatedSongs
                    message = "Catalog updated: ${updatedSongs.size} songs."

                } catch (e: Exception) {
                    message = "Import failed: ${e.message}"
                }
            }
        }
    }

    // CONNECT TO SONGHUB PC

    fun connectToPC() {

        val cleanIp = ip.trim()
        val cleanPort = port.trim().toIntOrNull()

        if (
            cleanIp.isBlank() ||
            cleanPort == null ||
            cleanPort !in 1..65535
        ) {
            message = "Check your IP address and port."
            return
        }

        socket?.close(1000, "Reconnect")

        connected = false
        connectionStatus = "Connecting..."

        prefs.edit()
            .putString("pc_ip", cleanIp)
            .putString("pc_port", cleanPort.toString())
            .apply()

        val request = Request.Builder()
            .url("ws://$cleanIp:$cleanPort/")
            .header("Origin", "https://songbook.songhub.space")
            .build()

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    activity.runOnUiThread {
                        connected = true
                        connectionStatus = "Connected"
                        message = "Connected to SongHub PC!"

                        lastPcStatus = ""
                        pendingScore = 0
                        pendingSongCode = ""
                        pendingSongTitle = ""
                        pendingSongArtist = ""
                    }
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    activity.runOnUiThread {

                        try {
                            val data = JSONObject(text)

                            when (data.optString("type")) {

                                "score_update" -> {
                                    pendingScore = data.optInt(
                                        "score",
                                        pendingScore
                                    )

                                    message = "Live score: $pendingScore"
                                }

                                "state_update" -> {

                                    val status = data.optString(
                                        "status",
                                        ""
                                    ).uppercase(Locale.ROOT)

                                    val nowPlaying =
                                        data.optJSONObject("now_playing")

                                    if (status == "SCORING") {

                                        val code = data.optString(
                                            "current_song_code"
                                        ).takeIf {
                                            it.isNotBlank()
                                        } ?: nowPlaying
                                            ?.optString("code", "")
                                            .orEmpty()

                                        val title = nowPlaying
                                            ?.optString("title", "")
                                            .orEmpty()

                                        val artist = nowPlaying
                                            ?.optString("artist", "")
                                            .orEmpty()

                                        if (code.isNotBlank()) {
                                            pendingSongCode = code
                                        }

                                        if (
                                            title.isNotBlank() &&
                                            title != "Unknown"
                                        ) {
                                            pendingSongTitle = title
                                        }

                                        if (artist.isNotBlank()) {
                                            pendingSongArtist = artist
                                        }

                                        pendingScore = data.optInt(
                                            "score",
                                            pendingScore
                                        )

                                        if (
                                            pendingSongTitle.isBlank() &&
                                            pendingSongCode.isNotBlank()
                                        ) {
                                            songs.firstOrNull {
                                                it.code == pendingSongCode
                                            }?.let { catalogSong ->
                                                pendingSongTitle =
                                                    catalogSong.title
                                                pendingSongArtist =
                                                    catalogSong.artist
                                            }
                                        }

                                        message =
                                            "Scoring: ${pendingSongTitle.ifBlank { "Song $pendingSongCode" }} — $pendingScore"
                                    }

                                    if (
                                        status == "IDLE" &&
                                        lastPcStatus == "SCORING"
                                    ) {

                                        val title = pendingSongTitle.ifBlank {
                                            songs.firstOrNull {
                                                it.code == pendingSongCode
                                            }?.title ?: "Unknown"
                                        }

                                        val artist = pendingSongArtist.ifBlank {
                                            songs.firstOrNull {
                                                it.code == pendingSongCode
                                            }?.artist.orEmpty()
                                        }

                                        if (
                                            pendingSongCode.isNotBlank() &&
                                            title != "Unknown"
                                        ) {

                                            val entry = ScoreEntry(
                                                id = UUID.randomUUID().toString(),
                                                code = pendingSongCode,
                                                title = title,
                                                artist = artist,
                                                score = pendingScore,
                                                playedAt = System.currentTimeMillis()
                                            )

                                            scoreHistory =
                                                listOf(entry) + scoreHistory

                                            saveScoreHistory(
                                                prefs,
                                                scoreHistory
                                            )

                                            message =
                                                "Saved score: $title — $pendingScore"

                                        } else {
                                            message =
                                                "Song finished, but song details were unavailable."
                                        }

                                        pendingSongCode = ""
                                        pendingSongTitle = ""
                                        pendingSongArtist = ""
                                        pendingScore = 0
                                    }

                                    if (status.isNotBlank()) {
                                        lastPcStatus = status
                                    }

                                    if (
                                        status != "SCORING" &&
                                        status != "IDLE"
                                    ) {
                                        message = "PC: $text"
                                    }
                                }

                                else -> {
                                    message = "PC: $text"
                                }
                            }

                        } catch (_: Exception) {
                            message = "PC: $text"
                        }
                    }
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    activity.runOnUiThread {
                        connected = false
                        connectionStatus = "Disconnected"
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    activity.runOnUiThread {
                        connected = false
                        connectionStatus =
                            "Connection failed: ${t.message ?: "Unknown error"}"
                    }
                }
            }
        )
    }

    // DISCONNECT

    fun disconnectFromPC() {
        socket?.close(1000, "Disconnected")
        socket = null
        connected = false
        connectionStatus = "Not connected"
        message = "Disconnected from PC."
    }

    // SEND PC COMMAND

    fun sendCommand(
        action: String,
        payload: String? = null
    ) {
        val currentSocket = socket

        if (!connected || currentSocket == null) {
            message = "Connect to SongHub PC first."
            return
        }

        val json = JSONObject().apply {
            put("action", action)
            put("payload", payload ?: JSONObject.NULL)
        }

        message = if (currentSocket.send(json.toString())) {
            "Command sent: $action"
        } else {
            "Could not send command."
        }
    }

    // CLOSE SOCKET WHEN APP CLOSES

    DisposableEffect(Unit) {
        onDispose {
            socket?.close(1000, "App closed")
            client.dispatcher.executorService.shutdown()
        }
    }

    // MAIN SONGBOOK PAGE

    if (!showScoresPage) {

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

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Column(modifier = Modifier.weight(1f)) {

                    Text(
                        "SONGHUB",
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 4.sp
                    )

                    Text(
                        "SONGBOOK",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = TextWhite
                    )
                }

                Column(horizontalAlignment = Alignment.End) {

                    Text(
                        "OFFLINE CATALOG",
                        color = Color(0xFFB58BFF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "${filteredSongs.size} SONGS",
                        color = Color(0xFFFFC66D),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = { showScoresPage = true },
                    contentPadding = PaddingValues(
                        horizontal = 11.dp,
                        vertical = 8.dp
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF9168F5),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "SCORES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(Modifier.width(7.dp))

                // SETTINGS GEAR WITH RED UPDATE INDICATOR

                Box(
                    modifier = Modifier
                        .size(43.dp)
                        .background(
                            Color(0xFF293653),
                            RoundedCornerShape(13.dp)
                        )
                        .clickable {
                            showCatalogSettings = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "⚙",
                        color = Accent,
                        fontSize = 27.sp
                    )

                    if (pendingAppVersionCode > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(17.dp)
                                .background(
                                    AlertRed,
                                    RoundedCornerShape(50)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "!",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // PC CONNECTION CARD

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF202B4B)
                ),
                shape = RoundedCornerShape(15.dp)
            ) {

                Column(Modifier.padding(9.dp)) {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(
                                    if (connected) Accent
                                    else Color(0xFFFFB454),
                                    RoundedCornerShape(50)
                                )
                        )

                        Spacer(Modifier.width(8.dp))

                        Text(
                            connectionStatus,
                            color = if (connected) Accent else TextWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        TextButton(
                            onClick = {
                                showPcSettings = !showPcSettings
                            },
                            contentPadding = PaddingValues(
                                horizontal = 7.dp,
                                vertical = 0.dp
                            )
                        ) {
                            Text(
                                if (showPcSettings) "HIDE ▲" else "EDIT ▼",
                                color = Color(0xFFB58BFF),
                                fontSize = 10.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (connected) disconnectFromPC()
                                else connectToPC()
                            },
                            contentPadding = PaddingValues(
                                horizontal = 12.dp,
                                vertical = 3.dp
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (connected)
                                    AlertRed
                                else Accent,
                                contentColor = if (connected)
                                    Color.White
                                else Navy
                            )
                        ) {
                            Text(
                                if (connected) "OFF" else "CONNECT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (showPcSettings) {

                        Spacer(Modifier.height(7.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {

                            OutlinedTextField(
                                value = ip,
                                onValueChange = { ip = it },
                                modifier = Modifier.weight(2f),
                                enabled = !connected,
                                label = { Text("PC IP") },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 13.sp
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Muted
                                )
                            )

                            OutlinedTextField(
                                value = port,
                                onValueChange = {
                                    port = it.filter(Char::isDigit)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !connected,
                                label = { Text("Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 13.sp
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextWhite,
                                    unfocusedTextColor = TextWhite,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Muted
                                )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(11.dp))

            // SEARCH

            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Search title, artist or song number...",
                        fontSize = 13.sp,
                        color = Muted
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 15.sp,
                    color = TextWhite
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color(0xFF52618D),
                    cursorColor = Accent
                )
            )

            Spacer(Modifier.height(6.dp))

            // A-Z FILTER

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                TextButton(
                    onClick = {
                        showAlphabet = !showAlphabet
                    },
                    contentPadding = PaddingValues(
                        horizontal = 10.dp,
                        vertical = 3.dp
                    )
                ) {
                    Text(
                        if (showAlphabet) "A–Z  ▲" else "A–Z  ▼",
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    if (selectedLetter == null) "All letters"
                    else "Showing letter $selectedLetter",
                    color = Muted,
                    fontSize = 11.sp
                )
            }

            if (showAlphabet) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    AlphabetButton(
                        text = "ALL",
                        selected = selectedLetter == null,
                        onClick = { selectedLetter = null }
                    )

                    alphabet.forEach { letter ->
                        AlphabetButton(
                            text = letter,
                            selected = selectedLetter == letter,
                            onClick = { selectedLetter = letter }
                        )
                    }
                }
            }

            // SEARCH PRIORITY

            LaunchedEffect(
                searchText,
                sortedSongs,
                selectedLetter
            ) {

                delay(180)

                val term = searchText.trim()
                    .lowercase(Locale.ROOT)

                filteredSongs = withContext(Dispatchers.Default) {

                    sortedSongs
                        .mapIndexedNotNull { index, song ->

                            val title = song.title.trim()
                                .lowercase(Locale.ROOT)

                            val artist = song.artist
                                .lowercase(Locale.ROOT)

                            val code = song.code
                                .lowercase(Locale.ROOT)

                            val firstLetter = song.title.trim()
                                .firstOrNull()
                                ?.uppercaseChar()
                                ?.toString()

                            val matchesLetter =
                                selectedLetter == null ||
                                        firstLetter == selectedLetter

                            if (!matchesLetter) {
                                return@mapIndexedNotNull null
                            }

                            val rank = when {

                                term.isBlank() -> 0
                                title == term -> 0
                                title.startsWith(term) -> 1
                                title.contains(" $term") -> 2
                                title.contains(term) -> 3
                                artist.startsWith(term) -> 4
                                artist.contains(term) -> 5
                                code.contains(term) -> 6

                                else -> return@mapIndexedNotNull null
                            }

                            Triple(rank, index, song)
                        }
                        .sortedWith(
                            compareBy<Triple<Int, Int, Song>> { it.first }
                                .thenBy { it.second }
                        )
                        .map { it.third }
                }
            }

            // SONG LIST

            when {

                loading -> {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(
                            modifier = Modifier.size(42.dp)
                        ) {
                            // Draw the moving arc directly from Compose state.
                            // Changing startAngle forces the visible arc to advance.
                            drawArc(
                                color = Accent,
                                startAngle = loadingRotation - 90f,
                                sweepAngle = 105f,
                                useCenter = false,
                                style = Stroke(
                                    width = 4.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            )
                        }
                    }
                }

                filteredSongs.isEmpty() -> {

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No songs found.",
                            color = Muted,
                            fontSize = 16.sp
                        )
                    }
                }

                else -> {

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(
                            top = 2.dp,
                            bottom = 10.dp
                        )
                    ) {

                        items(
                            items = filteredSongs,
                            key = { it.code },
                            contentType = { "song" }
                        ) { song ->

                            SongCard(
                                song = song,
                                onClick = {
                                    selectedSong = song
                                }
                            )
                        }
                    }
                }
            }

            // PLAYBACK CONTROLS

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF202B4B)
                ),
                shape = RoundedCornerShape(15.dp)
            ) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(7.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Button(
                        onClick = { sendCommand("play_pause") },
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF36CFC1),
                            contentColor = Navy
                        )
                    ) {
                        Text(
                            "PLAY / PAUSE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.width(5.dp))

                    Button(
                        onClick = { sendCommand("next") },
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF9168F5)
                        )
                    ) {
                        Text(
                            "NEXT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.width(5.dp))

                    Button(
                        onClick = { sendCommand("stop") },
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AlertRed
                        )
                    ) {
                        Text(
                            "STOP",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (message.isNotBlank()) {

                Text(
                    text = message,
                    color = Accent,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
            }
        }

    } else {

        ScoresPage(
            entries = scoreHistory,
            onBack = {
                showScoresPage = false
            },
            onRefresh = {
                scoreHistory = loadScoreHistory(prefs)
                message =
                    "Score history refreshed: ${scoreHistory.size} records."
            },
            onClear = {
                scoreHistory = emptyList()
                saveScoreHistory(prefs, emptyList())
                message = "Score history cleared."
            }
        )
    }

    // CATALOG SETTINGS

    if (showCatalogSettings) {

        AlertDialog(
            onDismissRequest = {
                showCatalogSettings = false
            },
            containerColor = Color(0xFF222E50),
            title = {
                Text(
                    "SONGHUB SETTINGS",
                    color = Accent,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {

                Column(
                    modifier = Modifier.heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    Text(
                        "Manage your song catalog and app updates.",
                        color = Muted,
                        fontSize = 13.sp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFF17213D),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "CURRENT APP VERSION",
                                color = Muted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Version ${getInstalledVersionName(context)}",
                                color = Accent,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Text(
                            "BUILD ${getInstalledVersionCode(context)}",
                            color = TextWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { showShareCodeDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Navy
                        )
                    ) {
                        Text("▦  SHARE CODE", fontWeight = FontWeight.ExtraBold)
                    }

                    HorizontalDivider(color = Color(0xFF52618D))

                    Text(
                        "ACTIVE CATALOG",
                        color = Color(0xFFB58BFF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        if (folderUri == null) {
                            "Internal catalog / APK assets"
                        } else {
                            "Documents / SongHub / songs_catalog.json"
                        },
                        color = TextWhite,
                        fontSize = 13.sp
                    )

                    Text(
                        "TOTAL SONGS: ${songs.size}",
                        color = Color(0xFFFFC66D),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Button(
                        onClick = {
                            folderPicker.launch(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF34466F)
                        )
                    ) {
                        Text("📁  CHOOSE DOCUMENTS FOLDER")
                    }

                    Button(
                        onClick = {

                            if (folderUri == null) {
                                message = "Pumili muna ng SongHub folder."
                            } else {
                                jsonPicker.launch(
                                    arrayOf(
                                        "application/json",
                                        "text/*",
                                        "*/*"
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF9168F5)
                        )
                    ) {
                        Text("＋  IMPORT / REPLACE JSON")
                    }

                    // CATALOG UPDATE BUTTON WITH NEW SONG COUNT

                    Button(
                        onClick = {
                            refreshCatalog()
                        },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Navy
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (loading) {
                                    "CHECKING CATALOG..."
                                } else {
                                    "↻  UPDATE SONG CATALOG"
                                },
                                fontWeight = FontWeight.ExtraBold
                            )

                            if (!loading && pendingNewSongCount > 0) {
                                Spacer(Modifier.width(9.dp))

                                Box(
                                    modifier = Modifier
                                        .background(
                                            AlertRed,
                                            RoundedCornerShape(50)
                                        )
                                        .padding(
                                            horizontal = 8.dp,
                                            vertical = 3.dp
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        pendingNewSongCount.toString(),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }

                    if (pendingNewSongCount > 0) {
                        Text(
                            "$pendingNewSongCount new song(s) detected from GitHub. Tap UPDATE SONG CATALOG to acknowledge.",
                            color = Color(0xFFFFA0AA),
                            fontSize = 11.sp
                        )
                    }

                    HorizontalDivider(color = Color(0xFF52618D))

                    // APP UPDATE HEADING WITH VERSION INDICATOR

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "APP UPDATE",
                            color = Color(0xFFB58BFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.weight(1f)
                        )

                        if (pendingAppVersionName.isNotBlank()) {
                            Text(
                                "NEW: v$pendingAppVersionName",
                                color = Accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    if (pendingAppVersionCode > 0 || appUpdateStatus.startsWith("Unable")) {
                        Button(
                            onClick = { checkForAppUpdate() },
                            enabled = !checkingAppUpdate && !downloadingAppUpdate,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF34466F)
                            )
                        ) {
                            Text(
                                if (checkingAppUpdate) "CHECKING APP UPDATE..." else
                                    if (appUpdateStatus.startsWith("Unable")) "RETRY APP UPDATE CHECK" else "CHECK APP UPDATE",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            appUpdateStatus,
                            color = if (appUpdateStatus.startsWith("Your app is up to date")) Accent else Muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (appUpdateMessage.isNotBlank()) {
                        Text(
                            appUpdateMessage,
                            color = if (appUpdateInfo == null &&
                                appUpdateMessage.startsWith("Your app is up to date")) {
                                Accent
                            } else {
                                Muted
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (appUpdateInfo != null) {

                        Text(
                            "NEW VERSION: ${appUpdateInfo!!.versionName}",
                            color = Accent,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )

                        Text(
                            appUpdateInfo!!.releaseNotes,
                            color = Muted,
                            fontSize = 12.sp
                        )

                        Button(
                            onClick = {
                                downloadUpdate()
                            },
                            enabled = !downloadingAppUpdate,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent,
                                contentColor = Navy
                            )
                        ) {
                            Text(
                                if (downloadingAppUpdate) {
                                    "DOWNLOADING APK..."
                                } else {
                                    "DOWNLOAD APP UPDATE"
                                },
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    if (downloadedApk != null) {

                        Button(
                            onClick = {
                                installUpdate()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF36B879),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                "INSTALL DOWNLOADED UPDATE",
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Text(
                        "The app checks GitHub for new versions. Android will ask you to confirm installation. Your existing app data should remain when the update uses the same package name and signing key.",
                        color = Muted,
                        fontSize = 10.sp
                    )

                    HorizontalDivider(color = Color(0xFF52618D))

                    Text(
                        "Automatic na iche-check ang GitHub catalog tuwing bubuksan ang app. Gagana pa rin ang saved catalog kapag offline.",
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCatalogSettings = false
                    }
                ) {
                    Text("DONE", color = Accent)
                }
            }
        )
    }

    // SHARE CODE QR DIALOG
    if (showShareCodeDialog) {
        val qrBitmap = remember { createShareCodeQr(SHARE_CODE_URL).asImageBitmap() }
        AlertDialog(
            onDismissRequest = { showShareCodeDialog = false },
            containerColor = Color(0xFF222E50),
            title = {
                Text("SONGHUB SHARE CODE", color = Accent, fontWeight = FontWeight.ExtraBold)
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "SCAN TO DOWNLOAD SONGHUB REMOTE",
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(15.dp))
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "SongHub Share Code QR",
                        modifier = Modifier
                            .size(230.dp)
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Scan this QR code to open the latest SongHub Remote release.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showShareCodeDialog = false }) {
                    Text("CLOSE", color = Accent)
                }
            }
        )
    }

    // APP UPDATE RESULT DIALOG

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
                            onClick = {
                                downloadUpdate()
                            },
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
                            onClick = {
                                installUpdate()
                            },
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
                    onClick = {
                        showAppUpdateDialog = false
                    },
                    enabled = !checkingAppUpdate
                ) {
                    Text("CLOSE", color = Accent)
                }
            }
        )
    }

    // SELECTED SONG DIALOG

    selectedSong?.let { song ->

        AlertDialog(
            onDismissRequest = {
                selectedSong = null
            },
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
                    Text(
                        "PLAY NOW",
                        fontWeight = FontWeight.Bold
                    )
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
                        Text(
                            "RESERVE",
                            color = Color(0xFFB58BFF)
                        )
                    }

                    TextButton(
                        onClick = {
                            selectedSong = null
                        }
                    ) {
                        Text(
                            "CANCEL",
                            color = Muted
                        )
                    }
                }
            }
        )
    }
}

// ALPHABET BUTTON

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
            .clickable {
                onClick()
            }
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

// SONG CARD

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
            .clickable {
                onClick()
            }
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

// SCORES PAGE

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
                    .thenBy {
                        it.title.lowercase(Locale.ROOT)
                    }
            )

            "AZ" -> filtered.sortedBy {
                it.title.lowercase(Locale.ROOT)
            }

            else -> filtered.sortedWith(
                compareByDescending<ScoreEntry> { it.score }
                    .thenBy {
                        it.title.lowercase(Locale.ROOT)
                    }
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

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

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

    // CLEAR CONFIRMATION

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
                    Text(
                        "CANCEL",
                        color = Accent
                    )
                }
            }
        )
    }
}

// SCORE SORT BUTTON

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
