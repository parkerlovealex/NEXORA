package com.nexora.browser.util

import com.nexora.browser.data.DevicePreset
import com.nexora.browser.data.GeneratedUserAgent
import com.nexora.browser.data.UserAgentTemplate
import java.util.UUID
import kotlin.random.Random

/** Configuration shared by the standalone UA generator and Profile Studio. */
data class UserAgentGenerationRequest(
    val androidCount: Int,
    val iosCount: Int,
    val androidDeviceMode: String = "RANDOM",
    val iosDeviceMode: String = "RANDOM",
    val selectedAndroidDeviceIds: Set<String> = emptySet(),
    val selectedIosDeviceIds: Set<String> = emptySet(),
    val distributionMode: String = "BALANCED",
    val customDeviceCounts: Map<String, Int> = emptyMap(),
    val androidOsVersion: String = "LATEST",
    val iosOsVersion: String = "LATEST",
    val androidBrowserFamilies: Set<String> = setOf("Chrome"),
    val iosBrowserFamilies: Set<String> = setOf("Safari"),
    val inAppBrowserIds: Set<String> = emptySet(),
    val locale: String = "en-US"
) {
    val totalCount: Int get() = androidCount + iosCount
}

object UserAgentGenerationEngine {
    private val validLocale = Regex("^[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*$")

    fun validate(
        request: UserAgentGenerationRequest,
        presets: List<DevicePreset>,
        inAppTemplates: List<UserAgentTemplate>,
        nativeAndroidUserAgent: String
    ): List<String> {
        val errors = mutableListOf<String>()
        if (request.androidCount < 0 || request.iosCount < 0) errors += "OS quantities cannot be negative."
        if (request.totalCount !in 1..500) errors += "Generate between 1 and 500 User-Agent records."
        if (!validLocale.matches(request.locale.trim())) errors += "Enter a valid locale tag such as en-US or bn-BD."
        if (request.androidCount > 0) {
            val pool = eligiblePool(presets, false, request.androidDeviceMode, request.selectedAndroidDeviceIds)
            if (pool.isEmpty()) errors += "Select at least one available Android device, or use the random device pool."
            if (request.androidBrowserFamilies.isEmpty() && request.inAppBrowserIds.isEmpty()) errors += "Select an Android browser family or an in-app browser template."
            if (request.androidBrowserFamilies.any { it != "Chrome" }) errors += "Only Chrome has a supported Android UA template in the current catalog/runtime."
            if ("Chrome" in request.androidBrowserFamilies && !nativeAndroidUserAgent.contains("Chrome/", ignoreCase = true)) errors += "The installed WebView did not report a Chromium version; an Android Chrome UA cannot be generated safely."
        }
        if (request.iosCount > 0) {
            val pool = eligiblePool(presets, true, request.iosDeviceMode, request.selectedIosDeviceIds)
            if (pool.isEmpty()) errors += "Select at least one available iOS template device, or use the random iOS template pool."
            if (request.iosBrowserFamilies.isEmpty() && request.inAppBrowserIds.isEmpty()) errors += "Select an iOS browser family or an in-app browser template."
            if (request.iosBrowserFamilies.any { it != "Safari" }) errors += "Only the Safari compatibility template is currently available for iOS."
        }
        if (request.distributionMode !in setOf("RANDOM", "BALANCED", "CUSTOM")) errors += "Choose a valid device distribution mode."
        if (request.distributionMode == "CUSTOM") {
            val eligibleIds = buildSet {
                if (request.androidCount > 0) addAll(eligiblePool(presets, false, request.androidDeviceMode, request.selectedAndroidDeviceIds).map { it.id })
                if (request.iosCount > 0) addAll(eligiblePool(presets, true, request.iosDeviceMode, request.selectedIosDeviceIds).map { it.id })
            }
            val invalidIds = request.customDeviceCounts.keys.filter { it !in eligibleIds }
            if (invalidIds.isNotEmpty()) errors += "Custom quantities include devices outside the selected device pool."
            val androidTotal = request.customDeviceCounts.filterKeys { id -> presets.any { it.id == id && !it.isIos } }.values.sum()
            val iosTotal = request.customDeviceCounts.filterKeys { id -> presets.any { it.id == id && it.isIos } }.values.sum()
            if (request.androidCount > 0 && androidTotal != request.androidCount) errors += "Custom Android device quantities must total ${request.androidCount}."
            if (request.androidCount == 0 && androidTotal != 0) errors += "Custom Android quantities must be zero when no Android records are requested."
            if (request.iosCount > 0 && iosTotal != request.iosCount) errors += "Custom iOS device quantities must total ${request.iosCount}."
            if (request.iosCount == 0 && iosTotal != 0) errors += "Custom iOS quantities must be zero when no iOS records are requested."
            if (request.customDeviceCounts.values.any { it < 0 }) errors += "Custom device quantities cannot be negative."
        }
        if (request.inAppBrowserIds.isNotEmpty()) {
            request.inAppBrowserIds.forEach { appId ->
                if (request.androidCount > 0 && inAppTemplates.none { it.appId == appId && it.targetOs == "ANDROID" }) {
                    errors += "No verified Android in-app UA template is available for $appId."
                }
                if (request.iosCount > 0 && inAppTemplates.none { it.appId == appId && it.targetOs == "IOS" }) {
                    errors += "No verified iOS in-app UA template is available for $appId."
                }
            }
        }
        return errors.distinct()
    }

    fun generate(
        request: UserAgentGenerationRequest,
        presets: List<DevicePreset>,
        inAppTemplates: List<UserAgentTemplate>,
        nativeAndroidUserAgent: String,
        databaseVersion: String,
        seed: Int = Random.nextInt()
    ): List<GeneratedUserAgent> {
        val errors = validate(request, presets, inAppTemplates, nativeAndroidUserAgent)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val random = Random(seed)
        val records = mutableListOf<GeneratedUserAgent>()
        records += generateForOs(
            count = request.androidCount,
            isIos = false,
            request = request,
            presets = presets,
            inAppTemplates = inAppTemplates,
            nativeAndroidUserAgent = nativeAndroidUserAgent,
            databaseVersion = databaseVersion,
            random = random
        )
        records += generateForOs(
            count = request.iosCount,
            isIos = true,
            request = request,
            presets = presets,
            inAppTemplates = inAppTemplates,
            nativeAndroidUserAgent = nativeAndroidUserAgent,
            databaseVersion = databaseVersion,
            random = random
        )
        return records.shuffled(random)
    }

    fun eligiblePool(
        presets: List<DevicePreset>,
        isIos: Boolean,
        mode: String,
        selectedIds: Set<String>
    ): List<DevicePreset> {
        val platform = presets.filter { it.isIos == isIos }
        return if (mode == "SELECTED") platform.filter { it.id in selectedIds } else platform
    }

    private fun generateForOs(
        count: Int,
        isIos: Boolean,
        request: UserAgentGenerationRequest,
        presets: List<DevicePreset>,
        inAppTemplates: List<UserAgentTemplate>,
        nativeAndroidUserAgent: String,
        databaseVersion: String,
        random: Random
    ): List<GeneratedUserAgent> {
        if (count <= 0) return emptyList()
        val mode = if (isIos) request.iosDeviceMode else request.androidDeviceMode
        val selected = if (isIos) request.selectedIosDeviceIds else request.selectedAndroidDeviceIds
        val pool = eligiblePool(presets, isIos, mode, selected).sortedBy { it.id }
        val devices = when (request.distributionMode) {
            "RANDOM" -> List(count) { pool[random.nextInt(pool.size)] }
            "CUSTOM" -> pool.flatMap { device -> List(request.customDeviceCounts[device.id] ?: 0) { device } }.shuffled(random)
            else -> List(count) { pool[it % pool.size] }
        }
        val browserFamilies = (if (isIos) request.iosBrowserFamilies else request.androidBrowserFamilies).toList().sorted()
        val appTemplates = if (request.inAppBrowserIds.isEmpty()) emptyList() else inAppTemplates
            .filter { it.targetOs == (if (isIos) "IOS" else "ANDROID") && it.appId in request.inAppBrowserIds }
            .sortedBy { it.appId }
        val variants = buildList<Pair<String, UserAgentTemplate?>> {
            browserFamilies.forEach { add(it to null) }
            appTemplates.forEach { add("In-app WebView" to it) }
        }
        return devices.mapIndexed { index, device ->
            val version = requestedVersion(if (isIos) request.iosOsVersion else request.androidOsVersion, device.osVersions)
            val (browser, appTemplate) = variants[index % variants.size]
            val ua = appTemplate?.render(device.id, version) ?: if (isIos) {
                UaBuilder.buildIosSafariTemplate(version)
            } else {
                UaBuilder.build(device, version, nativeAndroidUserAgent)
            }
            GeneratedUserAgent(
                id = UUID.randomUUID().toString(),
                targetOs = if (isIos) "IOS" else "ANDROID",
                deviceId = device.id,
                deviceName = device.displayName,
                osVersion = version,
                browserFamily = if (appTemplate != null) "In-app WebView" else browser,
                inAppBrowserId = appTemplate?.appId.orEmpty(),
                inAppBrowserName = appTemplate?.displayName.orEmpty(),
                locale = request.locale.trim(),
                userAgent = ua,
                databaseVersion = databaseVersion,
                runtimeNote = if (isIos) {
                    "iOS Safari compatibility template only; NEXORA Android still runs Android System WebView. The UA does not identify the exact iPhone model."
                } else if (appTemplate != null) {
                    "Evidence-backed in-app UA template. Applying it changes the UA string only, not the embedded app runtime."
                } else {
                    "Android Chrome UA uses the installed WebView Chromium version. It does not change the engine or hardware."
                }
            )
        }
    }

    private fun requestedVersion(requested: String, available: List<String>): String {
        if (available.isEmpty()) return ""
        if (requested != "LATEST" && requested in available) return requested
        return available.last()
    }
}
