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

package com.pedro.streamer.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.pedro.streamer.R
import com.pedro.streamer.utils.PathUtils
import com.pedro.streamer.utils.toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * Basic Screen service streaming implementation
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class ScreenService: Service(), ConnectChecker {

  companion object {
    private const val TAG = "DisplayService"
    private const val CHANNEL_ID = "DisplayStreamChannel"
    const val NOTIFY_ID = 123456
    private const val ACTION_STOP_CAPTURE = "com.pedro.streamer.screen.STOP_CAPTURE"
    var INSTANCE: ScreenService? = null
  }

  private var notificationManager: NotificationManager? = null
  private lateinit var genericStream: GenericStream
  private var mediaProjection: MediaProjection? = null
  private val mediaProjectionManager: MediaProjectionManager by lazy {
    applicationContext.getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
  }
  private var callback: ConnectChecker? = null
  private val width = 640
  private val height = 480
  private val vBitrate = 800 * 1000
  private var rotation = 90 //0 for landscape or 90 for portrait
  private val sampleRate = 44100
  private val isStereo = false
  private val aBitrate = 64 * 1000
  private var prepared = false
  private var recordPath = ""
  private var selectedAudioSource: Int = R.id.audio_source_microphone

  override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "RTP Display service create")
    notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL_ID, CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH)
      notificationManager?.createNotificationChannel(channel)
    }
    genericStream = GenericStream(baseContext, this, NoVideoSource(), NoAudioSource()).apply {
      // MediaProjection only produces frames when the screen changes. Keep a stable frame cadence.
      getGlInterface().setForceRender(true, 15)
    }
    // Do not initialize the microphone here: Android runtime permission is requested only
    // when the user starts screen capture. Prepare a silent AAC track and attach the
    // selected real audio source after MediaProjection permission has been granted.
    INSTANCE = this
    prepared = prepareEncoders()
    if (!prepared) {
      toast("No se pudieron preparar los codificadores. Consulta el diagnóstico de SCREEN.")
    }
  }

  private data class VideoProfile(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

  /**
   * Try conservative H.264/AAC profiles in descending quality order.
   * Audio is initially prepared with NoAudioSource so no microphone permission
   * or hardware AudioRecord is needed until the user chooses an audio source.
   */
  private fun prepareEncoders(): Boolean {
    val videoProfiles = listOf(
      VideoProfile(width, height, 20, vBitrate),
      VideoProfile(480, 360, 20, 500 * 1000),
      VideoProfile(320, 240, 15, 300 * 1000)
    )
    var videoReady = false
    for (profile in videoProfiles) {
      try {
        val result = genericStream.prepareVideo(
          profile.width,
          profile.height,
          profile.bitrate,
          fps = profile.fps,
          rotation = rotation
        )
        if (result) {
          Log.i(TAG, "Screen video encoder ready: ${profile.width}x${profile.height} @ ${profile.fps}fps, ${profile.bitrate}bps")
          videoReady = true
          break
        }
        Log.w(TAG, "Screen video profile rejected: ${profile.width}x${profile.height} @ ${profile.fps}fps")
      } catch (error: Exception) {
        Log.w(TAG, "Screen video profile failed: ${profile.width}x${profile.height}", error)
      }
    }
    if (!videoReady) {
      Log.e(TAG, "SCREEN diagnostic: no compatible H.264 video profile could be prepared")
      return false
    }

    val audioRates = listOf(sampleRate, 48000, 32000).distinct()
    for (rate in audioRates) {
      try {
        if (genericStream.prepareAudio(rate, isStereo, aBitrate)) {
          Log.i(TAG, "Screen AAC encoder ready: ${rate}Hz, mono, ${aBitrate}bps")
          return true
        }
        Log.w(TAG, "Screen AAC profile rejected: ${rate}Hz mono")
      } catch (error: Exception) {
        Log.w(TAG, "Screen AAC profile failed: ${rate}Hz mono", error)
      }
    }
    Log.e(TAG, "SCREEN diagnostic: H.264 is ready but no compatible AAC audio profile could be prepared")
    return false
  }

  fun requiresMicrophonePermission(): Boolean {
    return selectedAudioSource == R.id.audio_source_microphone ||
        selectedAudioSource == R.id.audio_source_mix
  }

  private fun showCaptureNotification() {
    val stopIntent = Intent(this, ScreenService::class.java).setAction(ACTION_STOP_CAPTURE)
    val stopPendingIntent = PendingIntent.getService(
      this, NOTIFY_ID, stopIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val status = when {
      genericStream.isStreaming && genericStream.isRecording -> "Transmitiendo y grabando pantalla"
      genericStream.isStreaming -> "Transmitiendo pantalla"
      genericStream.isRecording -> "Grabando pantalla"
      else -> "Captura de pantalla activa"
    }
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.notification_icon)
      .setContentTitle("Tinchodoko Streaming")
      .setContentText(status)
      .setSilent(true)
      .setOngoing(true)
      .addAction(0, "DETENER", stopPendingIntent)
      .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      var foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
      if (requiresMicrophonePermission()) {
        foregroundTypes = foregroundTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
      }
      startForeground(NOTIFY_ID, notification, foregroundTypes)
    } else {
      startForeground(NOTIFY_ID, notification)
    }
  }

  private fun stopCaptureFromNotification() {
    Log.i(TAG, "SCREEN capture stopped from notification")
    if (genericStream.isRecording) {
      genericStream.stopRecord()
      if (recordPath.isNotBlank()) PathUtils.updateGallery(this, recordPath)
    }
    if (genericStream.isStreaming) genericStream.stopStream()
    mediaProjection?.stop()
    mediaProjection = null
    notificationManager?.cancel(NOTIFY_ID)
    stopForeground(true)
    stopSelf()
  }

  override fun onBind(p0: Intent?): IBinder? {
    return null
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_CAPTURE) {
      stopCaptureFromNotification()
      return START_NOT_STICKY
    }
    Log.i(TAG, "SCREEN service started")
    return START_STICKY
  }

  fun sendIntent(): Intent {
    return mediaProjectionManager.createScreenCaptureIntent()
  }

  fun isStreaming(): Boolean {
    return genericStream.isStreaming
  }

  fun isRecording(): Boolean {
    return genericStream.isRecording
  }

  fun stopStream() {
    if (genericStream.isStreaming) {
      genericStream.stopStream()
      if (genericStream.isRecording || mediaProjection != null) showCaptureNotification()
      else {
        notificationManager?.cancel(NOTIFY_ID)
        stopForeground(true)
      }
    }
  }

  fun setCallback(connectChecker: ConnectChecker?) {
    callback = connectChecker
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.i(TAG, "RTP Display service destroy")
    stopStream()
    INSTANCE = null
    //release stream and media projection properly
    genericStream.release()
    mediaProjection?.stop()
    mediaProjection = null
  }

  fun prepareStream(resultCode: Int, data: Intent): Boolean {
    if (!prepared) {
      toast("Los codificadores de SCREEN no están listos. Revisa el diagnóstico en el registro.")
      return false
    }
    stopStream()
    mediaProjection?.stop()
    mediaProjection = null
    return try {
      // User consent has already been returned by createScreenCaptureIntent().
      // Android 14+ requires the typed foreground service to start BEFORE
      // getMediaProjection() is called.
      showCaptureNotification()
      val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
        ?: throw IllegalStateException("No se pudo obtener el permiso de captura de pantalla")
      mediaProjection = projection
      genericStream.changeVideoSource(ScreenSource(applicationContext, projection))
      applySelectedAudioSource()
      true
    } catch (error: Exception) {
      Log.e(TAG, "SCREEN diagnostic: failed to prepare MediaProjection or attach sources", error)
      mediaProjection?.stop()
      mediaProjection = null
      stopForeground(true)
      toast("No se pudo inicializar SCREEN: ${error.message ?: "error desconocido"}")
      false
    }
  }

  fun getCurrentAudioSource(): AudioSource = genericStream.audioSource

  fun getSelectedAudioSource(): Int = selectedAudioSource

  fun selectAudioSource(itemId: Int) {
    if (itemId != R.id.audio_source_microphone &&
      itemId != R.id.audio_source_internal &&
      itemId != R.id.audio_source_mix
    ) return
    val previous = selectedAudioSource
    selectedAudioSource = itemId
    if (mediaProjection == null) return
    try {
      // Upgrade/downgrade foreground-service type before touching AudioRecord.
      showCaptureNotification()
      applySelectedAudioSource()
    } catch (error: Exception) {
      selectedAudioSource = previous
      try { showCaptureNotification() } catch (_: Exception) {}
      throw error
    }
  }

  private fun applySelectedAudioSource() {
    when (selectedAudioSource) {
      R.id.audio_source_microphone -> {
        if (genericStream.audioSource !is MicrophoneSource) {
          genericStream.changeAudioSource(MicrophoneSource())
        }
      }
      R.id.audio_source_internal -> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
          throw IllegalArgumentException("El audio interno requiere Android 10 o superior")
        }
        val projection = mediaProjection
          ?: throw IllegalStateException("Primero debes autorizar la captura de pantalla")
        if (genericStream.audioSource !is InternalAudioSource) {
          genericStream.changeAudioSource(InternalAudioSource(projection))
        }
      }
      R.id.audio_source_mix -> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
          throw IllegalArgumentException("El audio mixto requiere Android 10 o superior")
        }
        val projection = mediaProjection
          ?: throw IllegalStateException("Primero debes autorizar la captura de pantalla")
        if (genericStream.audioSource !is MixAudioSource) {
          genericStream.changeAudioSource(MixAudioSource(projection))
        }
      }
    }
  }

  @Deprecated("Use selectAudioSource so microphone capture is deferred until projection consent")
  fun toggleAudioSource(itemId: Int) = selectAudioSource(itemId)

  fun toggleRecord(state: (RecordController.Status) -> Unit) {
    try {
      if (!genericStream.isRecording) {
        val folder = PathUtils.getRecordPath()
        if (!folder.exists()) folder.mkdirs()
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        recordPath = "${folder.absolutePath}/${sdf.format(Date())}.mp4"
        genericStream.startRecord(recordPath) { status ->
          if (status == RecordController.Status.RECORDING) {
            state(RecordController.Status.RECORDING)
          }
        }
        state(RecordController.Status.STARTED)
        showCaptureNotification()
      } else {
        genericStream.stopRecord()
        state(RecordController.Status.STOPPED)
        PathUtils.updateGallery(this, recordPath)
        if (genericStream.isStreaming || mediaProjection != null) showCaptureNotification()
        else {
          notificationManager?.cancel(NOTIFY_ID)
          stopForeground(true)
        }
      }
    } catch (error: Exception) {
      Log.e(TAG, "SCREEN diagnostic: recording operation failed", error)
      state(if (genericStream.isRecording) RecordController.Status.RECORDING else RecordController.Status.STOPPED)
      toast("No se pudo iniciar/detener la grabación: ${error.message ?: "error desconocido"}")
    }
  }

  fun startStream(endpoint: String): Boolean {
    if (genericStream.isStreaming) return true
    return try {
      genericStream.startStream(endpoint)
      showCaptureNotification()
      true
    } catch (error: Exception) {
      Log.e(TAG, "SCREEN diagnostic: stream start failed", error)
      toast("No se pudo iniciar la transmisión: ${error.message ?: "error desconocido"}")
      false
    }
  }

  override fun onConnectionStarted(url: String) {
    callback?.onConnectionStarted(url)
  }

  override fun onConnectionSuccess() {
    callback?.onConnectionSuccess()
  }

  override fun onNewBitrate(bitrate: Long) {
    callback?.onNewBitrate(bitrate)
  }

  override fun onConnectionFailed(reason: String) {
    callback?.onConnectionFailed(reason)
  }

  override fun onDisconnect() {
    callback?.onDisconnect()
  }

  override fun onAuthError() {
    callback?.onAuthError()
  }

  override fun onAuthSuccess() {
    callback?.onAuthSuccess()
  }
}