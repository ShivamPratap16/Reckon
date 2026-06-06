package com.reckon.bank

enum class BankResult { CHARGED, DECLINED }
enum class BankStatus { CHARGED, DECLINED, NOT_FOUND }
class BankTimeoutException(msg: String) : RuntimeException(msg)
