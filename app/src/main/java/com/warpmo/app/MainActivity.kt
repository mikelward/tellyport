package com.warpmo.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.warpmo.app.ui.WarpmoApp
import com.warpmo.app.ui.WarpmoViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: WarpmoViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshDiagnostics()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as WarpmoApplication
        viewModel = ViewModelProvider(
            this,
            WarpmoViewModel.Factory(app)
        )[WarpmoViewModel::class.java]

        setContent {
            WarpmoApp(
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
