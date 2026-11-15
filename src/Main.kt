package lint

import java.io.File
import kotlin.system.exitProcess

fun usage(): Int {
    System.err.println("usage: dockerfile-lint PATH [--tsv] [--fail-on medium|high|critical]\nExit: 0 ok, 1 findings at or above --fail-on, 2 bad input.")
    return 2
}

fun isDockerfile(n: String): Boolean {
    val l = n.toLowerCase()
    return l == "dockerfile" || l.startsWith("dockerfile.") || l.endsWith(".dockerfile")
}

fun run(args: Array<String>): Int {
    var path: String? = null
    var tsv = false
    var failOn: String? = null
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a == "--tsv") tsv = true
        else if (a == "--fail-on") {
            i++
            if (i >= args.size || args[i] !in listOf("medium", "high", "critical")) return usage()
            failOn = args[i]
        } else if (a.startsWith("-")) return usage()
        else if (path != null) return usage()
        else path = a
        i++
    }
    if (path == null) return usage()
    val root = File(path)
    if (!root.exists()) { System.err.println("error: no such file or directory: " + path); return 2 }
    val files: List<Pair<String, File>> =
        if (root.isDirectory) {
            val base = root.absoluteFile.toPath()
            root.walkTopDown().filter { it.isFile && isDockerfile(it.name) }
                .map { Pair(base.relativize(it.absoluteFile.toPath()).toString().replace('\\', '/'), it) }
                .sortedBy { it.first }.toList()
        } else listOf(Pair(root.name, root))
    val all = mutableListOf<Finding>()
    for ((label, f) in files) {
        if (f.length() > 4_000_000) continue
        all.addAll(audit(String(f.readBytes(), Charsets.UTF_8), label))
    }
    val sorted = sortFindings(all)
    val head = files.size.toString() + " files, " + sorted.size + " findings"
    println(if (tsv) "# " + head else head)
    for (f in sorted) {
        if (tsv) println(f.sev + "\t" + f.rule + "\t" + f.where + "\t" + f.msg)
        else { println(String.format("%-8s %s %s", f.sev.toUpperCase(), f.rule, f.where)); println("         " + f.msg) }
    }
    if (failOn != null) {
        val floor = RANK.getValue(failOn)
        if (sorted.any { RANK.getValue(it.sev) <= floor }) return 1
    }
    return 0
}

fun main(args: Array<String>) {
    exitProcess(run(args))
}
