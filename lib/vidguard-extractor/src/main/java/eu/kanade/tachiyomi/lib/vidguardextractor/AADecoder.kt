package eu.kanade.tachiyomi.lib.vidguardextractor

class AADecoder {

    fun decode(text: String, alt: Boolean = false): String {
        var newText = text.replace(Regex("\\s+|/\\*.*?\\*/"), "")
        newText = newText.toByteArray().toString(Charsets.UTF_8)

        val data = newText.split("+${ if (alt) "(ﾟɆﾟ)" else "(ﾟДﾟ)" }[ﾟoﾟ]")[1]
        val chars = data.split("+${ if (alt) "(ﾟɆﾟ)" else "(ﾟДﾟ)" }[ﾟεﾟ]+").drop(1)
        val char1 = if (alt) "ღ" else "c"
        val char2 = if (alt) "(ﾟɆﾟ)[ﾟΘﾟ]" else "(ﾟДﾟ)['0']"


        var txt = ""
        for (char in chars) {
            var modifiedChar = char
                .replace("(oﾟｰﾟo)", "u")
                .replace(char1, "0")
                .replace(char2, "c")
                .replace("ﾟΘﾟ", "1")
                .replace("!+[]", "1")
                .replace("-~", "1+")
                .replace("o", "3")
                .replace("_", "3")
                .replace("ﾟｰﾟ", "4")
                .replace("(+", "(")
            modifiedChar = modifiedChar.replace(Regex("\\(\\d\\)")) {
                it.groupValues[1]
            }

            val subChar = evalChar(modifiedChar)

            if (subChar.isNotEmpty()) {
                txt += "$subChar|"
            }
        }
        txt = txt.dropLast(1).replace("+", "")

        val txtResult = txt.split('|').joinToString("") { it.toInt(8).toChar().toString() }

        return toStringCases(txtResult)
    }

    private fun toStringCases(txtResult: String): String {
        val sumBase: String
        var modifiedTxtResult = ""
        if (txtResult.contains(".toString(")) {
            if (txtResult.contains("+(")) {
                sumBase = try {
                    "+" + Regex(".toString...(\\d+).").find(txtResult)!!.groupValues[1].toInt()
                } catch (e: Exception) {
                    ""
                }
                val txtPreTemp = Regex("..(\\d),(\\d+)").findAll(txtResult).map { it.destructured }
                val txtTemp = txtPreTemp.map { (n, b) -> b to n }.toList()
                for ((num, base) in txtTemp) {
                    val code = toString(num.toInt(), evalExp(base + sumBase).toInt())
                    modifiedTxtResult = txtResult.replace("($base,$num)", code).replace("\"|\\+", "")
                }
            } else {
                val txtTemp = Regex("(\\d+)\\.0.\\w+.([^)]+).").findAll(txtResult).map { it.destructured }
                for ((num, base) in txtTemp) {
                    val code = toString(num.toInt(), base.toInt())
                    modifiedTxtResult = txtResult.replace("$num.0.toString($base)", code).replace("'|\\+", "")
                }
            }
        }
        return modifiedTxtResult
    }
    private fun toString(number: Int, base: Int): String {
        val string = "0123456789abcdefghijklmnopqrstuvwxyz"
        return when {
            number < base -> string[number].toString()
            else -> toString(number / base, base) + string[number % base]
        }
    }

    private fun evalChar(expression: String): String {
        var result = expression.replace("(3^3^3)", "3").replace("(0^3^3)", "0")
        val regex = Regex("\\((\\d+[+-]\\d+)\\)")
        regex.findAll(result).forEach {
            result = result.replace(it.value, evalExp(it.groupValues[1]))
        }
        return result.replace("+", "")
    }

    private fun evalExp(exp: String): String {
        val (a, op, b) = Regex("(\\d+)([+-])(\\d+)").find(exp)!!.destructured
        return if (op == "+") (a.toInt() + b.toInt()).toString() else (a.toInt() - b.toInt()).toString()
    }
}
