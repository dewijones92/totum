package com.dewijones92.totum.common

public object NaturalOrder : Comparator<String> {
    private val parts = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val left = parts.findAll(a.lowercase()).map { it.value }.toList()
        val right = parts.findAll(b.lowercase()).map { it.value }.toList()
        left.zip(right).forEach { (x, y) ->
            val byNumber = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else 0
            val order = if (byNumber != 0) byNumber else x.compareTo(y)
            if (order != 0) return order
        }
        return left.size - right.size
    }
}
