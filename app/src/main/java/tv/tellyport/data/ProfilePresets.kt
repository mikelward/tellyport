package tv.tellyport.data

/**
 * Built-in presets for agnostic regional profiles, coordinates, and popular streaming apps.
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
            label = "New York (US East)",
            defaultName = "New York",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            altitude = 10.0
        ),
        LocationPreset(
            label = "London (UK)",
            defaultName = "London",
            regionCode = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            altitude = 11.0
        ),
        LocationPreset(
            label = "Sydney (Australia)",
            defaultName = "Sydney",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            altitude = 19.0
        ),
        LocationPreset(
            label = "Los Angeles (US West)",
            defaultName = "Los Angeles",
            regionCode = "US",
            latitude = 34.0522,
            longitude = -118.2437,
            altitude = 89.0
        ),
        LocationPreset(
            label = "Toronto (Canada)",
            defaultName = "Toronto",
            regionCode = "CA",
            latitude = 43.6532,
            longitude = -79.3832,
            altitude = 76.0
        ),
        LocationPreset(
            label = "Tokyo (Japan)",
            defaultName = "Tokyo",
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
        StreamingAppPreset("Stan", "au.com.stan.and"),
        StreamingAppPreset("9Now", "au.com.mi9.jumpin.app"),
        StreamingAppPreset("7plus", "com.swm.live"),
        StreamingAppPreset("ABC iview", "au.net.abc.iview"),
        StreamingAppPreset("ITVX", "com.itv.itvplayer"),
        StreamingAppPreset("Channel 4", "com.channel4.ondemand"),
        StreamingAppPreset("Hulu", "com.hulu.plus"),
        StreamingAppPreset("Disney+", "com.disney.disneyplus"),
        StreamingAppPreset("Max", "com.wbd.stream")
    )

    /**
     * Agnostic pre-seeded city profiles (New York, London, Sydney) leaving [VpnBackend]
     * explicitly configurable per profile without hardcoding which city uses Tailscale vs WG Tunnel.
     */
    fun defaultProfiles(): List<RegionalProfile> = listOf(
        RegionalProfile(
            id = "preset-ny",
            name = "New York",
            regionCode = "US",
            latitude = 40.7128,
            longitude = -74.0060,
            altitude = 10.0,
            accuracyMeters = 5.0f,
            vpnBackend = VpnBackend.UNCONFIGURED,
            tunnelName = null,
            mappedPackages = listOf("com.cbs.ott"),
            autoLaunchApp = true
        ),
        RegionalProfile(
            id = "preset-london",
            name = "London",
            regionCode = "UK",
            latitude = 51.5074,
            longitude = -0.1278,
            altitude = 11.0,
            accuracyMeters = 5.0f,
            vpnBackend = VpnBackend.UNCONFIGURED,
            tunnelName = null,
            mappedPackages = listOf("com.amazon.avod.thirdpartyclient", "uk.co.bbc.iplayer"),
            autoLaunchApp = true
        ),
        RegionalProfile(
            id = "preset-sydney",
            name = "Sydney",
            regionCode = "AU",
            latitude = -33.8688,
            longitude = 151.2093,
            altitude = 19.0,
            accuracyMeters = 5.0f,
            vpnBackend = VpnBackend.UNCONFIGURED,
            tunnelName = null,
            mappedPackages = listOf("au.com.stan.and", "au.com.mi9.jumpin.app", "com.swm.live"),
            autoLaunchApp = true
        )
    )

    fun friendlyAppName(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return streamingAppPresets.firstOrNull { it.packageName == packageName }?.label
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
