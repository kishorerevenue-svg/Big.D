package com.kishore.bigd.brain

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Android's downloader says the model download ended. */
class DownloadDone : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2L)
        if (id == Model.prefs(ctx).getLong("download", -1L)) Model.finish(ctx)
    }
}
