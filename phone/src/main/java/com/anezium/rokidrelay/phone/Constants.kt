package com.anezium.rokidrelay.phone

object Constants {
    const val AUTH_REQUEST_CODE = 7201
    const val CLIENT_PACKAGE = "com.anezium.rokidrelay.glasses"
    const val CLIENT_MAIN_ACTIVITY = "com.anezium.rokidrelay.glasses.MainActivity"
    const val CLIENT_ASSET_NAME = "rokid-relay-glasses.apk"

    const val KEY_EVENT = "rokid_relay.event"
    const val KEY_COMMAND = "rokid_relay.command"
    const val PROTOCOL_VERSION = 1

    const val ACTION_START = "com.anezium.rokidrelay.phone.START"
    const val ACTION_STOP = "com.anezium.rokidrelay.phone.STOP"
    const val ACTION_POST_TEST_NOTIFICATION = "com.anezium.rokidrelay.phone.POST_TEST_NOTIFICATION"
    const val ACTION_TEST_REPLY = "com.anezium.rokidrelay.phone.TEST_REPLY"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_START_REASON = "start_reason"
    const val EXTRA_TEST_REPLY = "rokid_relay_test_reply"
    const val EXTRA_TEST_MESSAGE = "rokid_relay_test_message"
    const val EXTRA_TEST_LONG = "rokid_relay_test_long"
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
    const val PREF_NOTIFICATION_POPUP_DURATION_MS = "notification_popup_duration_ms"
    const val PREF_CLEAR_PHONE_NOTIFICATION_AFTER_REPLY = "clear_phone_notification_after_reply"
    const val PREF_INBOX_ENTRY_LIMIT = "inbox_entry_limit"
    const val PREF_THREAD_MESSAGE_LIMIT = "thread_message_limit"
    const val PREF_INPUT_COMBO = "input_combo"
    const val PREF_SWIPE_MODE = "swipe_mode"
    const val PREF_CLIENT_APK_FINGERPRINT = "client_apk_fingerprint"

    const val TEST_NOTIFICATION_ID = 7202
    const val TEST_NOTIFICATION_SECOND_THREAD_ID = 7203
    const val TEST_NOTIFICATION_CHANNEL = "rokid_relay_test"
}
