package com.example.rinklnote

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.Source
import com.example.rinklnote.util.Money
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * 手动测试辅助：向设备本地 Room 库灌入「2026 年 8 月模拟账单」。
 * 运行：./gradlew :app:connectedAndroidTest --tests "com.example.rinklnote.MockBillsSeederTest"
 *
 * 说明：
 *  - 先按 App 种子逻辑补齐分类/账户（seedIfNeeded 幂等），再按名称反查 ID 灌入。
 *  - 仅追加，不删除任何既有账单（安全，可重复叠加；重复跑会产生重复 mock）。
 *  - source=APP、server_id=NULL —— 已登录且开启自动同步时，下次 sync 会上传服务端；
 *    只想本地测试请先登出或关闭自动同步（清除见 SQL 文件注释）。
 *  - 顺带写入 8 月预算 3000，用于验证超预算提示。日期按设备时区当天 0 点。
 */
@RunWith(AndroidJUnit4::class)
class MockBillsSeederTest {

    private data class Mock(
        val day: Int,
        val type: String,
        val cat: String,
        val sub: String?,
        val account: String,
        val amount: Double,
        val remark: String? = null
    )

    @Test
    fun seedAugustMockBills() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<RinklNoteApp>()
        // 确保分类/账户已存在（幂等）
        app.repository.seedIfNeeded()

        val db = app.database
        val catId = buildMap {
            db.categoryDao().getAllByType("EXPENSE").forEach { put(it.name, it.id) }
            db.categoryDao().getAllByType("INCOME").forEach { put(it.name, it.id) }
        }
        val accId = db.accountDao().getAll().associate { it.name to it.id }

        fun startOfDay(day: Int): Long =
            LocalDate.of(2026, 8, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val mocks = listOf(
            // 8/01
            Mock(1, "EXPENSE", "三餐", "早餐", "微信", 8.5),
            Mock(1, "EXPENSE", "三餐", "午餐", "微信", 25.0, "拉面"),
            Mock(1, "EXPENSE", "交通", "地铁", "支付宝", 4.0, "通勤"),
            Mock(1, "EXPENSE", "日用", "洗衣", "默认", 12.9),
            // 8/02
            Mock(2, "EXPENSE", "三餐", "午餐", "微信", 32.0, "午市套餐"),
            Mock(2, "EXPENSE", "娱乐", "电影", "支付宝", 45.0, "电影票"),
            Mock(2, "EXPENSE", "三餐", "晚餐", "微信", 18.0),
            // 8/03
            Mock(3, "EXPENSE", "三餐", "早餐", "微信", 6.0),
            Mock(3, "EXPENSE", "交通", "打车", "支付宝", 23.5, "打车到公司"),
            Mock(3, "EXPENSE", "网购", "淘宝", "微信", 89.9, "日用品"),
            // 8/04
            Mock(4, "EXPENSE", "三餐", "午餐", "微信", 28.0),
            Mock(4, "EXPENSE", "学习", "书籍", "支付宝", 59.0, "技术书"),
            // 8/05
            Mock(5, "EXPENSE", "三餐", "早餐", "微信", 8.0),
            Mock(5, "EXPENSE", "交通", "地铁", "支付宝", 4.0),
            Mock(5, "EXPENSE", "三餐", "晚餐", "微信", 22.0),
            Mock(5, "EXPENSE", "娱乐", "游戏", "微信", 30.0, "手游充值"),
            Mock(5, "INCOME", "工资", "基本工资", "默认", 12800.0, "8月工资"),
            // 8/06
            Mock(6, "EXPENSE", "三餐", "午餐", "微信", 26.0),
            Mock(6, "EXPENSE", "日用", "洗漱", "默认", 15.9),
            // 8/07
            Mock(7, "EXPENSE", "三餐", "早餐", "微信", 7.0),
            Mock(7, "EXPENSE", "交通", "公交", "默认", 2.0),
            Mock(7, "EXPENSE", "三餐", "午餐", "微信", 35.0, "会议餐"),
            // 8/08
            Mock(8, "EXPENSE", "三餐", "午餐", "微信", 24.0),
            Mock(8, "EXPENSE", "网购", "京东", "支付宝", 129.0, "蓝牙耳机"),
            Mock(8, "EXPENSE", "三餐", "晚餐", "微信", 20.0),
            // 8/09
            Mock(9, "EXPENSE", "三餐", "早餐", "微信", 6.5),
            Mock(9, "EXPENSE", "运动", "健身", "微信", 68.0, "健身月卡"),
            // 8/10
            Mock(10, "EXPENSE", "三餐", "午餐", "微信", 27.0),
            Mock(10, "EXPENSE", "交通", "打车", "支付宝", 19.0, "暴雨打车"),
            Mock(10, "EXPENSE", "日用", "家居", "默认", 39.0),
            // 8/11
            Mock(11, "EXPENSE", "三餐", "早餐", "微信", 8.0),
            Mock(11, "EXPENSE", "三餐", "午餐", "微信", 22.0),
            Mock(11, "EXPENSE", "娱乐", "旅游", "支付宝", 300.0, "周边游"),
            // 8/12
            Mock(12, "EXPENSE", "三餐", "早餐", "微信", 7.0),
            Mock(12, "EXPENSE", "三餐", "午餐", "微信", 30.0),
            Mock(12, "EXPENSE", "交通", "地铁", "支付宝", 4.0),
            // 8/13
            Mock(13, "EXPENSE", "三餐", "午餐", "微信", 25.0),
            Mock(13, "EXPENSE", "网购", "淘宝", "微信", 45.9, "零食"),
            // 8/14
            Mock(14, "EXPENSE", "三餐", "早餐", "微信", 7.5),
            Mock(14, "EXPENSE", "三餐", "晚餐", "微信", 21.0),
            Mock(14, "EXPENSE", "学习", "培训", "支付宝", 199.0, "线上课"),
            // 8/15
            Mock(15, "EXPENSE", "三餐", "午餐", "微信", 29.0),
            Mock(15, "EXPENSE", "日用", "洗漱", "默认", 18.8),
            Mock(15, "INCOME", "兼职", "项目", "支付宝", 1200.0, "外包费"),
            // 8/16
            Mock(16, "EXPENSE", "三餐", "早餐", "微信", 8.0),
            Mock(16, "EXPENSE", "交通", "公交", "默认", 2.0),
            Mock(16, "EXPENSE", "三餐", "午餐", "微信", 26.0),
            Mock(16, "EXPENSE", "娱乐", "电影", "支付宝", 55.0),
            // 8/17
            Mock(17, "EXPENSE", "三餐", "午餐", "微信", 24.0),
            Mock(17, "EXPENSE", "网购", "快递", "微信", 15.0, "运费"),
            // 8/18
            Mock(18, "EXPENSE", "三餐", "早餐", "微信", 6.0),
            Mock(18, "EXPENSE", "三餐", "午餐", "微信", 33.0, "聚餐"),
            Mock(18, "EXPENSE", "运动", "球类", "微信", 40.0, "羽毛球"),
            // 8/19
            Mock(19, "EXPENSE", "三餐", "午餐", "微信", 22.0),
            Mock(19, "EXPENSE", "日用", "家居", "默认", 25.5),
            Mock(19, "EXPENSE", "交通", "加油", "支付宝", 300.0, "油费"),
            // 8/20
            Mock(20, "EXPENSE", "三餐", "早餐", "微信", 8.0),
            Mock(20, "EXPENSE", "三餐", "午餐", "微信", 28.0),
            Mock(20, "EXPENSE", "网购", "淘宝", "微信", 79.0, "家居小物"),
            Mock(20, "INCOME", "理财", "基金", "微信", 150.0, "基金收益"),
            // 8/21
            Mock(21, "EXPENSE", "三餐", "午餐", "微信", 26.0),
            Mock(21, "EXPENSE", "交通", "打车", "支付宝", 27.5),
            Mock(21, "EXPENSE", "娱乐", "游戏", "微信", 30.0),
            // 8/22
            Mock(22, "EXPENSE", "三餐", "早餐", "微信", 7.0),
            Mock(22, "EXPENSE", "三餐", "午餐", "微信", 25.0),
            Mock(22, "EXPENSE", "三餐", "晚餐", "微信", 19.0),
            Mock(22, "EXPENSE", "日用", "洗衣", "默认", 20.0),
            // 8/23
            Mock(23, "EXPENSE", "三餐", "午餐", "微信", 30.0, "周末早午餐"),
            // 8/24
            Mock(24, "EXPENSE", "三餐", "早餐", "微信", 6.5),
            Mock(24, "EXPENSE", "三餐", "午餐", "微信", 23.0),
            Mock(24, "EXPENSE", "交通", "地铁", "支付宝", 4.0),
            Mock(24, "EXPENSE", "学习", "文具", "默认", 12.0, "笔记本"),
            // 8/25
            Mock(25, "EXPENSE", "三餐", "午餐", "微信", 27.0),
            Mock(25, "EXPENSE", "网购", "京东", "支付宝", 158.0, "机械键盘"),
            // 8/26
            Mock(26, "EXPENSE", "三餐", "早餐", "微信", 7.0),
            Mock(26, "EXPENSE", "三餐", "午餐", "微信", 24.0),
            Mock(26, "EXPENSE", "娱乐", "旅游", "支付宝", 150.0, "门票"),
            Mock(26, "INCOME", "其他", "红包", "微信", 200.0, "朋友红包"),
            // 8/27
            Mock(27, "EXPENSE", "三餐", "午餐", "微信", 29.0),
            Mock(27, "EXPENSE", "日用", "洗漱", "默认", 16.6),
            // 8/28
            Mock(28, "EXPENSE", "三餐", "早餐", "微信", 8.0),
            Mock(28, "EXPENSE", "三餐", "午餐", "微信", 25.0),
            Mock(28, "EXPENSE", "交通", "公交", "默认", 2.0),
            // 8/29
            Mock(29, "EXPENSE", "三餐", "午餐", "微信", 31.0),
            Mock(29, "EXPENSE", "娱乐", "电影", "支付宝", 50.0),
            // 8/30
            Mock(30, "EXPENSE", "三餐", "早餐", "微信", 7.0),
            Mock(30, "EXPENSE", "三餐", "午餐", "微信", 22.0),
            Mock(30, "EXPENSE", "网购", "淘宝", "微信", 63.8, "衣服"),
            // 8/31
            Mock(31, "EXPENSE", "三餐", "午餐", "微信", 28.0),
            Mock(31, "EXPENSE", "交通", "打车", "支付宝", 21.0),
            Mock(31, "EXPENSE", "日用", "家居", "默认", 35.0)
        )

        // 追加（不删除既有数据）。服务器同步默认以 date 起算，故用当天 0 点已足够。
        mocks.forEach { m ->
            val bill = Bill(
                amountMinor = Money.yuanToMinor(m.amount),
                billType = BillType.fromValue(m.type),
                categoryId = catId[m.cat] ?: error("未找到分类: ${m.cat}"),
                categoryName = m.cat,
                subCategoryName = m.sub,
                accountId = accId[m.account] ?: error("未找到账户: ${m.account}"),
                remark = m.remark,
                date = startOfDay(m.day),
                createdAt = startOfDay(m.day),
                source = Source.APP,
                deleted = false,
                dirty = false
            )
            db.billDao().insert(bill)
        }

        // 8 月预算 3000（验证超预算）；server_id=NULL 未同步，同样注意同步上传。
        db.budgetDao().upsert(
            Budget(
                monthStart = startOfDay(1),
                amountMinor = Money.yuanToMinor(3000.0)
            )
        )

        Log.i("MockBillsSeeder", "已写入 ${mocks.size} 条 8 月模拟账单 + 8 月预算 3000")
        assertTrue(mocks.isNotEmpty())
        assertTrue(catId.isNotEmpty())
        assertTrue(accId.isNotEmpty())
    }
}
