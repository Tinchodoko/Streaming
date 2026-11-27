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

package com.tinchodoko.library.rtmp

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import com.tinchodoko.common.AudioCodec
import com.tinchodoko.common.ConnectChecker
import com.tinchodoko.common.VideoCodec
import com.tinchodoko.encoder.input.sources.audio.AudioSource
import com.tinchodoko.encoder.input.sources.audio.MicrophoneSource
import com.tinchodoko.encoder.input.sources.video.Camera2Source
import com.tinchodoko.encoder.input.sources.video.VideoSource
import com.tinchodoko.library.base.StreamBase
import com.tinchodoko.library.util.streamclient.RtmpStreamClient
import com.tinchodoko.library.util.streamclient.StreamClientListener
import com.tinchodoko.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer

/**
 * Created by tinchodoko on 14/3/22.
 *
 * If you use VideoManager.Source.SCREEN/AudioManager.Source.INTERNAL. Call
 * changeVideoSourceScreen/changeAudioSourceInternal is necessary to start it.
 */

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class RtmpStream(
    context: Context, connectChecker: ConnectChecker, videoSource: VideoSource,
    audioSource: AudioSource
): StreamBase(context, videoSource, audioSource) {

  private val rtmpClient = RtmpClient(connectChecker)
  private val streamClientListener = object: StreamClientListener {
    override fun onRequestKeyframe() {
      requestKeyframe()
    }
  }
  override fun getStreamClient(): RtmpStreamClient = RtmpStreamClient(rtmpClient, streamClientListener)

  constructor(context: Context, connectChecker: ConnectChecker):
      this(context, connectChecker, Camera2Source(context), MicrophoneSource())

  override fun setVideoCodecImp(codec: VideoCodec) {
    rtmpClient.setVideoCodec(codec)
  }

  override fun setAudioCodecImp(codec: AudioCodec) {
    rtmpClient.setAudioCodec(codec)
  }

  override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) {
    rtmpClient.setAudioInfo(sampleRate, isStereo)
  }

  override fun startStreamImp(endPoint: String) {
    val resolution = super.getVideoResolution()
    rtmpClient.setVideoResolution(resolution.width, resolution.height)
    rtmpClient.setFps(super.getVideoFps())
    rtmpClient.connect(endPoint)
  }

  override fun stopStreamImp() {
    rtmpClient.disconnect()
  }

  override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    rtmpClient.setVideoInfo(sps, pps, vps)
  }

  override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    rtmpClient.sendVideo(videoBuffer, info)
  }

  override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    rtmpClient.sendAudio(audioBuffer, info)
  }
}