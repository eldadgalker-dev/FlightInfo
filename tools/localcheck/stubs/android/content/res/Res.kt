package android.content.res
class Locales { operator fun get(i: Int): java.util.Locale = java.util.Locale.US }
class Configuration { val locales = Locales() }
class Resources { val configuration = Configuration() }
