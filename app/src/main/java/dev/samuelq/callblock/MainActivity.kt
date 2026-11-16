package dev.samuelq.callblock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi

const val PREFS = "prefs"
const val KEY_ENABLED = "enabled"
const val KEY_INSTANT_DECLINE = "instant_decline"
const val KEY_REPEAT_CALLERS = "repeat_callers"
const val KEY_REPEAT_WINDOW = "repeat_window_min"
const val DEFAULT_REPEAT_WINDOW = 15
const val KEY_BLOCKED_COUNT = "blocked_count"
const val KEY_FIRST_BLOCKED = "first_blocked"
const val KEY_LAST_BLOCKED = "last_blocked"
const val KEY_LAST_HIDDEN = "last_hidden"
const val KEY_NOTIFY = "notify"
const val PREFS_RECENT = "recent_blocked"
private const val KEY_ROLE_PROMPTED = "role_prompted"
private const val KEY_NOTIFY_PROMPTED = "notify_prompted"

private const val REQ_ROLE = 1
private const val REQ_NOTIFY = 2
private val REPEAT_WINDOWS = intArrayOf(5, 10, 15, 30, 60)

// Shared by the app and the Quick Settings tile. Turning off blocking
// or repeat callers forgets all recently blocked numbers.
fun Context.saveSwitch(key: String, on: Boolean) {
    getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply()
    if (!on && (key == KEY_ENABLED || key == KEY_REPEAT_CALLERS)) {
        getSharedPreferences(PREFS_RECENT, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

fun Context.hasRole() =
    getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

class MainActivity : Activity() {
    private val toggle: Switch by view(R.id.toggle)
    private val toggleRow: View by view(R.id.toggle_row)
    private val notify: Switch by view(R.id.notify)
    private val notifyRow: View by view(R.id.notify_row)
    private val instantDecline: Switch by view(R.id.instant_decline)
    private val instantDeclineRow: View by view(R.id.instant_decline_row)
    private val repeatCallers: Switch by view(R.id.repeat_callers)
    private val repeatCallersRow: View by view(R.id.repeat_callers_row)
    private val repeatWindow: View by view(R.id.repeat_window)
    private val repeatWindowValue: TextView by view(R.id.repeat_window_value)
    private val stats: TextView by view(R.id.stats)
    private val permBanner: View by view(R.id.perm_banner)
    private val addTile: View by view(R.id.add_tile)
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var enableNotifyOnReturn = false
    // The tile and BlockService can change prefs while this screen is visible (opening the shade
    // doesn't always pause the activity). Kept in a field because prefs only holds listeners weakly.
    private val prefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    // Keeps "12 minutes ago" current while the screen is visible.
    private val ticker = object : Runnable {
        override fun run() {
            refreshStats()
            stats.postDelayed(this, DateUtils.MINUTE_IN_MILLIS)
        }
    }

    private fun <T : View> view(id: Int) = lazy(LazyThreadSafetyMode.NONE) { findViewById<T>(id) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setTitle(R.string.app_name) // Launchers also use the activity label, set full name here

        // Edge-to-edge enforced on API 35
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
        }

        findViewById<Button>(R.id.perm_button).setOnClickListener { openDefaultApps() }
        setUpRow(toggleRow, toggle)
        setUpRow(notifyRow, notify)
        setUpRow(instantDeclineRow, instantDecline)
        setUpRow(repeatCallersRow, repeatCallers)
        repeatWindow.setOnClickListener { pickRepeatWindow() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addTile.apply {
                visibility = View.VISIBLE
                setOnClickListener { requestAddTile() }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsChanged)
        if (!hasRole() && !prefs.getBoolean(KEY_ROLE_PROMPTED, false)) requestRole()
    }

    // Once on first launch. After that the banner opens Default apps instead.
    private fun requestRole() {
        prefs.edit().putBoolean(KEY_ROLE_PROMPTED, true).apply()
        try {
            // Needs ForResult: the dialog checks the calling package, which plain startActivity doesn't set.
            startActivityForResult(
                getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING),
                REQ_ROLE,
            )
        } catch (_: ActivityNotFoundException) {
            // The banner's button still leads to Default apps.
        }
    }

    private fun openDefaultApps() = startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))

    // Asked once on first launch then opens the app's notification settings.
    private fun setNotify(on: Boolean) {
        when {
            !on || canNotify() -> saveSwitch(KEY_NOTIFY, on)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !prefs.getBoolean(KEY_NOTIFY_PROMPTED, false) &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                prefs.edit().putBoolean(KEY_NOTIFY_PROMPTED, true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            }
            else -> notificationsBlocked()
        }
        refresh() // undo the switch's own change until it's actually allowed
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQ_NOTIFY && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            saveSwitch(KEY_NOTIFY, true)
        }
    }

    // The switch turns on in onResume if notifications were allowed there.
    private fun notificationsBlocked() {
        enableNotifyOnReturn = true
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsChanged)
        super.onDestroy()
    }

    // The whole row toggles the switch, and reports itself to accessibility services as that switch.
    private fun setUpRow(row: View, switch: Switch) {
        row.setOnClickListener { switch.toggle() }
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            @Suppress("DEPRECATION") // the int replacements need API 36
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = switch.isChecked
            }
        }
    }

    private fun repeatWindow() = prefs.getInt(KEY_REPEAT_WINDOW, DEFAULT_REPEAT_WINDOW)

    private fun pickRepeatWindow() {
        AlertDialog.Builder(this)
            .setTitle(R.string.repeat_window)
            .setSingleChoiceItems(
                REPEAT_WINDOWS.map { resources.getQuantityString(R.plurals.minutes, it, it) }.toTypedArray(),
                REPEAT_WINDOWS.indexOf(repeatWindow()),
            ) { dialog, which ->
                prefs.edit().putInt(KEY_REPEAT_WINDOW, REPEAT_WINDOWS[which]).apply()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Android 13+ can offer to add the tile directly.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestAddTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, BlockTile::class.java),
            getString(R.string.launcher_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result ->
            if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                Toast.makeText(this, R.string.tile_already_added, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Not cleared otherwise: onResume also runs between the permission result and Settings opening.
        if (enableNotifyOnReturn && canNotify()) {
            enableNotifyOnReturn = false
            saveSwitch(KEY_NOTIFY, true)
        }
        refresh() // also runs after the permission/role dialogs close, and after visiting Settings
        stats.postDelayed(ticker, DateUtils.MINUTE_IN_MILLIS)
    }

    override fun onPause() {
        stats.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val hasRole = hasRole()

        // Show off whenever blocking cannot actually run, without overwriting the saved choice.
        val enabled = hasRole && prefs.getBoolean(KEY_ENABLED, true)
        bind(toggleRow, toggle, KEY_ENABLED, enabled, hasRole) // greyed out until the banner is resolved
        bind(notifyRow, notify, prefs.getBoolean(KEY_NOTIFY, false) && canNotify(), enabled, ::setNotify)
        bind(instantDeclineRow, instantDecline, KEY_INSTANT_DECLINE,
            prefs.getBoolean(KEY_INSTANT_DECLINE, true), enabled)
        bind(repeatCallersRow, repeatCallers, KEY_REPEAT_CALLERS,
            prefs.getBoolean(KEY_REPEAT_CALLERS, true), enabled)
        val window = repeatWindow()
        repeatWindowValue.text = resources.getQuantityString(R.plurals.repeat_callers_desc, window, window)
        repeatWindow.setEnabledDeep(enabled)
        permBanner.visibility = if (hasRole) View.GONE else View.VISIBLE
        addTile.setEnabledDeep(hasRole)
        refreshStats()
    }

    // Counted by BlockService; this screen updates live through prefsChanged.
    private fun refreshStats() {
        val count = prefs.getInt(KEY_BLOCKED_COUNT, 0)
        stats.text = if (count == 0) {
            getString(R.string.stats_none)
        } else {
            val since = DateUtils.formatDateTime(
                this, prefs.getLong(KEY_FIRST_BLOCKED, 0), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
            val recent =
                if (prefs.getBoolean(KEY_LAST_HIDDEN, false)) R.string.stats_recent_hidden else R.string.stats_recent
            resources.getQuantityString(R.plurals.stats_total, count, count, since) + "\n" +
                getString(recent, lastBlockedAgo())
        }
    }

    // "Just now", "12 minutes ago", "Yesterday", then a date after a week.
    private fun lastBlockedAgo(): CharSequence {
        val last = prefs.getLong(KEY_LAST_BLOCKED, 0)
        val now = System.currentTimeMillis()
        if (now - last < DateUtils.MINUTE_IN_MILLIS) return getString(R.string.just_now)
        return DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS)
    }

    // Detach the listener while setting state so only real taps are persisted.
    private fun bind(row: View, view: Switch, key: String, checked: Boolean, enabled: Boolean) =
        bind(row, view, checked, enabled) { saveSwitch(key, it) }

    private fun bind(row: View, view: Switch, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
        view.setOnCheckedChangeListener(null)
        if (view.isChecked != checked) view.isChecked = checked
        view.setOnCheckedChangeListener { _, on -> onChange(on) }
        if (view.isEnabled != enabled) {
            row.setEnabledDeep(enabled)
            view.jumpDrawablesToCurrentState()
        }
    }

    // Disabling a layout doesn't grey out its children, so walk them too.
    private fun View.setEnabledDeep(enabled: Boolean) {
        isEnabled = enabled
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).setEnabledDeep(enabled)
    }
}
