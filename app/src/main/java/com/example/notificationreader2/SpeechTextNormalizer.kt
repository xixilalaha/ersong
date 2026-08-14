package com.example.notificationreader2

/** 将难以被 TTS 清晰读出的「孤立数字/符号」转成更稳定的读法。 */
object SpeechTextNormalizer {

    fun normalizeIsolatedContent(content: String): String {
        val trimmed = content.trim()
        if (trimmed in ISOLATED_DIGITS) {
            return "${trimmed}啊"
        }
        return ISOLATED_SYMBOL_READINGS[trimmed] ?: trimmed
    }

    private val ISOLATED_DIGITS: Set<String> = setOf(
        "0", "０", "零", "〇",
        "1", "１", "一", "壹",
        "2", "２", "二", "贰", "貳", "两", "兩",
        "3", "３", "三", "叁", "參",
        "4", "４", "四", "肆",
        "5", "５", "五", "伍",
        "6", "６", "六", "陆", "陸",
        "7", "７", "七", "柒",
        "8", "８", "八", "捌",
        "9", "９", "九", "玖"
    )

    private val ISOLATED_SYMBOL_READINGS: Map<String, String> = buildMap {
        addReading("句号", "。", ".", "．")
        addReading("逗号", "，", ",")
        addReading("问号", "？", "?")
        addReading("感叹号", "！", "!")
        addReading("顿号", "、")
        addReading("分号", "；", ";")
        addReading("冒号", "：", ":")
        addReading("省略号", "…", "……", "...", "。。。")
        addReading("波浪号", "~", "～")
        addReading("艾特", "@", "＠")
        addReading("井号", "#", "＃")
        addReading("百分号", "%", "％")
        addReading("星号", "*", "＊")
        addReading("加号", "+", "＋")
        addReading("减号", "-", "－")
        addReading("等号", "=", "＝")
        addReading("斜杠", "/", "／")
        addReading("反斜杠", "\\", "＼")
        addReading("下划线", "_", "＿")
        addReading("竖线", "|", "｜")
        addReading("与号", "&", "＆")
        addReading("左括号", "(", "（")
        addReading("右括号", ")", "）")
        addReading("左方括号", "[", "【")
        addReading("右方括号", "]", "】")
        addReading("小于号", "<", "＜")
        addReading("大于号", ">", "＞")
        addReading("双引号", "\"", "“", "”")
        addReading("单引号", "'", "‘", "’")
        addReading("美元符号", "$", "＄")
        addReading("人民币符号", "¥", "￥")
    }

    private fun MutableMap<String, String>.addReading(reading: String, vararg forms: String) {
        for (form in forms) {
            put(form, reading)
        }
    }
}
