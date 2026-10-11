package com.rtatuner.app

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.core.content.FileProvider
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

/** AGP's unit-test manifest omits the app provider; install the production declaration. */
internal fun installCsvFileProvider(application: Application) {
    val info = ProviderInfo().apply {
        name = FileProvider::class.java.name
        packageName = application.packageName
        authority = "${application.packageName}.fileprovider"
        applicationInfo = application.applicationInfo
        exported = false
        grantUriPermissions = true
        metaData = Bundle().apply {
            putInt("android.support.FILE_PROVIDER_PATHS", R.xml.file_paths)
        }
    }
    shadowOf(application.packageManager).addOrUpdateProvider(info)
    FileProvider().attachInfo(application, info)
}

/**
 * AndroidX uses a literal '/' in its canonical-path containment comparison because
 * Android filesystems use Unix separators. Robolectric uses the host filesystem;
 * Windows canonical paths contain '\\', so a correctly configured provider rejects
 * every child file. Normalize only that comparison in tests. The real provider still
 * parses file_paths.xml, resolves canonical files, and constructs the shared URI.
 */
@Implements(
    className = "androidx.core.content.FileProvider\$SimplePathStrategy",
    isInAndroidSdk = false
)
class HostFileProviderPathStrategyShadow {
    @Implementation
    protected fun belongsToRoot(filePath: String, rootPath: String): Boolean {
        val file = filePath.replace(File.separatorChar, '/').trimEnd('/')
        val root = rootPath.replace(File.separatorChar, '/').trimEnd('/')
        return file == root || file.startsWith("$root/")
    }
}
