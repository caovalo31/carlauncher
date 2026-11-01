package com.carlauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

private fun hex(c: String) = Color.parseColor(c)

// ordem: fundo, painel, texto, texto fraco, botões, destaque
private val THEMES = arrayOf(
    intArrayOf(hex("#14171C"), hex("#1E232B"), hex("#F2EFE8"), hex("#8B93A1"), hex("#2B323D"), hex("#FFB020")),
    intArrayOf(hex("#000000"), hex("#0D0F12"), hex("#F2EFE8"), hex("#7A828F"), hex("#1A1E24"), hex("#4FD1C5")),
    intArrayOf(hex("#0B1220"), hex("#142036"), hex("#E8F0FF"), hex("#8CA0C4"), hex("#1D2D4A"), hex("#5AA9FF")),
    intArrayOf(hex("#E9E6DF"), hex("#F8F6F1"), hex("#1B1E24"), hex("#5D6470"), hex("#DDD9CF"), hex("#B86E00")),
    intArrayOf(hex("#0C1511"), hex("#14231C"), hex("#E8F4EC"), hex("#86A394"), hex("#1D3328"), hex("#6FD16F"))
)
private var BG = THEMES[0][0]
private var PANEL = THEMES[0][1]
private var TEXT = THEMES[0][2]
private var DIM = THEMES[0][3]
private var PANEL2 = THEMES[0][4]
private var ACCENT = THEMES[0][5]
private val AMBER = Color.parseColor("#FFB020")
private val TEAL = Color.parseColor("#4FD1C5")
private val GREEN = Color.parseColor("#6FD16F")
private val RED = Color.parseColor("#FF6B5B")
private val LILAC = Color.parseColor("#9AA7FF")

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Um atalho que o usuário pode trocar (segurando o dedo em cima). */
private class Slot(val key: String, val label: String, val color: Int, val defPkg: String, val url: String)

/** Um app instalado (carregado uma vez em segundo plano e guardado na memória). */
private class AppEntry(val label: String, val pkg: String, val cls: String, val icon: Drawable?)

class MainActivity : Activity(), LocationListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var lm: LocationManager
    private lateinit var mm: MediaSessionManager
    private lateinit var speedView: TextView
    private lateinit var mediaTitle: TextView
    private lateinit var mediaSub: TextView
    private lateinit var playBtn: TextView

    private var controller: MediaController? = null
    private var lastSpeed = "0"
    private var editMode = false
    private var artView: ImageView? = null

    @Volatile
    private var apps: List<AppEntry> = emptyList()

    private val tileViews = arrayOfNulls<LinearLayout>(8)

    private val slots = listOf(
        Slot("s0", "Waze", TEAL, "com.waze", "https://waze.com/ul"),
        Slot("s1", "Maps", AMBER, "com.google.android.apps.maps", "https://www.google.com/maps"),
        Slot("s2", "Spotify", GREEN, "com.spotify.music", "https://open.spotify.com"),
        Slot("s3", "YouTube", RED, "com.google.android.youtube", "https://www.youtube.com"),
        Slot("s4", "Telefone", TEAL, "@dial", ""),
        Slot("s5", "WhatsApp", GREEN, "com.whatsapp", "https://web.whatsapp.com"),
        Slot("s6", "Postos", AMBER, "@fuel", "")
    )

    private val mediaCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMedia()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMedia()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            loadApps()
        }
    }

    // ---------- ciclo de vida ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        mm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        applyOrientation()
        applyTheme()
        setContentView(buildUi())

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(pkgReceiver, f)
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        applyOrientation()
        hideBars()
        startGps()
        bindMedia()
    }

    override fun onPause() {
        super.onPause()
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
        unbindMedia()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pkgReceiver)
        } catch (_: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyOrientation()
            hideBars()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // launcher: não sai da tela inicial
    }

    /** Trava sempre na horizontal. O botão "Girar tela" inverte o lado (180°). */
    private fun applyOrientation() {
        val want = if (prefs.getBoolean("flip", false))
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (requestedOrientation != want) requestedOrientation = want
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ---------- GPS / velocidade ----------
    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            return
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 2) pickBluetooth() else startGps()
    }

    override fun onLocationChanged(l: Location) {
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        val shown = if (kmh < 2f) 0 else Math.round(kmh)
        val s = shown.toString()
        if (lastSpeed != s) {
            lastSpeed = s
            speedView.text = s
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---------- música (Spotify, YouTube etc.) ----------
    private fun hasListenerAccess(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return s.contains(packageName)
    }

    private fun bindMedia() {
        if (!hasListenerAccess()) {
            mediaTitle.text = "Ativar controle de música"
            mediaSub.text = "Toque aqui e permita o Painel do Carro"
            return
        }
        val cn = ComponentName(this, MediaListener::class.java)
        try {
            mm.addOnActiveSessionsChangedListener(sessionsListener, cn)
            pick(mm.getActiveSessions(cn))
        } catch (_: SecurityException) {
            mediaTitle.text = "Ativar controle de música"
        }
    }

    private fun unbindMedia() {
        try {
            mm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(mediaCb)
        controller = null
    }

    private fun pick(list: List<MediaController>?) {
        controller?.unregisterCallback(mediaCb)
        controller = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        controller?.registerCallback(mediaCb)
        updateMedia()
    }

    private fun setArt(bm: Bitmap?) {
        val v = artView ?: return
        if (bm != null) {
            v.clearColorFilter()
            v.setImageBitmap(bm)
        } else {
            v.setImageResource(android.R.drawable.ic_media_play)
            v.setColorFilter(ACCENT)
        }
    }

    private fun updateMedia() {
        val c = controller
        if (c == null) {
            if (hasListenerAccess()) {
                mediaTitle.text = "Nenhuma música tocando"
                mediaSub.text = "Abra o Spotify ou o YouTube"
            } else {
                mediaTitle.text = "Ativar controle de música"
                mediaSub.text = "Toque aqui e permita o Painel do Carro"
            }
            playBtn.text = "▶"
            setArt(null)
            return
        }
        val md = c.metadata
        mediaTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Tocando"
        mediaSub.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: c.packageName
        playBtn.text = if (c.playbackState?.state == PlaybackState.STATE_PLAYING) "❚❚" else "▶"
        setArt(md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART))
    }

    private fun onMediaTap() {
        if (!hasListenerAccess()) {
            startSafe(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } else {
            try {
                controller?.sessionActivity?.send()
            } catch (_: Exception) {
            }
        }
    }

    private fun onPlayPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    // ---------- apps ----------
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(q, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    AppEntry(
                        it.loadLabel(pm).toString(),
                        it.activityInfo.packageName,
                        it.activityInfo.name,
                        it.loadIcon(pm)
                    )
                }
                .sortedBy { it.label.lowercase() }
            apps = list
        }.start()
    }

    private fun startSafe(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openApp(pkg: String, url: String?) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
            ?: if (url != null) Intent(Intent.ACTION_VIEW, Uri.parse(url)) else null
        if (i == null) {
            Toast.makeText(this, "App não instalado", Toast.LENGTH_SHORT).show()
        } else {
            startSafe(i)
        }
    }

    private fun launch(e: AppEntry) {
        startSafe(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(e.pkg, e.cls)
        )
    }

    private fun onSlotClick(s: Slot) {
        val pkg = prefs.getString(s.key, s.defPkg) ?: s.defPkg
        when (pkg) {
            "@dial" -> startSafe(Intent(Intent.ACTION_DIAL))
            "@fuel" -> startSafe(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("posto de gasolina")))
            )
            else -> openApp(pkg, if (pkg == s.defPkg && s.url.isNotEmpty()) s.url else null)
        }
    }

    private fun onSlotLongClick(index: Int) {
        val s = slots[index]
        val reset = AppEntry("Padrão", "@default", "", null)
        showPicker("Trocar atalho: " + s.label, reset) { e ->
            if (e.pkg == "@default") prefs.edit().remove(s.key).apply()
            else prefs.edit().putString(s.key, e.pkg).apply()
            tileViews[index]?.let { bindTile(it, s) }
        }
    }

    private fun showPicker(title: String, extra: AppEntry?, onPick: (AppEntry) -> Unit) {
        val data = apps
        if (data.isEmpty()) {
            Toast.makeText(this, "Carregando apps, tente de novo", Toast.LENGTH_SHORT).show()
            return
        }
        val list = if (extra != null) listOf(extra) + data else data
        val grid = GridView(this).apply {
            numColumns = 5
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(dp(8), dp(8), dp(8), dp(8))
            adapter = AppAdapter(list)
        }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(grid)
            .create()
        grid.setOnItemClickListener { _, _, pos, _ ->
            dlg.dismiss()
            onPick(list[pos])
        }
        dlg.show()
    }

    private inner class AppAdapter(val list: List<AppEntry>) : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(position: Int) = list[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(TextView(context).apply {
                    textSize = 12f
                    setTextColor(TEXT)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            val e = list[position]
            val img = v.getChildAt(0) as ImageView
            if (e.icon == null) {
                img.setImageResource(android.R.drawable.ic_menu_revert)
                img.setColorFilter(AMBER)
            } else {
                img.clearColorFilter()
                img.setImageDrawable(e.icon)
            }
            (v.getChildAt(1) as TextView).text = e.label
            return v
        }
    }

    // ---------- voz, bluetooth e ajustes ----------
    /** Abre o assistente de voz do celular (Google Assistant / Gemini). */
    private fun startVoice() {
        val tries = listOf(Intent(Intent.ACTION_ASSIST), Intent(Intent.ACTION_VOICE_COMMAND))
        for (i in tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Assistente de voz não encontrado", Toast.LENGTH_SHORT).show()
    }

    /** Escolhe o Bluetooth do carro: quando ele conectar, o painel abre sozinho. */
    private fun pickBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val devs = try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        if (devs.isEmpty()) {
            Toast.makeText(this, "Nenhum Bluetooth pareado. Pareie o do carro primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        val names = devs.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Qual é o Bluetooth do carro?")
            .setItems(names) { _, w ->
                prefs.edit().putString("btAddr", devs[w].address).putString("btName", names[w]).apply()
                Toast.makeText(this, "Pronto: abre ao conectar em " + names[w], Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Desativar") { _, _ ->
                prefs.edit().remove("btAddr").remove("btName").apply()
            }
            .show()
    }

    private fun applyTheme() {
        val t = THEMES[prefs.getInt("theme", 0).coerceIn(0, THEMES.size - 1)]
        BG = t[0]; PANEL = t[1]; TEXT = t[2]; DIM = t[3]; PANEL2 = t[4]; ACCENT = t[5]
    }

    private fun recreateUi() {
        applyTheme()
        setContentView(buildUi())
        updateMedia()
        hideBars()
    }

    private fun showSettings() {
        val btName = prefs.getString("btName", null)
        val overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        val items = arrayOf(
            "Editar tela (atalhos, layout, cores)",
            "Bluetooth do carro: " + (btName ?: "não escolhido"),
            "Abrir sozinho: " + (if (overlay) "permitido ✔" else "toque para permitir"),
            "Controle de música: " + (if (hasListenerAccess()) "ativo ✔" else "toque para ativar"),
            "Bateria: não deixar o Android fechar o painel",
            "Definir como tela inicial padrão"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ajustes")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> {
                        editMode = true
                        Toast.makeText(this, "Modo edição: toque num atalho para trocar o app", Toast.LENGTH_LONG).show()
                        recreateUi()
                    }
                    1 -> pickBluetooth()
                    2 -> startSafe(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                    )
                    3 -> startSafe(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    4 -> startSafe(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    5 -> startSafe(Intent(Settings.ACTION_HOME_SETTINGS))
                }
            }
            .show()
    }

    private fun showLayoutPicker() {
        val names = arrayOf("Clássico", "Música (capa grande)", "Velocidade (número gigante)", "Grade de apps")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Layout da tela")
            .setItems(names) { _, w ->
                prefs.edit().putInt("layout", w).apply()
                recreateUi()
            }
            .show()
    }

    private fun showThemePicker() {
        val names = arrayOf("Noite (âmbar)", "Preto (economiza bateria)", "Azul", "Claro (dia)", "Verde")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Cores")
            .setItems(names) { _, w ->
                prefs.edit().putInt("theme", w).apply()
                recreateUi()
            }
            .show()
    }

    // ---------- interface ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box(fill: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(18).toFloat()
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun pressable(fill: Int, stroke: Int = 0): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x40808080), box(fill, stroke), null)

    private fun lp(w: Int, h: Int, weight: Float = 0f, m: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(m), dp(m), dp(m), dp(m)) }

    private fun LinearLayout.spacer() {
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
    }

    private fun rootBox(vertical: Boolean) = LinearLayout(this).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setBackgroundColor(BG)
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private fun panel(horizontal: Boolean = false) = LinearLayout(this).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        background = box(PANEL)
        setPadding(dp(18), dp(10), dp(18), dp(10))
    }

    private fun clockTv(size: Float) = TextClock(this).apply {
        format24Hour = "HH:mm"; format12Hour = "HH:mm"
        textSize = size; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
    }

    private fun dateTv(size: Float) = TextClock(this).apply {
        format24Hour = "EEEE, d 'de' MMMM"; format12Hour = "EEEE, d 'de' MMMM"
        textSize = size; setTextColor(DIM)
    }

    private fun speedTv(size: Float): TextView {
        speedView = TextView(this).apply {
            text = lastSpeed; textSize = size; setTextColor(ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        return speedView
    }

    private fun unitTv() = TextView(this).apply { text = "km/h"; textSize = 16f; setTextColor(DIM) }

    private fun ctrlButton(txt: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
        text = txt
        textSize = size
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        background = pressable(PANEL2)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Botões de baixo: normal (Voz, Girar, Ajustes) ou modo edição (Layout, Tema, Concluir). */
    private fun controlsRow(vertical: Boolean, size: Float): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        fun add(t: String, a: () -> Unit) {
            row.addView(
                ctrlButton(t, size, a),
                lp(if (vertical) MATCH else 0, if (vertical) 0 else MATCH, 1f, 2)
            )
        }
        if (editMode) {
            add("Layout") { showLayoutPicker() }
            add("Cores") { showThemePicker() }
            add("Concluir") {
                editMode = false
                recreateUi()
            }
        } else {
            add("Voz") { startVoice() }
            add("Girar") {
                prefs.edit().putBoolean("flip", !prefs.getBoolean("flip", false)).apply()
                applyOrientation()
            }
            add("Ajustes") { showSettings() }
        }
        return row
    }

    private fun hint() = TextView(this).apply {
        text = if (editMode) "Toque num atalho para trocar o app" else "Segure um atalho para trocar o app"
        textSize = 11f; setTextColor(DIM)
    }

    private fun mediaCard(big: Boolean, withArt: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = box(PANEL, ACCENT)
            setPadding(dp(14), dp(8), dp(10), dp(8))
        }
        if (withArt) {
            artView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(PANEL2)
            }
            card.addView(artView, LinearLayout.LayoutParams(dp(120), dp(120)).apply { rightMargin = dp(16) })
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { onMediaTap() }
        }
        mediaTitle = TextView(this).apply {
            textSize = if (big) 26f else 17f
            setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
            maxLines = if (big) 2 else 1; ellipsize = TextUtils.TruncateAt.END
            text = "Música"
        }
        mediaSub = TextView(this).apply {
            textSize = if (big) 16f else 13f
            setTextColor(DIM)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(mediaTitle)
        texts.addView(mediaSub)
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

        val bw = dp(if (big) 78 else 60)
        val bh = if (big) dp(78) else MATCH
        fun bp() = LinearLayout.LayoutParams(bw, bh).apply { leftMargin = dp(6) }
        playBtn = ctrlButton("▶", if (big) 28f else 22f) { onPlayPause() }
        card.addView(ctrlButton("◀◀", if (big) 22f else 18f) { controller?.transportControls?.skipToPrevious() }, bp())
        card.addView(playBtn, bp())
        card.addView(ctrlButton("▶▶", if (big) 22f else 18f) { controller?.transportControls?.skipToNext() }, bp())
        return card
    }

    private fun makeTile(color: Int, icon: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM or Gravity.START
        background = pressable(PANEL, color)
        isClickable = true
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(icon), dp(icon)))
        addView(TextView(context).apply {
            textSize = 15f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
    }

    private fun setLabel(label: TextView, name: CharSequence) {
        label.text = if (editMode) "✎ $name" else name
    }

    private fun bindTile(v: LinearLayout, s: Slot) {
        val pkg = prefs.getString(s.key, s.defPkg) ?: s.defPkg
        val icon = v.getChildAt(0) as ImageView
        val label = v.getChildAt(1) as TextView
        when (pkg) {
            "@dial" -> {
                icon.setImageResource(android.R.drawable.ic_menu_call)
                icon.setColorFilter(s.color)
                setLabel(label, s.label)
            }
            "@fuel" -> {
                icon.setImageResource(android.R.drawable.ic_menu_mapmode)
                icon.setColorFilter(s.color)
                setLabel(label, s.label)
            }
            else -> try {
                icon.clearColorFilter()
                icon.setImageDrawable(packageManager.getApplicationIcon(pkg))
                setLabel(
                    label,
                    if (pkg == s.defPkg) s.label
                    else packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
                )
            } catch (_: PackageManager.NameNotFoundException) {
                icon.setImageResource(android.R.drawable.ic_menu_help)
                icon.setColorFilter(s.color)
                setLabel(label, s.label)
            }
        }
    }

    private fun makeAllTile(icon: Int): LinearLayout = makeTile(LILAC, icon).also {
        (it.getChildAt(0) as ImageView).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            setColorFilter(LILAC)
        }
        (it.getChildAt(1) as TextView).text = "Todos os apps"
        it.setOnClickListener { showPicker("Todos os apps", null) { e -> launch(e) } }
        tileViews[7] = it
    }

    /** Linha de atalhos. Números 0..6 são os atalhos editáveis; 7 é "Todos os apps". */
    private fun tilesRow(idx: List<Int>, icon: Int): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in idx) {
            val t: LinearLayout = if (i == 7) {
                makeAllTile(icon)
            } else {
                val s = slots[i]
                makeTile(s.color, icon).also { v ->
                    tileViews[i] = v
                    bindTile(v, s)
                    v.setOnClickListener { if (editMode) onSlotLongClick(i) else onSlotClick(s) }
                    v.setOnLongClickListener { onSlotLongClick(i); true }
                }
            }
            row.addView(t, lp(0, MATCH, 1f, 4))
        }
        return row
    }

    // ---------- 4 layouts ----------
    private fun buildUi(): View {
        tileViews.fill(null)
        artView = null
        return when (prefs.getInt("layout", 0)) {
            1 -> layoutMusic()
            2 -> layoutSpeed()
            3 -> layoutGrid()
            else -> layoutClassic()
        }
    }

    /** 0: Clássico: relógio e velocidade na esquerda, player e 8 atalhos na direita. */
    private fun layoutClassic(): View {
        val r = rootBox(false)
        val left = panel().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        left.addView(clockTv(54f))
        left.addView(dateTv(15f))
        left.spacer()
        left.addView(speedTv(92f))
        left.addView(unitTv())
        left.spacer()
        left.addView(controlsRow(false, 14f), LinearLayout.LayoutParams(MATCH, dp(40)))
        left.addView(hint(), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        r.addView(left, LinearLayout.LayoutParams(0, MATCH, 5f).apply { rightMargin = dp(10) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(mediaCard(false, false), lp(MATCH, 0, 0.8f, 4))
        right.addView(tilesRow(listOf(0, 1, 2, 3), 40), LinearLayout.LayoutParams(MATCH, 0, 1f))
        right.addView(tilesRow(listOf(4, 5, 6, 7), 40), LinearLayout.LayoutParams(MATCH, 0, 1f))
        r.addView(right, LinearLayout.LayoutParams(0, MATCH, 8f))
        return r
    }

    /** 1: Música: player grande com capa do álbum e uma fileira de atalhos. */
    private fun layoutMusic(): View {
        val r = rootBox(false)
        val left = panel().apply { setPadding(dp(14), dp(10), dp(14), dp(10)) }
        left.addView(clockTv(40f))
        left.addView(dateTv(12f))
        left.spacer()
        left.addView(speedTv(60f))
        left.addView(unitTv())
        left.spacer()
        left.addView(controlsRow(true, 13f), LinearLayout.LayoutParams(MATCH, dp(120)))
        r.addView(left, LinearLayout.LayoutParams(0, MATCH, 3f).apply { rightMargin = dp(10) })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(mediaCard(true, true), lp(MATCH, 0, 2.2f, 4))
        right.addView(tilesRow(listOf(0, 1, 2, 3, 7), 36), LinearLayout.LayoutParams(MATCH, 0, 1f))
        r.addView(right, LinearLayout.LayoutParams(0, MATCH, 8f))
        return r
    }

    /** 2: Velocidade: número gigante no meio, player em cima e atalhos embaixo. */
    private fun layoutSpeed(): View {
        val r = rootBox(true)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val clk = panel().apply {
            setPadding(dp(18), dp(6), dp(18), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        clk.addView(clockTv(40f))
        clk.addView(dateTv(12f))
        top.addView(clk, lp(0, MATCH, 3f, 4))
        top.addView(mediaCard(false, false), lp(0, MATCH, 7f, 4))
        r.addView(top, LinearLayout.LayoutParams(MATCH, 0, 0.9f))

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sp = panel().apply { gravity = Gravity.CENTER }
        val big = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        big.addView(speedTv(120f))
        big.addView(unitTv(), LinearLayout.LayoutParams(WRAP, WRAP).apply {
            leftMargin = dp(8); bottomMargin = dp(14)
        })
        sp.addView(big)
        mid.addView(sp, lp(0, MATCH, 8f, 4))
        mid.addView(controlsRow(true, 13f), lp(0, MATCH, 2f, 4))
        r.addView(mid, LinearLayout.LayoutParams(MATCH, 0, 2f))

        r.addView(tilesRow(listOf(0, 1, 2, 3, 4, 7), 30), LinearLayout.LayoutParams(MATCH, 0, 1f))
        return r
    }

    /** 3: Grade de apps: faixa fina em cima e 8 atalhos grandes. */
    private fun layoutGrid(): View {
        val r = rootBox(true)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val info = panel(true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(10), dp(4))
        }
        info.addView(clockTv(28f))
        info.addView(speedTv(28f), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(14) })
        info.addView(TextView(this).apply { text = " km/h"; textSize = 11f; setTextColor(DIM) })
        top.addView(info, lp(0, MATCH, 3.5f, 4))
        top.addView(mediaCard(false, false), lp(0, MATCH, 6f, 4))
        top.addView(controlsRow(false, 12f), lp(0, MATCH, 3.5f, 4))
        r.addView(top, LinearLayout.LayoutParams(MATCH, 0, 0.8f))
        r.addView(tilesRow(listOf(0, 1, 2, 3), 48), LinearLayout.LayoutParams(MATCH, 0, 1.5f))
        r.addView(tilesRow(listOf(4, 5, 6, 7), 48), LinearLayout.LayoutParams(MATCH, 0, 1.5f))
        return r
    }
}
