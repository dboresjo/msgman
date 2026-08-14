# *msgman* AI translation feature

This is a feature that uses AI to automatically generate missing translations. Since the parameters for this are quite numerous, a configuration file is required control it.

The feature is activated using the --translate switch when running *format* mode.

**Status: implemented.** The "Open questions and risks" section below is kept as the design record: every item is now resolved, each entry says what was decided and why. See `AiConfig`, `AiTranslate`, `AiProtocol`, `Translator` and the per-provider `*Factory` objects (`src/main/scala-ai-*`) for the code, and `install`/`build.sbt` for the `--with-ai` build-time wiring.

# Configuration file

The configuration file is seached for in the following order. Multiple files merged with the most local taking priority.
* .msgman in the current directory
* $HOME/.msgman
* /etc/msgman

The configuration file selects which linked-in AI provider to use and holds the other settings needed to connect to it. If the binary was built with only one provider linked in, this selection is optional, that provider is used automatically; it is required when more than one is linked in (see "AI provider and model" below). Authentication follows each provider's own environment variable convention (see "AI provider and model" below); the configuration file may optionally hold a fallback API key, used only when that environment variable is not set.

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
* Resolved: the *install* script accepts a repeatable `--with-ai <provider>` (`openai`, `claude`, or `gemini`), one flag per provider to link in. If omitted entirely, no provider is linked in and `--translate` is unavailable in the built binary; sttp-ai is not pulled in at all in that case. When at least one provider is given, the script checks for libcurl and libcrypto (OpenSSL) up front and fails with a clear message if either is missing, rather than leaving it to fail as a linker error later. The chosen providers are passed to sbt as `-Dmsgman.aiProviders=<comma-separated-list-or-empty>`.
* Resolved: `build.sbt` reads `-Dmsgman.aiProviders`, validates each entry against `openai`/`claude`/`gemini` (failing the build with a clear error on an unknown value), and adds `"com.softwaremill.sttp.ai" %%% <provider> % "0.8.0"` for each requested provider. An empty list adds nothing, so a plain `sbt nativeLink` still needs nothing beyond libc. The linked-in list is also embedded in the generated `BuildInfo.aiProviders: List[String]`. `sttpAiVersion` stays a manually-bumped literal, no automated update process.
* Resolved: each provider has a real (`src/main/scala-ai-<provider>`) and a stub (`src/main/scala-ai-<provider>-stub`) source directory, both defining the same `*Factory` object; `build.sbt`'s `Compile / unmanagedSourceDirectories` includes exactly one of the two per provider, chosen by `aiProviders`. This lets `Main.scala` reference all three factories unconditionally regardless of which providers were actually linked in, while a build with none linked pulls in no sttp-ai code at all.
* Resolved: README.md's Install section states the libcurl/libcrypto prerequisite for any build using `--with-ai`.

**AI provider and model**
* Resolved: more than one provider can be linked into a given binary (via `--with-ai`, see above), but only one is used at a time. Which one is chosen at runtime by the `.msgman` config file's `provider` key, from among whichever providers that particular binary was linked with; picking a provider not linked in is a fatal error, same handling as `--translate` on a build with no provider linked in at all.
* Resolved: if a binary was linked with exactly one provider, `provider` in `.msgman` is optional, that one provider is used automatically. It only needs to be set explicitly when more than one provider is linked in, since the choice is then ambiguous; leaving it unset in that case is a fatal error listing which providers are linked.
* Resolved: The default model used can be specified on a per-provider basis in the configuration file, and can be override with a --model switch when invoking msgman
* Resolved: the "added by msgman using <AI model>" comment uses the full model id (eg. claude-sonnet-5), not a display name.

**Configuration file**
* Resolved: flat `key = value` properties format, `#` for comments, dotted keys for per-provider settings (eg. `openai.model = ...`), matching the same dot-hierarchy convention msgman already uses for message keys. Chosen over HOCON/TOML/JSON/YAML because it needs no parsing library, a hand-rolled parser is a few lines and trivial to bring to 100% coverage (see Test coverage below), and it is the easiest of the options to hand-edit. The three config file locations merge key-by-key, most local wins per key, so this format's flatness is not a limitation for the merge itself.
* Resolved: authentication follows each sttp-ai client's own convention rather than msgman inventing its own, so the config file itself does not need to hold a secret. The API key for the provider selected in `.msgman` is picked up from that provider's own environment variable (`OPENAI_KEY` for OpenAI, `ANTHROPIC_API_KEY` for Claude, `GEMINI_API_KEY` for Gemini, confirmed against sttp-ai's own `fromEnv` support for each module). `.msgman` may optionally hold a fallback key value for the selected provider (`<provider>.fallback-key`), used only when the environment variable is not set; if a fallback key is present, treat the file as a secret the same as any key file (never commit it, and the "config files holding secrets must never be committed" warning applies to `.msgman` specifically when this field is used). Revised: a missing key (neither env var nor fallback set) *is* a fatal setup error, checked once up front before any AI call is attempted, alongside the provider/model checks (see Error handling), rather than surfacing as a per-key translation failure. A missing key can never succeed on retry, so treating it as an ordinary request failure would just mean the whole block/per-key retry loop repeats the identical failure for every missing key, with no useful message, before finally giving up.

**Translation safety**
* Resolved: before writing a translated value, msgman validates that it contains the same MessageFormat placeholder tokens (`{0}`, `{1}`, ...) and escaped quotes (`''`) as the master text, same multiset of tokens regardless of order. A response that fails this check is treated as a failed translation for that key, same handling as any other translation failure (see Error handling), rather than being written. The prompt should still instruct the model to preserve HTML markup verbatim, but markup itself is not mechanically validated, unlike placeholder tokens it has no fixed, easily-comparable form.

**Determinism**
* Resolved: for the purposes of deciding what --translate sends to the AI service, "missing" means a key that is either absent from the file entirely, or present with a language-code-prefixed placeholder value (eg. `cy: `, from a prior plain --fix). A key already holding a value preceded by an "added by msgman" comment is *not* considered missing, and is left alone, so re-running `format --translate` never re-translates a key it already translated, keeping the run idempotent. This is a narrower definition of "missing" than --strict's (see --strict interaction), which treats "added by msgman" values as missing too, for review-gating rather than for deciding what to (re-)send to the AI service.

**Error handling**
* Resolved: a distinction is drawn between setup problems and request-time failures. Setup problems, no provider selected, a selected provider not linked into this build, no API key available for it, or no model configured, are all knowable before any AI call is made and can never succeed by retrying, so they are checked once up front and are a fatal error for the whole run with one clear message, rather than being discovered by exhausting the block/per-key retry loop against the same unrecoverable failure. A request-time failure (network error, rate limit, malformed response, or a placeholder-validation failure, see Translation safety) is different: it might succeed on a smaller retry, so it leaves that key listed as missing, same as plain --fix, and is not fatal for the run, `--translate` stays safe to run unattended or in CI, a provider outage degrades to "some translations still missing" rather than aborting the whole `format` run. Either way, the actual reason is reported alongside each still-missing key (`msgman: translation failed [cy] key: <reason>`), not just the fact that it failed.
* Resolved: if any key was left missing because a translation was attempted and failed, `format --translate` exits with a dedicated exit code (`3`, the next free one after the existing `0`/`1`/`2`, see README.md's Exit codes table) once the run completes, rather than `0`. This is distinct from a key that is simply missing because --translate/--fix were never asked to address it, which stays exit `0` as today, so CI can tell "some translations are not yet provided" apart from "the AI service failed to provide some translations".
* Resolved: `--translate --verbose` prints every request to the model before it is sent (`[cy] requesting translation of site (site.back, site.change) from claude-sonnet-5`) and its outcome after (`[cy] received translation of site: site.back = Yn ol; site.change = Newid`, or `[cy] translation of site failed: <reason>`), to stdout. This surfaces block batching and the per-key fallback as they actually happen, including a block that fails and is then retried key by key, each retry logged as its own request/response pair. Only valid together with `--translate`, same restriction as `--model`.
* Revised: a third category exists alongside setup problems and request-time failures, a request-time failure that is nonetheless unrecoverable, eg. an authentication/permission error, or a model id the provider no longer serves (`sttp-ai`'s `AuthenticationException`/`PermissionException`/`InvalidRequestException`/`NotFoundException` subtypes, mapped per provider in each `*Factory`). `TranslationOutcome.Failure` carries a `fatal: Boolean` for this. A fatal failure skips the per-key fallback entirely (retrying with the same broken provider/model/key combination would just repeat it for every other key) and stops `--translate` immediately: no further blocks in that file, and no further target language files either, are attempted. It is reported once, as a single fatal error for the whole run (exit code `1`, not `3`), rather than as a wall of identical "translation failed" lines for every missing key.

**--strict interaction**
* Resolved: --strict also treats a value preceded by an "added by msgman" comment as missing, in addition to its existing language-code-prefix check. A --translate insertion is plain, unprefixed text, so without this it would not be caught by --strict at all; this closes that gap without needing a separate flag.

**Cost and latency**
* Resolved: batch at the message block level, one request per top-level key block covering all of that block's missing keys, since the Translation Context section already gathers the whole block's sibling keys per translation, so batching reuses that context rather than re-sending it per key. If a block's batched response fails validation (missing key, broken placeholders, ...), fall back to retrying just that block's keys individually, one request per key, rather than failing or retrying the whole block.

**Test coverage**
* Resolved: one trait per linked-in provider (`OpenAiTranslator`, `ClaudeTranslator`, `GeminiTranslator`, all extending the shared `Translator`), each wrapping that provider's sttp-ai client behind msgman's own "translate this block" call shape (`translateBlock`). Tests fake each trait directly (see `AiTranslateSpec`, `RunnerSpec`), so the code that decides what to translate, batches, falls back and validates the result (`AiTranslate`) is fully covered without a live network call. The prompt-building and response-parsing logic (`AiProtocol`) is provider-agnostic and pure, so it is covered normally too. Only the thin `*Factory` objects that actually call into sttp-ai are excluded from coverage the same way Main already is (see `coverageExcludedFiles` in build.sbt), since they cannot be exercised without a live network call.

