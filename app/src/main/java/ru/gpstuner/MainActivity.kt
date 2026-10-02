package ru.gpstuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import ru.gpstuner.ui.AppRoot
import ru.gpstuner.ui.theme.GpsTunerTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // для проверки на эмуляторе: adb shell am start -n ru.gpstuner/.MainActivity --ez demo true
        if (savedInstanceState == null && intent.getBooleanExtra("demo", false)) vm.connectDemo()
        if (savedInstanceState == null && intent.getBooleanExtra("demo_bridge", false)) vm.connectDemoBridge()
        setContent {
            GpsTunerTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}
