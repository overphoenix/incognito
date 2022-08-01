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

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import recalibrated.systems.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ProxyEndpoint
import recalibrated.systems.databinding.DialogSetHttpProxyBinding
import recalibrated.systems.databinding.DialogSetProxyBinding
import recalibrated.systems.databinding.FragmentSettingsScreenBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.INVALID_PORT
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.delay
import com.celzero.bravedns.util.Utilities.Companion.isAtleastQ
import com.celzero.bravedns.util.Utilities.Companion.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment(R.layout.fragment_settings_screen) {
    private val b by viewBinding(FragmentSettingsScreenBinding::bind)

    private var proxyEndpoint: ProxyEndpoint? = null

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        setupClickListeners()
    }

    private fun initView() {
        b.settingsActivityAllowBypassProgress.visibility = View.GONE

        if (isFdroidFlavour()) {
            b.settingsActivityCheckUpdateRl.visibility = View.GONE
        }

        // allow apps part of the vpn to request networks outside of it, effectively letting it bypass the vpn itself
        b.settingsActivityAllowBypassSwitch.isChecked = persistentState.allowBypass
        // use all internet-capable networks, not just the active network, as underlying transports for the vpn tunnel
        b.settingsActivityAllNetworkSwitch.isChecked = persistentState.useMultipleNetworks
        // enable logs
        b.settingsActivityEnableLogsSwitch.isChecked = persistentState.logsEnabled
        // Auto start app after reboot
        b.settingsActivityAutoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        // Kill app when firewalled
        b.settingsActivityKillAppSwitch.isChecked = persistentState.killAppOnFirewall
        // check for app updates
        b.settingsActivityCheckUpdateSwitch.isChecked = persistentState.checkForAppUpdate
        // use custom download manager
        b.settingsActivityDownloaderSwitch.isChecked = persistentState.useCustomDownloadManager
        // for protocol translation, enable only on DNS/DNS+Firewall mode
        if (appConfig.getBraveMode().isDnsActive()) {
            b.settingsActivityPtransSwitch.isChecked = persistentState.protocolTranslationType
        } else {
            persistentState.protocolTranslationType = false
            b.settingsActivityPtransSwitch.isChecked = false
        }

        observeCustomProxy()

        displayInternetProtocolUi()
        displayAppThemeUi()
        displayNotificationActionUi()
    }

    private fun observeCustomProxy() {
        appConfig.connectedProxy.observe(viewLifecycleOwner) {
            proxyEndpoint = it
        }
    }

    private fun displayNotificationActionUi() {
        b.settingsActivityNotificationRl.isEnabled = true
        when (NotificationActionType.getNotificationActionType(
            persistentState.notificationActionType)) {
            NotificationActionType.PAUSE_STOP -> {
                b.genSettingsNotificationDesc.text = getString(R.string.settings_notification_desc,
                                                               getString(
                                                                   R.string.settings_notification_desc1))
            }
            NotificationActionType.DNS_FIREWALL -> {
                b.genSettingsNotificationDesc.text = getString(R.string.settings_notification_desc,
                                                               getString(
                                                                   R.string.settings_notification_desc2))
            }
            NotificationActionType.NONE -> {
                b.genSettingsNotificationDesc.text = getString(R.string.settings_notification_desc,
                                                               getString(
                                                                   R.string.settings_notification_desc3))
            }
        }
    }

    private fun displayAppThemeUi() {
        b.settingsActivityThemeRl.isEnabled = true
        when (persistentState.theme) {
            Themes.SYSTEM_DEFAULT.id -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(
                    R.string.settings_theme_dialog_themes_1))
            }
            Themes.LIGHT.id -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(
                    R.string.settings_theme_dialog_themes_2))
            }
            Themes.DARK.id -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(
                    R.string.settings_theme_dialog_themes_3))
            }
            else -> {
                b.genSettingsThemeDesc.text = getString(R.string.settings_selected_theme, getString(
                    R.string.settings_theme_dialog_themes_4))
            }
        }
    }

    private fun displayInternetProtocolUi() {
        b.settingsActivityIpRl.isEnabled = true
        when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                b.genSettingsIpDesc.text = getString(R.string.settings_selected_ip_desc,
                                                     getString(R.string.settings_ip_dialog_ipv4))
                b.settingsActivityPtransRl.visibility = View.GONE
            }
            InternetProtocol.IPv6.id -> {
                b.genSettingsIpDesc.text = getString(R.string.settings_selected_ip_desc,
                                                     getString(R.string.settings_ip_dialog_ipv6))
                b.settingsActivityPtransRl.visibility = View.VISIBLE
            }
            InternetProtocol.IPv46.id -> {
                b.genSettingsIpDesc.text = getString(R.string.settings_selected_ip_desc,
                                                     getString(R.string.settings_ip_dialog_ipv46))
                b.settingsActivityPtransRl.visibility = View.GONE
            }
            else -> {
                b.genSettingsIpDesc.text = getString(R.string.settings_selected_ip_desc,
                                                     getString(R.string.settings_ip_text_ipv46))
                b.settingsActivityPtransRl.visibility = View.GONE
            }
        }
    }

    private fun handleLockdownModeIfNeeded() {
        val isLockdown = VpnController.isVpnLockdown()
        if (isLockdown) {
            b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
            b.settingsActivityAllowBypassRl.alpha = 0.5f
        } else {
            b.settingsActivityVpnLockdownDesc.visibility = View.GONE
            b.settingsActivityAllowBypassRl.alpha = 1f
        }
        b.settingsActivityAllowBypassSwitch.isEnabled = !isLockdown
    }

    private fun setupClickListeners() {
        b.settingsActivityEnableLogsSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.logsEnabled = b
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.prefAutoStartBootUp = b
        }

        b.settingsActivityKillAppSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.killAppOnFirewall = b
        }


        b.settingsActivityCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.checkForAppUpdate = b
        }

        b.settingsActivityAllNetworkSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            if (b) {
                showAllNetworksDialog()
                return@setOnCheckedChangeListener
            }

            persistentState.useMultipleNetworks = b
        }

        b.settingsActivityAllowBypassSwitch.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            persistentState.allowBypass = checked
            b.settingsActivityAllowBypassSwitch.isEnabled = false
            b.settingsActivityAllowBypassSwitch.visibility = View.INVISIBLE
            b.settingsActivityAllowBypassProgress.visibility = View.VISIBLE

            delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
                if (isAdded) {
                    b.settingsActivityAllowBypassSwitch.isEnabled = true
                    b.settingsActivityAllowBypassProgress.visibility = View.GONE
                    b.settingsActivityAllowBypassSwitch.visibility = View.VISIBLE
                }
            }
        }

        b.settingsActivityVpnLockdownDesc.setOnClickListener {
            openVpnProfile(requireContext())
        }

        b.settingsActivityThemeRl.setOnClickListener {
            enableAfterDelay(500, b.settingsActivityThemeRl)
            showThemeDialog()
        }

        // Ideally this property should be part of VPN category / section.
        // As of now the VPN section will be disabled when the
        // VPN is in lockdown mode.
        // TODO - Find a way to place this property to place in correct section.
        b.settingsActivityNotificationRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityNotificationRl)
            showNotificationActionDialog()
        }

        b.settingsActivityDownloaderSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.useCustomDownloadManager = b
        }

        b.settingsActivityIpRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityIpRl)
            showIpDialog()
        }

        b.settingsActivityPtransSwitch.setOnCheckedChangeListener { _, isSelected ->
            if (appConfig.getBraveMode().isDnsActive()) {
                persistentState.protocolTranslationType = isSelected
            } else {
                b.settingsActivityPtransSwitch.isChecked = false
                showToastUiCentered(requireContext(),
                                    getString(R.string.settings_protocol_translation_dns_inactive),
                                    Toast.LENGTH_SHORT)
            }
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun showThemeDialog() {
        val alertBuilder = AlertDialog.Builder(requireContext())
        alertBuilder.setTitle(getString(R.string.settings_theme_dialog_title))
        val items = arrayOf(getString(R.string.settings_theme_dialog_themes_1),
                            getString(R.string.settings_theme_dialog_themes_2),
                            getString(R.string.settings_theme_dialog_themes_3),
                            getString(R.string.settings_theme_dialog_themes_4))
        val checkedItem = persistentState.theme
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.theme == which) {
                return@setSingleChoiceItems
            }

            persistentState.theme = which
            when (which) {
                Themes.SYSTEM_DEFAULT.id -> {
                    if (requireActivity().isDarkThemeOn()) {
                        setThemeRecreate(R.style.AppTheme)
                    } else {
                        setThemeRecreate(R.style.AppThemeWhite)
                    }
                }
                Themes.LIGHT.id -> {
                    setThemeRecreate(R.style.AppThemeWhite)
                }
                Themes.DARK.id -> {
                    setThemeRecreate(R.style.AppTheme)
                }
                Themes.TRUE_BLACK.id -> {
                    setThemeRecreate(R.style.AppThemeTrueBlack)
                }
            }
        }
        alertBuilder.create().show()
    }

    private fun showIpDialog() {
        val alertBuilder = AlertDialog.Builder(requireContext())
        alertBuilder.setTitle(getString(R.string.settings_ip_dialog_title))
        val items = arrayOf(getString(R.string.settings_ip_dialog_ipv4),
                            getString(R.string.settings_ip_dialog_ipv6),
                            getString(R.string.settings_ip_dialog_ipv46))
        val checkedItem = persistentState.internetProtocolType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            // return if already selected item is same as current item
            if (persistentState.internetProtocolType == which) {
                return@setSingleChoiceItems
            }

            val protocolType = InternetProtocol.getInternetProtocol(which)
            persistentState.internetProtocolType = protocolType.id

            displayInternetProtocolUi()
        }
        alertBuilder.create().show()
    }

    private fun setThemeRecreate(theme: Int) {
        requireActivity().setTheme(theme)
        requireActivity().recreate()
    }

    private fun showNotificationActionDialog() {
        val alertBuilder = AlertDialog.Builder(requireContext())
        alertBuilder.setTitle(getString(R.string.settings_notification_dialog_title))
        val items = arrayOf(getString(R.string.settings_notification_dialog_option_1),
                            getString(R.string.settings_notification_dialog_option_2),
                            getString(R.string.settings_notification_dialog_option_3))
        val checkedItem = persistentState.notificationActionType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            if (persistentState.notificationActionType == which) {
                return@setSingleChoiceItems
            }

            when (NotificationActionType.getNotificationActionType(which)) {
                NotificationActionType.PAUSE_STOP -> {
                    b.genSettingsNotificationDesc.text = getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc1))
                    persistentState.notificationActionType = NotificationActionType.PAUSE_STOP.action
                }
                NotificationActionType.DNS_FIREWALL -> {
                    b.genSettingsNotificationDesc.text = getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc2))
                    persistentState.notificationActionType = NotificationActionType.DNS_FIREWALL.action
                }
                NotificationActionType.NONE -> {
                    b.genSettingsNotificationDesc.text = getString(
                        R.string.settings_notification_desc,
                        getString(R.string.settings_notification_desc3))
                    persistentState.notificationActionType = NotificationActionType.NONE.action
                }
            }
        }
        alertBuilder.create().show()
    }

    override fun onResume() {
        super.onResume()
        handleLockdownModeIfNeeded()
    }

    private fun showAllNetworksDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.settings_all_networks_dialog_title))
        builder.setMessage(getString(R.string.settings_all_networks_dialog_message))
        builder.setPositiveButton(
            getString(R.string.settings_all_networks_dialog_positive_btn)) { dialog, _ ->
            b.settingsActivityAllNetworkSwitch.isChecked = true
            persistentState.useMultipleNetworks = true
            dialog.dismiss()
        }

        builder.setNegativeButton(
            getString(R.string.settings_all_networks_dialog_negative_btn)) { dialog, _ ->
            b.settingsActivityAllNetworkSwitch.isChecked = false
            persistentState.useMultipleNetworks = false
            dialog.dismiss()
        }
        builder.setCancelable(false)
        builder.create().show()
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        delay(ms, lifecycleScope) {
            if (!isAdded) return@delay

            for (v in views) v.isEnabled = true
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
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
