package com.lizongying.mytv

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.lizongying.mytv.api.ApiClient

object UpdateManager {
    fun startDownload(context: Context, version: String) {
        val apkName = "my-tv-" + version + ".apk"
        val download = OneTimeWorkRequestBuilder<UpdateWorker>().setInputData(workDataOf(
            "VERSION_NAME" to version,
            "APK_FILENAME" to apkName,
            "DOWNLOAD_URL" to ApiClient.DOWNLOAD_HOST + version + "/" + apkName
        )).build()
        WorkManager.getInstance(context).enqueue(download)
    }
}
