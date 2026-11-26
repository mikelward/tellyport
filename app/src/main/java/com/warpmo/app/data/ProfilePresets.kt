package com.warpmo.app.data

/**
 * Built-in presets for default regional profiles, coordinates, and popular streaming apps.
 */
object ProfilePresets {

    data class LocationPreset(
        val label: String,
        val defaultName: String,
        val regionCode: String,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double
    )

    data class StreamingAppPreset(
        val label: String,
        val packageName: String
    )

    val locationPresets: List<LocationPreset> = listOf(
        LocationPreset(
            label = "US East (New York)",
            defaultName = "United States",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            altitude = 10.0
        ),
        LocationPreset(
            label = "UK (London)",
            defaultName = "United Kingdom",
            regionCode = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            altitude = 11.0
        ),
        LocationPreset(
            label = "Australia (Sydney)",
            defaultName = "Australia",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            altitude = 19.0
        ),
        LocationPreset(
            label = "US West (Los Angeles)",
            defaultName = "US West",
            regionCode = "US",
            latitude = 34.0522,
            longitude = -118.2437,
            altitude = 89.0
        ),
        LocationPreset(
            label = "Canada (Toronto)",
            defaultName = "Canada",
            regionCode = "CA",
            latitude = 43.6532,
            longitude = -79.3832,
            altitude = 76.0
        ),
        LocationPreset(
            label = "Japan (Tokyo)",
            defaultName = "Japan",
            regionCode = "JP",
            latitude = 35.6762,
            longitude = 139.6503,
            altitude = 40.0
        )
    )

    val streamingAppPresets: List<StreamingAppPreset> = listOf(
        StreamingAppPreset("Paramount+", "com.cbs.ott"),
        StreamingAppPreset("Prime Video", "com.amazon.avod.thirdpartyclient"),
        StreamingAppPreset("BBC iPlayer", "uk.co.bbc.iplayer"),
        StreamingAppPreset("ABC iview", "au.net.abc.iview"),
        StreamingAppPreset("Stan", "au.com.stan.and"),
        StreamingAppPreset("ITVX", "com.itv.itvplayer"),
        StreamingAppPreset("Channel 4", "com.channel4.ondemand"),
        StreamingAppPreset("Hulu", "com.hulu.plus"),
        StreamingAppPreset("Disney+", "com.disney.disneyplus"),
        StreamingAppPreset("Max", "com.wbd.stream"),
        StreamingAppPreset("None", "")
    )

    fun defaultProfiles(): List<RegionalProfile> = listOf(
        RegionalProfile(
            id = "preset-us",
            name = "US",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            altitude = 10.0,
            accuracyMeters = 5.0f,
            vpnBackend = VpnBackend.TAILSCALE,
            vpnTunnelOrNodeName = "us-nyc-exit",
            targetAppPackage = "com.cbs.ott",
            autoLaunchApp = true
        ),
        RegionalProfile(
            id = "preset-uk",
            name = "UK",
            regionCode = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            altitude = 11.0,
            accuracyMeters = 5.0f,
            vpnBackend = VpnBackend.TAILSCALE,
            vpnTunnelOrNodeName = "uk-lon-exit",
            targetAppPackage = "uk.co.bbc.iplayer",
            autoLaunchApp = true
        ),
        RegionalProfile(
            id = "preset-au",
            name = "AU",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            altitude = 19.0,
            accuracyMeters = 5.0f,
            vpnBackend = VpnBackend.WG_TUNNEL,
            vpnTunnelOrNodeName = "unifi-au-syd",
            targetAppPackage = "com.amazon.avod.thirdpartyclient",
            autoLaunchApp = true
        )
    )

    fun friendlyAppName(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return streamingAppPresets.firstOrNull { it.packageName == packageName }?.label
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
