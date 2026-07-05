# Rokid Relay Self-Arm Recovery

This recovery targets the Rokid RG glasses failure mode where the foreground
glasses helper is stopped and Android removes its accessibility service from
`enabled_accessibility_services`.

## Components

- Glasses package: `com.anezium.rokidrelay.glasses`
- Phone package: `com.anezium.rokidrelay.phone`
- Main activity: `com.anezium.rokidrelay.glasses/.MainActivity`
- Accessibility service:
  `com.anezium.rokidrelay.glasses/com.anezium.rokidrelay.glasses.RelayAccessibilityService`
- Watchdog fallback path:
  `/data/local/tmp/rokid-relay-a11y-watchdog.sh`

## One-Time Wireless ADB Bootstrap

The permanent recovery path starts with a guided phone flow:

1. The phone opens the glasses helper over CXR.
2. The glasses accessibility service opens the Android Wi-Fi panel first
   (`android.settings.panel.action.WIFI`), falls back to Wi-Fi Settings if
   needed, taps the Wi-Fi toggle by accessibility/gesture, and polls
   `WifiManager` until Wi-Fi is actually on.
3. The service opens Developer Options, enables Developer Options from the
   device-info build-number page if Android requires that detour, opens
   Wireless Debugging, turns it on, accepts the confirmation dialog, and
   clicks the pair-with-code entry.
4. The glasses service reads the 6-digit pairing code from all accessibility
   window roots, including Rokid MockWindow surfaces that are invisible to
   normal active-window traversal, then sends it to the phone over CXR.
5. If the code cannot be read automatically, the phone setup row exposes a
   manual 6-digit code field for the user to type the code shown on the HUD.
   This field is shown whenever the wireless bootstrap is in progress, not
   only after the glasses report an auto-read code.
6. The phone discovers `_adb-tls-pairing._tcp` with Android NSD, pairs with
   KADB, discovers the `_adb-tls-connect._tcp` port, and connects as shell.
7. Over that shell-uid ADB session the phone runs:

```sh
pm grant com.anezium.rokidrelay.glasses android.permission.WRITE_SECURE_SETTINGS
settings put global adb_wifi_enabled 1
setprop persist.adb.tcp.port 5555
setprop service.adb.tcp.port 5555
```

The same bootstrap appends the phone-generated Relay recovery public key to
`/data/misc/adb/adb_keys` so the loopback watchdog fallback can authenticate
without an on-glasses RSA trust prompt.

The glasses accessibility service must be configured with
`flagIncludeNotImportantViews`, `flagReportViewIds`, `canPerformGestures`, and
`canRequestTouchExplorationMode`. On Rokid RG Android 12 this raises the
service capabilities to include gesture dispatch and lets the service read the
Settings MockWindow surfaces used by the Wi-Fi panel and Wireless Debugging
pairing dialog.

## Primary Repair Path

After `WRITE_SECURE_SETTINGS` is granted, the glasses app repairs accessibility
directly from app code. `SelfArmController` writes the Relay service back into
`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, sets
`Settings.Secure.ACCESSIBILITY_ENABLED=1`, and opens `MainActivity`.

This direct repair is the success condition. The app no longer depends on
app-uid `setprop persist.adb.tcp.port` or `setprop ctl.restart adbd`, because
SELinux blocks those calls outside a shell-uid ADB session.

Recovery is triggered from:

- the glasses `MainActivity` foreground launch,
- `RelayAccessibilityService.onServiceConnected()`, which bypasses the
  stopped-package `BOOT_COMPLETED` gate once the service is alive,
- `BootReceiver` when Android delivers `BOOT_COMPLETED`.

## Fallback Watchdog

The shell watchdog remains as a secondary path when direct secure-settings
repair is unavailable but `127.0.0.1:5555` is already listening from the
one-time bootstrap. The app uses its provisioned recovery key to authenticate
to loopback ADB, installs the watchdog script, and starts it.

The watchdog supports:

```sh
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh start
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh stop
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh restart
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh status
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh repair
```

Every loop checks the accessibility enabled flag, the enabled service list, and
whether the Relay glasses app has a pid. When broken, it rewrites the service
list, enables accessibility, starts `.MainActivity`, and returns Home.
