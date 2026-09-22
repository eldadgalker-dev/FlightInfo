package org.json
class JSONArray(s: String = "") { fun length(): Int = 0; fun getJSONObject(i: Int): JSONObject = JSONObject(); fun getJSONArray(i: Int): JSONArray = JSONArray(); fun getDouble(i: Int): Double = 0.0 }
class JSONObject(s: String = "") {
  fun getJSONArray(k: String): JSONArray = JSONArray(); fun optJSONArray(k: String): JSONArray? = null
  fun getJSONObject(k: String): JSONObject = JSONObject(); fun optString(k: String, d: String = ""): String = d
  fun optInt(k: String, d: Int = 0): Int = d; fun getString(k: String): String = ""; fun optBoolean(k: String, d: Boolean = false): Boolean = d
  fun optLong(k: String, d: Long = 0L): Long = d; fun getLong(k: String): Long = 0L; fun getDouble(k: String): Double = 0.0
  fun optDouble(k: String, d: Double = 0.0): Double = d; fun has(k: String): Boolean = false; fun opt(k: String): Any? = null
  fun put(k: String, v: Any?): JSONObject = this
}
