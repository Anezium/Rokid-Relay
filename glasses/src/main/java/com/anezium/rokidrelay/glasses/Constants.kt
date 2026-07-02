package com.anezium.rokidrelay.glasses

object Constants {
    const val CLIENT_PACKAGE = "com.anezium.rokidrelay.glasses"
    const val CLIENT_MAIN_ACTIVITY = "com.anezium.rokidrelay.glasses.MainActivity"
    const val ACCESSIBILITY_SERVICE =
        "com.anezium.rokidrelay.glasses/com.anezium.rokidrelay.glasses.RelayAccessibilityService"
    const val SELF_ARM_WATCHDOG_ASSET = "rokid-relay-a11y-watchdog.sh"
    const val SELF_ARM_WATCHDOG_NAME = "rokid-relay-a11y-watchdog"
    const val SELF_ARM_WATCHDOG_VERSION = "2026-07-02.2"
    const val SELF_ARM_WATCHDOG_REMOTE_PATH = "/data/local/tmp/rokid-relay-a11y-watchdog.sh"

    const val KEY_EVENT = "rokid_relay.event"
    const val KEY_COMMAND = "rokid_relay.command"
    const val KEY_MEDIA = "rokid_relay.media"
    const val PROTOCOL_VERSION = 1
    const val BLE_WAKE_SERVICE_UUID = "8b66f35d-7db2-4b3e-9ed4-5fbc5d6b4f01"
    const val BLE_WAKE_CHARACTERISTIC_UUID = "8b66f35e-7db2-4b3e-9ed4-5fbc5d6b4f01"
}
