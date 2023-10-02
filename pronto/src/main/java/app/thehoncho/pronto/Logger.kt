package app.thehoncho.pronto

interface Logger {
    fun d(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun i(tag: String, message: String, throwable: Throwable? = null)
    fun v(tag: String, message: String, throwable: Throwable? = null)
}