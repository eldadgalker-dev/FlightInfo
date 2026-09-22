package kotlinx.coroutines
interface Job { fun cancel() }
interface CoroutineContext { operator fun plus(o: CoroutineContext): CoroutineContext = this }
class SupervisorJobImpl : Job, CoroutineContext { override fun cancel() {} }
fun SupervisorJob(): SupervisorJobImpl = SupervisorJobImpl()
object Dispatchers { val Default: CoroutineContext = SupervisorJobImpl(); val IO: CoroutineContext = SupervisorJobImpl(); val Main: CoroutineContext = SupervisorJobImpl() }
class CoroutineScope(val ctx: CoroutineContext)
val CoroutineScope.isActive: Boolean get() = true
fun CoroutineScope.launch(ctx: CoroutineContext = Dispatchers.Default, block: suspend CoroutineScope.() -> Unit): Job = SupervisorJobImpl()
fun CoroutineScope.cancel() {}
suspend fun delay(ms: Long) {}
suspend fun <T> withContext(ctx: CoroutineContext, block: suspend CoroutineScope.() -> T): T = throw UnsupportedOperationException()
