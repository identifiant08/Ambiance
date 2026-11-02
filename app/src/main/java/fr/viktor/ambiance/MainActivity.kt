package fr.viktor.ambiance

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle

/**
 * Point d'entrée unique, sur la TV comme sur le téléphone.
 * Sur un appareil Android TV (Mi Box), on ouvre l'écran d'ambiance ;
 * sur un téléphone (Pixel), la télécommande.
 */
class MainActivity : Activity() {

    companion object {
        fun isTv(ctx: Context): Boolean {
            val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
            val pm = ctx.packageManager
            return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si l'appli s'est fermée brutalement la dernière fois, on affiche la cause
        val crash = App.takeLastCrash(this)
        if (crash != null) {
            AlertDialog.Builder(this)
                .setTitle("Ambiance s'est fermée la dernière fois")
                .setMessage("Envoie une photo de ce message pour que le problème soit corrigé.\n\n$crash")
                .setPositiveButton("Continuer") { _, _ -> route() }
                .setOnCancelListener { route() }
                .show()
        } else {
            route()
        }
    }

    private fun route() {
        val target = if (isTv(this)) PlayerActivity::class.java else RemoteActivity::class.java
        startActivity(Intent(this, target))
        finish()
        overridePendingTransition(0, 0)
    }
}
