package kotlinx.coroutines.flow
interface Flow<T> { suspend fun collect(c: (T) -> Unit) {} }
interface StateFlow<T> : Flow<T> { val value: T }
class MutableStateFlow<T>(override var value: T) : StateFlow<T>
fun <T> Flow<T>.catch(b: (Throwable) -> Unit): Flow<T> = this
suspend fun <T> Flow<T>.collectLatest(b: (T) -> Unit) {}
fun <T> Flow<T>.sample(ms: Long): Flow<T> = this
fun <T> callbackFlow(b: suspend ProducerScope<T>.() -> Unit): Flow<T> = object : Flow<T> {}
class ProducerScope<T> { fun trySend(v: T) {} fun close() {} }
