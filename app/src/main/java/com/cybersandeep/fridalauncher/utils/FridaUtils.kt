package com.cybersandeep.fridalauncher.utils

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Utility class for Frida server operations
 */
object FridaUtils {
    private const val FRIDA_GITHUB_API = "https://api.github.com/repos/frida/frida/releases"
    const val FRIDA_BINARY_PATH = "/data/local/tmp/frida-server"
    private const val FRIDA_VERSION_FILE = "/data/local/tmp/frida-version.txt"
    
    /**
     * Data class to represent a Frida release
     */
    data class FridaRelease(
        val version: String,
        val releaseDate: String,
        val assets: List<FridaAsset>
    )
    
    /**
     * Data class to represent a Frida asset (binary)
     */
    data class FridaAsset(
        val name: String,
        val downloadUrl: String,
        val architecture: String,
        val size: Long
    )
    
    /**
     * Gson data classes for streaming JSON parsing
     */
    data class GithubRelease(
        @SerializedName("tag_name")
        val tagName: String,
        
        @SerializedName("published_at")
        val publishedAt: String,
        
        @SerializedName("assets")
        val assets: List<GithubAsset>
    )
    
    data class GithubAsset(
        @SerializedName("name")
        val name: String,
        
        @SerializedName("browser_download_url")
        val browserDownloadUrl: String,
        
        @SerializedName("size")
        val size: Long
    )
    
    /**
     * Get available Frida releases
     */
    suspend fun getAvailableFridaReleases(): List<FridaRelease> {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                
                Logger.i("Fetching Frida releases from GitHub API")
                
                val request = Request.Builder()
                    .url(FRIDA_GITHUB_API)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
                
                val releases = mutableListOf<FridaRelease>()
                val gson = Gson()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.e("Failed to fetch Frida releases: ${response.code}")
                        return@withContext emptyList()
                    }
                    
                    // Use JsonReader for streaming JSON parsing
                    JsonReader(response.body?.charStream() ?: return@withContext emptyList()).use { reader ->
                        reader.beginArray()
                        
                        while (reader.hasNext()) {
                            // Parse each release individually
                            val githubRelease = gson.fromJson<GithubRelease>(reader, GithubRelease::class.java)
                            
                            // Process the assets
                            val fridaAssets = mutableListOf<FridaAsset>()
                            
                            for (asset in githubRelease.assets) {
                                val name = asset.name
                                
                                // Only include frida-server assets
                                if (name.startsWith("frida-server-") && 
                                    (name.endsWith(".xz") || name.endsWith(".zip"))) {
                                    
                                    // Extract architecture from name
                                    val archPattern = "android-(arm|arm64|x86|x86_64)".toRegex()
                                    val matchResult = archPattern.find(name)
                                    val architecture = matchResult?.groupValues?.get(1) ?: "unknown"
                                    
                                    fridaAssets.add(
                                        FridaAsset(
                                            name = name,
                                            downloadUrl = asset.browserDownloadUrl,
                                            architecture = architecture,
                                            size = asset.size
                                        )
                                    )
                                }
                            }
                            
                            // Only add releases that have frida-server assets
                            if (fridaAssets.isNotEmpty()) {
                                releases.add(
                                    FridaRelease(
                                        version = githubRelease.tagName,
                                        releaseDate = githubRelease.publishedAt.split("T")[0],
                                        assets = fridaAssets
                                    )
                                )
                            }
                        }
                        
                        reader.endArray()
                    }
                }
                
                return@withContext releases
            } catch (e: Exception) {
                Logger.e("Error fetching Frida releases", e)
                return@withContext emptyList()
            }
        }
    }
    
    /**
     * Get the Frida server URL for a specific version and architecture
     */
    suspend fun getFridaServerUrl(version: String, architecture: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val releases = getAvailableFridaReleases()
                
                // First try exact version match in cached releases
                var release = releases.find { it.version == version }
                
                // If not found in cached list, fetch from GitHub API directly
                if (release == null) {
                    Logger.i("Version $version not in cached list, checking GitHub API...")
                    release = fetchSpecificVersion(version)
                }
                
                if (release != null) {
                    // Try exact architecture match
                    val asset = release.assets.find { it.architecture == architecture }
                    if (asset != null) {
                        Logger.i("Found matching Frida server: ${asset.name}")
                        return@withContext asset.downloadUrl
                    }
                    
                    // If no exact architecture match, log all available architectures
                    val availableArchs = release.assets.map { it.architecture }.distinct()
                    Logger.i("Available architectures for version $version: ${availableArchs.joinToString()}")
                    
                    // Try to find any asset that contains the architecture string
                    val fallbackAsset = release.assets.find { it.name.contains("-android-$architecture") }
                    if (fallbackAsset != null) {
                        Logger.i("Found fallback Frida server: ${fallbackAsset.name}")
                        return@withContext fallbackAsset.downloadUrl
                    }
                }
                
                Logger.e("No matching Frida server found for version $version and architecture $architecture")
                return@withContext null
            } catch (e: Exception) {
                Logger.e("Error fetching Frida server URL", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Validate if a custom version exists on GitHub
     */
    suspend fun validateCustomVersion(version: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val release = fetchSpecificVersion(version)
                release != null
            } catch (e: Exception) {
                Logger.e("Error validating custom version", e)
                false
            }
        }
    }
    
    /**
     * Fetch a specific version from GitHub API
     */
    private suspend fun fetchSpecificVersion(version: String): FridaRelease? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                
                val request = Request.Builder()
                    .url("$FRIDA_GITHUB_API/tags/$version")
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.e("Version $version not found on GitHub (HTTP ${response.code})")
                        return@withContext null
                    }
                    
                    val jsonString = response.body?.string() ?: return@withContext null
                    val gson = Gson()
                    val githubRelease = gson.fromJson(jsonString, GithubRelease::class.java)
                    
                    val assets = githubRelease.assets
                        .filter { it.name.startsWith("frida-server-") && it.name.endsWith(".xz") }
                        .map { asset ->
                            val archPattern = Regex("frida-server-.*-android-(.+)\\.xz")
                            val matchResult = archPattern.find(asset.name)
                            val architecture = matchResult?.groupValues?.get(1) ?: "unknown"
                            
                            FridaAsset(
                                name = asset.name,
                                downloadUrl = asset.browserDownloadUrl,
                                architecture = architecture,
                                size = asset.size
                            )
                        }
                    
                    return@withContext FridaRelease(
                        version = githubRelease.tagName,
                        releaseDate = githubRelease.publishedAt.substring(0, 10),
                        assets = assets
                    )
                }
            } catch (e: Exception) {
                Logger.e("Error fetching specific version $version", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Get the latest Frida server URL for the current device architecture
     */
    suspend fun getLatestFridaServerUrl(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val releases = getAvailableFridaReleases()
                if (releases.isEmpty()) {
                    Logger.e("No Frida releases found")
                    return@withContext null
                }
                
                // Get the latest release
                val latestRelease = releases.first()
                val arch = getDeviceArchitecture()
                Logger.i("Device architecture: $arch")
                
                // Find the asset for this architecture
                val asset = latestRelease.assets.find { it.architecture == arch }
                
                if (asset != null) {
                    Logger.i("Found matching Frida server: ${asset.name}")
                    return@withContext asset.downloadUrl
                }
                
                // If no exact architecture match, log all available architectures
                val availableArchs = latestRelease.assets.map { it.architecture }.distinct()
                Logger.i("Available architectures for latest version: ${availableArchs.joinToString()}")
                
                // Try to find any asset that contains the architecture string
                val fallbackAsset = latestRelease.assets.find { it.name.contains("-android-$arch") }
                if (fallbackAsset != null) {
                    Logger.i("Found fallback Frida server: ${fallbackAsset.name}")
                    return@withContext fallbackAsset.downloadUrl
                }
                
                Logger.e("No matching Frida server found for architecture: $arch")
                return@withContext null
            } catch (e: Exception) {
                Logger.e("Error fetching latest Frida server URL", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Download Frida server binary from the provided URL
     */
    suspend fun downloadFridaServerFromUrl(context: Context, url: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                
                Logger.i("Downloading Frida server from: $url")
                
                // Download the Frida server binary
                val downloadRequest = Request.Builder().url(url).build()
                val fridaFile = File(context.filesDir, "frida-server")
                
                // Stream directly to file instead of loading into memory
                client.newCall(downloadRequest).execute().use { downloadResponse ->
                    if (!downloadResponse.isSuccessful) {
                        Logger.e("Failed to download Frida server: ${downloadResponse.code}")
                        return@withContext null
                    }
                    
                    // Stream the response body directly
                    downloadResponse.body?.let { responseBody ->
                        if (url.endsWith(".xz")) {
                            // Handle XZ compressed file
                            Logger.i("Extracting XZ compressed Frida server")
                            
                            // Save the compressed file first, streaming directly
                            val compressedFile = File(context.filesDir, "frida-server.xz")
                            
                            // Stream the download directly to the compressed file
                            FileOutputStream(compressedFile).use { output ->
                                responseBody.byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                            
                            // Use the XZ library to decompress the file
                            try {
                                Logger.i("Decompressing XZ file using XZ library")
                                
                                // Create XZ input stream from the compressed file and stream to output
                                XZInputStream(FileInputStream(compressedFile)).use { xzInputStream ->
                                    // Write the decompressed data to the output file
                                    FileOutputStream(fridaFile).use { output ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        while (xzInputStream.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                        }
                                    }
                                }
                                
                                Logger.i("XZ decompression completed successfully")
                                
                                // Clean up the compressed file
                                compressedFile.delete()
                            } catch (e: Exception) {
                                Logger.e("Failed to decompress XZ file using XZ library", e)
                                
                                // If decompression fails, we can't proceed with an unusable binary
                                // Delete the compressed file to avoid confusion
                                compressedFile.delete()
                                if (fridaFile.exists()) {
                                    fridaFile.delete()
                                }
                                
                                throw IOException("Failed to decompress Frida server XZ file", e)
                            }
                        } else if (url.endsWith(".zip")) {
                            // Handle zip file by streaming directly
                            Logger.i("Extracting ZIP compressed Frida server")
                            
                            ZipInputStream(responseBody.byteStream()).use { zipStream ->
                                var entry = zipStream.nextEntry
                                
                                while (entry != null) {
                                    if (entry.name.contains("frida-server")) {
                                        FileOutputStream(fridaFile).use { output ->
                                            val buffer = ByteArray(8192)
                                            var bytesRead: Int
                                            while (zipStream.read(buffer).also { bytesRead = it } != -1) {
                                                output.write(buffer, 0, bytesRead)
                                            }
                                        }
                                        break
                                    }
                                    zipStream.closeEntry()
                                    entry = zipStream.nextEntry
                                }
                            }
                        } else {
                            // Handle direct binary
                            Logger.i("Saving Frida server binary directly")
                            FileOutputStream(fridaFile).use { output ->
                                responseBody.byteStream().use { input ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Make the file executable
                fridaFile.setExecutable(true)
                
                Logger.i("Frida server downloaded to ${fridaFile.absolutePath}")
                return@withContext fridaFile
            } catch (e: Exception) {
                Logger.e("Error downloading Frida server", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Get the device CPU architecture
     */
    fun getDeviceArchitecture(): String {
        return when (Build.SUPPORTED_ABIS[0]) {
            "armeabi-v7a" -> "arm"
            "arm64-v8a" -> "arm64"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            else -> "arm" // Default to arm if unknown
        }
    }
    
    /**
     * Copy the Frida server binary to /data/local/tmp and set permissions
     */
    suspend fun installFridaServer(fridaFile: File, version: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Commands to copy the file and set permissions
                executeSuCommand("cp ${fridaFile.absolutePath} $FRIDA_BINARY_PATH")
                executeSuCommand("chmod 755 $FRIDA_BINARY_PATH")
                
                // Save the version information
                saveInstalledVersion(version)
                
                // Verify the file exists after installation
                val isInstalled = isFridaServerInstalled()
                
                if (isInstalled) {
                    Logger.i("Frida server $version installed to $FRIDA_BINARY_PATH")
                    return@withContext true
                } else {
                    Logger.e("Failed to install Frida server")
                    return@withContext false
                }
            } catch (e: Exception) {
                Logger.e("Error installing Frida server", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Save the installed Frida version
     */
    suspend fun saveInstalledVersion(version: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Save version to a file in /data/local/tmp
                executeSuCommand("echo '$version' > $FRIDA_VERSION_FILE")
                Logger.i("Saved Frida version info: $version")
                return@withContext true
            } catch (e: Exception) {
                Logger.e("Error saving Frida version info", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Get the installed Frida version
     */
    suspend fun getInstalledFridaVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Check if the version file exists
                val checkResult = executeSuCommand("ls -la $FRIDA_VERSION_FILE")
                if (!checkResult.contains(FRIDA_VERSION_FILE) || checkResult.contains("No such file")) {
                    return@withContext null
                }
                
                // Read the version from the file
                val versionResult = executeSuCommand("cat $FRIDA_VERSION_FILE")
                if (versionResult.isNotEmpty()) {
                    return@withContext versionResult.trim()
                }
                
                return@withContext null
            } catch (e: Exception) {
                Logger.e("Error getting installed Frida version", e)
                return@withContext null
            }
        }
    }
    
    /**
     * Start the Frida server
     */
    suspend fun startFridaServer(): Boolean {
        return startFridaServerWithFlags("")
    }
    
    /**
     * Start the Frida server with custom flags
     */
    suspend fun startFridaServerWithFlags(flags: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // First check if the server is already running
                if (isFridaServerRunning()) {
                    Logger.i("Frida server is already running")
                    return@withContext true
                }
                
                // Command to start Frida server in the background with proper nohup
                val command = if (flags.isBlank()) {
                    "nohup $FRIDA_BINARY_PATH > /dev/null 2>&1 &"
                } else {
                    "nohup $FRIDA_BINARY_PATH $flags > /dev/null 2>&1 &"
                }
                
                Logger.i("Starting Frida server with command: $command")
                val output = executeSuCommand(command)
                Logger.d("Start command output: $output")
                
                // Give it a moment to start
                Thread.sleep(1500)
                
                // Verify the server is running
                val isRunning = isFridaServerRunning()
                
                if (isRunning) {
                    Logger.i("Frida server started successfully" + if (flags.isNotBlank()) " with flags: $flags" else "")
                    return@withContext true
                } else {
                    // Try to get more diagnostic information
                    val checkOutput = executeSuCommand("ls -la $FRIDA_BINARY_PATH")
                    Logger.d("Frida binary check: $checkOutput")
                    
                    // Try to run it with output to see any errors
                    val testCommand = if (flags.isBlank()) {
                        "$FRIDA_BINARY_PATH 2>&1"
                    } else {
                        "$FRIDA_BINARY_PATH $flags 2>&1"
                    }
                    val testOutput = executeSuCommand(testCommand)
                    Logger.e("Failed to start Frida server. Test output: $testOutput")
                    
                    return@withContext false
                }
            } catch (e: Exception) {
                Logger.e("Error starting Frida server", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Stop the Frida server
     */
    suspend fun stopFridaServer(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Command to kill the Frida server process
                executeSuCommand("pkill -f frida-server")
                
                // Give it a moment to stop
                Thread.sleep(500)
                
                // Verify the server is stopped
                val isRunning = isFridaServerRunning()
                
                if (!isRunning) {
                    Logger.i("Frida server stopped")
                    return@withContext true
                } else {
                    Logger.e("Failed to stop Frida server")
                    return@withContext false
                }
            } catch (e: Exception) {
                Logger.e("Error stopping Frida server", e)
                return@withContext false
            }
        }
    }
    
    // Shared superuser process
    private var suProcess: Process? = null
    private var suOutputStream: java.io.OutputStream? = null
    
    /**
     * Get or create a superuser process
     */
    fun getSuProcess(): Pair<Process, java.io.OutputStream>? {
        if (suProcess == null || suOutputStream == null) {
            try {
                val process = Runtime.getRuntime().exec("su")
                val outputStream = process.outputStream
                suProcess = process
                suOutputStream = outputStream
            } catch (e: Exception) {
                // Don't log the full stack trace for permission denied errors
                // as this is an expected condition on non-rooted devices
                if (e.message?.contains("Permission denied") == true) {
                    Logger.i("Failed to get superuser process: Permission denied")
                } else {
                    Logger.e("Failed to get superuser process", e)
                }
                return null
            }
        }
        return Pair(suProcess!!, suOutputStream!!)
    }
    
    /**
     * Close the superuser process
     */
    fun closeSuProcess() {
        try {
            suOutputStream?.let {
                it.write("exit\n".toByteArray())
                it.flush()
                it.close()
            }
            suProcess?.destroy()
            suProcess = null
            suOutputStream = null
        } catch (e: Exception) {
            Logger.e("Error closing superuser process", e)
        }
    }
    
    /**
     * Execute a command with superuser privileges
     */
    fun executeSuCommand(command: String): String {
        // First check if root is available to avoid unnecessary errors
        if (!isRootAvailable()) {
            Logger.i("Skipping su command as root is not available: $command")
            return ""
        }
        
        val suProcessPair = getSuProcess()
        if (suProcessPair == null) {
            Logger.i("Could not get su process, skipping command: $command")
            return ""
        }
        
        val (process, outputStream) = suProcessPair
        
        try {
            // Write the command
            outputStream.write("$command\n".toByteArray())
            outputStream.flush()
            
            // Give the command time to execute
            Thread.sleep(500)
            
            // Read the output
            val inputStream = process.inputStream
            val available = inputStream.available()
            val buffer = ByteArray(if (available > 0) available else 1024)
            val output = StringBuilder()
            
            while (inputStream.available() > 0 && inputStream.read(buffer) != -1) {
                output.append(String(buffer))
            }
            
            return output.toString()
        } catch (e: Exception) {
            Logger.e("Error executing su command: $command", e)
            return ""
        }
    }
    
    /**
     * Check if the Frida server is installed
     */
    suspend fun isFridaServerInstalled(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Command to check if Frida server exists
                val result = executeSuCommand("ls -la $FRIDA_BINARY_PATH")
                
                // Check if the output contains the binary path and doesn't contain "No such file"
                val isInstalled = result.contains(FRIDA_BINARY_PATH) && !result.contains("No such file")
                Logger.i("Frida server installed check: $isInstalled")
                return@withContext isInstalled
            } catch (e: Exception) {
                Logger.e("Error checking if Frida server is installed", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Uninstall the Frida server
     */
    suspend fun uninstallFridaServer(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // First stop the server if it's running
                if (isFridaServerRunning()) {
                    stopFridaServer()
                }
                
                // Command to remove the Frida server binary and version file
                executeSuCommand("rm -f $FRIDA_BINARY_PATH")
                executeSuCommand("rm -f $FRIDA_VERSION_FILE")
                
                // Verify the file is gone
                val result = executeSuCommand("ls -la $FRIDA_BINARY_PATH")
                val isInstalled = result.contains(FRIDA_BINARY_PATH) && !result.contains("No such file")
                
                if (!isInstalled) {
                    Logger.i("Frida server uninstalled")
                    return@withContext true
                } else {
                    Logger.e("Failed to uninstall Frida server")
                    return@withContext false
                }
            } catch (e: Exception) {
                Logger.e("Error uninstalling Frida server", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Check if the Frida server is running
     */
    suspend fun isFridaServerRunning(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Try multiple commands to check if Frida server is running
                // Some devices might not have ps -A, so we try different variations
                
                // First try with ps -A
                var result = executeSuCommand("ps -A | grep frida-server")
                if (result.contains("frida-server")) {
                    return@withContext true
                }
                
                // If that didn't work, try with ps without -A
                result = executeSuCommand("ps | grep frida-server")
                if (result.contains("frida-server")) {
                    return@withContext true
                }
                
                // If that didn't work, try with pidof
                result = executeSuCommand("pidof frida-server")
                if (result.trim().isNotEmpty()) {
                    return@withContext true
                }
                
                // If all checks failed, the server is not running
                return@withContext false
            } catch (e: Exception) {
                Logger.e("Error checking Frida server status", e)
                return@withContext false
            }
        }
    }
    
    /**
     * Check if root access is available
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val exitValue = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            
            val isRoot = exitValue == 0 && output.contains("uid=0")
            Logger.i("Root access check: $isRoot")
            isRoot
        } catch (e: Exception) {
            Logger.i("Root access is not available: ${e.message}")
            // Don't log the full stack trace for permission denied errors
            // as this is an expected condition on non-rooted devices
            if (e.message?.contains("Permission denied") != true) {
                Logger.e("Error checking root access", e)
            }
            false
        }
    }
    
    /**
     * Check if we can use non-root mode
     */
    fun canUseNonRootMode(context: Context): Boolean {
        return try {
            // Create a test script in the app's private directory
            val testFile = File(context.filesDir, "test.sh")
            
            // Write a simple script
            FileOutputStream(testFile).use { output ->
                output.write("#!/system/bin/sh\necho 'test'\n".toByteArray())
            }
            
            // Make it executable
            testFile.setExecutable(true)
            
            // Try to execute it
            val process = Runtime.getRuntime().exec(testFile.absolutePath)
            val exitValue = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            
            // Clean up
            testFile.delete()
            
            val canUseNonRoot = exitValue == 0 && output.contains("test")
            Logger.i("Non-root mode check: $canUseNonRoot")
            canUseNonRoot
        } catch (e: Exception) {
            Logger.i("Non-root mode is not available: ${e.message}")
            false
        }
    }
}