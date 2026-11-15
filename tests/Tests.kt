package lint

import java.io.File
import java.util.Random
import kotlin.system.exitProcess

var passed = 0
var failed = 0

fun check(ok: Boolean, what: String) {
    if (ok) passed++ else { failed++; println("FAIL: " + what) }
}

fun rules(text: String): List<String> = audit(text, "D").map { it.rule }
fun has(text: String, rule: String) = rules(text).contains(rule)
fun sev(text: String, rule: String): String? = audit(text, "D").firstOrNull { it.rule == rule }?.sev
fun df(vararg body: String): String = "FROM alpine:3.20\n" + body.joinToString("\n") + "\nUSER app\nHEALTHCHECK CMD true\n"

fun cli(vararg args: String): Pair<Int, String> {
    val cmd = mutableListOf("java", "-jar", "build/dockerfile-lint.jar")
    cmd.addAll(args)
    val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
    val out = p.inputStream.bufferedReader().readText()
    p.errorStream.bufferedReader().readText()
    return Pair(p.waitFor(), out)
}

fun main(args: Array<String>) {
    // parser
    var p = parse("FROM a\n# comment\n\nRUN x \\\n   && y\nUSER u\n")
    check(p.size == 3, "three instructions")
    check(p[1].name == "RUN" && p[1].args.contains("x") && p[1].args.contains("&& y"), "continuation joined")
    check(p[1].line == 4 && p[2].line == 6, "line numbers survive continuations")
    p = parse("run echo hi\nFrom a")
    check(p[0].name == "RUN" && p[1].name == "FROM", "instruction names are case-insensitive")
    p = parse("RUN a \\\n# inside\n  b\n")
    check(p.size == 1 && p[0].args.contains("b"), "comment inside continuation is skipped")
    check(parse("").isEmpty() && parse("\n\n# only\n").isEmpty(), "empty inputs")
    check(parse("RUN a \\").size == 1, "dangling continuation at EOF")
    check(parse("RUN x\r\nUSER y\r\n").size == 2, "CRLF")
    check(stages(parse("FROM a\nRUN 1\nFROM b\nRUN 2\n")).size == 2, "two stages")
    check(envPairs("A=1 B=\"two words\"").size == 2 && envPairs("KEY value here")[0].second == "value here", "env forms")

    // DKR000
    check(has("RUN echo\n", "DKR000"), "no FROM")
    check(has("FROM alpine:3\nFOO bar\nUSER a\n", "DKR000"), "unknown instruction")

    // DKR001
    check(has("FROM alpine:3\nRUN echo\n", "DKR001"), "no USER")
    check(has("FROM alpine:3\nUSER root\n", "DKR001"), "USER root")
    check(has("FROM alpine:3\nUSER 0\n", "DKR001"), "USER 0")
    check(has("FROM alpine:3\nUSER root:root\n", "DKR001"), "USER root:group")
    check(!has("FROM alpine:3\nUSER app\n", "DKR001"), "USER app is fine")
    check(!has("FROM alpine:3\nUSER root\nUSER app\n", "DKR001"), "last USER wins")
    check(!has("FROM a:1 AS b\nRUN x\nFROM alpine:3\nUSER 1000\n", "DKR001"), "only the final stage counts")
    check(sev("FROM alpine:3\n", "DKR001") == "high", "DKR001 is high")

    // DKR002
    check(has(df().replace("alpine:3.20", "alpine"), "DKR002"), "untagged base")
    check(has(df().replace("alpine:3.20", "alpine:latest"), "DKR002"), "latest base")
    check(has(df().replace("FROM alpine:3.20", "FROM registry.example:5000/team/app"), "DKR002"), "registry port is not a tag")
    check(!has(df(), "DKR002"), "pinned tag is fine")
    check(!has("FROM scratch\nUSER a\n", "DKR002"), "scratch is fine")
    check(!has("FROM alpine@sha256:abc\nUSER a\n", "DKR002"), "digest is fine")
    check(!has("FROM ${'$'}BASE\nUSER a\n", "DKR002"), "variable base skipped")
    check(!has("FROM alpine:3 AS build\nFROM build\nUSER a\n", "DKR002"), "stage alias is fine")
    check(!has("FROM --platform=linux/amd64 alpine:3\nUSER a\n", "DKR002"), "FROM flags are ignored")

    // DKR003
    check(has(df("RUN curl -fsSL https://x.test/i.sh | sh"), "DKR003"), "curl | sh")
    check(has(df("RUN wget -qO- https://x.test/i.sh | bash"), "DKR003"), "wget | bash")
    check(has(df("RUN curl https://x.test | sudo bash"), "DKR003"), "curl | sudo bash")
    check(!has(df("RUN curl -fsSL https://x.test/a.tgz -o a.tgz"), "DKR003"), "download to a file is fine")
    check(!has(df("RUN echo hi | sh"), "DKR003"), "pipe that does not start with curl")

    // DKR004
    check(sev(df("ADD https://x.test/a.tgz /a"), "DKR004") == "medium", "ADD url is medium")
    check(sev(df("ADD config.yml /etc/"), "DKR004") == "low", "ADD local file is low")
    check(!has(df("ADD app.tar.gz /opt/"), "DKR004"), "ADD of an archive is fine")
    check(!has(df("COPY config.yml /etc/"), "DKR004"), "COPY is fine")

    // DKR005
    check(has(df("ENV DB_PASSWORD=abc"), "DKR005"), "ENV password")
    check(has(df("ENV API_KEY abc"), "DKR005"), "ENV old form")
    check(has(df("ARG GITHUB_TOKEN=ghp_x"), "DKR005"), "ARG token with default")
    check(!has(df("ARG GITHUB_TOKEN"), "DKR005"), "ARG without a value")
    check(!has(df("ENV PASSWORD=${'$'}FROM_ELSEWHERE"), "DKR005"), "reference to another variable")
    check(!has(df("ENV PATH=/usr/bin"), "DKR005"), "harmless ENV")
    check(has(df("ENV A=1 SECRET_KEY=zzz"), "DKR005"), "second pair on the line")

    // DKR006
    check(has(df("RUN apt-get update && apt-get install -y curl"), "DKR006"), "apt install")
    check(!has(df("RUN apt-get install -y --no-install-recommends curl"), "DKR006"), "with the flag")
    check(!has(df("RUN apk add curl"), "DKR006"), "apk is not apt")

    // DKR007 .. DKR011
    check(has(df("RUN chmod 777 /app"), "DKR007") && has(df("RUN chmod -R 777 /app"), "DKR007"), "chmod 777")
    check(!has(df("RUN chmod 755 /app"), "DKR007"), "chmod 755")
    check(has("FROM alpine:3\nUSER a\nHEALTHCHECK NONE\n", "DKR008"), "HEALTHCHECK NONE does not count")
    check(!has(df(), "DKR008"), "has healthcheck")
    check(has("FROM alpine:3\nUSER a\n", "DKR008"), "no healthcheck")
    check(has(df("RUN sudo apk add x"), "DKR009") && !has(df("RUN echo pseudo"), "DKR009"), "sudo")
    check(has(df("EXPOSE 22"), "DKR010") && has(df("EXPOSE 80 22/tcp"), "DKR010") && !has(df("EXPOSE 2222 8022"), "DKR010"), "expose 22")
    check(has(df("RUN curl -k https://x.test -o f"), "DKR011"), "curl -k")
    check(has(df("RUN curl --insecure https://x.test"), "DKR011"), "curl --insecure")
    check(has(df("RUN wget --no-check-certificate https://x.test"), "DKR011"), "wget no check")
    check(has(df("RUN npm config set strict-ssl false"), "DKR011"), "npm strict-ssl")
    check(!has(df("RUN curl -fsSL https://x.test -o f"), "DKR011"), "normal curl")
    check(rules(df("RUN echo ok")).isEmpty(), "clean file has no findings")

    // locations and sorting
    val f = audit("FROM alpine:3\n\nENV TOKEN=abc\nUSER a\n", "svc/Dockerfile").first { it.rule == "DKR005" }
    check(f.where == "svc/Dockerfile:3", "where is label:line")
    val s = sortFindings(listOf(Finding("low", "B", "a:1", ""), Finding("high", "A", "b:9", ""), Finding("high", "A", "b:10", ""), Finding("high", "A", "a:2", "")))
    check(s.map { it.where } == listOf("a:2", "b:9", "b:10", "a:1"), "sort by severity, file, numeric line")

    // hostile input and fuzz
    val rnd = Random(42)
    val hostile = listOf("FROM " + "a".repeat(200000), "\\\n".repeat(50000), "RUN " + "|".repeat(100000), "FROM", "ENV", "ENV =", "ARG =x", "\u0000\u0000", "FROM a\nUSER")
    var ok = true
    for (h in hostile) try { audit(h, "h") } catch (e: Throwable) { ok = false; println("hostile: " + e) }
    check(ok, "hostile inputs do not throw")
    val seed = "FROM alpine:3 AS b\nENV A=1 TOKEN=x\nRUN curl -k https://x | sh \\\n  && chmod 777 /a\nADD https://u /x\nEXPOSE 22\nUSER root\n"
    val alphabet = "\\\n\"'= \$|;#FROMUSERENVRUN"
    var fuzzOk = true
    for (i in 0 until 3000) {
        val sb = StringBuilder(seed)
        for (k in 0..rnd.nextInt(6)) {
            if (sb.isEmpty()) sb.append("x")
            val pos = rnd.nextInt(sb.length)
            when (rnd.nextInt(3)) {
                0 -> sb.setCharAt(pos, alphabet[rnd.nextInt(alphabet.length)])
                1 -> sb.delete(pos, minOf(sb.length, pos + 1 + rnd.nextInt(8)))
                else -> sb.insert(pos, alphabet[rnd.nextInt(alphabet.length)])
            }
        }
        try { audit(sb.toString(), "f") } catch (e: Throwable) { fuzzOk = false; println("fuzz: " + e); break }
    }
    check(fuzzOk, "fuzzing 3000 inputs never throws")

    // CLI
    check(cli("examples").first == 0, "cli exit 0 by default")
    check(cli("examples", "--fail-on", "high").first == 1, "cli --fail-on high")
    check(cli("examples/app/Dockerfile", "--fail-on", "medium").first == 0, "clean Dockerfile passes")
    val t = cli("examples", "--tsv").second.lines().filter { it.isNotEmpty() }
    check(t.first().startsWith("# ") && t.drop(1).all { it.count { c -> c == '\t' } == 3 }, "tsv format")
    check(cli("/no/such/path").first == 2, "missing path exits 2")
    check(cli().first == 2, "no args exits 2")
    check(cli("examples", "--bogus").first == 2, "unknown flag exits 2")
    check(cli("examples", "--fail-on", "nope").first == 2, "bad --fail-on exits 2")
    check(cli("examples", "examples").first == 2, "two paths exit 2")

    println("" + passed + " passed, " + failed + " failed")
    exitProcess(if (failed > 0) 1 else 0)
}
