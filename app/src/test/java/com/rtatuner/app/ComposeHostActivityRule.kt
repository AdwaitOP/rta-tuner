package com.rtatuner.app

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Register the Compose test host before ActivityScenario launches in either variant. */
internal class ComposeHostActivityRule : ExternalResource() {
    override fun before() {
        val application = RuntimeEnvironment.getApplication()
        val host = ComponentName(application, ComponentActivity::class.java)
        val packageManager = shadowOf(application.packageManager)
        packageManager.addOrUpdateActivity(ActivityInfo().apply {
            name = host.className
            packageName = host.packageName
            applicationInfo = application.applicationInfo
            enabled = true
            exported = true
        })
        packageManager.addIntentFilterForActivity(host, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addCategory(Intent.CATEGORY_DEFAULT)
        })
    }
}
