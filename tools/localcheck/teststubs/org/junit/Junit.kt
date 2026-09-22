package org.junit
@Target(AnnotationTarget.FUNCTION) annotation class Test
object Assert {
    @JvmStatic fun assertTrue(c: Boolean) { if (!c) throw AssertionError("assertTrue") }
    @JvmStatic fun assertTrue(m: String, c: Boolean) { if (!c) throw AssertionError(m) }
    @JvmStatic fun assertFalse(c: Boolean) { if (c) throw AssertionError("assertFalse") }
    @JvmStatic fun assertEquals(a: Any?, b: Any?) { if (a != b) throw AssertionError("expected $a got $b") }
    @JvmStatic fun assertEquals(a: Double, b: Double, d: Double) { if (Math.abs(a - b) > d) throw AssertionError("expected $a got $b") }
    @JvmStatic fun assertNotNull(a: Any?) { if (a == null) throw AssertionError("null") }
    @JvmStatic fun assertNull(a: Any?) { if (a != null) throw AssertionError("not null") }
}
