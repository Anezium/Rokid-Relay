package com.anezium.rokidrelay.phone

object Constants {
    const val AUTH_REQUEST_CODE = 7201
    const val COMPANION_REQUEST_CODE = 7204
    const val CLIENT_PACKAGE = "com.anezium.rokidrelay.glasses"
    const val CLIENT_MAIN_ACTIVITY = "com.anezium.rokidrelay.glasses.MainActivity"
    const val CLIENT_ACCESSIBILITY_SERVICE =
        "com.anezium.rokidrelay.glasses/com.anezium.rokidrelay.glasses.RelayAccessibilityService"
    const val CLIENT_ASSET_NAME = "rokid-relay-glasses.apk"
    const val SELF_ARM_WATCHDOG_ASSET = "rokid-relay-a11y-watchdog.sh"
    const val SELF_ARM_WATCHDOG_VERSION = "2026-07-08.1"

    const val KEY_EVENT = "rokid_relay.event"
    const val KEY_COMMAND = "rokid_relay.command"
    const val KEY_MEDIA = "rokid_relay.media"
    const val PROTOCOL_VERSION = 2
    const val BLE_WAKE_SERVICE_UUID = "8b66f35d-7db2-4b3e-9ed4-5fbc5d6b4f01"
    const val BLE_WAKE_CHARACTERISTIC_UUID = "8b66f35e-7db2-4b3e-9ed4-5fbc5d6b4f01"

    const val ACTION_START = "com.anezium.rokidrelay.phone.START"
    const val ACTION_STOP = "com.anezium.rokidrelay.phone.STOP"
    const val ACTION_RELAUNCH = "com.anezium.rokidrelay.phone.RELAUNCH"
    const val ACTION_POST_TEST_NOTIFICATION = "com.anezium.rokidrelay.phone.POST_TEST_NOTIFICATION"
    const val ACTION_TEST_REPLY = "com.anezium.rokidrelay.phone.TEST_REPLY"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_START_REASON = "start_reason"
    const val EXTRA_WAKE_NOTIFICATION_ID = "wake_notification_id"
    const val EXTRA_TEST_REPLY = "rokid_relay_test_reply"
    const val EXTRA_TEST_MESSAGE = "rokid_relay_test_message"
    const val EXTRA_TEST_LONG = "rokid_relay_test_long"
    const val EXTRA_TEST_IMAGE = "rokid_relay_test_image"
    const val EXTRA_TEST_ENABLE_IMAGE_PREVIEWS = "rokid_relay_test_enable_image_previews"
    const val EXTRA_TEST_ID = "rokid_relay_test_id"
    const val EXTRA_TEST_COUNT = "rokid_relay_test_count"
    const val EXTRA_TEST_THREAD_INDEX = "rokid_relay_test_thread_index"

    const val PREFS = "rokid_relay"
    const val PREF_AUTH_TOKEN = "auth_token"
    const val PREF_STT_ENGINE = "stt_engine"
    const val PREF_STT_OPENAI_KEY = "stt_openai_key"
    const val PREF_STT_OPENAI_LABEL = "stt_openai_label"
    const val PREF_STT_ELEVENLABS_KEY = "stt_elevenlabs_key"
    const val PREF_STT_ELEVENLABS_LABEL = "stt_elevenlabs_label"
    const val PREF_STT_AZURE_KEY = "stt_azure_key"
    const val PREF_STT_AZURE_LABEL = "stt_azure_label"
    const val PREF_STT_AZURE_REGION = "stt_azure_region"
    const val PREF_STT_LANGUAGE = "stt_language"
    const val PREF_RELAY_ENABLED = "relay_enabled"
    const val PREF_NOTIFICATION_POPUP_DURATION_MS = "notification_popup_duration_ms"
    const val PREF_NOTIFICATION_FONT_SIZE_SP = "notification_font_size_sp"
    const val PREF_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY = "clear_phone_notification_after_reply"
    const val PREF_PAUSE_NOTIFICATION_FORWARDING_WHEN_SCREEN_ON = "pause_notification_forwarding_when_screen_on"
    const val PREF_NOTIFICATION_IMAGE_PREVIEWS_ENABLED = "notification_image_previews_enabled"
    const val PREF_INBOX_ENTRY_LIMIT = "inbox_entry_limit"
    const val PREF_THREAD_MESSAGE_LIMIT = "thread_message_limit"
    const val PREF_INPUT_COMBO = "input_combo"
    const val PREF_SWIPE_MODE = "swipe_mode"
    const val PREF_NOTIFICATION_OVERLAY_Y_OFFSET_DP = "notification_overlay_y_offset_dp"
    const val PREF_CLIENT_APK_FINGERPRINT = "client_apk_fingerprint"
    const val PREF_CLIENT_NEEDS_FOREGROUND_LAUNCH = "client_needs_foreground_launch"
    const val PREF_HELPER_UPDATED_AT_MS = "helper_updated_at_ms"
    const val PREF_HELPER_UPDATE_ATTEMPT_VERSION_CODE = "helper_update_attempt_version_code"
    const val PREF_HELPER_UPDATE_ATTEMPT_DAY = "helper_update_attempt_day"
    const val PREF_HELPER_UPDATE_ATTEMPT_COUNT = "helper_update_attempt_count"
    const val PREF_HELPER_UPDATE_NEXT_RETRY_AT_MS = "helper_update_next_retry_at_ms"
    const val PREF_HELPER_UPDATE_LAST_FAILURE_REASON = "helper_update_last_failure_reason"
    const val PREF_HELPER_UPDATE_VERIFICATION_REQUIRED_VERSION_CODE =
        "helper_update_verification_required_version_code"
    const val PREF_HELPER_UPDATE_VERIFICATION_STARTED_AT_MS =
        "helper_update_verification_started_at_ms"
    const val PREF_HELPER_LEGACY_VERIFICATION_LOGGED_VERSION_CODE =
        "helper_legacy_verification_logged_version_code"
    const val PREF_SELF_ARM_PROVISIONED = "self_arm_provisioned"
    const val PREF_SELF_ARM_KEY_PRESENT = "self_arm_key_present"
    const val PREF_SELF_ARM_DISABLE_PENDING = "self_arm_disable_pending"

    const val TEST_NOTIFICATION_ID = 7202
    const val TEST_NOTIFICATION_SECOND_THREAD_ID = 7203
    const val TEST_NOTIFICATION_IMAGE_ID = 7205
    const val TEST_NOTIFICATION_CHANNEL = "rokid_relay_test"
}
