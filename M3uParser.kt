package com.iptv.app

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val name: String, val url: String, val group: String)

object Store { var channels: List<Channel> = emptyList() }

object M3uParser {
    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name = ""; var group = ""
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXTINF")) {
                name = l.substringAfterLast(",", "").trim()
                group = Regex("group-title=\"([^\"]*)\"").find(l)?.groupValues?.get(1) ?: ""
            } else if (!l.startsWith("#")) {
                out.add(Channel(name.ifEmpty { l }, l, group))
                name = ""; group = ""
            }
        }
        return out
    }

    /** Downloads a playlist from http:// or https:// (follows redirects, even http<->https). */
    fun fetch(start: String): String {
        var url = start.trim()
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IPTV")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Bad redirect")
                url = URL(URL(url), loc).toString()
                c.disconnect()
            } else if (code in 200..299) {
                return c.inputStream.bufferedReader().readText()
            } else throw IOException("HTTP $code")
        }
        throw IOException("Too many redirects")
    }
}
