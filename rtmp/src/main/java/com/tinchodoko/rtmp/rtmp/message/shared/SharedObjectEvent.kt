package com.tinchodoko.rtmp.rtmp.message.shared

import com.tinchodoko.rtmp.amf.AmfData

data class SharedObjectEvent(
  val type: SharedObjectEventType,
  val data: LinkedHashMap<String, AmfData>
)