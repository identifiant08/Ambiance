package fr.viktor.ambiance

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var p: SharedPreferences

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("ambiance", Context.MODE_PRIVATE)
    }

    /** Plafond de définition vidéo : 720, 1080 ou 2160. */
    var quality: Int
        get() = p.getInt("quality", 1080)
        set(v) = p.edit().putInt("quality", v).apply()

    var lastUpdate: Long
        get() = p.getLong("lastUpdate", 0L)
        set(v) = p.edit().putLong("lastUpdate", v).apply()

    /** Dernière TV utilisée par la télécommande, sous la forme hôte:port. */
    var lastTv: String
        get() = p.getString("lastTv", "").orEmpty()
        set(v) = p.edit().putString("lastTv", v).apply()
}
