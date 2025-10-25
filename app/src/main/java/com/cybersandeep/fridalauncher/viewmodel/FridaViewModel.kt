package com.cybersandeep.fridalauncher.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cybersandeep.fridalauncher.utils.FridaUtils
import com.cybersandeep.fridalauncher.utils.FridaUtils.FridaRelease
import com.cybersandeep.fridalauncher.utils.Logger
import kotlinx.coroutines.launch
import java.io.File

class FridaViewModel : ViewModel() {
    
    enum class RootStatus {
        UNKNOWN,
        AVAILABLE,
        NOT_AVAILABLE,
        NON_ROOT_MODE
    }
    
    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage
    
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    
    private val _isServerInstalled = MutableLiveData<Boolean>()
    val isServerInstalled: LiveData<Boolean> = _isServerInstalled
    
    private val _isServerRunning = MutableLiveData<Boolean>()
    val isServerRunning: LiveData<Boolean> = _isServerRunning
    
    private val _installedVersion = MutableLiveData<String>()
    val installedVersion: LiveData<String> = _installedVersion
    
    private val _availableReleases = MutableLiveData<List<FridaRelease>>()
    val availableReleases: LiveData<List<FridaRelease>> = _availableReleases
    
    private val _selectedVersion = MutableLiveData<String>()
    val selectedVersion: LiveData<String> = _selectedVersion
    
    private val _selectedArchitecture = MutableLiveData<String>()
    
    private val _lastCustomFlags = MutableLiveData<String>()
    val lastCustomFlags: LiveData<String> = _lastCustomFlags
    val selectedArchitecture: LiveData<String> = _selectedArchitecture
    
    private val _rootAccessStatus = MutableLiveData<RootStatus>()
    val rootAccessStatus: LiveData<RootStatus> = _rootAccessStatus
    
    init {
        _isServerInstalled.value = false
        _isServerRunning.value = false
        _isLoading.value = false
        _rootAccessStatus.value = RootStatus.UNKNOWN
        _installedVersion.value = "Unknown"
        _selectedArchitecture.value = FridaUtils.getDeviceArchitecture()
    }
    fun checkStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Checking Frida server status..."
            
            try {
                val isInstalled = FridaUtils.isFridaServerInstalled()
                _isServerInstalled.value = isInstalled
                
                // Check installed version
                val installedVersion = FridaUtils.getInstalledFridaVersion()
                _installedVersion.value = installedVersion ?: "Unknown"
                
                if (isInstalled) {
                    _rootAccessStatus.value = RootStatus.AVAILABLE
                    val isRunning = FridaUtils.isFridaServerRunning()
                    _isServerRunning.value = isRunning
                    
                    if (isRunning) {
                        _statusMessage.value = "Frida server ${_installedVersion.value} is installed and running"
                    } else {
                        _statusMessage.value = "Frida server ${_installedVersion.value} is installed but not running"
                    }
                } else {
                    _statusMessage.value = "Frida server is not installed"
                    _isServerRunning.value = false
                        _rootAccessStatus.value = RootStatus.NON_ROOT_MODE
                }
            } catch (e: Exception) {
                Logger.e("Error checking Frida server status", e)
                        _rootAccessStatus.value = RootStatus.NOT_AVAILABLE
                _statusMessage.value = "Error checking status: ${e.message}"
                _rootAccessStatus.value = RootStatus.NOT_AVAILABLE
            } finally {
                _isLoading.value = false
            }
        }
    }
    

    fun loadAvailableReleases() {
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Loading available Frida releases..."
            
            try {
                val releases = FridaUtils.getAvailableFridaReleases()
                _availableReleases.value = releases
                
                if (releases.isNotEmpty()) {
                    // Set the latest version as selected by default
                    _selectedVersion.value = releases.first().version
                    _statusMessage.value = "Loaded ${releases.size} Frida releases"
                } else {
                    _statusMessage.value = "No Frida releases found"
                }
            } catch (e: Exception) {
                Logger.e("Error loading Frida releases", e)
                _statusMessage.value = "Error loading releases: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    

    fun setSelectedVersion(version: String) {
        _selectedVersion.value = version
    }
    
    fun setStatusMessage(message: String) {
        _statusMessage.value = message
    }
    
    suspend fun validateAndSetCustomVersion(version: String): Boolean {
        _statusMessage.value = "Validating custom version: $version"
        Logger.i("Validating custom version: $version")
        
        return try {
            val isValid = FridaUtils.validateCustomVersion(version)
            
            if (isValid) {
                _selectedVersion.value = version
                _statusMessage.value = "✓ Version $version validated successfully"
                Logger.i("Custom version $version validated successfully")
                true
            } else {
                _statusMessage.value = "✗ Version $version not found on GitHub"
                Logger.e("Custom version $version not found on GitHub")
                false
            }
        } catch (e: Exception) {
            _statusMessage.value = "Error validating version: ${e.message}"
            Logger.e("Error validating custom version", e)
            false
        }
    }

    fun setSelectedArchitecture(architecture: String) {
        _selectedArchitecture.value = architecture
    }
    

    fun downloadAndInstallFridaServer(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            
            val version = _selectedVersion.value
            val architecture = _selectedArchitecture.value
            
            if (version == null || architecture == null) {
                _statusMessage.value = "No version or architecture selected"
                _isLoading.value = false
                return@launch
            }
            
            _statusMessage.value = "Fetching Frida server $version for $architecture..."
            
            try {
                // Get the Frida server URL for the selected version and architecture
                val url = FridaUtils.getFridaServerUrl(version, architecture)
                
                if (url == null) {
                    _statusMessage.value = "Failed to get Frida server URL for $version ($architecture)"
                    _isLoading.value = false
                    return@launch
                }
                
                _statusMessage.value = "Downloading Frida server $version..."
                
                // Download the Frida server
                val fridaFile: File?
                try {
                    fridaFile = FridaUtils.downloadFridaServerFromUrl(context, url)
                    
                    if (fridaFile == null) {
                        _statusMessage.value = "Failed to download Frida server"
                        _isLoading.value = false
                        return@launch
                    }
                } catch (e: Exception) {
                    Logger.e("Error downloading or decompressing Frida server", e)
                    _statusMessage.value = "Error: ${e.message}"
                    _isLoading.value = false
                    return@launch
                }
                
                _statusMessage.value = "Installing Frida server $version..."
                
                // Install the Frida server
                val isInstalled = FridaUtils.installFridaServer(fridaFile, version)
                
                if (isInstalled) {
                    _statusMessage.value = "Frida server $version installed successfully"
                    _isServerInstalled.value = true
                    _installedVersion.value = version ?: "Unknown"
                } else {
                    _statusMessage.value = "Failed to install Frida server"
                }
                
                // Clean up the downloaded file
                cleanupDownloadedFile(fridaFile)
                
            } catch (e: Exception) {
                Logger.e("Error installing Frida server", e)
                _statusMessage.value = "Error installing Frida server: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    

    fun startFridaServer() {
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Starting Frida server..."
            
            try {
                val isStarted = FridaUtils.startFridaServer()
                
                if (isStarted) {
                    _statusMessage.value = "Frida server started successfully"
                    _isServerRunning.value = true
                } else {
                    _statusMessage.value = "Failed to start Frida server"
                }
            } catch (e: Exception) {
                Logger.e("Error starting Frida server", e)
                _statusMessage.value = "Error starting Frida server: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    

    fun startFridaServerWithCustomFlags(flags: String) {
        // Sanitize input to prevent command injection
        val sanitizedFlags = sanitizeFlags(flags)
        
        // Save the flags for future use
        _lastCustomFlags.value = sanitizedFlags
        
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Starting Frida server with custom flags: $sanitizedFlags"
            
            try {
                val isStarted = FridaUtils.startFridaServerWithFlags(sanitizedFlags)
                
                if (isStarted) {
                    _statusMessage.value = "Frida server started successfully with flags: $sanitizedFlags"
                    _isServerRunning.value = true
                } else {
                    _statusMessage.value = "Failed to start Frida server with flags: $sanitizedFlags"
                }
            } catch (e: Exception) {
                Logger.e("Error starting Frida server with custom flags", e)
                _statusMessage.value = "Error starting Frida server with flags: $sanitizedFlags - ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    

    private fun sanitizeFlags(flags: String): String {

        return flags.replace(Regex("[;&|<>$`\\\\]"), "")
            .trim()
    }
    

    fun stopFridaServer() {
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Stopping Frida server..."
            
            try {
                val isStopped = FridaUtils.stopFridaServer()
                
                if (isStopped) {
                    _statusMessage.value = "Frida server stopped successfully"
                    _isServerRunning.value = false
                } else {
                    _statusMessage.value = "Failed to stop Frida server"
                }
            } catch (e: Exception) {
                Logger.e("Error stopping Frida server", e)
                _statusMessage.value = "Error stopping Frida server: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    

    fun uninstallFridaServer() {
        viewModelScope.launch {
            _isLoading.value = true
            _statusMessage.value = "Uninstalling Frida server..."
            
            try {
                val isUninstalled = FridaUtils.uninstallFridaServer()
                
                if (isUninstalled) {
                    _statusMessage.value = "Frida server uninstalled successfully"
                    _isServerInstalled.value = false
                    _isServerRunning.value = false
                } else {
                    _statusMessage.value = "Failed to uninstall Frida server"
                }
            } catch (e: Exception) {
                Logger.e("Error uninstalling Frida server", e)
                _statusMessage.value = "Error uninstalling Frida server: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    

    private fun cleanupDownloadedFile(file: File) {
        try {
            if (file.exists()) {
                file.delete()
                Logger.i("Cleaned up downloaded file: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Logger.e("Error cleaning up downloaded file", e)
        }
    }
    
    /**
     * Clean up resources when ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        FridaUtils.closeSuProcess()
    }
}