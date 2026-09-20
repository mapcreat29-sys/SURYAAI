package com.surya.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import java.util.Locale

object SmartCommands {

    private val digitWords = mapOf(
        "शून्य" to "0", "जीरो" to "0", "zero" to "0",
        "एक" to "1", "one" to "1",
        "दो" to "2", "two" to "2",
        "तीन" to "3", "three" to "3",
        "चार" to "4", "four" to "4",
        "पांच" to "5", "पाँच" to "5", "five" to "5",
        "छह" to "6", "छः" to "6", "six" to "6",
        "सात" to "7", "seven" to "7",
        "आठ" to "8", "eight" to "8",
        "नौ" to "9", "nine" to "9"
    )

    private val stopWords = setOf(
        "call", "dial", "कॉल", "करो", "करना", "लगाओ", "को", "फोन", "फ़ोन",
        "please", "to", "सूर्या", "सूर्य", "सूरज", "surya", "suraj", "rdx", "hey", "हे"
    )

    fun run(ctx: Context, raw: String): String {
        Commands.ok = true
        Commands.retry = false
        val t = raw.lowercase(Locale.getDefault()).trim()
        if (t.isEmpty()) return ""

        if (t.contains("call") || t.contains("dial") || t.contains("कॉल") ||
            t.contains("फोन करो") || t.contains("फ़ोन करो")
        ) {
            return callSomeone(ctx, t)
        }

        val first = Commands.run(ctx, raw)
        if (Commands.retry) {
            val guess = fuzzyOpen(ctx, t)
            if (guess != null) {
                Commands.ok = true
                Commands.retry = false
                return guess
            }
        }
        return first
    }

    private fun extractNumber(t: String): String {
        val sb = StringBuilder()
        for (tok in t.split(" ", ",", "-")) {
            val w = tok.trim()
            if (w.isEmpty()) continue
            val word = digitWords[w]
            if (word != null) {
                sb.append(word)
                continue
            }
            for (ch in w) {
                if (ch in '0'..'9') sb.append(ch)
                else if (ch in '०'..'९') sb.append('0' + (ch - '०'))
                else if (ch == '+' && sb.isEmpty()) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun callSomeone(ctx: Context, t: String): String {
        val number = extractNumber(t)
        if (number.count { it.isDigit() } >= 3) return dial(ctx, number, number)

        val name = t.split(" ")
            .filter { it.isNotBlank() && it !in stopWords }
            .joinToString(" ")
        if (name.isEmpty()) {
            Commands.ok = false
            return Lang.t(ctx, "किसे कॉल करूँ?", "Whom should I call?")
        }

        val found = findContact(ctx, name)
        if (found == null) {
            Commands.ok = false
            return Lang.t(
                ctx,
                "कॉन्टैक्ट नहीं मिला (या इजाज़त नहीं दी): $name",
                "Contact not found (or permission not given): $name"
            )
        }
        return dial(ctx, found.second, found.first)
    }

    private fun dial(ctx: Context, number: String, label: String): String {
        val clean = number.filter { it.isDigit() || it == '+' }
        val granted = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val c: Context = SuryaService.instance ?: ctx
        val i = Intent(
            if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:$clean")
        )
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            c.startActivity(i)
            if (granted) Lang.t(ctx, "कॉल लगा रहा हूँ: $label", "Calling: $label")
            else Lang.t(ctx, "डायलर खोला: $label", "Opened dialer: $label")
        } catch (e: Exception) {
            Commands.ok = false
            Lang.t(ctx, "कॉल नहीं लग पाया", "Couldn't place the call")
        }
    }

    private fun findContact(ctx: Context, name: String): Pair<String, String>? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null

        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cur = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null
        ) ?: return null

        val q = name.lowercase(Locale.getDefault())
        val qSkel = skeleton(q)
        var bestName = ""
        var bestNum = ""
        var bestScore = 0.0

        cur.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                val l = n.lowercase(Locale.getDefault())
                var score = 0.0
                if (l == q || (q.length >= 2 && l.contains(q))) {
                    score = 1.0
                } else {
                    val ls = skeleton(l)
                    if (qSkel.length >= 2 && ls.length >= 2) {
                        score = 1.0 - distance(qSkel, ls).toDouble() / maxOf(qSkel.length, ls.length)
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    bestName = n
                    bestNum = num
                }
            }
        }
        return if (bestScore >= 0.7) Pair(bestName, bestNum) else null
    }

    private fun fuzzyOpen(ctx: Context, t: String): String? {
        var name = t
        for (w in listOf("open", "ओपन", "खोलिए", "खोलो", "खोल", "चालू", "स्टार्ट", "start", "करो", "please", "सूर्या", "सूर्य", "सूरज", "surya", "suraj", "rdx")) {
            name = name.replace(w, " ")
        }
        name = name.trim()
        if (name.length < 2) return null

        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
        val qSkel = skeleton(name)

        var bestPkg: String? = null
        var bestLabel = ""
        var bestScore = 0.0

        for (ri in list) {
            val label = ri.loadLabel(pm).toString()
            val l = label.lowercase(Locale.getDefault())
            var score = 0.0
            if (l == name || l.contains(name)) {
                score = 1.0
            } else {
                val lSkel = skeleton(l)
                if (qSkel.length >= 2 && lSkel.length >= 2) {
                    score = 1.0 - distance(qSkel, lSkel).toDouble() / maxOf(qSkel.length, lSkel.length)
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestPkg = ri.activityInfo.packageName
                bestLabel = label
            }
        }

        if (bestPkg == null || bestScore < 0.7) return null
        val launch = pm.getLaunchIntentForPackage(bestPkg) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = SuryaService.instance ?: ctx
        c.startActivity(launch)
        return Lang.t(ctx, "खोल रहा हूँ: $bestLabel", "Opening: $bestLabel")
    }

    private fun skeleton(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale.getDefault())) {
            val m: Char? = when (ch) {
                'क', 'ख', 'च', 'छ', 'c', 'q', 'k' -> 'k'
                'ग', 'घ', 'g' -> 'g'
                'ज', 'झ', 'j', 'z' -> 'j'
                'ट', 'ठ', 'ड', 'ढ', 'त', 'थ', 'द', 'ध', 't', 'd' -> 't'
                'न', 'ण', 'ं', 'ँ', 'n' -> 'n'
                'प', 'फ', 'p', 'f' -> 'p'
                'ब', 'भ', 'b' -> 'b'
                'म', 'm' -> 'm'
                'य', 'y' -> 'y'
                'र', 'r' -> 'r'
                'ल', 'l' -> 'l'
                'व', 'w', 'v' -> 'v'
                'श', 'ष', 'स', 's' -> 's'
                in '0'..'9' -> ch
                else -> null
            }
            if (m != null && (sb.isEmpty() || sb.last() != m)) sb.append(m)
        }
        return sb.toString()
    }

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
