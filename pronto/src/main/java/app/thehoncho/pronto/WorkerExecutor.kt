package app.thehoncho.pronto

import app.thehoncho.pronto.command.Command

interface WorkerExecutor {
    fun handleCommand(command: Command)
    fun isRunning(): Boolean
    fun getConnection(): PTPUsbConnection
}