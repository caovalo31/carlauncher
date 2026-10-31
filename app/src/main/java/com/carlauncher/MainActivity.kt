package com.carlauncher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

private val BG = Color.parseColor("#14171C")
private val PANEL = Color.parseColor("#1E232B")
private val TEXT = Color.parseColor("#F2EFE8")
private val DIM = Color.parseColor("#8B93A1")
private val AMBER = Color.parseColor("#FFB020")
private val TEAL = Color.parseColor("#4FD1C5")
private val GREEN = Color.parseColor("#6FD16F")
private val RED = Color.parseColor("#FF6B5B")
private val LILAC = Color.parseColor("#9AA7FF")

private class Tile(val name: String, val color: Int, val action: () -> Unit)

class MainActivity : Activity(), LocationListener {

    private lateinit var speedView: TextView
    private lateinit var lm: LocationManager

    private val tiles by lazy {
        listOf(
            Tile("Waze", TEAL) { openApp("com.waze", "https://waze.com/ul") },
            Tile("Maps", AMBER) { openApp("com.google.android.apps.maps", "https://www.google.com/maps") },
            Tile("Spotify", GREEN) { openApp("com.spotify.music", "https://open.spotify.com") },
            Tile("YouTube", RED) { openApp("com.google.android.youtube", "https://www.youtube.com") },
            Tile("Telefone", TEAL) { startSafe(Intent(Intent.ACTION_DIAL)) },
            Tile("WhatsApp", GREEN) { openApp("com.whatsapp", "https://web.whatsapp.com") },
            Tile("Postos", AMBER) {
                startSafe(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("posto de gasolina"))))
            },
            Tile("Todos os apps", LILAC) { showApps() }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        startGps()
    }

    override fun onPause() {
        super.onPause()
        lm.removeUpdates(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // launcher: não sai da tela inicial
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
        startGps()
    }

    override fun onLocationChanged(l: Location) {
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        speedView.text = (if (kmh < 2f) 0 else Math.round(kmh)).toString()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---------- abrir apps ----------
    private fun startSafe(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openApp(pkg: String, fallbackUrl: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
        startSafe(i)
    }

    private fun showApps() {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val names = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setItems(names) { _, which ->
                val ai = apps[which].activityInfo
                startSafe(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(ai.packageName, ai.name)
                )
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

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BG)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = box(PANEL)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        left.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 54f; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
        })
        left.addView(TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"; format12Hour = "EEEE, d 'de' MMMM"
            textSize = 15f; setTextColor(DIM)
        })
        left.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        speedView = TextView(this).apply {
            text = "0"; textSize = 92f; setTextColor(AMBER); typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        left.addView(speedView)
        left.addView(TextView(this).apply { text = "km/h"; textSize = 16f; setTextColor(DIM) })
        left.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 5f).apply {
            rightMargin = dp(10)
        })

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val perRow = 4
        tiles.chunked(perRow).forEachIndexed { r, rowTiles ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEach { t ->
                val v = TextView(this).apply {
                    text = t.name; textSize = 17f; setTextColor(TEXT)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.BOTTOM or Gravity.START
                    background = box(PANEL, t.color)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener { t.action() }
                }
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                })
            }
            right.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 8f))
        return root
    }
}
