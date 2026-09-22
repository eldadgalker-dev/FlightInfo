package kotlinx.coroutines.channels
suspend fun <T> kotlinx.coroutines.flow.ProducerScope<T>.awaitClose(b: () -> Unit) {}
