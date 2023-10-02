package app.thehoncho.pronto

class Session(private val logger: Logger) {
    private var xId = 0
    val nextId: Int
        get() = xId++

    val log: Logger
        get() = logger
}