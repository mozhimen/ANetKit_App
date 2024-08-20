package com.mozhimen.taskk.provider.download.impls

import com.mozhimen.taskk.task.provider.cons.intErrorCode2strError

/**
 * @ClassName AppDownloadException
 * @Description TODO
 * @Author Mozhimen
 * @Date 2023/11/7 15:10
 * @Version 1.0
 */
fun Int.intErrorCode2downloadException(): DownloadException =
    DownloadException(this)

fun Int.intErrorCode2downloadException(msg: String): DownloadException =
    DownloadException(this, msg)

class DownloadException : Exception {
    constructor(code: Int) : this(code, code.intErrorCode2strError())
    constructor(code: Int, msg: String) : super() {
        _code = code
        _msg = msg
    }

    private var _code: Int
    val code get() = _code
    private var _msg: String
    val msg get() = _msg

    override fun toString(): String {
        return "DownloadException(code=$_code, msg='$_msg')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadException) return false

        if (_code != other._code) return false
        return _msg == other._msg
    }

    override fun hashCode(): Int {
        var result = _code
        result = 31 * result + _msg.hashCode()
        return result
    }
}