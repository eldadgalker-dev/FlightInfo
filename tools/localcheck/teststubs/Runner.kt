import java.lang.reflect.Modifier
fun main() {
    val cls = Class.forName("org.skytrack.CoreTests")
    val obj = cls.getDeclaredConstructor().newInstance()
    var fails = 0
    for (m in cls.declaredMethods.filter { it.isAnnotationPresent(org.junit.Test::class.java) && Modifier.isPublic(it.modifiers) }.sortedBy { it.name }) {
        try { m.invoke(obj); println("PASS ${m.name}") } catch (e: java.lang.reflect.InvocationTargetException) { fails++; println("FAIL ${m.name}: ${e.targetException}") }
    }
    println("failures: $fails")
}
