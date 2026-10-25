# SafeGuard — Android DNS protection prototype

SafeGuard has a one-button setup: tap **Enable protection** and approve Android's VPN permission. It routes IPv4 UDP DNS requests to CleanBrowsing's Family Filter DNS (185.228.168.168) through a local VPN DNS proxy.

## Build an APK
Every push to `main` runs the GitHub Actions workflow. When the run succeeds, open **Actions → Build SafeGuard APK → Artifacts**, download `SafeGuard-debug-apk`, extract it, and install `app-debug.apk`.

## Important limitations
- This is DNS-based filtering, not AI content analysis or a guarantee of blocking every adult page.
- Apps or browsers using DNS-over-HTTPS/other encrypted DNS, direct IP addresses, or embedded content may bypass DNS filtering.
- This prototype handles IPv4 UDP DNS. It does not implement TCP DNS or a full IPv6 DNS proxy.
- Android may stop a service due to device-specific battery/background policies. The user must approve the VPN prompt.
- The family DNS service is third-party; its filtering policy and availability are controlled by that provider.
- The debug APK is for testing, not a Play Store release.

## Build locally
Install JDK 17 and Gradle 8.9, then run `gradle assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
