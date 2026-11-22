package com.jollydoddger.photoframe.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Share › Photo Frame (TV): pick an album, then the uploads carry on in [UploadService]. */
class ShareActivity : Activity() {
    private val NEW_ALBUM = "+ New album…"
    private lateinit var uris: List<Uri>
    private lateinit var status: TextView
    private lateinit var busy: ProgressBar
    private lateinit var albumRow: LinearLayout
    private lateinit var spinner: Spinner
    private lateinit var newName: EditText
    private lateinit var send: Button
    private lateinit var panel: AddressPanel
    private var connect: Connect? = null
    private var base: String? = null
    private var albums: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uris = sharedUris(intent)
        if (uris.isEmpty()) { Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show(); finish(); return }

        val photos = uris.size
        val title = TextView(this).apply {
            text = if (photos == 1) "Send to the TV" else "Send $photos to the TV"
            textSize = 20f; setTextColor(Color.WHITE)
        }
        status = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(170, 176, 186)); setPadding(0, dp(6), 0, dp(6)) }
        busy = ProgressBar(this)
        spinner = Spinner(this)
        newName = EditText(this).apply {
            hint = "Album name"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); visibility = View.GONE
        }
        albumRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "Album"; setTextColor(Color.rgb(170, 176, 186)); setPadding(0, dp(10), 0, 0) })
            addView(spinner); addView(newName)
            visibility = View.GONE
        }
        panel = AddressPanel(this) { url -> connected(url) }.also { it.view.visibility = View.GONE }
        send = Button(this).apply { text = "Send"; isEnabled = false; setOnClickListener { go() } }
        val cancel = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            minimumWidth = dp(300)
            addView(title); addView(status); addView(busy); addView(albumRow); addView(panel.view); addView(buttons)
        })

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                newName.visibility = if (spinner.selectedItem == NEW_ALBUM) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        status.text = "Looking for the TV…"
        connect = Connect(this) { url ->
            if (url != null) connected(url)
            else { busy.visibility = View.GONE; status.text = ""; panel.view.visibility = View.VISIBLE; panel.askForAddress() }
        }.also { it.start() }
    }

    private fun connected(url: String) {
        base = url
        panel.view.visibility = View.GONE
        busy.visibility = View.VISIBLE
        status.text = "Connected to $url"
        Thread {
            val list = runCatching { Tv.albums(url) }.getOrNull()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                busy.visibility = View.GONE
                if (list == null) { panel.view.visibility = View.VISIBLE; panel.askForAddress("The TV didn't answer."); return@runOnUiThread }
                albums = list
                val labels = list.map { it.ifEmpty { "Unsorted" } } + NEW_ALBUM
                spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                val last = list.indexOf(Tv.lastAlbum(this))
                if (last >= 0) spinner.setSelection(last)
                albumRow.visibility = View.VISIBLE
                send.isEnabled = true
            }
        }.start()
    }

    private fun go() {
        val url = base ?: return
        val pos = spinner.selectedItemPosition
        val album = if (pos in albums.indices) albums[pos] else newName.text.toString().trim().also {
            if (it.isEmpty()) { newName.error = "Name the album"; return }
        }
        Tv.saveAlbum(this, album)
        UploadService.send(this, url, album, uris)
        Toast.makeText(this, if (uris.size == 1) "Sending to the TV" else "Sending ${uris.size} to the TV", Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(i: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        i.clipData?.let { c -> for (n in 0 until c.itemCount) c.getItemAt(n).uri?.let(out::add) }
        when (i.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra(Intent.EXTRA_STREAM))?.let(out::add)
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let(out::addAll)
        }
        return out.toList()
    }

    override fun onDestroy() {
        connect?.cancel()
        super.onDestroy()
    }
}
