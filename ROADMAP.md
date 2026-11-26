# Tellyport Roadmap

This document tracks planned enhancements beyond the core zero-touch Google TV Streamer automation release.

## Near-Term Enhancements
- **Embedded `libtailscale` (`gomobile` `.aar`) Evaluation:**
  - Today, Android's external Tailscale client (`com.tailscale.ipn`) only exposes binary `CONNECT_VPN` / `DISCONNECT_VPN` broadcast intents without an API to programmatically switch Exit Nodes, which is why Tellyport enforces a single-`TAILSCALE` profile guardrail.
  - Evaluate embedding [`libtailscale`](https://github.com/tailscale/tailscale-android) natively via `gomobile` `.aar` (or a dedicated Tellyport `VpnService` backed by `tsnet` / `libtailscale`) so Tellyport can switch seamlessly across multiple regional Tailscale exit nodes (e.g., New York, London, Sydney) directly inside the app without external client intents or manual exit-node selection.
- **Native SOCKS5 / WireGuard `VpnService` Wrapper:**
  - Pair `VpnBackend.SOCKS_WRAPPER` with an embedded local `tun2socks` / WireGuard userspace tunnel for zero-external-app regional routing.
- **Per-Profile DNS & Geo-Leak Verification:**
  - Add an optional lightweight IP / geo-IP sanity check after `TRANSPORT_VPN` stabilization to confirm the active egress country matches `RegionalProfile.regionCode` before relaunching the streaming app.

## Household & Quality-of-Life Features
- **On-Screen Minimal TV Toast / Overlay Indicator:**
  - Display a brief non-intrusive Google TV banner (*"Tellyport → Switching to London..."*) during zero-touch interception so household members see clear progress while the app relaunches.
- **Profile Backup & Sync:**
  - Export/import regional profiles and app mappings (excluding secret keys) over LAN or QR code for multi-TV households.
- **Play Store & Release Hardening:**
  - Split release signing, R8 shrinking verification with `kotlinx.serialization` and Shizuku reflection keep rules, and automated Android TV emulator instrumentation tests.
