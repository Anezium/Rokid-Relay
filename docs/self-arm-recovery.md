# Rokid Relay Self-Arm Recovery

This recovery targets the Rokid RG firmware 1.21.009 failure mode where the
system force-stops the foreground glasses app and removes its accessibility
service from `enabled_accessibility_services`.

## Android Components

- Glasses package: `com.anezium.rokidrelay.glasses`
- Main activity: `com.anezium.rokidrelay.glasses/.MainActivity`
- Accessibility service:
  `com.anezium.rokidrelay.glasses/com.anezium.rokidrelay.glasses.RelayAccessibilityService`
- Watchdog remote path:
  `/data/local/tmp/rokid-relay-a11y-watchdog.sh`

## Provisioning Model

The phone companion provisions self-arm only after the user arms the relay. It
starts the glasses helper in the foreground, sends the versioned watchdog script
with a `self_arm_provision` CXR message, and waits for the helper to ACK with
`self_arm_status` before the phone marks the recovery as provisioned. The
glasses helper accepts provisioning only during a short foreground-launch window
and only when the message names the expected package, accessibility component,
and watchdog version. After that it stores `armed=true` in private files and
attempts recovery in the background on app launch and on `BOOT_COMPLETED`.

Disable sends `self_arm_disable`, clears the glasses `armed` flag, stops the
watchdog, clears the ADB TCP properties back to `-1`, and restarts `adbd`
before the phone bridge shuts down. If the phone cannot reach the glasses at
disable time, the phone marks disable as pending and will not report the
recovery disabled until the glasses helper ACKs the request.

## Privileged Repair Paths

The glasses app never ships a hardcoded ADB key. The phone companion generates
a per-install recovery ADB key in its private app files when Relay is armed:

- `<phone app files>/self-arm/adbkey`
- `<phone app files>/self-arm/adbkey.pub`

The key is sent only inside the local CXR provisioning payload and then stored
in the glasses app private files. If the key is not already trusted by `adbd`,
the glasses helper enables loopback ADB TCP, sends the public key once with
`AUTH_RSAPUBLICKEY`, and waits for Android to trust it. The Relay Accessibility
service can auto-accept the standard ADB authorization dialog when it is already
enabled, the dialog belongs to an expected Android/Rokid system package, and the
dialog text contains the expected fingerprint for the generated recovery key. No
key material is committed to the repository.

When a provisioned key exists and ADB loopback is listening on
`127.0.0.1:5555`, the glasses app signs the ADB auth token, pushes the watchdog
to `/data/local/tmp`, runs:

```sh
setprop persist.adb.tcp.port 5555
setprop service.adb.tcp.port 5555
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh restart
```

`persist.adb.tcp.port=5555` exposes ADB TCP on the glasses. ADB authentication
still requires the generated recovery key, but this should be treated as a
trusted-device recovery mode, not a general consumer default.

If the app has `WRITE_SECURE_SETTINGS`, it also repairs
`enabled_accessibility_services` directly from app code when opened.

## Watchdog Behavior

The shell watchdog supports:

```sh
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh start
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh stop
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh restart
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh status
sh /data/local/tmp/rokid-relay-a11y-watchdog.sh repair
```

Every loop checks:

- `settings get secure accessibility_enabled`
- `settings get secure enabled_accessibility_services`
- whether the Relay accessibility service is present
- whether the glasses app has a pid

When broken, it rewrites the accessibility service list, sets
`accessibility_enabled=1`, starts `.MainActivity` with `--activity-clear-top`,
and returns Home by default.
