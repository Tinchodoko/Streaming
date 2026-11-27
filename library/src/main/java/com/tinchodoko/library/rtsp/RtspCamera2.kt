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
package com.tinchodoko.library.rtsp

import android.content.Context
import android.media.MediaCodec
import android.os.Build
import androidx.annotation.RequiresApi
import com.tinchodoko.common.AudioCodec
import com.tinchodoko.common.ConnectChecker
import com.tinchodoko.common.VideoCodec
import com.tinchodoko.library.base.Camera2Base
import com.tinchodoko.library.util.streamclient.RtspStreamClient
import com.tinchodoko.library.util.streamclient.StreamClientListener
import com.tinchodoko.library.view.OpenGlView
import com.tinchodoko.rtsp.rtsp.RtspClient
import java.nio.ByteBuffer

/**
 * More documentation see:
 * [com.tinchodoko.library.base.Camera2Base]
 *
 * Created by tinchodoko on 4/06/17.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
class RtspCamera2 : Camera2Base {

  private val streamClientListener = object: StreamClientListener {
    override fun onRequestKeyframe() {
      requestKeyFrame()
    }
  }
  private lateinit var rtspClient: RtspClient
  private lateinit var streamClient: RtspStreamClient

  constructor(openGlView: OpenGlView, connectChecker: ConnectChecker): super(openGlView) {
    init(connectChecker)
  }

  constructor(context: Context, connectChecker: ConnectChecker): super(context) {
    init(connectChecker)
  }

  private fun init(connectChecker: ConnectChecker) {
    rtspClient = RtspClient(connectChecker)
    streamClient = RtspStreamClient(rtspClient, streamClientListener)
  }

  override fun getStreamClient(): RtspStreamClient = streamClient

  override fun setVideoCodecImp(codec: VideoCodec) {
    rtspClient.setVideoCodec(codec)
  }

  override fun setAudioCodecImp(codec: AudioCodec) {
    rtspClient.setAudioCodec(codec)
  }

  override fun onAudioInfoImp(isStereo: Boolean, sampleRate: Int) {
    rtspClient.setAudioInfo(sampleRate, isStereo)
  }

  override fun startStreamImp(url: String) {
    rtspClient.connect(url)
  }

  override fun stopStreamImp() {
    rtspClient.disconnect()
  }

  override fun getAudioDataImp(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    rtspClient.sendAudio(audioBuffer, info)
  }

  override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    rtspClient.setVideoInfo(sps, pps, vps)
  }

  override fun getVideoDataImp(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
    rtspClient.sendVideo(videoBuffer, info)
  }
}
