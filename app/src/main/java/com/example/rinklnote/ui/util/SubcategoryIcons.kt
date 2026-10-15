package com.example.rinklnote.ui.util

import androidx.annotation.DrawableRes
import com.example.rinklnote.R

/** 由 SVG 图标目录生成；按母分类区分同名子标签，未知自建标签回落到母分类图标。 */
@DrawableRes
fun subcategoryIconRes(categoryName: String, subcategoryName: String?): Int {
    if (subcategoryName == null || subcategoryName == "不细分") return R.drawable.ic_subcategory_none
    return subcategoryIcons[categoryName]?.get(subcategoryName) ?: categoryIconRes(categoryName)
}

private val subcategoryIcons = mapOf(
    "三餐" to mapOf(
        "早餐" to R.drawable.ic_subcategory_meals_breakfast,
        "午餐" to R.drawable.ic_subcategory_meals_lunch,
        "晚餐" to R.drawable.ic_subcategory_meals_dinner,
        "零食" to R.drawable.ic_subcategory_meals_snack,
        "外卖" to R.drawable.ic_subcategory_meals_takeaway,
        "饮品" to R.drawable.ic_subcategory_meals_drink,
    ),
    "交通" to mapOf(
        "公交" to R.drawable.ic_subcategory_transport_bus,
        "地铁" to R.drawable.ic_subcategory_transport_train,
        "打车" to R.drawable.ic_subcategory_transport_taxi,
        "加油" to R.drawable.ic_subcategory_transport_fuel,
        "停车费" to R.drawable.ic_subcategory_transport_parking,
        "火车机票" to R.drawable.ic_subcategory_transport_ticket,
        "共享单车" to R.drawable.ic_subcategory_transport_bike,
    ),
    "日用" to mapOf(
        "洗衣" to R.drawable.ic_subcategory_daily_laundry,
        "洗漱" to R.drawable.ic_subcategory_daily_wash,
        "家居" to R.drawable.ic_subcategory_daily_sofa,
        "纸品清洁" to R.drawable.ic_subcategory_daily_tissue,
    ),
    "学习" to mapOf(
        "书籍" to R.drawable.ic_subcategory_study_book,
        "文具" to R.drawable.ic_subcategory_study_stationery,
        "培训" to R.drawable.ic_subcategory_study_training,
        "考试" to R.drawable.ic_subcategory_study_exam,
        "课程" to R.drawable.ic_subcategory_study_lesson,
    ),
    "运动" to mapOf(
        "健身" to R.drawable.ic_subcategory_sports_dumbbell,
        "跑步" to R.drawable.ic_subcategory_sports_run,
        "球类" to R.drawable.ic_subcategory_sports_ball,
    ),
    "娱乐" to mapOf(
        "电影" to R.drawable.ic_subcategory_entertainment_film,
        "游戏" to R.drawable.ic_subcategory_entertainment_game,
        "旅游" to R.drawable.ic_subcategory_entertainment_travel,
        "演出" to R.drawable.ic_subcategory_entertainment_stage,
        "KTV" to R.drawable.ic_subcategory_entertainment_microphone,
    ),
    "网购" to mapOf(
        "淘宝" to R.drawable.ic_subcategory_shopping_bag,
        "京东" to R.drawable.ic_subcategory_shopping_cart,
        "快递" to R.drawable.ic_subcategory_shopping_parcel,
    ),
    "医疗" to mapOf(
        "门诊" to R.drawable.ic_subcategory_medical_clinic,
        "药品" to R.drawable.ic_subcategory_medical_capsule,
        "体检" to R.drawable.ic_subcategory_medical_health,
        "口腔" to R.drawable.ic_subcategory_medical_tooth,
        "眼镜" to R.drawable.ic_subcategory_medical_glasses,
    ),
    "居家" to mapOf(
        "房租" to R.drawable.ic_subcategory_home_house,
        "房贷" to R.drawable.ic_subcategory_home_mortgage,
        "物业" to R.drawable.ic_subcategory_home_building,
        "水电燃气" to R.drawable.ic_subcategory_home_utilities,
        "宽带" to R.drawable.ic_subcategory_home_wifi,
    ),
    "人情" to mapOf(
        "红包礼金" to R.drawable.ic_subcategory_social_packet,
        "礼物" to R.drawable.ic_subcategory_social_gift,
        "请客" to R.drawable.ic_subcategory_social_dining,
        "随礼" to R.drawable.ic_subcategory_social_giftcard,
    ),
    "宠物" to mapOf(
        "粮食" to R.drawable.ic_subcategory_pet_petfood,
        "医疗" to R.drawable.ic_subcategory_pet_petmedical,
        "用品" to R.drawable.ic_subcategory_pet_pettoy,
        "洗护" to R.drawable.ic_subcategory_pet_petwash,
    ),
    "美妆个护" to mapOf(
        "护肤彩妆" to R.drawable.ic_subcategory_beauty_cosmetics,
        "理发美发" to R.drawable.ic_subcategory_beauty_scissors,
        "美容" to R.drawable.ic_subcategory_beauty_mirror,
    ),
    "服饰" to mapOf(
        "衣裤" to R.drawable.ic_subcategory_clothing_clothes,
        "鞋帽" to R.drawable.ic_subcategory_clothing_shoe,
        "配饰" to R.drawable.ic_subcategory_clothing_jewelry,
    ),
    "母婴" to mapOf(
        "奶粉尿布" to R.drawable.ic_subcategory_baby_bottle,
        "玩具" to R.drawable.ic_subcategory_baby_toy,
        "早教" to R.drawable.ic_subcategory_baby_early,
    ),
    "汽车" to mapOf(
        "加油" to R.drawable.ic_subcategory_car_fuel,
        "保养维修" to R.drawable.ic_subcategory_car_wrench,
        "保险" to R.drawable.ic_subcategory_car_carshield,
        "洗车" to R.drawable.ic_subcategory_car_carwash,
    ),
    "数码" to mapOf(
        "手机电脑" to R.drawable.ic_subcategory_digital_devices,
        "配件" to R.drawable.ic_subcategory_digital_plug,
        "软件会员" to R.drawable.ic_subcategory_digital_member,
    ),
    "保险" to mapOf(
        "社保商保" to R.drawable.ic_subcategory_insurance_shield,
        "车险" to R.drawable.ic_subcategory_insurance_carshield,
    ),
    "旅行" to mapOf(
        "机票火车" to R.drawable.ic_subcategory_travel_ticket,
        "酒店" to R.drawable.ic_subcategory_travel_hotel,
        "景点门票" to R.drawable.ic_subcategory_travel_landmark,
    ),
    "工资" to mapOf(
        "基本工资" to R.drawable.ic_subcategory_salary_wallet,
        "奖金" to R.drawable.ic_subcategory_salary_bonus,
        "补贴" to R.drawable.ic_subcategory_salary_subsidy,
    ),
    "兼职" to mapOf(
        "劳务" to R.drawable.ic_subcategory_parttime_labor,
        "项目" to R.drawable.ic_subcategory_parttime_project,
        "其他" to R.drawable.ic_subcategory_parttime_other,
    ),
    "理财" to mapOf(
        "利息" to R.drawable.ic_subcategory_finance_interest,
        "基金" to R.drawable.ic_subcategory_finance_fund,
        "股票" to R.drawable.ic_subcategory_finance_stock,
    ),
    "其他" to mapOf(
        "红包" to R.drawable.ic_subcategory_other_packet,
        "返还" to R.drawable.ic_subcategory_other_refund,
        "其他收入" to R.drawable.ic_subcategory_other_income,
    ),
    "报销" to mapOf(
        "差旅报销" to R.drawable.ic_subcategory_reimburse_travelreceipt,
        "日常报销" to R.drawable.ic_subcategory_reimburse_receipt,
    ),
    "二手转卖" to mapOf(
        "闲置出售" to R.drawable.ic_subcategory_resale_resale,
        "回款" to R.drawable.ic_subcategory_resale_payment,
    ),
    "红包礼金" to mapOf(
        "收红包" to R.drawable.ic_subcategory_redpacket_receivepacket,
        "压岁钱" to R.drawable.ic_subcategory_redpacket_lucky,
        "礼金" to R.drawable.ic_subcategory_redpacket_giftmoney,
    ),
)
