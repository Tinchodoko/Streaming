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

import android.media.MediaCodec
import com.tinchodoko.common.AudioCodec
import com.tinchodoko.common.ConnectChecker
import com.tinchodoko.library.base.OnlyAudioBase
import com.tinchodoko.library.util.streamclient.RtspStreamClient
import com.tinchodoko.rtsp.rtsp.RtspClient
import java.nio.ByteBuffer

/**
 * More documentation see:
 * [com.tinchodoko.library.base.OnlyAudioBase]
 *
 * Created by tinchodoko on 10/07/18.
 */
class RtspOnlyAudio(connectChecker: ConnectChecker) : OnlyAudioBase() {

  private val rtspClient = RtspClient(connectChecker).apply { setOnlyAudio(true) }
  private val streamClient = RtspStreamClient(rtspClient, null)

  override fun getStreamClient(): RtspStreamClient = streamClient

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
}