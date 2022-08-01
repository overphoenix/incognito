/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import recalibrated.systems.BuildConfig
import com.celzero.bravedns.NonStoreAppUpdater
import recalibrated.systems.R
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RefreshDatabase
import recalibrated.systems.databinding.ActivityHomeScreenBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.PKG_NAME_PLAY_STORE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_UPDATE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.RemoteFileTagUtil
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getPackageMetadata
import com.celzero.bravedns.util.Utilities.Companion.isPlayStoreFlavour
import com.celzero.bravedns.util.Utilities.Companion.isWebsiteFlavour
import com.celzero.bravedns.util.Utilities.Companion.localBlocklistDownloadBasePath
import com.celzero.bravedns.util.Utilities.Companion.oldLocalBlocklistDownloadDir
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class HomeScreenActivity : AppCompatActivity(R.layout.activity_home_screen) {
    private val b by viewBinding(ActivityHomeScreenBinding::bind)

    private val refreshDatabase by inject<RefreshDatabase>()

    private lateinit var settingsFragment: SettingsFragment
    private lateinit var homeScreenFragment: HomeScreenFragment
    private lateinit var aboutFragment: AboutFragment

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val appUpdateManager by inject<AppUpdater>()

    /* TODO : This task need to be completed.
             Add all the appinfo in the global variable during appload
             Handle those things in the application instead of reaching to DB every time
             Call the coroutine scope to insert/update/delete the values */

    object GlobalVariable {
        var DEBUG = BuildConfig.DEBUG
    }

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        updateNewVersion()

        if (savedInstanceState == null) {
            homeScreenFragment = HomeScreenFragment()
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                              homeScreenFragment,
                                                              homeScreenFragment.javaClass.simpleName).commit()
        }

        setupNavigationItemSelectedListener()

        // TODO: Remove this from home screen and move it to vpn service
        IpRulesManager.loadIpRules()

        refreshDatabase.deleteOlderDataFromNetworkLogs()

        initUpdateCheck()

        observeAppState()
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(this) {
            if (it == BraveVPNService.State.PAUSED) {
                Utilities.openPauseActivityAndFinish(this)
            }
        }
    }

    private fun removeThisMethod() {
        // for version v054
        updateIfRethinkConnectedv053x()
        moveRemoteBlocklistFileFromAsset()

        io {
            moveLocalBlocklistFiles()

            // path: /data/data/com.celzero.bravedns/files
            val oldFolder = File(this.filesDir.canonicalPath)
            deleteUnwantedFolders(oldFolder)

            // already there is a local blocklist file available, complete the initial filetag read
            if (persistentState.localBlocklistTimestamp != INIT_TIME_MS) {
                RethinkBlocklistManager.readJson(this, AppDownloadManager.DownloadType.LOCAL,
                                                 persistentState.localBlocklistTimestamp)
            }
        }

    }

    // fixme: find a cleaner way to implement this, move this to some other place
    private fun moveRemoteBlocklistFileFromAsset() {
        io {
            // already there is a remote blocklist file available
            if (persistentState.remoteBlocklistTimestamp != INIT_TIME_MS) {
                RethinkBlocklistManager.readJson(this, AppDownloadManager.DownloadType.REMOTE,
                                                 persistentState.remoteBlocklistTimestamp)
                return@io
            }

            RemoteFileTagUtil.moveFileToLocalDir(this.applicationContext, persistentState)
        }
    }

    private fun updateIfRethinkConnectedv053x() {
        if (!appConfig.isRethinkDnsConnectedv053x()) return

        io {
            appConfig.updateRethinkPlusCountv053x(persistentState.getRemoteBlocklistCount())
            persistentState.dnsType = AppConfig.DnsType.RETHINK_REMOTE.type
            appConfig.enableRethinkDnsPlus()
        }
    }

    private fun moveLocalBlocklistFiles() {
        val path = oldLocalBlocklistDownloadDir(this, persistentState.localBlocklistTimestamp)
        val blocklistsExist = Constants.ONDEVICE_BLOCKLISTS.all {
            File(path + File.separator + it.filename).exists()
        }
        if (!blocklistsExist) return

        changeDefaultLocalBlocklistLocation()
    }

    // move the files of local blocklist to specific folder (../files/local_blocklist/<timestamp>)
    private fun changeDefaultLocalBlocklistLocation() {
        val baseDir = localBlocklistDownloadBasePath(this, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                     persistentState.localBlocklistTimestamp)
        File(baseDir).mkdirs()
        Constants.ONDEVICE_BLOCKLISTS.forEach {
            val currentFile = File(oldLocalBlocklistDownloadDir(this,
                                                                persistentState.localBlocklistTimestamp) + it.filename)
            val newFile = File(baseDir + File.separator + it.filename)
            com.google.common.io.Files.move(currentFile, newFile)
        }
    }

    private fun deleteUnwantedFolders(fileOrDirectory: File) {
        // TODO: delete the old folders with timestamp in the files dir (../files/<timestamp>)
        // this operation is performed after moving the local blocklist to
        // specific folder

        if (fileOrDirectory.name.startsWith("16")) {
            if (fileOrDirectory.isDirectory) {
                fileOrDirectory.listFiles()?.forEach { child ->
                    deleteUnwantedFolders(child)
                }
            }
            fileOrDirectory.delete()
        }
    }

    private fun updateNewVersion() {
        if (!isNewVersion()) return

        val version = getLatestVersion()
        persistentState.appVersion = version
        persistentState.showWhatsNewChip = true

        // FIXME: remove this post v054
        // this is to fix the local blocklist default download location
        removeThisMethod()
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    private fun getLatestVersion(): Int {
        val pInfo: PackageInfo? = getPackageMetadata(this.packageManager, this.packageName)
        return pInfo?.versionCode ?: 0
    }

    //FIXME - Move it to Android's built-in WorkManager
    private fun initUpdateCheck() {
        if (!isUpdateRequired()) return

        val diff = System.currentTimeMillis() - persistentState.lastAppUpdateCheck

        val daysElapsed = TimeUnit.MILLISECONDS.toDays(diff)
        Log.i(LOG_TAG_UI, "App update check initiated, number of days: $daysElapsed")
        if (daysElapsed <= 1L) return

        checkForUpdate()
    }

    private fun isUpdateRequired(): Boolean {
        val calendar: Calendar = Calendar.getInstance()
        val day: Int = calendar.get(Calendar.DAY_OF_WEEK)
        return (day == Calendar.FRIDAY || day == Calendar.SATURDAY) && persistentState.checkForAppUpdate
    }

    fun checkForUpdate(
            isInteractive: AppUpdater.UserPresent = AppUpdater.UserPresent.NONINTERACTIVE) {

        // Check updates only for play store / website version. Not fDroid.
        if (!isPlayStoreFlavour() && !isWebsiteFlavour()) {
            if (DEBUG) Log.d(LOG_TAG_APP_UPDATE,
                             "Check for update: Not play or website- ${BuildConfig.FLAVOR}")
            return
        }

        if (isGooglePlayServicesAvailable() && isPlayStoreFlavour()) {
            appUpdateManager.checkForAppUpdate(isInteractive, this,
                                               installStateUpdatedListener) // Might be play updater or web updater
        } else {
            get<NonStoreAppUpdater>().checkForAppUpdate(isInteractive, this,
                                                        installStateUpdatedListener) // Always web updater
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        // applicationInfo.enabled - When false, indicates that all components within
        // this application are considered disabled, regardless of their individually set enabled status.
        // TODO: prompt dialog to user that Playservice is disabled, so switch to update
        // check for website
        return Utilities.getApplicationInfo(this, PKG_NAME_PLAY_STORE)?.enabled ?: false
    }

    private val installStateUpdatedListener = object : AppUpdater.InstallStateListener {
        override fun onStateUpdate(state: AppUpdater.InstallState) {
            Log.i(LOG_TAG_UI, "InstallStateUpdatedListener: state: " + state.status)
            when (state.status) {
                AppUpdater.InstallStatus.DOWNLOADED -> {
                    //CHECK THIS if AppUpdateType.FLEXIBLE, otherwise you can skip
                    showUpdateCompleteSnackbar()
                }
                else -> {
                    appUpdateManager.unregisterListener(this)
                }
            }
        }

        override fun onUpdateCheckFailed(installSource: AppUpdater.InstallSource,
                                         isInteractive: AppUpdater.UserPresent) {
            runOnUiThread {
                if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                    showDownloadDialog(installSource,
                                       getString(R.string.download_update_dialog_failure_title),
                                       getString(R.string.download_update_dialog_failure_message))
                }
            }
        }

        override fun onUpToDate(installSource: AppUpdater.InstallSource,
                                isInteractive: AppUpdater.UserPresent) {
            runOnUiThread {
                if (isInteractive == AppUpdater.UserPresent.INTERACTIVE) {
                    showDownloadDialog(installSource,
                                       getString(R.string.download_update_dialog_message_ok_title),
                                       getString(R.string.download_update_dialog_message_ok))
                }
            }
        }

        override fun onUpdateAvailable(installSource: AppUpdater.InstallSource) {
            runOnUiThread {
                showDownloadDialog(installSource, getString(R.string.download_update_dialog_title),
                                   getString(R.string.download_update_dialog_message))
            }
        }

        override fun onUpdateQuotaExceeded(installSource: AppUpdater.InstallSource) {
            runOnUiThread {
                showDownloadDialog(installSource,
                                   getString(R.string.download_update_dialog_trylater_title),
                                   getString(R.string.download_update_dialog_trylater_message))
            }
        }
    }

    private fun showUpdateCompleteSnackbar() {
        val snack = Snackbar.make(b.container, getString(R.string.update_complete_snack_message),
                                  Snackbar.LENGTH_INDEFINITE)
        snack.setAction(
            getString(R.string.update_complete_action_snack)) { appUpdateManager.completeUpdate() }
        snack.setActionTextColor(ContextCompat.getColor(this, R.color.primaryLightColorText))
        snack.show()
    }

    private fun showDownloadDialog(source: AppUpdater.InstallSource, title: String,
                                   message: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setCancelable(true)
        if (message == getString(
                R.string.download_update_dialog_message_ok) || message == getString(
                R.string.download_update_dialog_failure_message) || message == getString(
                R.string.download_update_dialog_trylater_message)) {
            builder.setPositiveButton(
                getString(R.string.hs_download_positive_default)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
        } else {
            if (source == AppUpdater.InstallSource.STORE) {
                builder.setPositiveButton(
                    getString(R.string.hs_download_positive_play_store)) { _, _ ->
                    appUpdateManager.completeUpdate()
                }
            } else {
                builder.setPositiveButton(
                    getString(R.string.hs_download_positive_website)) { _, _ ->
                    initiateDownload()
                }
            }
            builder.setNegativeButton(
                getString(R.string.hs_download_negative_default)) { dialogInterface, _ ->
                persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                dialogInterface.dismiss()
            }
        }

        builder.create().show()
    }


    private fun initiateDownload() {
        val url = Constants.RETHINK_APP_DOWNLOAD_LINK
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = uri
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    override fun onStop() {
        super.onStop()
        try {
            appUpdateManager.unregisterListener(installStateUpdatedListener)
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG_DOWNLOAD, "Unregister receiver exception")
        }
    }

    private fun setupNavigationItemSelectedListener() {
        b.navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_internet_manager -> {
                    homeScreenFragment = HomeScreenFragment()
                    supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                                      homeScreenFragment,
                                                                      homeScreenFragment.javaClass.simpleName).commit()
                    return@setOnItemSelectedListener true
                }

                R.id.navigation_settings -> {
                    settingsFragment = SettingsFragment()
                    supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                                      settingsFragment,
                                                                      settingsFragment.javaClass.simpleName).commit()
                    return@setOnItemSelectedListener true
                }

                R.id.navigation_about -> {
                    aboutFragment = AboutFragment()
                    supportFragmentManager.beginTransaction().replace(R.id.fragment_container,
                                                                      aboutFragment,
                                                                      aboutFragment.javaClass.simpleName).commit()
                    return@setOnItemSelectedListener true
                }
            }
            false
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch {
            f()
        }
    }

}
