package com.tesla.dashboard.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tesla.dashboard.R
import com.tesla.dashboard.data.local.SettingsRepository
import com.tesla.dashboard.data.local.TripRepository
import com.tesla.dashboard.data.model.Trip
import com.tesla.dashboard.databinding.ActivityHistoryBinding
import com.tesla.dashboard.databinding.ItemTripBinding
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import com.tesla.dashboard.util.UnitFormatter
import com.tesla.dashboard.util.UnitSystem
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

/**
 * 历史行程页面
 *
 * 以 RecyclerView 列表展示所有已记录的行程,每行显示:
 * - 日期(行程开始时间)
 * - 里程(km)
 * - 时长(小时:分钟)
 * - 均速(km/h)
 *
 * ## 数据来源
 * 通过 Hilt 注入 [TripRepository],观察 [TripRepository.getAllTrips] 返回的 Flow。
 * 数据库数据变化时列表自动更新(响应式)。
 *
 * ## 空状态
 * 当没有行程记录时,显示空状态提示文本。
 *
 * @AndroidEntryPoint 使 Hilt 能在此 Activity 中进行字段注入。
 */
@AndroidEntryPoint
class HistoryActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例 */
    private lateinit var binding: ActivityHistoryBinding

    /** 行程记录仓库,由 Hilt 字段注入 */
    @Inject
    lateinit var tripRepository: TripRepository

    /** 设置仓库,由 Hilt 字段注入 (读取单位系统) */
    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** 列表适配器 */
    private val adapter = TripListAdapter()

    /** 当前单位系统 (由 unitSystemFlow 驱动, 切换后列表实时换算) */
    private var currentUnitSystem: UnitSystem = UnitSystem.METRIC

    /** 日期格式化器(线程安全: SimpleDateFormat 非线程安全,仅在主线程使用) */
    private val dateFormat by lazy {
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm",
            resources.configuration.getLocales()[0],
        )
    }

    /**
     * Activity 创建入口
     *
     * 1. 初始化 ViewBinding
     * 2. 配置 RecyclerView
     * 3. 开始观察行程数据
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 返回按钮
        binding.backButton.setOnClickListener { finish() }

        // 配置 RecyclerView
        binding.tripsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.tripsRecyclerView.adapter = adapter

        // 观察主题颜色 — 实时应用配色
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)

        // 观察单位系统 — 切换后列表实时换算刷新
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.unitSystemFlow.collect { unitCode ->
                    currentUnitSystem = UnitSystem.fromCode(unitCode)
                    adapter.notifyDataSetChanged()
                }
            }
        }

        // 观察行程数据
        observeTrips()
    }

    /**
     * 应用主题颜色到历史列表页
     *
     * @param c 当前主题颜色集合
     */
    override fun applyThemeColors(c: ThemeColors) {
        currentColors = c
        binding.rootLayout.setBackgroundColor(c.background)
        binding.backButton.setTextColor(c.accentCyan)
        binding.titleText.setTextColor(c.textPrimary)
    }

    /**
     * 观察行程列表数据
     *
     * 使用 [repeatOnLifecycle] 在 STARTED 状态下安全收集 Flow。
     * 当行程列表变化时更新适配器,并切换空状态显示。
     */
    private fun observeTrips() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tripRepository.getAllTrips().collect { trips ->
                    adapter.submitList(trips)

                    // 切换空状态显示
                    if (trips.isEmpty()) {
                        binding.emptyStateText.visibility = View.VISIBLE
                        binding.tripsRecyclerView.visibility = View.GONE
                    } else {
                        binding.emptyStateText.visibility = View.GONE
                        binding.tripsRecyclerView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    // ===== 行程列表适配器 =====

    /**
     * 行程列表 RecyclerView 适配器
     *
     * 使用简单 MutableList + notifyDataSetChanged() 方式,
     * 适合行程记录这种低频更新场景。
     * 每个列表项使用 [ItemTripBinding](ViewBinding)进行类型安全绑定。
     */
    private inner class TripListAdapter : RecyclerView.Adapter<TripListAdapter.TripViewHolder>() {

        /** 行程数据列表 */
        private val trips = mutableListOf<Trip>()

        /**
         * 更新列表数据
         *
         * @param newTrips 新的行程列表(来自数据库 Flow)
         */
        fun submitList(newTrips: List<Trip>) {
            trips.clear()
            trips.addAll(newTrips)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripViewHolder {
            val binding = ItemTripBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return TripViewHolder(binding)
        }

        override fun onBindViewHolder(holder: TripViewHolder, position: Int) {
            holder.bind(trips[position])
        }

        override fun getItemCount(): Int = trips.size

        /**
         * 行程列表项 ViewHolder
         *
         * 绑定 [Trip] 数据到 [ItemTripBinding] 中的各个 TextView。
         */
        inner class TripViewHolder(
            private val binding: ItemTripBinding,
        ) : RecyclerView.ViewHolder(binding.root) {

            /**
             * 绑定行程数据到列表项视图
             *
             * @param trip 行程数据
             */
            fun bind(trip: Trip) {
                // 日期: 格式化行程开始时间
                binding.dateText.text = dateFormat.format(Date(trip.startTime))

                // 里程: 保留 1 位小数 (按单位系统换算)
                binding.distanceText.text = itemView.context.getString(
                    R.string.format_distance_value,
                    UnitFormatter.distanceValue(trip.distanceKm, currentUnitSystem),
                    UnitFormatter.distanceUnit(itemView.context, currentUnitSystem),
                )

                // 时长: 格式化为 "Xh Ym" 或 "Xm Ys"
                binding.durationText.text = formatDuration(trip.durationSec)

                // 均速: 取整 (按单位系统换算)
                binding.avgSpeedText.text = itemView.context.getString(
                    R.string.format_speed_value,
                    UnitFormatter.speedValue(trip.avgSpeed, currentUnitSystem),
                    UnitFormatter.speedUnit(itemView.context, currentUnitSystem),
                )
            }

            /**
             * 格式化时长
             *
             * @param seconds 总秒数
             * @return 格式化后的时长字符串,如 "1h 23m" 或 "5m 30s"
             */
            private fun formatDuration(seconds: Long): String {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60

                return when {
                    hours > 0 -> itemView.context.getString(
                        R.string.format_duration_h_m, hours, minutes
                    )
                    minutes > 0 -> itemView.context.getString(
                        R.string.format_duration_m_s, minutes, secs
                    )
                    else -> itemView.context.getString(R.string.format_duration_s, secs)
                }
            }
        }
    }
}
