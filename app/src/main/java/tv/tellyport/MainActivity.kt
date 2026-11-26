package tv.tellyport

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import rikka.shizuku.Shizuku
import tv.tellyport.ui.TellyportApp
import tv.tellyport.ui.TellyportViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: TellyportViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshDiagnostics()
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        viewModel.refreshDiagnostics()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as TellyportApplication
        viewModel = ViewModelProvider(
            this,
            TellyportViewModel.Factory(app)
        )[TellyportViewModel::class.java]

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        setContent {
            TellyportApp(
                viewModel = viewModel,
                onRequestLocationPermission = ::requestLocationPermissions,
                onRequestNotificationPermission = ::requestNotificationPermission
            )
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshDiagnostics()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    private fun requestLocationPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}
