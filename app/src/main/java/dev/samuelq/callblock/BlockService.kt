package dev.samuelq.callblock

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.PhoneNumberUtils
import android.text.format.DateUtils

class BlockService : CallScreeningService() {
    private val recent by lazy { getSharedPreferences(PREFS_RECENT, MODE_PRIVATE) }

    override fun onScreenCall(details: Call.Details) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, true) ||
            details.callDirection != Call.Details.DIRECTION_INCOMING
        ) {
            respondToCall(details, CallResponse.Builder().build())
            return
        }
        val now = System.currentTimeMillis()
        forgetOldNumbers(now, prefs.getInt(KEY_REPEAT_WINDOW, DEFAULT_REPEAT_WINDOW) * DateUtils.MINUTE_IN_MILLIS)
        val allowRepeats = prefs.getBoolean(KEY_REPEAT_CALLERS, false)
        val number = details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        // CallScreeningService already filters contacts out since the app does not request READ_CONTACTS
        // https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/callfiltering/CallScreeningServiceFilter.java#290
        val block = number == null || !(allowRepeats && isRepeatCall(number, now))
        if (block) {
            val count = prefs.getInt(KEY_BLOCKED_COUNT, 0) + 1
            val edit = prefs.edit()
            if (count == 1) edit.putLong(KEY_FIRST_BLOCKED, now)
            edit.putInt(KEY_BLOCKED_COUNT, count)
                .putLong(KEY_LAST_BLOCKED, now)
                .putBoolean(KEY_LAST_HIDDEN, number == null)
                .apply()
            if (number != null && prefs.getBoolean(KEY_NOTIFY, false) && canNotify()) {
                notifyBlocked(number, now)
            }
        } else {
            dismissBlocked(number)
        }
        respondToCall(
            details,
            CallResponse.Builder()
                .setDisallowCall(block)
                .setRejectCall(block && prefs.getBoolean(KEY_INSTANT_DECLINE, false))
                .setSkipNotification(block)
                .build()
        )
    }

    private fun forgetOldNumbers(now: Long, windowMs: Long) {
        val edit = recent.edit()
        for ((key, time) in recent.all) {
            if (now - (time as Long) > windowMs) edit.remove(key)
        }
        edit.apply()
    }

    // True if this number was blocked within the window, so this call should ring. Otherwise,
    // remembers it for next time. Only asked about calls that would be blocked.
    private fun isRepeatCall(number: String, now: Long): Boolean {
        val key = PhoneNumberUtils.normalizeNumber(number)
        if (recent.contains(key)) return true // expired entries were just removed
        // Keep the first call's time, so further calls don't extend the window.
        recent.edit().putLong(key, now).apply()
        return false
    }
}
