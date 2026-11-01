package com.carlauncher

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Abre o painel sozinho quando o Bluetooth do carro (escolhido em Ajustes) conecta. */
class BtReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val want = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("btAddr", null) ?: return
        val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        if (dev?.address != want) return
        // sem a permissão "sobrepor outros apps", o Android 10+ bloqueia abrir telas em segundo plano
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) return
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {
        }
    }
}
