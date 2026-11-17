package cn.ppps.forwarder.utils

import android.content.Context
import android.provider.Telephony
import com.xuexiang.xrouter.utils.TextUtils

//短信拦截规则工具：命中号码黑名单或关键词黑名单的短信不再转发
object SmsInterceptUtils {

    private const val TAG: String = "SmsInterceptUtils"

    //是否命中拦截规则
    fun isIntercepted(from: String, content: String, simSlot: Int = -1): Boolean {
        if (!SettingUtils.enableSmsIntercept) return false

        //【最高优先级】白名单绝对优先：号码或内容命中白名单，立即放行，不再检查任何拦截规则。
        //即使同一条短信同时命中号码黑名单/关键词黑名单，也一律以白名单为准放行。
        if (isWhitelisted(from, content)) return false

        //卡槽生效范围：ALL=不分卡槽；SIM1/SIM2=仅对应卡槽的短信才检查拦截规则（卡槽未知时不拦截，避免误拦）
        val scope = SettingUtils.smsInterceptSimSlot
        if (scope == "SIM1" && simSlot != 0) return false
        if (scope == "SIM2" && simSlot != 1) return false

        //号码黑名单：逗号/分号分隔（兼容中英文），发件人号码以任一拦截号码结尾即命中（便于匹配含+86前缀的号码）
        if (!TextUtils.isEmpty(SettingUtils.smsInterceptPhones)) {
            val phones = SettingUtils.smsInterceptPhones
                .replace(";", ",").replace("；", ",").replace("，", ",").replace("\n", ",")
            for (phone in phones.split(",")) {
                val number = phone.trim()
                if (!TextUtils.isEmpty(number) && from.endsWith(number)) {
                    Log.i(TAG, "命中号码拦截规则：from = $from, interceptPhone = $number")
                    return true
                }
            }
        }

        //关键词黑名单：一行一个关键词，支持正则表达式，命中内容任意一项即返回 true
        if (!TextUtils.isEmpty(SettingUtils.smsInterceptKeywords)) {
            for (line in SettingUtils.smsInterceptKeywords.split("\n")) {
                val keyword = line.trim()
                if (keyword.isEmpty()) continue
                try {
                    val regex = Regex(keyword)
                    if (regex.containsMatchIn(content)) {
                        Log.i(TAG, "命中关键词拦截规则：keyword = $keyword, content = $content")
                        return true
                    }
                } catch (e: Exception) {
                    //正则表达式非法时，降级为普通包含匹配
                    Log.w(TAG, "拦截关键词正则非法，降级为包含匹配：$keyword, ${e.message}")
                    if (content.contains(keyword)) {
                        Log.i(TAG, "命中关键词拦截规则：keyword = $keyword, content = $content")
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * 是否命中白名单（绝对优先，高于一切拦截规则）
     * 白名单同时支持两种写法，任一命中即放行：
     * 1) 号码：发件人号码与白名单项按「归一化号码」结尾匹配（兼容 +86/0086、空格、横杠、括号等格式）
     * 2) 内容：短信正文包含该内容（支持正则，非法正则降级为包含匹配）
     * 号码归一化只用于号码判定，避免把纯文字关键词误归一化成空串而放行全部短信。
     */
    fun isWhitelisted(from: String, content: String): Boolean {
        if (TextUtils.isEmpty(SettingUtils.smsInterceptWhitelist)) return false
        val fromNorm = normalizePhone(from)
        for (line in SettingUtils.smsInterceptWhitelist.split("\n")) {
            val item = line.trim()
            if (item.isEmpty()) continue

            //号码匹配：归一化后按结尾匹配（长度≥4 防止短数字把全部号码放行），并保留原始结尾匹配兜底
            val itemNorm = normalizePhone(item)
            if ((itemNorm.length >= 4 && fromNorm.endsWith(itemNorm)) || from.endsWith(item)) {
                Log.i(TAG, "命中白名单（号码）→ 放行：from = $from, whitelist = $item")
                return true
            }

            //内容匹配：支持正则，非法正则降级为包含匹配
            try {
                if (Regex(item).containsMatchIn(content)) {
                    Log.i(TAG, "命中白名单（内容）→ 放行：whitelist = $item")
                    return true
                }
            } catch (e: Exception) {
                if (content.contains(item)) {
                    Log.i(TAG, "命中白名单（内容，包含匹配）→ 放行：whitelist = $item")
                    return true
                }
            }
        }
        return false
    }

    /**
     * 号码归一化：去掉 +、空格、横杠、括号等非数字字符，并去掉 +86/0086 国际前缀，
     * 只保留数字本体，使白名单号码在不同格式下也能一致匹配。
     * 纯文字关键词会被归一化为空串，调用方需用长度门槛区分，避免误判。
     */
    private fun normalizePhone(raw: String): String {
        var s = raw.trim().replace(Regex("[^0-9]"), "")
        if (s.startsWith("0086") && s.length > 12) {
            s = s.substring(4)
        } else if (s.startsWith("86") && s.length == 13) {
            s = s.substring(2)
        }
        return s
    }

    //从系统短信收件箱删除指定短信（安卓4.4+要求本应用为默认短信应用，否则删除失败）
    fun deleteSmsFromInbox(context: Context, from: String, content: String): Int {
        return try {
            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val selection = "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ?"
            val deletedCount = context.contentResolver.delete(uri, selection, arrayOf(from, content))
            Log.i(TAG, "删除本地短信：from = $from, deletedCount = $deletedCount")
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "删除本地短信失败（需将本应用设为默认短信应用）：" + e.message.toString())
            0
        }
    }
}
