package dev.mooner.slplugin

class BadResponseException(code: Int, message: String?) :
    Exception("Device responded with code: $code message: $message") {

    constructor(code: Int): this(code, null)
}