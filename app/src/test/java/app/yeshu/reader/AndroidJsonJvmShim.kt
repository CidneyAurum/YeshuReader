@file:Suppress("unused")

package org.json

/**
 * Minimal JVM implementation for the Android org.json API used by AiClient.
 *
 * Android local unit tests otherwise receive the mockable android.jar stubs, whose JSON methods
 * return defaults. Keeping this shim in the test source set lets the production parser execute on
 * the host JVM without Robolectric, a device, network access, or a production dependency change.
 */
class JSONObject {
    internal val values: MutableMap<String, Any?>

    constructor() {
        values = linkedMapOf()
    }

    constructor(source: String) {
        values = JsonParser(source).parseObjectRoot().toMutableMap()
    }

    internal constructor(values: Map<String, Any?>) {
        this.values = values.toMutableMap()
    }

    fun getJSONArray(name: String): JSONArray =
        values[name] as? JSONArray ?: throw JSONException("$name is not a JSONArray")

    fun getJSONObject(name: String): JSONObject =
        values[name] as? JSONObject ?: throw JSONException("$name is not a JSONObject")

    fun optJSONObject(name: String): JSONObject? = values[name] as? JSONObject

    fun optJSONArray(name: String): JSONArray? = values[name] as? JSONArray

    fun has(name: String): Boolean = values.containsKey(name)

    fun isNull(name: String): Boolean = !values.containsKey(name) || values[name] == null

    fun getString(name: String): String = when (val value = values[name]) {
        null -> throw JSONException("$name is null or missing")
        is String -> value
        else -> value.toString()
    }

    fun optString(name: String): String = optString(name, "")

    fun optString(name: String, fallback: String): String = when (val value = values[name]) {
        null -> fallback
        is String -> value
        else -> value.toString()
    }

    fun put(name: String, value: Any?): JSONObject = apply { values[name] = value }

    // android.jar 的 org.json 有基本类型重载，生产代码针对它编译后会直接引用这些签名
    // （例如 put("temperature", 0.3) → put(String, double)）。垫片缺了它们，
    // 测试就会在运行时抛 NoSuchMethodError。
    fun put(name: String, value: Double): JSONObject = apply { values[name] = value }
    fun put(name: String, value: Int): JSONObject = apply { values[name] = value }
    fun put(name: String, value: Long): JSONObject = apply { values[name] = value }
    fun put(name: String, value: Boolean): JSONObject = apply { values[name] = value }

    /** 生产代码用 JSONObject.toString() 生成请求体，垫片必须实现它，否则请求体不是 JSON。 */
    override fun toString(): String =
        values.entries.joinToString(",", "{", "}") { (key, value) -> "${quoteJson(key)}:${renderJson(value)}" }
}

class JSONArray internal constructor(internal val values: MutableList<Any?>) {
    constructor() : this(mutableListOf())

    constructor(source: String) : this(JsonParser(source).parseArrayRoot().toMutableList())

    fun length(): Int = values.size

    fun getJSONObject(index: Int): JSONObject =
        values.getOrNull(index) as? JSONObject
            ?: throw JSONException("Value at $index is not a JSONObject")

    fun optJSONObject(index: Int): JSONObject? = values.getOrNull(index) as? JSONObject

    fun put(value: Any?): JSONArray = apply { values += value }

    fun put(value: Double): JSONArray = apply { values += value }
    fun put(value: Int): JSONArray = apply { values += value }
    fun put(value: Long): JSONArray = apply { values += value }
    fun put(value: Boolean): JSONArray = apply { values += value }

    override fun toString(): String = values.joinToString(",", "[", "]") { renderJson(it) }
}

private fun quoteJson(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (char in value) {
        when (char) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (char < ' ') sb.append("\\u%04x".format(char.code)) else sb.append(char)
        }
    }
    sb.append('"')
    return sb.toString()
}

private fun renderJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> quoteJson(value)
    is Boolean, is Number -> value.toString()
    is JSONObject, is JSONArray -> value.toString()
    else -> quoteJson(value.toString())
}

class JSONException(message: String) : RuntimeException(message)

private class JsonParser(private val source: String) {
    private var index = 0

    fun parseObjectRoot(): Map<String, Any?> {
        val value = parseDocument()
        return (value as? JSONObject)?.values
            ?: throw JSONException("JSON document is not an object")
    }

    fun parseArrayRoot(): List<Any?> {
        val value = parseDocument()
        return (value as? JSONArray)?.values
            ?: throw JSONException("JSON document is not an array")
    }

    private fun parseDocument(): Any? {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (index != source.length) fail("Unexpected trailing content")
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (index >= source.length) fail("Unexpected end of input")
        return when (source[index]) {
            '{' -> JSONObject(parseObject())
            '[' -> JSONArray(parseArray().toMutableList())
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> fail("Unexpected character '${source[index]}'")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        if (consume('}')) return emptyMap()
        val result = linkedMapOf<String, Any?>()
        while (true) {
            skipWhitespace()
            if (index >= source.length || source[index] != '"') fail("Expected object key")
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            if (consume('}')) return result
            expect(',')
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        if (consume(']')) return emptyList()
        val result = mutableListOf<Any?>()
        while (true) {
            result += parseValue()
            skipWhitespace()
            if (consume(']')) return result
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < source.length) {
            when (val char = source[index++]) {
                '"' -> return result.toString()
                '\\' -> {
                    if (index >= source.length) fail("Incomplete escape")
                    when (val escaped = source[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> result.append(parseUnicodeEscape())
                        else -> fail("Unsupported escape \\$escaped")
                    }
                }
                else -> result.append(char)
            }
        }
        fail("Unterminated string")
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > source.length) fail("Incomplete unicode escape")
        val digits = source.substring(index, index + 4)
        index += 4
        return digits.toIntOrNull(16)?.toChar() ?: fail("Invalid unicode escape")
    }

    private fun parseNumber(): Number {
        val start = index
        if (source[index] == '-') index++
        while (index < source.length && source[index].isDigit()) index++
        if (index < source.length && source[index] == '.') {
            index++
            while (index < source.length && source[index].isDigit()) index++
        }
        if (index < source.length && source[index] in "eE") {
            index++
            if (index < source.length && source[index] in "+-") index++
            while (index < source.length && source[index].isDigit()) index++
        }
        val token = source.substring(start, index)
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: fail("Invalid number")
    }

    private fun parseLiteral(token: String, value: Any?): Any? {
        if (!source.startsWith(token, index)) fail("Expected $token")
        index += token.length
        return value
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        if (index >= source.length || source[index] != expected) fail("Expected '$expected'")
        index++
    }

    private fun consume(expected: Char): Boolean {
        if (index < source.length && source[index] == expected) {
            index++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun fail(message: String): Nothing = throw JSONException("$message at index $index")

}
