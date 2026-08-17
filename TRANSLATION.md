# *msgman* AI translation feature

`--translate` generates missing translations using an AI service and adds
them to the relevant messages file. It is used together with *format*, and
cannot be combined with `--fix`.

See `AiConfig`, `AiTranslate`, `AiProtocol`, `Translator` and the per-provider
`*Factory` objects (`src/main/scala-ai-*`) for the implementation, and
`install`/`build.sbt` for the `--with-ai` build-time wiring.

## Configuration file

`--translate` is configured through a `.msgman` properties file, since the
number of settings involved is too large for command-line switches alone.

The file is searched for in the following order, and multiple files found
are merged, with the most local taking priority:

* `.msgman` in the current directory
* `$HOME/.msgman`
* `/etc/msgman`

The format is flat `key = value` properties, with `#` for comments and
dotted keys for per-provider settings (e.g. `openai.model = ...`), matching
the same dot-hierarchy convention `msgman` already uses for message keys.
The three file locations merge key by key, so this flat format is not a
limitation for the merge.

### Provider selection

A binary can be built with more than one AI provider linked in (see "Build
and install" below), but only one is used at a time. The `provider` key in
`.msgman` selects which one, from among whichever providers that binary was
linked with. Selecting a provider that was not linked in is a fatal setup
error.

If a binary was linked with exactly one provider, `provider` is optional and
that provider is used automatically. It only needs to be set when more than
one provider is linked in; leaving it unset in that case is a fatal error
listing which providers are linked.

### Model selection

The default model to use can be set per provider in the configuration file
(e.g. `openai.model = ...`), and can be overridden for a single run with the
`--model` switch.

The "added by msgman" comment (see "Inserted translations" below) records the
full model id used (e.g. `claude-sonnet-5`), not a display name.

### Authentication

Authentication follows each provider's own environment variable convention,
so the configuration file itself does not need to hold a secret:

* OpenAI: `OPENAI_KEY`
* Claude: `ANTHROPIC_API_KEY`
* Gemini: `GEMINI_API_KEY`

`.msgman` may optionally hold a fallback key for the selected provider
(`<provider>.fallback-key`), used only when the environment variable is not
set. If a fallback key is present, treat the file as a secret and never
commit it.

A missing key, neither the environment variable nor a fallback set, is a
fatal setup error, checked up front alongside the provider and model checks
(see "Error handling" below), rather than surfacing as a per-key translation
failure.

## Translation context

When requesting a translation, `msgman` builds up a context consisting of:

* the project name and description from `build.sbt`, if present in the
  current directory
* the contents of `TRANSLATION-CONTEXT.md`, if present in the current
  directory (the location can be overridden in the configuration, either as
  an absolute or relative path)
* the top-level key of the block containing the missing translation
* the sub-keys of the other entries in the same message block, each with
  both master and target language text (where the target language text
  exists)
* the code and full name of both the master and target languages
* the sub-key of the text to be translated
* the master language text of the text to be translated

## Batching

Translations are requested one block at a time: a single request per
top-level key block covers all of that block's missing keys, reusing the
same sibling-key context described above rather than re-sending it per key.

If a block's batched response fails validation (a missing key, or broken
placeholders, see "Translation safety" below), `msgman` falls back to
retrying just that block's keys individually, one request per key, rather
than failing or retrying the whole block.

## Inserted translations

Unless stealth mode is set in the configuration, every added translation is
preceded by a comment line:

```
## added by msgman using <AI model>
```

## What counts as missing

For the purposes of `--translate`, a key is "missing" if it is either absent
from the file entirely, or present with a language-code-prefixed placeholder
value (e.g. `cy: `, left over from a prior plain `--fix`). A key already
holding a value preceded by an "added by msgman" comment is not considered
missing, and is left alone, so re-running `format --translate` never
re-translates a key it already translated and stays idempotent.

This is narrower than what `--strict` treats as missing during `verify` (see
"--strict interaction" below), which flags "added by msgman" values too, for
review-gating rather than for deciding what to send to the AI service.

## Translation safety

Before writing a translated value, `msgman` validates that it contains the
same MessageFormat placeholder tokens (`{0}`, `{1}`, ...) and escaped quotes
(`''`) as the master text, the same multiset of tokens regardless of order.
A response that fails this check is treated as a failed translation for that
key (see "Error handling" below) and is not written.

The prompt also instructs the model to preserve HTML markup verbatim, but
markup itself is not mechanically validated, unlike placeholder tokens it has
no fixed, easily-comparable form.

## Error handling

Problems fall into three categories:

* **Setup problems** — no provider selected, a selected provider not linked
  into this build, no API key available for it, or no model configured.
  These are knowable before any AI call is made and can never succeed by
  retrying, so they are checked once up front and are a fatal error for the
  whole run, with one clear message.
* **Request-time failures** — a network error, rate limit, malformed
  response, or a placeholder-validation failure (see "Translation safety"
  above). These might succeed on a smaller retry, so the affected key is
  left listed as missing, the same as plain `--fix`, and the run is not
  fatal. This keeps `--translate` safe to run unattended or in CI: a
  provider outage degrades to "some translations still missing" rather than
  aborting the whole `format` run.
* **Fatal request-time failures** — a request-time failure that is
  nonetheless unrecoverable, e.g. an authentication/permission error, or a
  model id the provider no longer serves. `TranslationOutcome.Failure`
  carries a `fatal: Boolean` for this. A fatal failure skips the per-key
  fallback entirely, since retrying with the same broken provider/model/key
  combination would just repeat it for every other key, and stops
  `--translate` immediately: no further blocks in that file, and no further
  target language files, are attempted. It is reported once, as a single
  fatal error for the whole run, rather than as a wall of identical
  "translation failed" lines.

Whichever category applies, the actual reason is reported alongside each
still-missing key: `msgman: translation failed [cy] key: <reason>`.

### Exit codes

If any key was left missing because a translation was attempted and failed
(a non-fatal request-time failure), `format --translate` exits with code
`3` once the run completes, rather than `0`. This is distinct from a key
that is simply missing because `--translate`/`--fix` were never asked to
address it, which stays exit `0`, so CI can tell "some translations are not
yet provided" apart from "the AI service failed to provide some
translations". A fatal request-time failure, or a setup problem, exits with
code `1`.

## --strict interaction

`--strict` (used with *verify*) also treats a value preceded by an "added by
msgman" comment as missing, in addition to its existing language-code-prefix
check. A `--translate` insertion is plain, unprefixed text, so without this
it would not be caught by `--strict` at all.

## --verbose output

`--translate --verbose` prints every request to the model before it is sent,
and its outcome after, to stdout:

```
[cy] requesting translation of site (site.back, site.change) from claude-sonnet-5
[cy] received translation of site: site.back = Yn ol; site.change = Newid
```

or, on failure:

```
[cy] translation of site failed: <reason>
```

This surfaces block batching and the per-key fallback as they actually
happen, including a block that fails and is then retried key by key, each
retry logged as its own request/response pair.

## Build and install

`--translate` needs at least one AI provider linked into the binary. The
*install* script accepts a repeatable `--with-ai <provider>` flag, one per
provider to link in (`openai`, `claude`, or `gemini`). If omitted entirely,
no provider is linked in and `--translate` is unavailable in the built
binary.

Any provider needs libcurl and libcrypto (OpenSSL) installed on the machine,
at both build and run time, since sttp's Scala Native backend talks HTTP
through curl. The install script checks for both up front when `--with-ai`
is given, and fails with a clear message before attempting a build if either
is missing. A plain build with no `--with-ai` flags needs nothing beyond
libc.

Each provider has a real (`src/main/scala-ai-<provider>`) and a stub
(`src/main/scala-ai-<provider>-stub`) source directory, both defining the
same `*Factory` object; `build.sbt`'s `Compile / unmanagedSourceDirectories`
includes exactly one of the two per provider, chosen by the linked-in
providers. This lets `Main.scala` reference all three factories
unconditionally regardless of which providers were actually linked in, while
a build with none linked pulls in no sttp-ai code at all. The linked-in list
is embedded in the generated `BuildInfo.aiProviders: List[String]`.

## Test coverage

One trait per linked-in provider (`OpenAiTranslator`, `ClaudeTranslator`,
`GeminiTranslator`, all extending the shared `Translator`) wraps that
provider's sttp-ai client behind msgman's own "translate this block" call
shape (`translateBlock`). Tests fake each trait directly (see
`AiTranslateSpec`, `RunnerSpec`), so the code that decides what to translate,
batches, falls back and validates the result (`AiTranslate`) is fully
covered without a live network call. The prompt-building and
response-parsing logic (`AiProtocol`) is provider-agnostic and pure, so it is
covered normally too. Only the thin `*Factory` objects that actually call
into sttp-ai are excluded from coverage, the same way `Main` already is (see
`coverageExcludedFiles` in `build.sbt`), since they cannot be exercised
without a live network call.
