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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.widget.AdapterView.OnItemClickListener
import android.widget.GridView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

class MainActivity : AppCompatActivity() {

  private lateinit var list: GridView
  private val activities: MutableList<ActivityLink> = mutableListOf()
  private var pendingIntent: Intent? = null

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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    fitAppPadding()
    transitionAnim(true)
    val tvVersion = findViewById<TextView>(R.id.tv_version)
    tvVersion.text = getString(R.string.version, BuildConfig.VERSION_NAME)
    list = findViewById(R.id.list)
    createList()
    setListAdapter(activities)
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
    activities.add(
      ActivityLink(
        Intent(this, OldApiActivity::class.java),
        getString(R.string.old_api), VERSION_CODES.JELLY_BEAN
      )
    )
    activities.add(
      ActivityLink(
        Intent(this, FromFileActivity::class.java),
        getString(R.string.from_file), VERSION_CODES.JELLY_BEAN_MR2
      )
    )
    activities.add(
      ActivityLink(
        Intent(this, ScreenActivity::class.java),
        getString(R.string.display), VERSION_CODES.LOLLIPOP
      )
    )
    activities.add(
      ActivityLink(
        Intent(this, RotationActivity::class.java),
        getString(R.string.rotation_rtmp), VERSION_CODES.LOLLIPOP
      )
    )
  }

  private fun setListAdapter(activities: List<ActivityLink>) {
    list.adapter = ImageAdapter(activities)
    list.onItemClickListener =
      OnItemClickListener { _, _, position, _ ->
        val link = activities[position]
        if (Build.VERSION.SDK_INT < link.minSdk) {
          showMinSdkError(link.minSdk)
        } else {
          openActivity(link.intent)
        }
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

    val cameraGranted = ContextCompat.checkSelfPermission(
      this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    val microphoneGranted = ContextCompat.checkSelfPermission(
      this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

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
    val named: String = when (minSdk) {
      VERSION_CODES.JELLY_BEAN_MR2 -> "JELLY_BEAN_MR2"
      VERSION_CODES.LOLLIPOP -> "LOLLIPOP"
      else -> "JELLY_BEAN"
    }
    toast("You need min Android $named (API $minSdk)")
  }
}
