package com.iptv.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var index = 0
    private lateinit var title: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        index = intent.getIntExtra("index", 0)
        val root = FrameLayout(this)
        val pv = PlayerView(this)
        root.addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0x88000000.toInt()) }
        title = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); setPadding(24, 0, 0, 0); setSingleLine() }
        fun btn(t: String, a: () -> Unit) = Button(this).apply { text = t; setOnClickListener { a() } }
        bar.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(btn("◀") { play(index - 1) })
        bar.addView(btn("▶") { play(index + 1) })
        bar.addView(btn("Cast TV") { castToTv() })
        bar.addView(btn("Mirror") { mirror() })
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        setContentView(root)

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android) IPTV")
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().also {
            pv.player = it
            it.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, "Playback error: ${e.errorCodeName}", Toast.LENGTH_LONG).show()
                }
            })
        }
        play(index)
    }

    private fun play(i: Int) {
        if (i !in Store.channels.indices) return
        index = i
        val ch = Store.channels[i]
        title.text = ch.name
        player?.apply { setMediaItem(MediaItem.fromUri(ch.url)); prepare(); playWhenReady = true }
    }

    private fun castToTv() {
        val ch = Store.channels.getOrNull(index) ?: return
        Toast.makeText(this, "Searching for TVs…", Toast.LENGTH_SHORT).show()
        Thread {
            val tvs = try { Dlna.discover(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (tvs.isEmpty()) {
                    Toast.makeText(this, "No TV found. Phone and TV must be on the same Wi-Fi.", Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Cast to")
                        .setItems(tvs.map { it.name }.toTypedArray()) { _, k ->
                            Thread {
                                val ok = try { Dlna.play(tvs[k], ch.url, ch.name); true } catch (e: Exception) {
                                    runOnUiThread { Toast.makeText(this, "Cast failed: ${e.message}", Toast.LENGTH_LONG).show() }
                                    false
                                }
                                if (ok) runOnUiThread {
                                    player?.pause()
                                    Toast.makeText(this, "Casting to ${tvs[k].name}", Toast.LENGTH_SHORT).show()
                                }
                            }.start()
                        }.show()
                }
            }
        }.start()
    }

    private fun mirror() {
        try { startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }
        catch (e: Exception) { Toast.makeText(this, "Open Smart View / Cast from quick settings", Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
