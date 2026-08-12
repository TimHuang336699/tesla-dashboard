package com.tesla.dashboard.ui.plugins

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.tesla.dashboard.BuildConfig
import com.tesla.dashboard.R
import com.tesla.dashboard.data.remote.PluginCatalogRepository
import com.tesla.dashboard.databinding.ActivityPluginCenterBinding
import com.tesla.dashboard.databinding.ItemSettingsRowBinding
import com.tesla.dashboard.databinding.ItemSettingsSectionHeaderBinding
import com.tesla.dashboard.databinding.ItemSettingsSwitchBinding
import com.tesla.dashboard.plugin.PluginCategory
import com.tesla.dashboard.plugin.PluginManager
import com.tesla.dashboard.plugin.PluginUiState
import com.tesla.dashboard.plugin.ble.BleExtensionPlugin
import com.tesla.dashboard.plugin.market.MarketPluginInfo
import com.tesla.dashboard.util.AppLog
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import com.tesla.dashboard.util.VersionUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as KJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 鎻掍欢涓績 (v0.5.2 鎻掍欢绯荤粺 + v0.5.3 鎻掍欢甯傚満鍦ㄧ嚎鍖?
 *
 * 涓や釜椤电:
 * - "宸插畨瑁?: 灞曠ず鎵€鏈夊凡娉ㄥ唽鎻掍欢 (鎸夊垎绫诲垎缁?, 鏀寔鍚敤/鍋滅敤;
 *   鐐瑰嚮宸插惎鐢ㄧ殑 BLE 鎷撳睍鍛戒护鎻掍欢杩涘叆鍛戒护椤甸潰 [BleExtensionActivity]
 * - "甯傚満": 浠?[PluginCatalogRepository.CATALOG_URL] 鎷夊彇 plugin-catalog.json,
 *   灞曠ず澶栭儴鍙€夋彃浠? 鏀寔鍒锋柊 / 鍏煎鎬ф鏌?/ APK 涓嬭浇
 *   (鍔ㄦ€佸姞杞戒粛鍦ㄥ畨鍏ㄥ璁′腑)
 */
@AndroidEntryPoint
class PluginCenterActivity : BaseImmersiveActivity() {

    private lateinit var binding: ActivityPluginCenterBinding

    @Inject
    lateinit var pluginManager: PluginManager

    @Inject
    lateinit var catalogRepository: PluginCatalogRepository

    /** 甯傚満鎻掍欢鍒楄〃 */
    private var marketPlugins: List<MarketPluginInfo> = emptyList()

    /** 甯傚満鍔犺浇鐘舵€? loading / error / null */
    private var marketStatus: String? = null

    /** 涓嬭浇鐘舵€侀泦鍚?(鎻掍欢 ID 鈫?鐘舵€? */
    private val downloadStates = mutableMapOf<String, String>()

    companion object {
        const val STATE_DOWNLOADING = "downloading"
        const val STATE_DOWNLOADED = "downloaded"
    }

    /** 姝ｅ湪杩愯鐨勫競鍦哄姞杞?job (鐢ㄤ簬鍙栨秷骞跺彂璇锋眰) */
    private var marketJob: KJob? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPluginCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = getString(R.string.settings_plugin_center)
        setupTabs()
        observePlugins()
        applyThemeColors(themeManager.colors.value)
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(R.string.plugin_center_tab_installed),
        )
        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(R.string.plugin_center_tab_market),
        )
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun switchTab(position: Int) {
        val showInstalled = position == 0
        binding.scrollInstalled.visibility = if (showInstalled) View.VISIBLE else View.GONE
        binding.scrollMarket.visibility = if (showInstalled) View.GONE else View.VISIBLE
        if (!showInstalled && marketStatus == null && marketPlugins.isEmpty()) {
            loadMarket(forceRefresh = false)
        }
    }

    // ===== 宸插畨瑁呴〉绛?=====

    private fun observePlugins() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pluginManager.pluginStates.collect { states ->
                    if (states.isEmpty()) return@collect
                    renderPlugins(states)
                }
            }
        }
    }

    private fun renderPlugins(states: List<PluginUiState>) {
        val container = binding.installedContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        var lastCategory: PluginCategory? = null

        states.forEach { state ->
            // 鍒嗙被鍒嗙粍澶?
            val category = state.plugin.category
            if (category != lastCategory) {
                lastCategory = category
                val headerBinding = ItemSettingsSectionHeaderBinding.inflate(inflater, container, false)
                headerBinding.tvSectionTitle.setText(category.labelRes)
                container.addView(headerBinding.root)
            }

            val rowBinding = ItemSettingsSwitchBinding.inflate(inflater, container, false)
            rowBinding.tvRowTitle.text = getString(state.plugin.nameRes)
            val summary = buildString {
                append(getString(state.plugin.descriptionRes))
                append("  路  v")
                append(state.plugin.version)
                if (state.plugin.isExperimental) {
                    append("  路  ")
                    append(getString(R.string.plugin_experimental_tag))
                }
            }
            rowBinding.tvRowSummary.text = summary
            rowBinding.switchRow.isChecked = state.enabled
            rowBinding.switchRow.setOnCheckedChangeListener { _, isChecked ->
                pluginManager.setEnabled(state.plugin.id, isChecked)
            }
            rowBinding.root.setOnClickListener {
                // 鐐瑰嚮琛? 杩涘叆 BLE 鎷撳睍鍛戒护椤甸潰 (浠呭綋鍚敤)
                if (state.enabled && state.plugin is BleExtensionPlugin) {
                    startActivity(Intent(this, BleExtensionActivity::class.java))
                } else {
                    rowBinding.switchRow.toggle()
                }
            }
            container.addView(rowBinding.root)
        }

        applyThemeColors(currentColors)
    }

    // ===== 甯傚満椤电 =====

    /**
     * 鎷夊彇甯傚満鐩綍骞舵覆鏌?
     *
     * @param forceRefresh 寮哄埗鑱旂綉鍒锋柊 (蹇界暐缂撳瓨)
     */
    private fun loadMarket(forceRefresh: Boolean) {
        marketJob?.cancel()
        marketStatus = "loading"
        renderMarket()
        marketJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                catalogRepository.fetchCatalog(forceRefresh)
            }
            if (!isActive) return@launch
            result.onSuccess { plugins ->
                marketPlugins = plugins
                marketStatus = null
                renderMarket()
            }.onFailure {
                if (!isActive) return@onFailure
                marketStatus = "error"
                renderMarket()
            }
        }
    }

    private fun renderMarket() {
        val container = binding.marketContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // 鍒锋柊琛?
        val refreshBinding = ItemSettingsRowBinding.inflate(inflater, container, false)
        refreshBinding.tvRowTitle.setText(R.string.plugin_market_refresh)
        refreshBinding.tvRowSummary.text = getString(R.string.plugin_market_source_summary)
        refreshBinding.tvRowChevron.text = getString(R.string.plugin_market_refresh_icon)
        refreshBinding.root.setOnClickListener { loadMarket(forceRefresh = true) }
        container.addView(refreshBinding.root)

        // 璇存槑琛?
        val noteBinding = ItemSettingsRowBinding.inflate(inflater, container, false)
        noteBinding.tvRowTitle.setText(R.string.plugin_market_note_title)
        noteBinding.tvRowSummary.setText(R.string.plugin_market_note_summary)
        noteBinding.tvRowChevron.visibility = View.GONE
        container.addView(noteBinding.root)

        when (marketStatus) {
            "loading" -> {
                addMessageRow(container, inflater, getString(R.string.plugin_market_loading))
                return
            }
            "error" -> {
                addMessageRow(container, inflater, getString(R.string.plugin_market_load_failed))
                return
            }
        }

        if (marketPlugins.isEmpty()) {
            addMessageRow(container, inflater, getString(R.string.plugin_market_empty))
            return
        }

        marketPlugins.forEach { plugin ->
            val rowBinding = ItemSettingsRowBinding.inflate(inflater, container, false)
            rowBinding.root.tag = plugin.id
            rowBinding.tvRowTitle.text = plugin.name
            val installed = pluginManager.getPlugin(plugin.id) != null
            val compatible = VersionUtils.meetsMinimum(BuildConfig.VERSION_NAME, plugin.minAppVersion)
            val summary = buildString {
                append(plugin.description.ifBlank { getString(R.string.plugin_market_no_description) })
                append("  路  v")
                append(plugin.version)
                append("  路  ")
                append(plugin.categoryEnum?.labelRes?.let { getString(it) }
                    ?: getString(R.string.plugin_market_category_unknown))
                if (plugin.experimental) {
                    append("  路  ")
                    append(getString(R.string.plugin_experimental_tag))
                }
                if (plugin.minAppVersion != null && !compatible) {
                    append("  路  ")
                    append(getString(R.string.plugin_market_incompatible, plugin.minAppVersion))
                }
            }
            rowBinding.tvRowSummary.text = summary
            rowBinding.tvRowChevron.text = statusText(plugin, installed, compatible)
            rowBinding.root.setOnClickListener {
                val downloading = downloadStates[plugin.id] == STATE_DOWNLOADING
                if (!installed && compatible && plugin.downloadUrl != null && !downloading) {
                    startDownload(plugin)
                }
            }
            container.addView(rowBinding.root)
        }

        applyThemeColors(currentColors)
        tintMarketChevrons(currentColors)
    }

    /**
     * 鏍规嵁褰撳墠涓婚閲嶅埛甯傚満椤电鍚勮鍙充晶鍥炬爣棰滆壊
     * - 鍒锋柊琛?鈫?accentCyan
     * - 鎻掍欢琛?鈫?鍙笅杞?宸插畨瑁呬负 accentCyan, 鍚﹀垯 textSecondary
     */
    private fun tintMarketChevrons(c: ThemeColors) {
        val container = binding.marketContainer
        if (container.childCount < 2) return
        // 鍒锋柊琛?(index 0)
        container.getChildAt(0).findViewById<TextView>(R.id.tvRowChevron)?.setTextColor(c.accentCyan)
        // 鎻掍欢琛?(浠?index 2 寮€濮?
        for (i in 2 until container.childCount) {
            val child = container.getChildAt(i)
            val id = child.tag as? String ?: continue
            val chevron = child.findViewById<TextView>(R.id.tvRowChevron) ?: continue
            if (chevron.visibility != View.VISIBLE) continue
            val plugin = marketPlugins.find { it.id == id } ?: continue
            val installed = pluginManager.getPlugin(plugin.id) != null
            val compatible = VersionUtils.meetsMinimum(BuildConfig.VERSION_NAME, plugin.minAppVersion)
            chevron.setTextColor(if (!installed && compatible && plugin.downloadUrl != null) c.accentCyan else c.textSecondary)
        }
    }

    /** 琛屽彸渚х姸鎬佹枃鏈?(涓嬭浇 / 宸蹭笅杞?/ 宸插畨瑁?/ 涓嶅吋瀹? */
    private fun statusText(plugin: MarketPluginInfo, installed: Boolean, compatible: Boolean): String = when {
        installed -> getString(R.string.plugin_market_action_installed)
        !compatible -> getString(R.string.plugin_market_incompatible_short)
        downloadStates[plugin.id] == STATE_DOWNLOADING -> getString(R.string.plugin_market_action_downloading)
        downloadStates[plugin.id] == STATE_DOWNLOADED -> getString(R.string.plugin_market_action_downloaded)
        plugin.downloadUrl != null -> getString(R.string.plugin_market_action_download)
        else -> "—"
    }

    private fun addMessageRow(
        container: android.widget.LinearLayout,
        inflater: LayoutInflater,
        message: String,
    ) {
        val rowBinding = ItemSettingsRowBinding.inflate(inflater, container, false)
        rowBinding.tvRowTitle.text = message
        rowBinding.tvRowSummary.visibility = View.GONE
        rowBinding.tvRowChevron.visibility = View.GONE
        container.addView(rowBinding.root)
    }

    /**
     * 涓嬭浇鎻掍欢 APK 鍒板簲鐢ㄧ鏈夌洰褰?(filesDir/plugins/<id>.apk)
     */
    private fun startDownload(plugin: MarketPluginInfo) {
        val url = plugin.downloadUrl ?: return
        downloadStates[plugin.id] = STATE_DOWNLOADING
        renderMarket()
        lifecycleScope.launch {
            val result = runCatching {
                catalogRepository.downloadApk(url, "${plugin.id}.apk")
            }
            result.onSuccess {
                downloadStates[plugin.id] = STATE_DOWNLOADED
                AppLog.d("PluginMarket", "Downloaded ${plugin.id}")
                Toast.makeText(applicationContext, R.string.plugin_market_download_done, Toast.LENGTH_SHORT).show()
            }.onFailure {
                downloadStates.remove(plugin.id)
                AppLog.w("PluginMarket", "Download failed: ${it.message}")
                Toast.makeText(applicationContext, R.string.plugin_market_download_failed, Toast.LENGTH_SHORT).show()
            }
            renderMarket()
        }
    }

    override fun applyThemeColors(c: ThemeColors) {
        currentColors = c
        binding.rootLayout.setBackgroundColor(c.background)
        binding.btnBack.imageTintList = ColorStateList.valueOf(c.accentCyan)
        binding.tvTitle.setTextColor(c.textPrimary)
        binding.tabLayout.setTabTextColors(c.textSecondary, c.accentCyan)
        binding.tabLayout.setSelectedTabIndicatorColor(c.accentCyan)
        val containers = listOf(binding.installedContainer, binding.marketContainer)
        containers.forEach { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                child.findViewById<TextView>(R.id.tvSectionTitle)?.setTextColor(c.textSecondary)
                child.findViewById<TextView>(R.id.tvRowTitle)?.setTextColor(c.textPrimary)
                child.findViewById<TextView>(R.id.tvRowSummary)?.setTextColor(c.textSecondary)
                child.findViewById<SwitchMaterial>(R.id.switchRow)?.apply {
                    thumbTintList = ColorStateList.valueOf(c.accentGreen)
                    trackTintList = ColorStateList.valueOf(c.divider)
                }
            }
        }
    }
}

