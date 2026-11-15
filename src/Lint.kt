package lint

class Finding(val sev: String, val rule: String, val where: String, val msg: String)

class Instruction(val name: String, val args: String, val line: Int)

class Stage(val from: Instruction?, val items: MutableList<Instruction> = mutableListOf())

val KNOWN = setOf(
    "FROM", "RUN", "CMD", "LABEL", "MAINTAINER", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT",
    "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK", "SHELL"
)

val RANK = mapOf("critical" to 0, "high" to 1, "medium" to 2, "low" to 3, "info" to 4)

/** Joins backslash continuations and drops comments and blank lines. */
fun parse(text: String): List<Instruction> {
    val out = mutableListOf<Instruction>()
    val lines = text.split("\r\n", "\n", "\r")
    var i = 0
    while (i < lines.size) {
        val start = i + 1
        var line = lines[i].trim()
        i++
        if (line.isEmpty() || line.startsWith("#")) continue
        val sb = StringBuilder()
        while (true) {
            if (line.endsWith("\\")) {
                sb.append(line.substring(0, line.length - 1)).append(' ')
                if (i >= lines.size) break
                line = lines[i].trim()
                i++
                // comment lines inside a continuation are ignored by Docker
                while (line.startsWith("#") && i < lines.size) { line = lines[i].trim(); i++ }
                if (line.startsWith("#")) break
            } else {
                sb.append(line)
                break
            }
        }
        val full = sb.toString().trim()
        if (full.isEmpty()) continue
        val sp = full.indexOfFirst { it == ' ' || it == '\t' }
        val name = (if (sp < 0) full else full.substring(0, sp)).toUpperCase()
        val args = if (sp < 0) "" else full.substring(sp + 1).trim()
        out.add(Instruction(name, args, start))
    }
    return out
}

fun stages(ins: List<Instruction>): List<Stage> {
    val res = mutableListOf<Stage>()
    for (x in ins) {
        if (x.name == "FROM") res.add(Stage(x)) else {
            if (res.isEmpty()) res.add(Stage(null))
            res.last().items.add(x)
        }
    }
    return res
}

private val PIPE_SHELL = Regex("""(curl|wget)\b[^|;&]*\|\s*(sudo\s+)?(ba|z|da)?sh\b""")
private val SECRET_KEY = Regex("""(PASSWORD|PASSWD|SECRET|TOKEN|API_?KEY|PRIVATE_?KEY|ACCESS_?KEY)""", RegexOption.IGNORE_CASE)
private val INSECURE = Regex("""(curl\b[^;&|]*\s(-k|--insecure)\b|wget\b[^;&|]*--no-check-certificate|strict-ssl\s+false|NODE_TLS_REJECT_UNAUTHORIZED=0|GIT_SSL_NO_VERIFY=(1|true)|pip[^;&|]*--trusted-host)""")
private val ARCHIVE = Regex("""\.(tar|tar\.gz|tgz|tar\.bz2|tar\.xz|txz)$""", RegexOption.IGNORE_CASE)

fun envPairs(args: String): List<Pair<String, String>> {
    // ENV KEY=value KEY2=value2  or  ENV KEY value  or  ARG KEY[=value]
    val res = mutableListOf<Pair<String, String>>()
    val t = args.trim()
    if (t.isEmpty()) return res
    if (t.contains('=') && !t.substringBefore('=').contains(' ')) {
        for (m in Regex("""([A-Za-z_][A-Za-z0-9_]*)=("[^"]*"|'[^']*'|\S*)""").findAll(t)) {
            res.add(Pair(m.groupValues[1], m.groupValues[2].trim('"', '\'')))
        }
    } else {
        val sp = t.indexOfFirst { it == ' ' || it == '\t' }
        if (sp > 0) res.add(Pair(t.substring(0, sp), t.substring(sp + 1).trim().trim('"', '\'')))
        else res.add(Pair(t, ""))
    }
    return res
}

fun audit(text: String, label: String): List<Finding> {
    val out = mutableListOf<Finding>()
    val ins = parse(text)
    fun at(x: Instruction) = label + ":" + x.line
    val st = stages(ins)
    val froms = st.mapNotNull { it.from }
    if (froms.isEmpty()) {
        out.add(Finding("info", "DKR000", label + ":1", "no FROM instruction: not a usable Dockerfile"))
        return out
    }
    val aliases = mutableSetOf<String>()
    for (x in ins) {
        if (x.name !in KNOWN) {
            out.add(Finding("info", "DKR000", at(x), "unknown instruction: " + x.name.take(20)))
        }
    }
    for (f in froms) {
        val parts = f.args.split(Regex("\\s+")).filter { !it.startsWith("--") }
        val image = parts.firstOrNull() ?: ""
        val asIdx = parts.indexOfFirst { it.equals("AS", ignoreCase = true) }
        if (asIdx >= 0 && asIdx + 1 < parts.size) aliases.add(parts[asIdx + 1].toLowerCase())
        if (image.isEmpty() || image == "scratch" || image.contains('$') || image.toLowerCase() in aliases && image != parts.firstOrNull()) continue
        if (image.toLowerCase() in aliases) continue
        if (image.contains("@sha256:")) continue
        val lastSeg = image.substringAfterLast('/')
        if (!lastSeg.contains(':')) out.add(Finding("medium", "DKR002", at(f), "base image " + image + " has no tag: the build is not reproducible"))
        else if (lastSeg.substringAfter(':') == "latest") out.add(Finding("medium", "DKR002", at(f), "base image " + image + " uses the latest tag"))
    }
    val last = st.last()
    val users = last.items.filter { it.name == "USER" }
    if (users.isEmpty()) {
        out.add(Finding("high", "DKR001", at(last.from ?: ins.first()), "final stage has no USER instruction: the container runs as root"))
    } else {
        val u = users.last().args.substringBefore(':').trim()
        if (u == "root" || u == "0") out.add(Finding("high", "DKR001", at(users.last()), "final USER is root"))
    }
    if (last.items.none { it.name == "HEALTHCHECK" && !it.args.trim().equals("NONE", true) }) {
        out.add(Finding("low", "DKR008", at(last.from ?: ins.first()), "no HEALTHCHECK in the final stage"))
    }
    for (x in ins) {
        when (x.name) {
            "RUN" -> {
                if (PIPE_SHELL.containsMatchIn(x.args)) out.add(Finding("high", "DKR003", at(x), "remote script piped into a shell: the build runs whatever the server returns"))
                if (Regex("""apt(-get)?\s+(-\S+\s+)*install""").containsMatchIn(x.args) && !x.args.contains("--no-install-recommends")) {
                    out.add(Finding("low", "DKR006", at(x), "apt-get install without --no-install-recommends: larger image, more packages to patch"))
                }
                if (Regex("""chmod\s+(-R\s+)?(0?777|a\+rwx)""").containsMatchIn(x.args)) out.add(Finding("medium", "DKR007", at(x), "chmod 777: world-writable files in the image"))
                if (Regex("""(^|[;&|]\s*|\s)sudo\s""").containsMatchIn(x.args)) out.add(Finding("medium", "DKR009", at(x), "sudo in a build step: run the step as root or drop privileges with USER"))
                if (INSECURE.containsMatchIn(x.args)) out.add(Finding("medium", "DKR011", at(x), "TLS verification is disabled in this step"))
            }
            "ADD" -> {
                val src = x.args.split(Regex("\\s+")).filter { !it.startsWith("--") }.firstOrNull() ?: ""
                if (src.startsWith("http://") || src.startsWith("https://")) out.add(Finding("medium", "DKR004", at(x), "ADD from a URL: no checksum, content can change between builds; use curl with a checksum"))
                else if (!ARCHIVE.containsMatchIn(src)) out.add(Finding("low", "DKR004", at(x), "ADD of a local file that is not an archive: use COPY"))
            }
            "ENV", "ARG" -> for ((k, v) in envPairs(x.args)) {
                if (SECRET_KEY.containsMatchIn(k) && v.isNotEmpty() && !v.startsWith("$")) {
                    out.add(Finding("high", "DKR005", at(x), x.name + " " + k + " sets a credential: it stays in the image layers and history"))
                }
            }
            "EXPOSE" -> if (x.args.split(Regex("\\s+")).any { it == "22" || it.startsWith("22/") }) {
                out.add(Finding("medium", "DKR010", at(x), "port 22 exposed: containers should not run sshd"))
            }
        }
    }
    return out
}

fun sortFindings(f: List<Finding>): List<Finding> = f.sortedWith(Comparator { a, b ->
    val r = RANK.getValue(a.sev).compareTo(RANK.getValue(b.sev))
    if (r != 0) return@Comparator r
    val fa = a.where.substringBeforeLast(':'); val fb = b.where.substringBeforeLast(':')
    val c = fa.compareTo(fb)
    if (c != 0) return@Comparator c
    val la = a.where.substringAfterLast(':').toIntOrNull() ?: 0
    val lb = b.where.substringAfterLast(':').toIntOrNull() ?: 0
    if (la != lb) return@Comparator la.compareTo(lb)
    a.rule.compareTo(b.rule)
})
