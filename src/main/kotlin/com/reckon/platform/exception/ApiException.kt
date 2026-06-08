package com.reckon.platform.exception

import org.springframework.http.HttpStatus

class ApiException(val status: HttpStatus, val code: String, msg: String) : RuntimeException(msg)
