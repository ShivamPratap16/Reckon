package com.reckon.platform

@JvmInline
value class Paisa(val value: Long) {
    init {
        require(value >= 0) { "Paisa must be non-negative, got $value" }
    }
    companion object {
        fun ofRupees(rupees: Long) = Paisa(rupees * 100)
    }
    fun toDisplay(): String = "₹%.2f".format(value / 100.0)
}
