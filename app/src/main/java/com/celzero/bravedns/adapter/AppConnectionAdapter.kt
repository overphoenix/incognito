/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import recalibrated.systems.R
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.data.AppConnections
import recalibrated.systems.databinding.ListItemAppConnDetailsBinding
import com.celzero.bravedns.ui.AppConnectionBottomSheet
import com.celzero.bravedns.util.LoggerConstants

class AppConnectionAdapter(val context: Context, val connLists: List<AppConnections>,
                           val uid: Int) :
        RecyclerView.Adapter<AppConnectionAdapter.ConnectionDetailsViewHolder>(),
        AppConnectionBottomSheet.OnBottomSheetDialogFragmentDismiss {

    private lateinit var adapter: AppConnectionAdapter

    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int): AppConnectionAdapter.ConnectionDetailsViewHolder {
        val itemBinding = ListItemAppConnDetailsBinding.inflate(LayoutInflater.from(parent.context),
                                                                parent, false)
        adapter = this
        return ConnectionDetailsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: AppConnectionAdapter.ConnectionDetailsViewHolder,
                                  position: Int) {
        // updates the app-wise connections from network log to AppInfo screen
        holder.update(position)
    }

    override fun getItemCount(): Int {
        return connLists.size
    }

    override fun notifyDataset(position: Int) {
        this.notifyItemChanged(position)
    }

    inner class ConnectionDetailsViewHolder(private val b: ListItemAppConnDetailsBinding) :
            RecyclerView.ViewHolder(b.root) {
        fun update(position: Int) {
            displayTransactionDetails(position)
            setupClickListeners(connLists[position], position)
        }

        private fun setupClickListeners(appConn: AppConnections, position: Int) {
            b.acdContainer.setOnClickListener {
                val status = IpRulesManager.getStatus(uid, appConn.ipAddress)
                // open bottom sheet for options
                openBottomSheet(appConn.ipAddress, status, position)
            }
        }

        private fun openBottomSheet(ipAddress: String, ipRuleStatus: IpRulesManager.IpRuleStatus,
                                    position: Int) {
            if (context !is AppCompatActivity) {
                Log.wtf(LoggerConstants.LOG_TAG_UI, context.getString(R.string.ct_btm_sheet_error))
                return
            }

            val bottomSheetFragment = AppConnectionBottomSheet(adapter, uid, ipAddress,
                                                               ipRuleStatus, position)
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayTransactionDetails(position: Int) {
            val conn = connLists[position]

            b.acdCount.text = conn.count.toString()
            b.acdFlag.text = conn.flag
            b.acdIpAddress.text = conn.ipAddress
            if (conn.dnsQuery != null) {
                b.acdDomainName.text = conn.dnsQuery
            }

        }
    }

}
