package app.snapsync.world

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

actual fun worldTest(body: suspend CoroutineScope.() -> Unit): Unit = runBlocking { body() }
