package app.thehoncho.pronto.command

import app.thehoncho.pronto.WorkerExecutor

abstract class MultipleCommand: PTPAction {

    abstract fun execute(executor: WorkerExecutor)
}