package com.xxxx.parcel.util

import android.util.Log
import java.util.regex.Matcher
import java.util.regex.Pattern

class SmsParser {
    // 综合取件码模式
    private val codePattern: Pattern = Pattern.compile(
        """(?i)(取件码为?|提货号为?|取货码为?|提货码为?|取件码|提货号|取货码|提货码|凭|快递|京东|天猫|中通|顺丰|韵达|菜鸟|签收码|签收编号|提货编码|收货编码)[：:\s]*[『「【\(\[ ]*([A-Za-z0-9\s-]{3,}(?:[，,、\s][A-Za-z0-9\s-]{3,})*)[」』】\)\]]*"""
    )

    // 改进的地址匹配模式（版本一优势）
    private val addressPatterns = listOf(
        Pattern.compile("""(?i)取件地址[:\s]*([^，。！？\s]+[\s\S]*?)(?=取件码|$|请|尽快|及时)"""),
        Pattern.compile("""(?i)(?:近邻宝|丰巢|菜鸟|中通|顺丰|韵达|圆通|申通|京东)[^，。！？]*?[|｜]?\s*([^，。！？\s]+?(?:驿站|快递柜|快递点|柜|室|号|栋|楼|单元|小区|学校|食堂|医院)[^，。！？]*)"""),
        Pattern.compile("""(?i)(?:到|位于|放至|送达|放入)[\s]*([^，。！？\s]+?(?:驿站|快递柜|快递点|柜|室|号|栋|楼|单元|小区|学校|食堂|医院)[^，。！？]*)"""),
        Pattern.compile("""([\u4e00-\u9fa5a-zA-Z0-9\s-]+?(?:驿站|快递柜|快递点|快递室|快递站|门牌|柜|室|号|栋|楼|单元|小区|花园|苑|广场|大厦|超市|便利店|学校|食堂|医院|银行|校内|校外|路|街|巷)[^，。！？]*)""")
    )

    // 优先级最低的保底模式
    private val lockerPattern: Pattern =
        Pattern.compile("""(?i)([0-9A-Z-]+)号(?:柜|快递柜|丰巢柜|蜂巢柜|熊猫柜|兔喜快递柜)""")

    private val customAddressPatterns = mutableListOf<String>()
    private val customCodePatterns = mutableListOf<Pattern>()
    private val ignoreKeywords = mutableListOf<String>()

    data class ParseResult(val address: String, val code: String, val success: Boolean)

    fun parseSms(sms: String): ParseResult {
        var foundAddress = ""
        var foundCode = ""

        // 1. 检查忽略
        for (ignoreKeyword in ignoreKeywords) {
            if (ignoreKeyword.isNotBlank() && sms.contains(ignoreKeyword, ignoreCase = true)) {
                return ParseResult("", "", false)
            }
        }

        // 2. 提取取件码
        val codeMatcher = codePattern.matcher(sms)
        if (codeMatcher.find()) {
            val rawCode = codeMatcher.group(2) ?: ""
            foundCode = rawCode.split(Regex("[，,、]")).joinToString(", ") { cleanCodeText(it) }
        }

        // 3. 提取地址 (自定义 -> 长地址模式 -> 柜号保底)
        for (pattern in customAddressPatterns) {
            if (sms.contains(pattern, ignoreCase = true)) {
                foundAddress = pattern
                break
            }
        }

        if (foundAddress.isEmpty()) {
            for (pattern in addressPatterns) {
                val matcher = pattern.matcher(sms)
                if (matcher.find()) {
                    foundAddress = (if (matcher.groupCount() >= 1) matcher.group(1) else matcher.group(0)) ?: ""
                    if (foundAddress.isNotEmpty()) break
                }
            }
        }

        if (foundAddress.isEmpty()) {
            val lockerMatcher = lockerPattern.matcher(sms)
            if (lockerMatcher.find()) {
                foundAddress = lockerMatcher.group()
            }
        }

        // 4. 清洗
        foundAddress = cleanAddressText(foundAddress, foundCode)
        foundAddress = finalCleanAddress(foundAddress)

        val success = foundAddress.isNotEmpty() && foundCode.isNotEmpty()
        return ParseResult(foundAddress, foundCode, success)
    }

    private fun cleanAddressText(address: String, code: String): String {
        if (address.isEmpty()) return ""
        var cleaned = address
        val noise = listOf("您的邮政快递包裹已到", "您的快递已到", "包裹已到", "已到", "位于", "放至")
        noise.forEach { cleaned = cleaned.replace(it, "") }
        
        if (code.isNotEmpty()) {
            code.split(", ").forEach { cleaned = cleaned.replace(it, "") }
        }
        
        return cleaned.replace(Regex("""[『「【\(\[\]』】\)\],，。！？!?|｜]"""), " ").trim()
    }

    private fun finalCleanAddress(address: String): String {
        return address.replace(Regex("^[^\\w\u4e00-\u9fa5]+"), "").trim()
    }

    private fun cleanCodeText(code: String): String {
        return code.replace(Regex("""[『「【\(\[」』】\)\]\s]"""), "").trim()
    }

    // --- 补全被其他类调用的公开方法 (解决 Unresolved reference 报错) ---

    fun addCustomAddressPattern(pattern: String) {
        if (pattern.isNotBlank()) customAddressPatterns.add(pattern)
    }

    fun addCustomCodePattern(pattern: String) {
        if (pattern.isNotBlank()) {
            try {
                customCodePatterns.add(Pattern.compile(pattern))
            } catch (e: Exception) {
                Log.e("SmsParser", "Invalid Regex: $pattern")
            }
        }
    }

    fun clearAllCustomPatterns() {
        customAddressPatterns.clear()
        customCodePatterns.clear()
        ignoreKeywords.clear()
    }

    fun addIgnoreKeyword(keyword: String) {
        if (keyword.isNotBlank() && !ignoreKeywords.contains(keyword)) {
            ignoreKeywords.add(keyword)
        }
    }

    fun removeIgnoreKeyword(keyword: String) {
        ignoreKeywords.remove(keyword)
    }

    fun getIgnoreKeywords(): List<String> = ignoreKeywords.toList()

    fun clearIgnoreKeywords() {
        ignoreKeywords.clear()
    }
}
