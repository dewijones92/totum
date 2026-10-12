package com.dewijones92.totum.data.update

@JvmInline
public value class AppVersion private constructor(private val parts: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(index) { 0 } - other.parts.getOrElse(index) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    public companion object {
        public fun parse(raw: String): AppVersion? {
            val parts = raw.trim().removePrefix("v").split('.')
            val numbers = parts.map { part -> part.takeIf { it.all(Char::isDigit) }?.toIntOrNull() }
            return if (numbers.isEmpty() || numbers.any { it == null }) null else AppVersion(numbers.filterNotNull())
        }
    }
}
