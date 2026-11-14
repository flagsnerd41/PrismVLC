package com.example.myvlc

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Updater.checkAndNotify(applicationContext)
        return Result.success()
    }
}
