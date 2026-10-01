package com.bangla.translator

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bangla.translator.data.AppPreferences
import com.bangla.translator.data.ModelDownloadState
import com.bangla.translator.databinding.ActivityMainBinding
import com.bangla.translator.service.BanglaAccessibilityService
import com.bangla.translator.service.NotificationTranslationService
import com.bangla.translator.translation.TranslationEngine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appPreferences: AppPreferences

    // Runtime permission launcher for Android 13+ POST_NOTIFICATIONS
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            appPreferences.isNotificationTranslationEnabled = true
            binding.switchNotifications.isChecked = true
            checkNotificationListenerStatus()
        } else {
            binding.switchNotifications.isChecked = false
            Toast.makeText(this, "Notification permission is required to display translated alerts.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appPreferences = AppPreferences(this)

        setupListeners()
        observeModelState()
        TranslationEngine.checkModelAvailability()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        checkNotificationListenerStatus()
        TranslationEngine.checkModelAvailability()
    }

    private fun setupListeners() {
        // Accessibility Service Button
        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Translation Model Download Button
        binding.btnDownloadModel.setOnClickListener {
            binding.pbModelDownload.visibility = View.VISIBLE
            binding.btnDownloadModel.isEnabled = false
            TranslationEngine.prepareModelIfNeeded(
                onSuccess = {
                    runOnUiThread {
                        binding.pbModelDownload.visibility = View.GONE
                        binding.btnDownloadModel.isEnabled = true
                        Toast.makeText(this, "Bengali translation model ready for offline use!", Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { error ->
                    runOnUiThread {
                        binding.pbModelDownload.visibility = View.GONE
                        binding.btnDownloadModel.isEnabled = true
                        Toast.makeText(this, "Failed to download model: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        // Overlay Feature Switch
        binding.switchOverlay.isChecked = appPreferences.isOverlayEnabled
        binding.switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            appPreferences.isOverlayEnabled = isChecked
        }

        // Notification Feature Switch
        binding.switchNotifications.isChecked = appPreferences.isNotificationTranslationEnabled
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                handleEnableNotifications()
            } else {
                appPreferences.isNotificationTranslationEnabled = false
                binding.btnEnableNotificationAccess.visibility = View.GONE
            }
        }

        // Notification Access Button
        binding.btnEnableNotificationAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }
    }

    private fun handleEnableNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        appPreferences.isNotificationTranslationEnabled = true
        checkNotificationListenerStatus()
    }

    private fun observeModelState() {
        lifecycleScope.launch {
            TranslationEngine.modelState.collectLatest { state ->
                when (state) {
                    is ModelDownloadState.Ready -> {
                        binding.tvModelStatus.text = getString(R.string.model_status_ready)
                        binding.tvModelStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_active))
                        binding.btnDownloadModel.text = "Model Up to Date"
                        binding.btnDownloadModel.isEnabled = false
                        binding.pbModelDownload.visibility = View.GONE
                    }
                    is ModelDownloadState.Downloading -> {
                        binding.tvModelStatus.text = getString(R.string.model_status_downloading)
                        binding.tvModelStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                        binding.pbModelDownload.visibility = View.VISIBLE
                        binding.btnDownloadModel.isEnabled = false
                    }
                    is ModelDownloadState.NotDownloaded -> {
                        binding.tvModelStatus.text = getString(R.string.model_status_needed)
                        binding.tvModelStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_inactive))
                        binding.btnDownloadModel.text = getString(R.string.btn_download_model)
                        binding.btnDownloadModel.isEnabled = true
                        binding.pbModelDownload.visibility = View.GONE
                    }
                    is ModelDownloadState.Error -> {
                        binding.tvModelStatus.text = "Error: ${state.message}"
                        binding.tvModelStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_inactive))
                        binding.btnDownloadModel.text = "Retry Download"
                        binding.btnDownloadModel.isEnabled = true
                        binding.pbModelDownload.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = isAccessibilityServiceEnabled(this, BanglaAccessibilityService::class.java)
        if (isEnabled) {
            binding.tvAccessibilityStatus.text = getString(R.string.accessibility_status_enabled)
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            binding.btnEnableAccessibility.text = "Accessibility Active"
        } else {
            binding.tvAccessibilityStatus.text = getString(R.string.accessibility_status_disabled)
            binding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            binding.btnEnableAccessibility.text = getString(R.string.btn_enable_accessibility)
        }
    }

    private fun checkNotificationListenerStatus() {
        if (!appPreferences.isNotificationTranslationEnabled) {
            binding.btnEnableNotificationAccess.visibility = View.GONE
            return
        }

        val isListenerActive = isNotificationListenerServiceEnabled(this, NotificationTranslationService::class.java)
        if (!isListenerActive) {
            binding.btnEnableNotificationAccess.visibility = View.VISIBLE
        } else {
            binding.btnEnableNotificationAccess.visibility = View.GONE
        }
    }

    companion object {
        fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
            val expectedComponentName = ComponentName(context, serviceClass)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }

        fun isNotificationListenerServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
            val expectedComponentName = ComponentName(context, serviceClass).flattenToString()
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(expectedComponentName)
        }
    }
}
