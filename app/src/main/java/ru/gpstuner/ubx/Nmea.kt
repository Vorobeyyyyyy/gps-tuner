package ru.gpstuner.ubx

object Nmea {
    fun checksumOk(line: String): Boolean {
        val star = line.lastIndexOf('*')
        if (!line.startsWith("$") || star < 0 || star + 3 > line.length) return false
        var cs = 0
        for (i in 1 until star) cs = cs xor line[i].code
        return line.substring(star + 1, star + 3).toIntOrNull(16) == cs
    }

    /** Тип предложения без талкера: «$GNRMC,…» → «RMC». */
    fun type(line: String): String = if (line.length >= 6) line.substring(3, 6) else ""

    fun fields(line: String): List<String> {
        val star = line.lastIndexOf('*')
        return line.substring(1, if (star > 0) star else line.length).split(',')
    }

    /** Собирает предложение с контрольной суммой из тела без «$» и «*». */
    fun build(body: String): String {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return "$%s*%02X\r\n".format(body, cs)
    }
}

data class Rmc(val valid: Boolean, val speedKmh: Double?)

data class Gga(val quality: Int, val numSv: Int?, val hdop: Double?)

fun parseRmc(f: List<String>) = Rmc(
    valid = f.getOrNull(2) == "A",
    speedKmh = f.getOrNull(7)?.toDoubleOrNull()?.times(1.852),
)

fun parseGga(f: List<String>) = Gga(
    quality = f.getOrNull(6)?.toIntOrNull() ?: 0,
    numSv = f.getOrNull(7)?.toIntOrNull(),
    hdop = f.getOrNull(8)?.toDoubleOrNull(),
)
