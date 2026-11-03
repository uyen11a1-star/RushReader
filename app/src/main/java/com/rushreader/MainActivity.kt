package com.rushreader

import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.rushreader.core.IntentUris
import com.rushreader.pdf.BitmapPool
import com.rushreader.pdf.RenderPolicy
import com.rushreader.ui.RushApp
import com.rushreader.ui.RushTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        if (savedInstanceState == null) handleIntent(intent)
        setContent { RushTheme { RushApp(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        val uris = IntentUris.extract(i)
        if (uris.isEmpty()) return
        // Chỉ giữ quyền lâu dài khi nguồn thực sự cấp (không tự ý đòi thêm quyền)
        if (i.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            uris.forEach { try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (ignored: Exception) {} }
        }
        vm.open(uris)
    }

    override fun onStart() { super.onStart(); RenderPolicy.pressure = false }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) BitmapPool.clear()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) RenderPolicy.pressure = true
    }
}
