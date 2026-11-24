/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pedro.streamer

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.GridView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.streamer.file.FromFileActivity
import com.pedro.streamer.oldapi.OldApiActivity
import com.pedro.streamer.rotation.RotationActivity
import com.pedro.streamer.screen.ScreenActivity
import com.pedro.streamer.utils.ActivityLink
import com.pedro.streamer.utils.ImageAdapter
import com.pedro.streamer.utils.fitAppPadding
import com.pedro.streamer.utils.toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

  private lateinit var list: GridView
  private lateinit var homeContent: android.view.View
  private lateinit var infoContent: android.view.View
  private lateinit var currentVersionText: TextView
  private lateinit var updateStatusText: TextView
  private lateinit var changelogText: TextView
  private lateinit var updateButton: Button
  private val activities: MutableList<ActivityLink> = mutableListOf()
  private var pendingIntent: Intent? = null
  private val ioExecutor = Executors.newSingleThreadExecutor()
  private val preferences by lazy { getSharedPreferences("app_updates", Context.MODE_PRIVATE) }
  private val downloadManager by lazy { getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }
  private var receiverRegistered = false
  private val downloadRefreshHandler = Handler(Looper.getMainLooper())
  private var downloadRefreshAttempts = 0
  private val downloadStatusPoll = object : Runnable {
    override fun run() {
      val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
      if (id == -1L) return
      val status = getDownloadStatus(id)
      when (status) {
        DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED -> {
          downloadRefreshAttempts = 0
          refreshDownloadState()
        }
        else -> {
          if (downloadRefreshAttempts < 20) {
            downloadRefreshAttempts += 1
            refreshDownloadState()
            downloadRefreshHandler.postDelayed(this, 500L)
          } else {
            downloadRefreshAttempts = 0
            refreshDownloadState()
          }
        }
      }
    }
  }

  private val cameraPermissionsLauncher =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      val granted = result[Manifest.permission.CAMERA] == true &&
          result[Manifest.permission.RECORD_AUDIO] == true
      val intent = pendingIntent
      pendingIntent = null
      if (granted && intent != null) {
        startActivity(intent)
        transitionAnim(false)
      } else {
        toast("Camera and microphone permissions are required for camera streaming")
      }
    }

  private val downloadReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
      val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
      if (completedId == preferences.getLong(KEY_DOWNLOAD_ID, -1L)) {
        downloadRefreshAttempts = 0
        downloadRefreshHandler.removeCallbacks(downloadStatusPoll)
        downloadRefreshHandler.postDelayed(downloadStatusPoll, 300L)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    fitAppPadding()

    homeContent = findViewById(R.id.home_content)
    infoContent = findViewById(R.id.info_content)
    currentVersionText = findViewById(R.id.tv_current_version)
    updateStatusText = findViewById(R.id.tv_update_status)
    changelogText = findViewById(R.id.tv_changelog)
    updateButton = findViewById(R.id.b_update)
    list = findViewById(R.id.list)

    findViewById<TextView>(R.id.tv_version).text =
      getString(R.string.version, BuildConfig.VERSION_NAME)

    findViewById<Button>(R.id.tab_home).setOnClickListener { showTab(false) }
    findViewById<Button>(R.id.tab_info).setOnClickListener { showTab(true) }
    showTab(false)
    findViewById<android.view.View>(R.id.link_github).setOnClickListener {
      openUrl(REPOSITORY_URL)
    }
    updateButton.setOnClickListener { onUpdateButtonClicked() }

    currentVersionText.text = "Versión actual: ${BuildConfig.VERSION_NAME}"
    createList()
    setListAdapter(activities)
    registerDownloadReceiver()
    refreshDownloadState()
    scheduleDownloadStatusPolling()
    loadChangelog()
  }

  override fun onResume() {
    super.onResume()
    cleanupInstalledUpdate()
    refreshDownloadState()
    scheduleDownloadStatusPolling()
  }

  override fun onDestroy() {
    if (receiverRegistered) {
      unregisterReceiver(downloadReceiver)
      receiverRegistered = false
    }
    downloadRefreshHandler.removeCallbacks(downloadStatusPoll)
    ioExecutor.shutdown()
    super.onDestroy()
  }

  private fun showTab(showInfo: Boolean) {
    homeContent.visibility = if (showInfo) android.view.View.GONE else android.view.View.VISIBLE
    infoContent.visibility = if (showInfo) android.view.View.VISIBLE else android.view.View.GONE
    val homeTab = findViewById<Button>(R.id.tab_home)
    val infoTab = findViewById<Button>(R.id.tab_info)
    homeTab.setBackgroundResource(R.drawable.button_outline_black)
    infoTab.setBackgroundResource(R.drawable.button_outline_black)
    homeTab.setTextColor(ContextCompat.getColor(this, R.color.black))
    infoTab.setTextColor(ContextCompat.getColor(this, R.color.black))
  }

  @Suppress("DEPRECATION")
  private fun registerDownloadReceiver() {
    val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
    } else {
      registerReceiver(downloadReceiver, filter)
    }
    receiverRegistered = true
  }

  private fun onUpdateButtonClicked() {
    val downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
    if (downloadId != -1L) {
      val status = getDownloadStatus(downloadId)
      when (status) {
        DownloadManager.STATUS_SUCCESSFUL -> installDownloadedApk(downloadId)
        DownloadManager.STATUS_PENDING,
        DownloadManager.STATUS_RUNNING,
        DownloadManager.STATUS_PAUSED -> toast("La descarga todavía está en curso")
        else -> checkForUpdates()
      }
    } else {
      checkForUpdates()
    }
  }

  private fun checkForUpdates() {
    updateButton.isEnabled = false
    updateButton.text = "BUSCANDO..."
    updateStatusText.text = "Comprobando las últimas versiones publicadas..."
    ioExecutor.execute {
      try {
        val json = JSONObject(readUrl(LATEST_RELEASE_URL))
        val tag = json.optString("tag_name")
        val latestBuild = tag.removePrefix("v").toIntOrNull()
          ?: throw IllegalStateException("La versión publicada no tiene un tag válido")
        val releaseName = json.optString("name", "Tinchodoko Streaming")
        val assets = json.getJSONArray("assets")
        var apkUrl: String? = null
        var apkName: String? = null
        for (index in 0 until assets.length()) {
          val asset = assets.getJSONObject(index)
          val name = asset.optString("name")
          val url = asset.optString("browser_download_url")
          if (name.endsWith(".apk", ignoreCase = true) &&
            name.startsWith("Streaming-v1.0.") &&
            url.startsWith("https://github.com/Tinchodoko/Streaming/releases/download/")
          ) {
            apkName = name
            apkUrl = url
            break
          }
        }
        if (apkUrl == null || apkName == null) {
          throw IllegalStateException("La última Release no contiene el APK oficial")
        }
        runOnUiThread {
          updateButton.isEnabled = true
          if (latestBuild > BuildConfig.VERSION_CODE) {
            val version = "1.0.$latestBuild"
            updateStatusText.text = "Hay una nueva versión disponible: $version"
            AlertDialog.Builder(this)
              .setTitle("Nueva versión disponible")
              .setMessage("Se encontró $releaseName ($version). ¿Quieres descargarla?")
              .setPositiveButton("SÍ, DESCARGAR") { _, _ ->
                startApkDownload(apkUrl!!, apkName!!, latestBuild, version)
              }
              .setNegativeButton("NO") { _, _ ->
                updateButton.text = "BUSCAR ACTUALIZACIÓN"
              }
              .show()
          } else {
            updateStatusText.text = "Tienes la última versión"
            updateButton.text = "BUSCAR ACTUALIZACIÓN"
            toast("Tienes la última versión")
          }
        }
      } catch (error: Exception) {
        runOnUiThread {
          updateButton.isEnabled = true
          updateButton.text = "BUSCAR ACTUALIZACIÓN"
          updateStatusText.text = "No se pudo comprobar la actualización."
          toast("Error al buscar actualizaciones: ${error.message ?: "conexión no disponible"}")
        }
      }
    }
  }

  private fun startApkDownload(url: String, fileName: String, build: Int, version: String) {
    try {
      val request = DownloadManager.Request(Uri.parse(url))
        .setTitle("Tinchodoko Streaming $version")
        .setDescription("Descargando actualización")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
        .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
      val id = downloadManager.enqueue(request)
      preferences.edit()
        .putLong(KEY_DOWNLOAD_ID, id)
        .putInt(KEY_DOWNLOAD_VERSION, build)
        .putString(KEY_DOWNLOAD_NAME, version)
        .apply()
      updateButton.text = "DESCARGANDO..."
      updateButton.isEnabled = false
      updateStatusText.text = "Descargando $version. Recibirás una notificación al finalizar."
      downloadRefreshAttempts = 0
      downloadRefreshHandler.removeCallbacks(downloadStatusPoll)
      downloadRefreshHandler.postDelayed(downloadStatusPoll, 750L)
      toast("Descarga iniciada")
    } catch (error: Exception) {
      updateButton.isEnabled = true
      updateButton.text = "BUSCAR ACTUALIZACIÓN"
      toast("No se pudo iniciar la descarga: ${error.message}")
    }
  }

  private fun refreshDownloadState() {
    val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
    if (id == -1L) {
      updateButton.isEnabled = true
      updateButton.text = "BUSCAR ACTUALIZACIÓN"
      return
    }
    when (getDownloadStatus(id)) {
      DownloadManager.STATUS_SUCCESSFUL -> {
        updateButton.isEnabled = true
        updateButton.text = "ACTUALIZAR"
        val version = preferences.getString(KEY_DOWNLOAD_NAME, "") ?: ""
        updateStatusText.text = "Actualización $version descargada. Pulsa ACTUALIZAR para continuar."
      }
      DownloadManager.STATUS_PENDING,
      DownloadManager.STATUS_RUNNING,
      DownloadManager.STATUS_PAUSED -> {
        updateButton.isEnabled = false
        updateButton.text = "DESCARGANDO..."
      }
      -1 -> {
        updateButton.isEnabled = false
        updateButton.text = "VERIFICANDO..."
        updateStatusText.text = "Verificando el estado de la descarga..."
      }
      DownloadManager.STATUS_FAILED -> {
        downloadManager.remove(id)
        clearDownloadState()
        updateButton.isEnabled = true
        updateButton.text = "BUSCAR ACTUALIZACIÓN"
        updateStatusText.text = "La descarga no se completó. Puedes intentarlo nuevamente."
      }
      else -> {
        downloadManager.remove(id)
        clearDownloadState()
        updateButton.isEnabled = true
        updateButton.text = "BUSCAR ACTUALIZACIÓN"
        updateStatusText.text = "No hay una descarga pendiente."
      }
    }
  }

  private fun scheduleDownloadStatusPolling() {
    val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
    if (id == -1L) return
    val status = getDownloadStatus(id)
    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
      refreshDownloadState()
      return
    }
    downloadRefreshAttempts = 0
    downloadRefreshHandler.removeCallbacks(downloadStatusPoll)
    downloadRefreshHandler.postDelayed(downloadStatusPoll, 500L)
  }

  private fun getDownloadStatus(id: Long): Int {
    val query = DownloadManager.Query().setFilterById(id)
    val cursor = downloadManager.query(query) ?: return -1
    cursor.use {
      if (!it.moveToFirst()) return -1
      val index = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
      return if (index >= 0) it.getInt(index) else -1
    }
  }

  private fun installDownloadedApk(id: Long) {
    val uri = downloadManager.getUriForDownloadedFile(id)
    if (uri == null) {
      toast("No se encontró el APK descargado. Vuelve a buscar la actualización.")
      downloadManager.remove(id)
      clearDownloadState()
      refreshDownloadState()
      return
    }
    try {
      val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      startActivity(installIntent)
    } catch (error: Exception) {
      toast("Android no pudo abrir el instalador: ${error.message}")
    }
  }

  private fun cleanupInstalledUpdate() {
    val id = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
    val downloadedBuild = preferences.getInt(KEY_DOWNLOAD_VERSION, -1)
    if (id != -1L && downloadedBuild > 0 && BuildConfig.VERSION_CODE >= downloadedBuild) {
      downloadManager.remove(id)
      clearDownloadState()
      updateStatusText.text = "Actualización instalada correctamente."
      updateButton.text = "BUSCAR ACTUALIZACIÓN"
      updateButton.isEnabled = true
      currentVersionText.text = "Versión actual: ${BuildConfig.VERSION_NAME}"
    }
  }

  private fun clearDownloadState() {
    preferences.edit()
      .remove(KEY_DOWNLOAD_ID)
      .remove(KEY_DOWNLOAD_VERSION)
      .remove(KEY_DOWNLOAD_NAME)
      .apply()
  }

  private fun loadChangelog() {
    ioExecutor.execute {
      try {
        val content = readUrl(CHANGELOG_URL).trim()
        runOnUiThread {
          changelogText.text = if (content.isBlank()) "Todavía no hay cambios publicados." else content
        }
      } catch (_: Exception) {
        runOnUiThread {
          changelogText.text = "No se pudo cargar el historial. Comprueba tu conexión."
        }
      }
    }
  }

  private fun readUrl(address: String): String {
    val connection = URL(address).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 12000
    connection.readTimeout = 15000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", "Tinchodoko-Streaming-Android")
    try {
      val code = connection.responseCode
      if (code !in 200..299) throw IllegalStateException("HTTP $code")
      return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
      connection.disconnect()
    }
  }

  private fun openUrl(address: String) {
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
    } catch (_: Exception) {
      toast("No se pudo abrir el enlace")
    }
  }

  @Suppress("DEPRECATION")
  private fun transitionAnim(isOpen: Boolean) {
    if (Build.VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val type = if (isOpen) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE
      overrideActivityTransition(type, R.anim.slide_in, R.anim.slide_out)
    } else {
      overridePendingTransition(R.anim.slide_in, R.anim.slide_out)
    }
  }

  @SuppressLint("NewApi")
  private fun createList() {
    activities.add(ActivityLink(Intent(this, OldApiActivity::class.java), getString(R.string.old_api), VERSION_CODES.JELLY_BEAN))
    activities.add(ActivityLink(Intent(this, FromFileActivity::class.java), getString(R.string.from_file), VERSION_CODES.JELLY_BEAN_MR2))
    activities.add(ActivityLink(Intent(this, ScreenActivity::class.java), getString(R.string.display), VERSION_CODES.LOLLIPOP))
    activities.add(ActivityLink(Intent(this, RotationActivity::class.java), getString(R.string.rotation_rtmp), VERSION_CODES.LOLLIPOP))
  }

  private fun setListAdapter(activities: List<ActivityLink>) {
    list.adapter = ImageAdapter(activities)
    list.onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
      val link = activities[position]
      if (Build.VERSION.SDK_INT < link.minSdk) showMinSdkError(link.minSdk) else openActivity(link.intent)
    }
  }

  private fun openActivity(intent: Intent) {
    val className = intent.component?.className
    val requiresCamera = className == OldApiActivity::class.java.name ||
        className == RotationActivity::class.java.name
    if (!requiresCamera || Build.VERSION.SDK_INT < VERSION_CODES.M) {
      startActivity(intent)
      transitionAnim(false)
      return
    }
    val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val microphoneGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    if (cameraGranted && microphoneGranted) {
      startActivity(intent)
      transitionAnim(false)
    } else {
      pendingIntent = intent
      val requested = mutableListOf<String>()
      if (!cameraGranted) requested.add(Manifest.permission.CAMERA)
      if (!microphoneGranted) requested.add(Manifest.permission.RECORD_AUDIO)
      cameraPermissionsLauncher.launch(requested.toTypedArray())
    }
  }

  private fun showMinSdkError(minSdk: Int) {
    val named = when (minSdk) {
      VERSION_CODES.JELLY_BEAN_MR2 -> "JELLY_BEAN_MR2"
      VERSION_CODES.LOLLIPOP -> "LOLLIPOP"
      else -> "JELLY_BEAN"
    }
    toast("You need min Android $named (API $minSdk)")
  }

  companion object {
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOAD_VERSION = "download_version"
    private const val KEY_DOWNLOAD_NAME = "download_name"
    private const val REPOSITORY_URL = "https://github.com/Tinchodoko/Streaming"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Tinchodoko/Streaming/releases/latest"
    private const val CHANGELOG_URL = "https://raw.githubusercontent.com/Tinchodoko/Streaming/main/.github/releases"
  }
}
