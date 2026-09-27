package com.textrelay.app.data

import org.json.JSONObject

data class Message(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val ts: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("sid", senderId)
        .put("name", senderName)
        .put("text", text)
        .put("ts", ts)

    companion object {
        fun fromJson(o: JSONObject): Message = Message(
            id = o.getString("id"),
            senderId = o.getString("sid"),
            senderName = o.optString("name", "?"),
            text = o.getString("text"),
            ts = o.getLong("ts")
        )
    }
}
