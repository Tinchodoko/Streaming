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
package com.tinchodoko.streamer.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tinchodoko.common.ConnectChecker
import com.tinchodoko.encoder.input.sources.audio.InternalAudioSource
import com.tinchodoko.encoder.input.sources.audio.MicrophoneSource
import com.tinchodoko.encoder.input.sources.audio.MixAudioSource
import com.tinchodoko.library.base.recording.RecordController
import com.tinchodoko.streamer.R
import com.tinchodoko.streamer.utils.fitAppPadding
import com.tinchodoko.streamer.utils.toast
import com.tinchodoko.streamer.utils.updateMenuColor

/**
 * Example code to stream the device screen.
 * Necessary API 21+
 *
 * More documentation see:
 * [com.tinchodoko.library.base.DisplayBase]
 * [com.tinchodoko.library.generic.GenericDisplay]
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class ScreenActivity : AppCompatActivity(), ConnectChecker {

  enum class Action {
    STREAM, RECORD, NONE
  }

  private lateinit var button: ImageView
  private lateinit var bRecord: ImageView
  private lateinit var etUrl: EditText
  private var currentAudioSource: MenuItem? = null
  private var action = Action.NONE
  private var pendingAudioSource: Int? = null

  private val microphonePermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      val sourceToApply = pendingAudioSource
      pendingAudioSource = null
      if (granted) {
        if (sourceToApply != null) {
          try {
            val service = ScreenService.INSTANCE
            if (service == null) {
              toast("Screen service is not ready. Please try again.")
            } else {
              service.selectAudioSource(sourceToApply)
              invalidateOptionsMenu()
            }
          } catch (e: Exception) {
            toast("No se pudo seleccionar el audio: ${e.message}")
          }
        }
        if (action != Action.NONE) launchScreenCapture()
      } else {
        action = Action.NONE
        toast("Microphone permission denied. Select Internal Audio to continue without the microphone.")
      }
    }

  private val activityResultContract =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val data = result.data
      try {
        if (data != null && result.resultCode == RESULT_OK) {
          val screenService = ScreenService.INSTANCE
          if (screenService != null) {
            if (screenService.prepareStream(result.resultCode, data)) {
              when (action) {
                Action.STREAM -> startStream()
                Action.RECORD -> toggleRecord()
                else -> {}
              }
            } else {
              button.setImageResource(R.drawable.stream_icon)
              bRecord.setImageResource(R.drawable.record_icon)
              toast("No se pudo preparar la captura. Revisa el diagnóstico de SCREEN.")
            }
          } else {
            toast("Screen service is not ready. Please try again.")
          }
        } else {
          toast("Screen capture permission was not granted")
          button.setImageResource(R.drawable.stream_icon)
          bRecord.setImageResource(R.drawable.record_icon)
        }
      } catch (error: Exception) {
        button.setImageResource(R.drawable.stream_icon)
        bRecord.setImageResource(R.drawable.record_icon)
        toast("SCREEN no pudo iniciarse: ${error.message ?: "error desconocido"}")
      } finally {
        action = Action.NONE
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    setContentView(R.layout.activity_display)
    fitAppPadding()
    button = findViewById(R.id.b_start_stop)
    etUrl = findViewById(R.id.et_rtp_url)
    bRecord = findViewById(R.id.b_record)
    val screenService = ScreenService.INSTANCE
    if (screenService == null) {
      startService(Intent(this, ScreenService::class.java))
    }
    if (screenService != null && screenService.isStreaming()) {
      button.setImageResource(R.drawable.stream_stop_icon)
    } else {
      button.setImageResource(R.drawable.stream_icon)
    }
    if (screenService != null && screenService.isRecording()) {
      bRecord.setImageResource(R.drawable.stop_icon)
    } else {
      bRecord.setImageResource(R.drawable.record_icon)
    }
    button.setOnClickListener {
      val service = ScreenService.INSTANCE
      if (service != null) {
        service.setCallback(this)
        if (!service.isStreaming() && !service.isRecording()) {
          requestScreenAction(Action.STREAM)
        } else if (!service.isStreaming()) {
          startStream()
        } else {
          stopStream()
        }
      }
    }
    bRecord.setOnClickListener {
      val service = ScreenService.INSTANCE
      if (service != null) {
        service.setCallback(this)
        if (!service.isStreaming() && !service.isRecording()) {
          requestScreenAction(Action.RECORD)
        } else toggleRecord()
      }
    }
  }

  private fun requestScreenAction(requestedAction: Action) {
    val service = ScreenService.INSTANCE ?: run {
      toast("Screen service is not ready. Please try again.")
      return
    }
    val usesMicrophone = service.requiresMicrophonePermission()
    if (usesMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      action = requestedAction
      microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    } else {
      action = requestedAction
      launchScreenCapture()
    }
  }

  private fun launchScreenCapture() {
    val service = ScreenService.INSTANCE
    if (service == null) {
      action = Action.NONE
      toast("Screen service is not ready. Please try again.")
      return
    }
    try {
      activityResultContract.launch(service.sendIntent())
    } catch (error: Exception) {
      action = Action.NONE
      toast("No se pudo solicitar la captura de pantalla: ${error.message ?: "error desconocido"}")
    }
  }

  private fun startStream() {
    val endpoint = etUrl.text.toString().trim()
    val supported = endpoint.startsWith("rtmp://", ignoreCase = true) ||
        endpoint.startsWith("rtsp://", ignoreCase = true) ||
        endpoint.startsWith("srt://", ignoreCase = true)
    if (!supported) {
      toast("Protocolo no compatible. Usa rtmp://, rtsp:// o srt://")
      return
    }
    val started = ScreenService.INSTANCE?.startStream(endpoint) == true
    button.setImageResource(if (started) R.drawable.stream_stop_icon else R.drawable.stream_icon)
  }

  private fun stopStream() {
    button.setImageResource(R.drawable.stream_icon)
    ScreenService.INSTANCE?.stopStream()
  }

  private fun toggleRecord() {
    ScreenService.INSTANCE?.toggleRecord { state ->
      when (state) {
        RecordController.Status.STARTED -> bRecord.setImageResource(R.drawable.pause_icon)
        RecordController.Status.STOPPED -> bRecord.setImageResource(R.drawable.record_icon)
        RecordController.Status.RECORDING -> bRecord.setImageResource(R.drawable.stop_icon)
        else -> {}
      }
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.screen_menu, menu)
    val defaultAudioSource = when (ScreenService.INSTANCE?.getSelectedAudioSource()) {
      R.id.audio_source_microphone -> menu.findItem(R.id.audio_source_microphone)
      R.id.audio_source_internal -> menu.findItem(R.id.audio_source_internal)
      R.id.audio_source_mix -> menu.findItem(R.id.audio_source_mix)
      else -> menu.findItem(R.id.audio_source_microphone)
    }
    currentAudioSource = defaultAudioSource.updateMenuColor(this, currentAudioSource)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.audio_source_microphone, R.id.audio_source_internal, R.id.audio_source_mix -> {
        val service = ScreenService.INSTANCE
        if (service != null) {
          if (item.itemId == R.id.audio_source_microphone ||
            item.itemId == R.id.audio_source_mix
          ) {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
              pendingAudioSource = item.itemId
              microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
              return true
            }
          }
          try {
            service.selectAudioSource(item.itemId)
            currentAudioSource = item.updateMenuColor(this, currentAudioSource)
          } catch (e: Exception) {
            toast("No se pudo cambiar la fuente de audio: ${e.message ?: "error desconocido"}")
          }
        }
        return true
      }
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onResume() {
    super.onResume()
    val service = ScreenService.INSTANCE
    bRecord.setImageResource(if (service?.isRecording() == true) R.drawable.stop_icon else R.drawable.record_icon)
    button.setImageResource(if (service?.isStreaming() == true) R.drawable.stream_stop_icon else R.drawable.stream_icon)
  }

  override fun onDestroy() {
    super.onDestroy()
    val screenService = ScreenService.INSTANCE
    if (screenService != null && !screenService.isStreaming() && !screenService.isRecording()) {
      screenService.setCallback(null)
      if (isFinishing) stopService(Intent(this, ScreenService::class.java))
    }
  }

  override fun onConnectionStarted(url: String) {}

  override fun onConnectionSuccess() {
    toast("Connected")
  }

  override fun onConnectionFailed(reason: String) {
    stopStream()
    toast("Failed: $reason")
  }

  override fun onNewBitrate(bitrate: Long) {}

  override fun onDisconnect() {
    toast("Disconnected")
  }

  override fun onAuthError() {
    stopStream()
    toast("Auth error")
  }

  override fun onAuthSuccess() {
    toast("Auth success")
  }
}
