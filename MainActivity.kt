package com.iptv.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val shown = mutableListOf<Int>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: TextView
    private lateinit var search: EditText

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load { contentResolver.openInputStream(uri)!!.bufferedReader().readText() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("iptv", MODE_PRIVATE)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val urlBox = EditText(this).apply {
            hint = "http:// or https:// playlist .m3u URL"
            setText(prefs.getString("url", ""))
            setSingleLine()
        }
        val row = LinearLayout(this)
        val loadBtn = Button(this).apply { text = "Load URL" }
        val fileBtn = Button(this).apply { text = "Open file" }
        row.addView(loadBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(fileBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        search = EditText(this).apply { hint = "Search channels"; setSingleLine() }
        status = TextView(this).apply { text = "Paste an M3U link and tap Load" }
        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        list.adapter = adapter

        root.addView(urlBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)

        loadBtn.setOnClickListener {
            val u = urlBox.text.toString().trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                Toast.makeText(this, "URL must start with http:// or https://", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putString("url", u).apply()
                load { M3uParser.fetch(u) }
            }
        }
        fileBtn.setOnClickListener { picker.launch(arrayOf("*/*")) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("index", shown[pos]))
        }
        refresh()
    }

    private fun load(block: () -> String) {
        status.text = "Loading…"
        Thread {
            try {
                val chans = M3uParser.parse(block())
                Store.channels = chans
                runOnUiThread { refresh() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error: ${e.message}" }
            }
        }.start()
    }

    private fun refresh() {
        val q = search.text.toString().trim().lowercase()
        shown.clear()
        val names = mutableListOf<String>()
        Store.channels.forEachIndexed { i, c ->
            if (q.isEmpty() || c.name.lowercase().contains(q) || c.group.lowercase().contains(q)) {
                shown.add(i)
                names.add(if (c.group.isNotEmpty()) "${c.name}  [${c.group}]" else c.name)
            }
        }
        adapter.clear(); adapter.addAll(names)
        if (Store.channels.isNotEmpty()) status.text = "${names.size} / ${Store.channels.size} channels"
    }
}
