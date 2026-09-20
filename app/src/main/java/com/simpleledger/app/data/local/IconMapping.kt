package com.simpleledger.app.data.local

/*
 * emoji → iconId 的迁移映射表（唯一真相）。
 *
 * 【为什么需要它】
 * v3 及更早版本把图标以 emoji 字符串存在 `categories.emoji` / `sections.emoji`。
 * v4 起改为 `iconId`（Int，指向 docs/design/icons 的 50 枚手绘图标），
 * 迁移必须把历史值翻译过来，否则用户已有的分类会全部丢掉图标。
 *
 * 【三条约束 —— 改动前必读】
 * (1) 键用 `\uXXXX` 转义构造，源文件里**不出现 emoji 字符本身**。
 *     本项目的验收项之一是「源码零 emoji」，而这些键只用于翻译历史数据，
 *     不是要显示的视觉资产。
 * (2) 比对前先剥掉 U+FE0F（变体选择符）。有 6 个 emoji 在旧源码里带 VS16，
 *     但不同输入法/系统写入数据库时可能不带；剥掉后两种写法都能命中。
 *     见文件末尾 [VS16_ICON_IDS]。
 * (3) 与 docs/design/icons/manifest.json 的 iconId 1–50 **双向一一对应**。
 *     改动必须两边同步；`IconMappingTest` 会断言条目数、转义序列与双射性。
 */

object IconMapping {

    /** 分类默认图标：43 = tag（原分类默认值） */
    const val DEFAULT_CATEGORY_ICON_ID = 43

    /** 分区默认图标：1 = pin（原分区默认值） */
    const val DEFAULT_SECTION_ICON_ID = 1

    /** 合法的 iconId 区间，供 UI 侧做兜底校验 */
    val ICON_ID_RANGE: IntRange = 1..50

    /**
     * 50 条：emoji（已剥 VS16）→ iconId。
     * 顺序即 iconId 升序，便于与 manifest.json 逐行核对。
     */
    val EMOJI_TO_ICON_ID: Map<String, Int> = linkedMapOf(
        "\uD83D\uDCCC" to 1,  //  1  pin            分区默认 / 图钉
        "\uD83C\uDF5A" to 2,  //  2  rice-bowl      餐饮
        "\uD83C\uDF5C" to 3,  //  3  noodle-bowl    餐饮 / 面食
        "\u2615" to 4,  //  4  coffee         餐饮 / 饮品
        "\uD83C\uDF7A" to 5,  //  5  beer           餐饮 / 酒水
        "\uD83E\uDD57" to 6,  //  6  salad          餐饮 / 轻食
        "\uD83C\uDF69" to 7,  //  7  donut          餐饮 / 甜品
        "\uD83D\uDE8C" to 8,  //  8  bus            交通 / 公交
        "\uD83D\uDE97" to 9,  //  9  car            交通 / 自驾
        "\uD83D\uDE95" to 10,  // 10  taxi           交通 / 打车
        "\uD83D\uDEB2" to 11,  // 11  bicycle        交通 / 骑行
        "\u26FD" to 12,  // 12  fuel           交通 / 加油
        "\u2708" to 13,  // 13  plane          交通 / 出行
        "\uD83C\uDFE8" to 14,  // 14  hotel          旅行 / 住宿
        "\uD83D\uDECD" to 15,  // 15  bag            购物
        "\uD83C\uDFE0" to 16,  // 16  house          居住
        "\uD83D\uDD28" to 17,  // 17  hammer         装修
        "\uD83D\uDEE0" to 18,  // 18  tools          装修 / 工具
        "\uD83D\uDCA1" to 19,  // 19  bulb           水电 / 能耗
        "\uD83D\uDC8A" to 20,  // 20  pill           医疗 / 药品
        "\uD83C\uDFE5" to 21,  // 21  hospital       医疗 / 就诊
        "\uD83C\uDFAE" to 22,  // 22  gamepad        娱乐 / 游戏
        "\uD83C\uDFAC" to 23,  // 23  film           娱乐 / 影音
        "\u26BD" to 24,  // 24  ball           运动
        "\uD83C\uDFB8" to 25,  // 25  guitar         娱乐 / 乐器
        "\uD83D\uDCDA" to 26,  // 26  book           学习 / 书籍
        "\uD83C\uDF93" to 27,  // 27  grad-cap       学习 / 课程
        "\uD83D\uDCBB" to 28,  // 28  laptop         学习 / 数码
        "\uD83D\uDCF1" to 29,  // 29  phone          通讯 / 数码
        "\uD83D\uDC31" to 30,  // 30  pet            宠物
        "\uD83C\uDF81" to 31,  // 31  gift           礼物 / 人情
        "\uD83E\uDDE7" to 32,  // 32  red-envelope   红包
        "\uD83D\uDC76" to 33,  // 33  baby           育儿
        "\uD83D\uDC85" to 34,  // 34  nail           美容
        "\uD83D\uDC87" to 35,  // 35  haircut        理发
        "\uD83D\uDCB0" to 36,  // 36  money-bag      收入
        "\uD83D\uDCC8" to 37,  // 37  invest         理财 / 投资
        "\uD83E\uDDFE" to 38,  // 38  receipt        账单 / 票据
        "\uD83E\uDDF4" to 39,  // 39  lotion         日用
        "\uD83E\uDDF8" to 40,  // 40  teddy          玩具
        "\u2728" to 41,  // 41  sparkle        其他收入
        "\uD83D\uDCE6" to 42,  // 42  box            其他支出
        "\uD83C\uDFF7" to 43,  // 43  tag            分类默认
        "\uD83E\uDDF1" to 44,  // 44  brick          装修 / 主材
        "\uD83D\uDC77" to 45,  // 45  helmet         装修 / 人工
        "\uD83D\uDECB" to 46,  // 46  sofa           装修 / 家具
        "\uD83D\uDCFA" to 47,  // 47  tv             装修 / 家电
        "\uD83D\uDCD0" to 48,  // 48  ruler          装修 / 设计费
        "\uD83D\uDCB5" to 49,  // 49  banknote       装修 / 报销
        "\u21A9" to 50,  // 50  refund         装修 / 退款
    )

    /**
     * 生成迁移 SQL 的 CASE 体（不含 CASE / END），例如
     * `WHEN '\uD83C\uDF5A' THEN 2 WHEN ...`。
     *
     * 表内键已剥除 VS16，因此与 SQL 里
     * `CASE REPLACE(emoji, char(65039), '')` 的左侧一致。
     */
    fun sqlCaseWhen(): String =
        EMOJI_TO_ICON_ID.entries.joinToString(" ") { (emoji, id) -> "WHEN '$emoji' THEN $id" }

    /** 带变体选择符的 emoji 对应的 iconId（仅供单测断言约束 (2)） */
    val VS16_ICON_IDS: Set<Int> = setOf(13, 15, 18, 43, 46, 50)
}
