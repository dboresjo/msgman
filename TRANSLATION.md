# *msgman* AI translation feature

This is a feature that uses AI to automatically generate missing translations. Since the parameters for this are quite numerous, a configuration file is required control it.

The feature is activated using the --translate switch when running *format* mode.

# Configuration file

The configuration file is seached for in the following order. Multiple files merged with the most local taking priority.
* .msgman in the current directory
* $HOME/.msgman
* /etc/msgman

The configuration file selects which linked-in AI provider to use and holds the other settings needed to connect to it. Authentication follows each provider's own environment variable convention (see "AI provider and model" below); the configuration file may optionally hold a fallback API key, used only when that environment variable is not set.

The default model used can be specified on a per-provider basis in the configuration file, and can be override with a --model switch when invoking msgman

# Translation Context

When requesting a translation from the translation service, a context is built up consisting of

* project name and description from build.sbt, if present in the current directory
* the context of TRANSLATION-CONTEXT.md, if present in the current directory (location can be overridded in configuration, either absolute or relative path)
* the top-level key of the block containing the missing translation
* sub-keys of the other entires in the same message block, in each case with both master and target language texts (where the target language text exists)
* the code and full name of both master and target languages
* the sub-key of the text to be translated
* the master language text of the text to be translated 

# Inserted Translation

Unless stealth mode is specified in the configuration, all added translations are preceded with a comment line with a double-hash, "## added by msgman using <AI model>"

# Open questions and risks

These are not yet settled and should be resolved before, or during, implementation.

**Scala Native compatibility**
* Resolved: msgman was migrated to Scala 3.3.7 (from 2.13), so sttp-ai's Scala 3 Native artifacts (core, openai, claude, gemini) can now be used.
* The migration surfaced a related, separate issue worth knowing about: Scala 3's own coverage instrumentation needs `java.security.SecureRandom`, which Scala Native's javalib does not implement (a confirmed open upstream bug, [scala/scala3#16124](https://github.com/scala/scala3/issues/16124)). msgman works around it with `com.github.lolgab %%% "scala-native-crypto"`, a third-party OpenSSL-backed `SecureRandom` shim, but that dependency is scoped so it is only pulled in by the `sbt coverage` command, not by a plain `sbt test` or `sbt nativeLink` (see build.sbt). The production binary has no OpenSSL dependency today.
* sttp's Scala Native backend talks HTTP through curl, which requires libcurl and libcrypto (OpenSSL) to be installed on the machine, at both build and run time. Unlike the coverage-only dependency above, this would be a genuine **production** runtime dependency of the `--translate` feature itself, msgman currently needs nothing beyond libc to run.
* Partially resolved: the *install* script now accepts a repeatable `--with-ai <provider>` (`openai`, `claude`, or `gemini`), one flag per provider to link in. If omitted entirely, no provider is linked in and `--translate` is unavailable in the built binary; sttp-ai should not be pulled in at all in that case. When at least one provider is given, the script checks for libcurl and libcrypto (OpenSSL) up front and fails with a clear message if either is missing, rather than leaving it to fail as a linker error later. The chosen providers are passed to sbt as `-Dmsgman.aiProviders=<comma-separated-list-or-empty>`.
* Resolved: `build.sbt` reads `-Dmsgman.aiProviders` (a comma-separated list, empty/unset when `--with-ai` was not given), validates each entry against `openai`/`claude`/`gemini` (failing the build with a clear error on an unknown value), and adds `"com.softwaremill.sttp.ai" %%% <provider> % "0.8.0"` for each requested provider. An empty list adds nothing, so a plain `sbt nativeLink` still needs nothing beyond libc. The linked-in list is also embedded in the generated `BuildInfo.aiProviders: List[String]`, so the `--translate` implementation (not yet written) can fail fast at runtime with "not available in this build" for a provider that was not linked in, rather than a missing-class error. Resolved: `sttpAiVersion` in build.sbt stays a manually-bumped literal, no automated update process needed.
* Resolved: README.md's Install section states the libcurl/libcrypto prerequisite for any build using `--with-ai`.

**AI provider and model**
* Resolved: more than one provider can be linked into a given binary (via `--with-ai`, see above), but only one is used at a time. Which one is chosen at runtime by the `.msgman` config file, from among whichever providers that particular binary was linked with; picking a provider not linked in is a fatal error, same handling as `--translate` on a build with no provider linked in at all.
* Resolved: The default model used can be specified on a per-provider basis in the configuration file, and can be override with a --model switch when invoking msgman
* Resolved: the "added by msgman using <AI model>" comment uses the full model id (eg. claude-sonnet-5), not a display name.

**Configuration file**
* Resolved: flat `key = value` properties format, `#` for comments, dotted keys for per-provider settings (eg. `openai.model = ...`), matching the same dot-hierarchy convention msgman already uses for message keys. Chosen over HOCON/TOML/JSON/YAML because it needs no parsing library, a hand-rolled parser is a few lines and trivial to bring to 100% coverage (see Test coverage below), and it is the easiest of the options to hand-edit. The three config file locations merge key-by-key, most local wins per key, so this format's flatness is not a limitation for the merge itself.
* Resolved: authentication follows each sttp-ai client's own convention rather than msgman inventing its own, so the config file itself does not need to hold a secret. The API key for the provider selected in `.msgman` is picked up from that provider's own environment variable (`OPENAI_KEY` for OpenAI, `ANTHROPIC_API_KEY` for Claude, Gemini's own equivalent to be confirmed at implementation time against sttp-ai's `fromEnv` support for that module). `.msgman` may optionally hold a fallback key value for the selected provider, used only when the environment variable is not set; if a fallback key is present, treat the file as a secret the same as any key file (never commit it, and the "config files holding secrets must never be committed" warning applies to `.msgman` specifically when this field is used).

**Translation safety**
* Resolved: before writing a translated value, msgman validates that it contains the same MessageFormat placeholder tokens (`{0}`, `{1}`, ...) and escaped quotes (`''`) as the master text, same multiset of tokens regardless of order. A response that fails this check is treated as a failed translation for that key, same handling as any other translation failure (see Error handling), rather than being written. The prompt should still instruct the model to preserve HTML markup verbatim, but markup itself is not mechanically validated, unlike placeholder tokens it has no fixed, easily-comparable form.

**Determinism**
* Resolved: for the purposes of deciding what --translate sends to the AI service, "missing" means a key that is either absent from the file entirely, or present with a language-code-prefixed placeholder value (eg. `cy: `, from a prior plain --fix). A key already holding a value preceded by an "added by msgman" comment is *not* considered missing, and is left alone, so re-running `format --translate` never re-translates a key it already translated, keeping the run idempotent. This is a narrower definition of "missing" than --strict's (see --strict interaction), which treats "added by msgman" values as missing too, for review-gating rather than for deciding what to (re-)send to the AI service.

**Error handling**
* Resolved: a failed translation request (network error, rate limit, malformed response, or a placeholder-validation failure, see Translation safety) leaves that key listed as missing, same as plain --fix; it is not a fatal error for the run, so --translate is safe to run unattended or in CI, a provider outage degrades to "some translations still missing" rather than aborting the whole `format` run.
* Resolved: if any key was left missing because a translation was attempted and failed, `format --translate` exits with a dedicated exit code (`3`, the next free one after the existing `0`/`1`/`2`, see README.md's Exit codes table) once the run completes, rather than `0`. This is distinct from a key that is simply missing because --translate/--fix were never asked to address it, which stays exit `0` as today, so CI can tell "some translations are not yet provided" apart from "the AI service failed to provide some translations".

**--strict interaction**
* Resolved: --strict also treats a value preceded by an "added by msgman" comment as missing, in addition to its existing language-code-prefix check. A --translate insertion is plain, unprefixed text, so without this it would not be caught by --strict at all; this closes that gap without needing a separate flag.

**Cost and latency**
* Resolved: batch at the message block level, one request per top-level key block covering all of that block's missing keys, since the Translation Context section already gathers the whole block's sibling keys per translation, so batching reuses that context rather than re-sending it per key. If a block's batched response fails validation (missing key, broken placeholders, ...), fall back to retrying just that block's keys individually, one request per key, rather than failing or retrying the whole block.

**Test coverage**
* Resolved: one trait per linked-in provider (eg. `OpenAiTranslator`, `ClaudeTranslator`, `GeminiTranslator`), each wrapping that provider's sttp-ai client behind msgman's own "translate this block" call shape. Tests fake each trait directly, so the code that decides what to translate and validates the result is fully covered without a live network call; the concrete implementations that actually wrap sttp-ai are excluded from coverage the same way Main already is (see `coverageExcludedFiles` in build.sbt), since they cannot be exercised without a live network call either.

