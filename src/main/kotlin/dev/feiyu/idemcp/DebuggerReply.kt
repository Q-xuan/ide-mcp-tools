package dev.feiyu.idemcp

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun <T> awaitDebuggerReply(
    reply: Deferred<T>,
    stallTimeoutMs: Long,
    onStall: suspend () -> Unit,
): T {
    if (withTimeoutOrNull(stallTimeoutMs) { reply.join(); true } != true) onStall()
    return reply.await()
}
