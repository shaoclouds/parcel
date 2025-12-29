package com.xxxx.parcel.util

import android.util.Log
import java.util.regex.Matcher
import java.util.regex.Pattern

class SmsParser {
    // --- 核心模式库（整合版本一的强匹配与版本二的柜号优先） ---
    
    // 优先级 1：快递柜号模式（来自版本二，针对“xx号柜”最直接的匹配）
    private val lockerPattern: Pattern =
        Pattern.compile("""(?i)([0-9A-Z-]+)号(?:柜|快递柜|丰巢柜|蜂巢柜|熊猫柜|兔喜快递柜)""")

    // 优先级 2：复合地址模式列表（来自版本一，覆盖取件地址、品牌、到/放至等场景）
    private val addressPatterns = listOf(
        Pattern.compile("""(?i)取件地址[:\s]*([^，。！？\s]+[\s\S]*?)(?=取件码|$|请|尽快|及时)"""),
        Pattern.compile("""(?i)(?:近邻宝|丰巢|菜鸟|中通|顺丰|韵达|圆通|申通|京东)[^，。！？]*?[|｜]?\s*([^，。！？\s]+?(?:驿站|快递柜|快递点|柜|室|号|栋|楼|单元|小区|学校|食堂|医院)[^，。！？]*)"""),
        Pattern.compile("""(?i)(?:到|位于|放至|送达|放入)[\s]*([^任务，。！？\s]+?(?:驿站|快递柜|快递点|柜|室|号|栋|楼|单元|小区|学校|食堂|医院)[^，。！？]*)"""),
        Pattern.compile("""([\u4e00-\u9fa5a-zA-Z0-9\s-]+?(?:驿站|快递柜|快递点|快递室|快递站|门牌|柜|室|号|栋|楼|单元|小区|花园|苑|广场|大厦|超市|便利店|学校|食堂|医院|银行|校内|校外|路|街|巷)[^，。！？]*)""")
    )

    // 综合取件码模式（结合版本一的括号支持与版本二的多码匹配）
    private val codePattern: Pattern = Pattern.compile(
        """(?i)(取件码为?|提货号为?|取货码为?|提货码为?|取件码|提货号|取货码|提货码|凭|快递|京东|天猫|中通|顺丰|韵达|菜鸟|签收码|签收编号|提货编码|收货编码)[：:\s]*[『「【\(\[ ]*([A-Za-z0-9\s-]{3,}(?:[，,、\s][A-Za-z0-9\s-]{3,})*)[」』】\)\]]*"""
    )

    // 动态规则存储
    private val customAddressPatterns = mutableListOf<String>()
    private val customCodePatterns = mutableListOf<Pattern>()
    private val ignoreKeywords = mutableListOf<String>()

    data class ParseResult(val address: String, val code: String, val success: Boolean)

    fun parseSms(sms: String): ParseResult {
        var foundAddress = ""
        var foundCode = ""

        Log.d("SmsParser", "开始解析: $sms")

        // 1. 拦截器：检查忽略关键词
        for (ignoreKeyword in ignoreKeywords) {
            if (ignoreKeyword.isNotBlank() && sms.contains(ignoreKeyword, ignoreCase = true)) {
                return ParseResult("", "", false)
            }
        }

        // 2. 提取取件码 (优先提取，方便地址清洗时排除干扰)
        // 先看自定义规则
        for (pattern in customCodePatterns) {
            val matcher = pattern.matcher(sms)
            if (matcher.find()) {
                foundCode = matcher.group(1) ?: ""
                break
            }
        }
        
        // 使用通用模式提取取件码
        if (foundCode.isEmpty()) {
            val codeMatcher = codePattern.matcher(sms)
            if (codeMatcher.find()) {
                val rawCode = codeMatcher.group(2) ?: ""
                // 拆分多码（版本二逻辑）并清洗（版本一逻辑）
                foundCode = rawCode.split(Regex("[，,、]"))
                    .joinToString(", ") { cleanCodeText(it) }
            }
        }

        // 3. 提取地址
        // 3.1 自定义地址规则
        for (pattern in customAddressPatterns) {
            if (sms.contains(pattern, ignoreCase = true)) {
                foundAddress = pattern
                break
            }
        }

        // 3.2 匹配柜号 (版本二的高优先级逻辑)
        if (foundAddress.isEmpty()) {
            val lockerMatcher = lockerPattern.matcher(sms)
            if (lockerMatcher.find()) {
                foundAddress = lockerMatcher.group()
            }
        }

        // 3.3 匹配复合地址模式 (版本一强项)
        if (foundAddress.isEmpty()) {
            for (pattern in addressPatterns) {
                val matcher = pattern.matcher(sms)
                if (matcher.find()) {
                    val groupCount = matcher.groupCount()
                    foundAddress = if (groupCount >= 1) matcher.group(1) ?: "" else matcher.group(0)
                    if (foundAddress.isNotEmpty()) break
                }
            }
        }

        // 4. 深度清洗逻辑 (版本一的核心优势)
        foundAddress = cleanAddressText(foundAddress, foundCode)
        foundAddress = finalCleanAddress(foundAddress, sms)
        foundCode = finalCleanCode(foundCode)

        val success = foundAddress.isNotEmpty() && foundCode.isNotEmpty()
        return ParseResult(foundAddress, foundCode, success)
    }

    private fun cleanAddressText(address: String, code: String): String {
        if (address.isEmpty()) return ""
        var cleaned = address

        // 移除取件码
        if (code.isNotEmpty()) {
            code.split(", ").forEach { singleCode ->
                cleaned = cleaned.replace(singleCode, "")
            }
        }

        // 移除干扰词
        val noise = listOf("凭", "到", "位于", "已到达", "到达", "送达", "放入", "取件", "请", "尽快", "及时")
        noise.forEach { cleaned = cleaned.replace(it, "") }

        // 移除符号
        cleaned = cleaned.replace(Regex("""[『「【\(\[」』】\)\],，。！？!?|｜]"""), " ")
        
        // 移除多余空格
        return cleaned.replace(Regex("\\s+"), " ").trim()
    }

    private fun finalCleanAddress(address: String, originalSms: String): String {
        if (address.isEmpty()) return ""
        // 移除开头非中文字符（保留字母数字）
        var cleaned = address.replace(Regex("^[^\\w\u4e00-\u9fa5]+"), "").trim()
        
        // 如果清洗后太短，尝试最后保底模式
        if (cleaned.length < 2) {
             val backup = Pattern.compile("""([\u4e00-\u9fa5]{2,}(?:驿站|柜|店|楼))""").matcher(originalSms)
             if (backup.find()) cleaned = backup.group(1) ?: ""
        }
        return cleaned
    }

    private fun cleanCodeText(code: String): String {
        return code.replace(Regex("""[『「【\(\[」』】\)\]\s]"""), "").trim()
    }

    private fun finalCleanCode(code: String): String {
        // 移除重复项并过滤掉不合格的码
        return code.split(", ")
            .filter { it.length >= 2 && !it.contains(Regex("""[年月日]""")) }
            .distinct()
            .joinToString(", ")
    }

    // --- 动态规则管理方法 ---
    fun addCustomAddressPattern(pattern: String) { customAddressPatterns.add(pattern) }
    fun addCustomCodePattern(pattern: String) {
        try { customCodePatterns.add(Pattern.compile(pattern)) } catch (e: Exception) { }
    }
    fun clearAllCustomPatterns() {
        customAddressPatterns.clear()
        customCodePatterns.clear()
        ignoreKeywords.clear()
    }
    fun addIgnoreKeyword(keyword: String) { if (keyword.isNotBlank()) ignoreKeywords.add(keyword) }
}
