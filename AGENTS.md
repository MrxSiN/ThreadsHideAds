# ThreadsHideAds: rules for agents

Read `README.md` and `docs/` for the project's architecture, host contracts and build. Add repository-specific rules above the shared block.

## Repository rules

- **Language.** Policy (what to hook, what is an ad, retries, limits, reason codes) is hand-written Brainfuck in `brainfuck/src/*.bf`, checked by `tools/bftool/lint.py`. Host code (Java under `app/src/main/java`, C under `app/src/main/cpp`) only carries mechanisms the core cannot: libxposed registration, reflection, DexKit, JNI, views, threads, logging. A new decision goes into Brainfuck, never into the host.
- **Migration debt.** The Java/C host and the Python build tools in `tools/bftool` are tracked debt (`docs/MIGRATION.md`, "Ecosystem rules v1"). Moving the Brainfuck build to BBC + XAX is blocked upstream; until it unblocks, change those tools only as far as a fix needs and add no new tool in another language.
- **Generated stays generated.** Never edit `app/src/main/cpp/generated/*`, `bridge/BfAbi.java` or `brainfuck/generated/*` by hand; change `brainfuck/constants.txt` or a source and run `python -m tools.bftool.gen`.
- **ABI.** Opcodes and table indexes in `brainfuck/constants.txt` are append-only; document each one in `docs/BRAINFUCK_ABI.md` and `docs/BRAINFUCK_MEMORY_MAP.md`.
- **Discovery.** Find Threads classes by unobfuscated names or by shape, never by obfuscated names (`X.1Ls`) or version offsets; obfuscated names belong only in `HOOK_NOTES.md` findings and test fixtures. Ambiguous discovery installs nothing; unknown content fails open.
- **Identity.** Keep the package name, signing identity, `META-INF/xposed/*` metadata and LSPosed/LSPatch support.
- **Security boundary.** No root-detector evasion, module or package concealment, hidden Xposed metadata, anti-analysis, SELinux or mount-namespace manipulation, or attestation spoofing. Ordinary release engineering (R8, symbol stripping) is fine.
- **Checks before a PR.** `python -m tools.bftool.gen --check`, `python -m tools.bftool.lint` on each changed source, `scripts/check-project.sh`, `./gradlew :app:testDebugUnitTest :app:assembleDebug`, `python -m unittest discover -s tests/compiler`; a behavior change also needs a device run, reported with its evidence label.

<!-- ecosystem-policy v1 (2026-11-13): identical in every MrxSiN first-party repository. Change it everywhere in one coordinated set of PRs, never in one repository alone. -->
## Ecosystem rules (v1)

Shared by MrxSiN's first-party repositories. Any other rule in this file that is stricter or more specific to this repository wins.

- **Who owns what.** XAX owns the semantic graph, verifier, compiler, ABIs, backends, targets and artifacts. BBC owns the Brainfuck frontend, BFX and Brainfuck-specific optimization. XAX-MCP owns MCP transport, tool schemas, sandbox and permissions. Dependencies point one way: consumers -> XAX-MCP / BBC -> XAX.
- **New production logic** is XAX, or Brainfuck + XAX, wherever the toolchains reach the target; this repository's own language rules say where that is. Existing code in other languages is tracked migration debt, not permission to add more. Declarative config, assets, fixtures and licenses are not code to convert.
- **Upstream first.** When a toolchain lacks something this repository needs: reproduce it on the toolchain's latest `main`, search its issues and PRs, then fix a small bounded gap with a PR in the owning repository, or open (or link) an issue for a large one and mark only the affected work blocked-upstream. No consumer-side shims, private toolchain forks or local toolchain patches.
- **Pins.** Build against exact toolchain commits, recorded in one machine-readable place. Check the toolchains' latest commits before nontrivial work; move a pin by reviewed PR once checks pass, and write down why when an older pin stays.
- **No hardcoding** of secrets, personal or machine paths, toolchain locations, obfuscated host names or version-specific offsets. Machine-local paths belong in your own `~/.claude/CLAUDE.md` or `CLAUDE.local.md`, never in the repository. Specification constants (opcodes, ABI layouts, protocol markers, security limits) are fine when named and documented.
- **Design.** SOLID, DRY, KISS, YAGNI, Law of Demeter, Boy Scout Rule and Separation of Concerns, applied with judgment. They never override correctness, safety, verified state or evidence quality.
- **Evidence.** Report only what actually ran: EXECUTED, MEASURED, NOT RUN or BLOCKED, with the reason. Emulator, Wine or qemu runs are not device runs. Never rewrite old evidence; add new.
- **Git.** Work on a branch from the default branch and land it by pull request; the owner reviews and merges. No force-push to the default branch, no history rewrites. No AI attribution in commits, PRs or comments; commits use the repository's git identity. PR titles say what changed in plain words.
- **Security.** Report vulnerabilities privately, never in a public issue. Never print or commit tokens.
<!-- /ecosystem-policy v1 -->
