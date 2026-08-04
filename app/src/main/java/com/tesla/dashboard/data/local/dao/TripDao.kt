package com.tesla.dashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tesla.dashboard.data.local.entity.TripEntity
import kotlinx.coroutines.flow.Flow

/**
 * 行程记录数据访问对象(DAO)
 *
 * 提供行程的增删改查操作:
 * - 写操作(insert / update / delete)为 suspend,在 IO 线程执行
 * - 查询操作(getAllTrips / getTripById)返回 [Flow],支持响应式观察
 * - [getTripByIdNow] 为单次同步查询,用于 endTrip 等需要立即获取当前记录的场景
 */
@Dao
interface TripDao {

    /**
     * 插入一条行程记录
     *
     * @param trip 行程实体(id 传 0 时由 Room 自动生成)
     * @return 新插入记录的 id
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity): Long

    /**
     * 更新一条行程记录
     *
     * 根据 trip.id 匹配已有记录并更新全部字段。
     *
     * @param trip 行程实体(需包含有效的 id)
     */
    @Update
    suspend fun updateTrip(trip: TripEntity)

    /**
     * 删除一条行程记录
     *
     * @param trip 行程实体(需包含有效的 id)
     */
    @Delete
    suspend fun deleteTrip(trip: TripEntity)

    /**
     * 获取所有行程记录(按开始时间倒序)
     *
     * @return 行程列表 [Flow],数据库数据变化时自动更新
     */
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<TripEntity>>

    /**
     * 根据 id 获取行程记录(响应式)
     *
     * @param id 行程 id
     * @return 行程实体 [Flow],数据库数据变化时自动更新;不存在时发射 null
     */
    @Query("SELECT * FROM trips WHERE id = :id")
    fun getTripById(id: Long): Flow<TripEntity?>

    /**
     * 根据 id 同步获取行程记录(单次查询,非响应式)
     *
     * 用于 [com.tesla.dashboard.data.local.TripRepository.endTrip] 等
     * 需要立即获取当前记录的场景。
     *
     * @param id 行程 id
     * @return 行程实体,不存在则返回 null
     */
    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTripByIdNow(id: Long): TripEntity?
}
