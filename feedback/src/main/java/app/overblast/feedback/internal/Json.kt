package app.overblast.feedback.internal

// A SMALL JSON ENCODER AND READER, so the library ships no JSON dependency. The platform's own
// `org.json` would do on a device, but it is a stub on a JVM unit test, and the payload encoder is
// exactly the code the tests have to pin. Objects are `Map<String, Any?>` (insertion order kept),
// arrays are `List<*>`; an entry whose value is null is left out of an object rather than written as
// `null`, because every optional field in the contract is optional by absence.

internal object Json {
  fun encode(value: Any?): String {
    val sb = StringBuilder()
    write(sb, value)
    return sb.toString()
  }

  private fun write(sb: StringBuilder, value: Any?) {
    when (value) {
      null -> sb.append("null")
      is String -> writeString(sb, value)
      is Boolean -> sb.append(if (value) "true" else "false")
      is Int, is Long, is Short, is Byte -> sb.append(value.toString())
      is Double -> writeDouble(sb, value)
      is Float -> writeDouble(sb, value.toDouble())
      is Number -> writeDouble(sb, value.toDouble())
      is Map<*, *> -> {
        sb.append('{')
        var first = true
        for ((k, v) in value) {
          if (v == null) continue
          if (!first) sb.append(',')
          first = false
          writeString(sb, k.toString())
          sb.append(':')
          write(sb, v)
        }
        sb.append('}')
      }
      is Iterable<*> -> {
        sb.append('[')
        var first = true
        for (v in value) {
          if (!first) sb.append(',')
          first = false
          write(sb, v)
        }
        sb.append(']')
      }
      else -> writeString(sb, value.toString())
    }
  }

  private fun writeDouble(sb: StringBuilder, d: Double) {
    if (d.isNaN() || d.isInfinite()) {
      sb.append('0')
      return
    }
    if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) {
      sb.append(d.toLong().toString())
      return
    }
    // Plain decimal, never an exponent: `java.math.BigDecimal` keeps 0.0001 as 0.0001.
    sb.append(java.math.BigDecimal(d).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString())
  }

  private fun writeString(sb: StringBuilder, s: String) {
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        '\b' -> sb.append("\\b")
        '\u000C' -> sb.append("\\f")
        ' ', ' ' -> sb.append(String.format("\\u%04x", c.code))
        else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
      }
    }
    sb.append('"')
  }

  /** Parses a JSON text. Objects come back as `Map<String, Any?>`, numbers as `Double`. Throws on malformed input. */
  fun parse(text: String): Any? {
    val p = Parser(text)
    p.skipWs()
    val v = p.value()
    p.skipWs()
    if (!p.atEnd()) throw IllegalArgumentException("trailing characters at ${p.pos}")
    return v
  }

  private class Parser(val s: String) {
    var pos = 0

    fun atEnd() = pos >= s.length

    fun skipWs() {
      while (pos < s.length && s[pos].isWhitespace()) pos++
    }

    fun value(): Any? {
      if (atEnd()) throw IllegalArgumentException("unexpected end")
      return when (val c = s[pos]) {
        '{' -> obj()
        '[' -> arr()
        '"' -> str()
        't' -> lit("true", true)
        'f' -> lit("false", false)
        'n' -> lit("null", null)
        else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $pos")
      }
    }

    private fun lit(word: String, v: Any?): Any? {
      if (!s.startsWith(word, pos)) throw IllegalArgumentException("bad literal at $pos")
      pos += word.length
      return v
    }

    private fun num(): Double {
      val start = pos
      while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
      return s.substring(start, pos).toDouble()
    }

    private fun str(): String {
      pos++ // opening quote
      val sb = StringBuilder()
      while (true) {
        if (atEnd()) throw IllegalArgumentException("unterminated string")
        val c = s[pos++]
        when (c) {
          '"' -> return sb.toString()
          '\\' -> {
            if (atEnd()) throw IllegalArgumentException("bad escape")
            when (val e = s[pos++]) {
              '"' -> sb.append('"')
              '\\' -> sb.append('\\')
              '/' -> sb.append('/')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                if (pos + 4 > s.length) throw IllegalArgumentException("bad unicode escape")
                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                pos += 4
              }
              else -> throw IllegalArgumentException("bad escape '$e'")
            }
          }
          else -> sb.append(c)
        }
      }
    }

    private fun obj(): Map<String, Any?> {
      pos++
      val out = LinkedHashMap<String, Any?>()
      skipWs()
      if (!atEnd() && s[pos] == '}') {
        pos++
        return out
      }
      while (true) {
        skipWs()
        if (atEnd() || s[pos] != '"') throw IllegalArgumentException("expected key at $pos")
        val k = str()
        skipWs()
        if (atEnd() || s[pos] != ':') throw IllegalArgumentException("expected ':' at $pos")
        pos++
        skipWs()
        out[k] = value()
        skipWs()
        if (atEnd()) throw IllegalArgumentException("unterminated object")
        when (s[pos++]) {
          ',' -> continue
          '}' -> return out
          else -> throw IllegalArgumentException("expected ',' or '}' at ${pos - 1}")
        }
      }
    }

    private fun arr(): List<Any?> {
      pos++
      val out = ArrayList<Any?>()
      skipWs()
      if (!atEnd() && s[pos] == ']') {
        pos++
        return out
      }
      while (true) {
        skipWs()
        out.add(value())
        skipWs()
        if (atEnd()) throw IllegalArgumentException("unterminated array")
        when (s[pos++]) {
          ',' -> continue
          ']' -> return out
          else -> throw IllegalArgumentException("expected ',' or ']' at ${pos - 1}")
        }
      }
    }
  }
}
