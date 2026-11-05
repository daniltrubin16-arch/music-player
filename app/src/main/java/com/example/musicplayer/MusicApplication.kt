package com.example.musicplayer

import android.app.Application
import com.example.musicplayer.data.AppDatabase
import com.example.musicplayer.player.PlayerManager

class MusicApplication : Application() {
    lateinit var player: PlayerManager
        private set
    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        player = PlayerManager(this)
        database = AppDatabase.create(this)
    }
}
