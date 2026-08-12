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
 * 插件中心 (v0.5.2 插件系统 + v0.5.3 插件市场在线化)
 *
 * 两个页签:
 * - "已安装": 展示所有已注册插件 (按分类分组), 支持启用/停用;
 *   点击已启用的 BLE 拓展命令插件进入命令页面 [BleExtensionActivity]
 * - "市场": 从 [PluginCatalogRepository.CATALOG_URL] 拉取 plugin-catalog.json,
 *   展示外部可选插件, 支持刷新 / 兼容性检查 / APK 下载
 *   (动态加载仍在安全审计中)
 */
@AndroidEntryPoint
class PluginCenterActivity : BaseImmersiveActivity() {

    private lateinit var binding: ActivityPluginCenterBinding

    @Inject
    lateinit var pluginManager: PluginManager

    @Inject
    lateinit var catalogRepository: PluginCatalogRepository

    /** 市场插件列表 */
    private var marketPlugins: List<MarketPluginInfo> = emptyList()

    /** 市场加载状态: loading / error / null */
    private var marketStatus: String? = null

    /** 下载状态集合 (插件 ID → "downloading" | "downloaded") */
    private val downloadStates = mutableMapOf<String, String>()

    /** 正在运行的市场加载 job (用于取消并发请求) */
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

    // ===== 已安装页签 =====

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
            // 分类分组头
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
                append("  ·  v")
                append(state.plugin.version)
                if (state.plugin.isExperimental) {
                    append("  ·  ")
                    append(getString(R.string.plugin_experimental_tag))
                }
            }
            rowBinding.tvRowSummary.text = summary
            rowBinding.switchRow.isChecked = state.enabled
            rowBinding.switchRow.setOnCheckedChangeListener { _, isChecked ->
                pluginManager.setEnabled(state.plugin.id, isChecked)
            }
            rowBinding.root.setOnClickListener {
                // 点击行: 进入 BLE 拓展命令页面 (仅当启用)
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

    // ===== 市场页签 =====

    /**
     * 拉取市场目录并渲染
     *
     * @param forceRefresh 强制联网刷新 (忽略缓存)
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

        // 刷新行
        val refreshBinding = ItemSettingsRowBinding.inflate(inflater, container, false)
        refreshBinding.tvRowTitle.setText(R.string.plugin_market_refresh)
        refreshBinding.tvRowSummary.text = getString(R.string.plugin_market_source_summary)
        refreshBinding.tvRowChevron.text = getString(R.string.plugin_market_refresh_icon)
        refreshBinding.root.setOnClickListener { loadMarket(forceRefresh = true) }
        container.addView(refreshBinding.root)

        // 说明行
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
                append("  ·  v")
                append(plugin.version)
                append("  ·  ")
                append(plugin.categoryEnum?.labelRes?.let { getString(it) }
                    ?: getString(R.string.plugin_market_category_unknown))
                if (plugin.experimental) {
                    append("  ·  ")
                    append(getString(R.string.plugin_experimental_tag))
                }
                if (plugin.minAppVersion != null && !compatible) {
                    append("  ·  ")
                    append(getString(R.string.plugin_market_incompatible, plugin.minAppVersion))
                }
            }
            rowBinding.tvRowSummary.text = summary
            rowBinding.tvRowChevron.text = statusText(plugin, installed, compatible)
            rowBinding.root.setOnClickListener {
                val downloading = downloadStates[plugin.id] == "downloading"
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
     * 根据当前主题重刷市场页签各行右侧图标颜色
     * - 刷新行 → accentCyan
     * - 插件行 → 可下载/已安装为 accentCyan, 否则 textSecondary
     */
    private fun tintMarketChevrons(c: ThemeColors) {
        val container = binding.marketContainer
        if (container.childCount < 2) return
        // 刷新行 (index 0)
        container.getChildAt(0).findViewById<TextView>(R.id.tvRowChevron)?.setTextColor(c.accentCyan)
        // 插件行 (从 index 2 开始)
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

    /** 行右侧状态文本 (下载 / 已下载 / 已安装 / 不兼容) */
    private fun statusText(plugin: MarketPluginInfo, installed: Boolean, compatible: Boolean): String = when {
        installed -> getString(R.string.plugin_market_action_installed)
        !compatible -> getString(R.string.plugin_market_incompatible_short)
        downloadStates[plugin.id] == "downloading" -> getString(R.string.plugin_market_action_downloading)
        downloadStates[plugin.id] == "downloaded" -> getString(R.string.plugin_market_action_downloaded)
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
     * 下载插件 APK 到应用私有目录 (filesDir/plugins/<id>.apk)
     */
    private fun startDownload(plugin: MarketPluginInfo) {
        val url = plugin.downloadUrl ?: return
        downloadStates[plugin.id] = "downloading"
        renderMarket()
        lifecycleScope.launch {
            val result = runCatching {
                catalogRepository.downloadApk(url, "${plugin.id}.apk")
            }
            result.onSuccess {
                downloadStates[plugin.id] = "downloaded"
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
