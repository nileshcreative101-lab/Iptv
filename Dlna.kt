package com.iptv.app

import android.content.Context
import android.net.wifi.WifiManager
import java.net.*

data class Renderer(val name: String, val controlUrl: String)

/** Minimal DLNA/UPnP controller: finds TVs (Samsung etc.) and pushes a stream URL to them. */
object Dlna {
    private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"

    fun discover(ctx: Context, timeoutMs: Int = 3500): List<Renderer> {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("dlna").apply { setReferenceCounted(false); acquire() }
        val locations = linkedSetOf<String>()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 800
                val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $AVT\r\n\r\n").toByteArray()
                val addr = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(msg, msg.size, addr, 1900)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val t = String(p.data, 0, p.length)
                        Regex("(?i)location:\\s*(\\S+)").find(t)?.groupValues?.get(1)?.let { locations.add(it) }
                    } catch (e: SocketTimeoutException) { }
                }
            }
        } finally { lock.release() }

        val result = mutableListOf<Renderer>()
        for (loc in locations) {
            try {
                val c = URL(loc).openConnection() as HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 3000
                val xml = c.inputStream.bufferedReader().readText()
                val name = Regex("<friendlyName>(.*?)</friendlyName>").find(xml)?.groupValues?.get(1) ?: loc
                val svc = xml.split("<service>").drop(1).firstOrNull { it.contains("AVTransport") } ?: continue
                val ctl = Regex("<controlURL>(.*?)</controlURL>").find(svc)?.groupValues?.get(1) ?: continue
                result.add(Renderer(name, URL(URL(loc), ctl).toString()))
            } catch (e: Exception) { }
        }
        return result.distinctBy { it.controlUrl }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun soap(control: String, action: String, body: String) {
        val env = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$AVT"><InstanceID>0</InstanceID>$body</u:$action></s:Body></s:Envelope>"""
        val c = URL(control).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000; c.readTimeout = 8000
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"$AVT#$action\"")
        c.outputStream.use { it.write(env.toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) throw java.io.IOException("TV replied HTTP $code")
    }

    fun play(r: Renderer, url: String, title: String) {
        runCatching { soap(r.controlUrl, "Stop", "") }
        val mime = if (url.contains(".m3u8")) "application/vnd.apple.mpegurl" else "video/mpeg"
        val meta = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"0\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"
        soap(r.controlUrl, "SetAVTransportURI",
            "<CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(meta)}</CurrentURIMetaData>")
        soap(r.controlUrl, "Play", "<Speed>1</Speed>")
    }
}
