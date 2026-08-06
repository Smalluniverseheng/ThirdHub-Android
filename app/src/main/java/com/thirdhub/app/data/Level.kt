package com.thirdhub.app.data

/* 会员等级（与网页版一致：只卖存储容量，不卖 Token） */
data class Level(val id: String, val label: String, val storageBytes: Long, val price: Int)

object Levels {
    val all = listOf(
        Level("guest", "游客", 0L, 0),
        Level("satellite", "卫星", 100L * 1024 * 1024, 0),
        Level("planet", "行星", 1024L * 1024 * 1024, 29),
        Level("star", "恒星", 5L * 1024 * 1024 * 1024, 99),
        Level("galaxy", "星系", 20L * 1024 * 1024 * 1024, 199),
        Level("universe", "宇宙", Long.MAX_VALUE, 399),
    )

    fun byId(id: String?): Level = all.firstOrNull { it.id == id } ?: all[0]

    fun fmt(bytes: Long): String {
        if (bytes == Long.MAX_VALUE) return "无限"
        if (bytes <= 0) return "0"
        val mb = bytes / 1024.0 / 1024.0
        if (mb < 1024) return "%.0f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }
}
