.PHONY: build test sample

build:
	mkdir -p build && kotlinc src/Lint.kt src/Main.kt -include-runtime -d build/dockerfile-lint.jar 2>&1 | grep -v 'JAVA_TOOL_OPTIONS' | grep -E 'error|exception' ; test -f build/dockerfile-lint.jar

test: build
	kotlinc src/Lint.kt tests/Tests.kt -include-runtime -d build/tests.jar 2>&1 | grep -E 'error|exception' ; java -jar build/tests.jar

sample: build
	mkdir -p docs site
	java -jar build/dockerfile-lint.jar examples 2>/dev/null > docs/sample-output.txt; true
	java -jar build/dockerfile-lint.jar examples --tsv 2>/dev/null > docs/sample-output.tsv; true
	python3 scripts/site.py docs/sample-output.tsv site/index.html "dockerfile-lint" "Lints Dockerfiles for security problems: root user, mutable tags, curl-pipe-shell, secrets in ENV, disabled TLS checks. Kotlin." "Synthetic Dockerfiles."
