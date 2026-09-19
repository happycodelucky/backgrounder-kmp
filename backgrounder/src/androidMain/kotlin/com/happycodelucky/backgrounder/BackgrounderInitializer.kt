package com.happycodelucky.backgrounder

import android.app.Application
import android.content.Context
import androidx.startup.Initializer

/**
 * `androidx.startup` initializer that populates `BackgroundTaskManager.shared` before
 * `Application.onCreate` runs. Registered in the library's manifest, so a
 * consumer that keeps the `InitializationProvider` gets it for free.
 *
 * Only constructs the instance. Registering workers and calling `start()`
 * stay in `Application.onCreate`, because the registry seals at `start()`
 * and must see every registration first. Nothing here touches `WorkManager`:
 * the ephemeral sweep runs inside `start()`, after `Configuration.Provider`
 * has had its chance to install our `WorkerFactory`.
 *
 * Idempotent with an explicit `BackgroundTaskManager.configure(application)` call —
 * whichever runs first wins.
 */
public class BackgrounderInitializer : Initializer<BackgroundTaskManager> {
    override fun create(context: Context): BackgroundTaskManager =
        SharedBackgroundTaskManager.peek() ?: BackgroundTaskManager.configure(application = context.applicationContext as Application)

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
