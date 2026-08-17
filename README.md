# msgman

A command line tool for managing Scala Play Framework messages files for multiple languages.

`msgman` sorts every messages file entry into a canonical order:

1. Dots separate segments of each key.
2. Keys are sorted alphabetically at each level of the hierarchy.
3. Entries sharing the same top-level key form a block, and blocks are separated
   by a single blank line.

For example, given the keys `site.change`, `site.back`, `date.year`, `date.day`
and `phase`, the canonical order is:

```
date.day = ...
date.year = ...

phase = ...

site.back = ...
site.change = ...
```

Top-level blocks can be pulled ahead of this alphabetical order with
`--priority-keys` (or `priority-keys` in `.msgman`, see below). Given
`--priority-keys phase,site` with the same keys:

```
phase = ...

site.back = ...
site.change = ...

date.day = ...
date.year = ...
```

## Comments

A `#` comment is treated as attaching to the following key, and is retained
when the file is re-sorted — with one distinction:

* A single-hashed (`#`) comment sitting at the start of a block (i.e. directly
  before the first of a run of entries sharing a top-level key) attaches to
  the **block** as a whole. It is written once, above the block, wherever that
  block ends up after sorting — not tied to whichever specific entry happens
  to sort first.
* Any other comment — one that isn't at the start of a block, or one that is
  double-hashed (`##`) — attaches to the **individual entry** immediately
  following it, and travels with that entry when the file is re-sorted. When
  the file is rewritten, these are always written double-hashed, so a later
  `format` or `verify` run recognises them as line comments again even if they
  were originally written with a single hash.
* A comment with no following key at all (e.g. a trailing note at the end of
  the file) is preserved verbatim at the bottom of the file.

For example:

```
# Change of business name
changeBusinessName.heading = ...
changeBusinessName.title = ...
## only relevant to the confirmation page
changeBusinessName.confirmation.title = ...
```

`# Change of business name` is a block comment for `changeBusinessName`;
`## only relevant to the confirmation page` is a line comment tied to
`changeBusinessName.confirmation.title` alone.

## Install

### From source

Requires a JDK, [sbt](https://www.scala-sbt.org/) and the
[Scala Native toolchain](https://scala-native.org/en/stable/user/setup.html)
(a C compiler and LLVM).

```
./install
```

This does a clean build (`sbt clean nativeLink`) and then installs the
resulting binary:

* system-wide, to `/usr/local/bin`, if the machine has passwordless `sudo`
  available
* otherwise locally, to `~/.local/bin`, for the current user only (the script
  warns if that directory isn't already on your `PATH`)

If a required tool (`sbt`, or the C compiler/LLVM the Scala Native toolchain
needs) is missing, the script stops with an explanatory error rather than a
raw build failure.

### AI translation support (optional)

`--translate` (documented in [TRANSLATION.md](TRANSLATION.md)) needs at least
one AI provider linked into the binary. Pass
`--with-ai <provider>` to the install script, repeated for each provider to
link in (`openai`, `claude`, `gemini`), or pass `--with-ai all` once to link
in every supported provider:

```
./install --with-ai openai --with-ai claude
./install --with-ai all
```

Any provider needs libcurl and libcrypto (OpenSSL) installed on the machine,
at both build and run time (e.g. the `libcurl4-openssl-dev` and `libssl-dev`
packages on Debian/Ubuntu). The script checks for both up front when
`--with-ai` is given, and fails with a clear message before attempting a build
if either is missing.


### Debian package

Prebuilt `.deb` packages (amd64 and arm64, built with `--translate` support
for all three AI providers) are attached to each
[GitHub release](https://github.com/dboresjo/msgman/releases):

```
sudo apt install ./msgman_<version>_<arch>.deb
```

No JDK or Scala Native toolchain is required for this route, only the
package's own runtime dependencies (libc, and libcurl/libssl for
`--translate`), which apt resolves automatically.

## Usage

```
msgman <format|verify> [options]
```

### Commands

* **format** — rewrites every messages file in place into canonical order, if
  it isn't already.
  * Translations missing from a non-master language file (present in the
    master, absent from that file) are listed to stdout.
  * Translations for keys that don't exist in the master language are removed
    from the file and listed to stdout.
  * A key that appears more than once in a single file is merged into one
    entry if every occurrence has the same value; if any two occurrences
    disagree, that's a fatal error (reported to stderr) and no files are
    changed.
* **verify** — makes no changes to any file.
  * Exits with a fatal error if any file is not already in canonical order
    (listed to stderr), or if any translation is missing (listed to stderr).
  * Translations for keys that don't exist in the master language are listed
    to stdout, but do not affect the exit code.
  * A key that appears more than once in a single file is always a fatal
    error, listed to stdout — even if every occurrence has the same value.

### Options

| Option                     | Description                                                                                                                                               | Default       |
|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|---------------|
| `--master <code>`          | Country code of the master language, an ISO 2-letter code.                                                                                                | `en`          |
| `--file-pattern <pattern>` | Filename pattern for messages files. `$1` is replaced by the language code.                                                                               | `messages.$1` |
| `--path <dir>`             | Directory to look for messages files in, relative to the current directory.                                                                               | `conf`        |
| `--fix`                    | *(format only)* Add missing translations to the relevant file, with the value prefixed by that file's target language code, e.g. `cy: `                   | off           |
| `--translate`              | *(format only)* Generate missing translations using an AI service and add them to the relevant file. Cannot be combined with `--fix`.                     | off           |
| `--model <id>`             | Override the AI model configured for `--translate`'s selected provider. Only valid together with `--translate`.                                           |               |
| `--verbose`                | Print each translation request to the AI service before it is sent, and its response (or failure reason) after. Only valid together with `--translate`.   | off           |
| `--strict`                 | *(verify only)* Treat a value prefixed with a language code (e.g. `en: `) or preceded by an "added by msgman" comment as missing, rather than translated. | off           |
| `--require <codes>`        | Require a messages file to exist for each of a comma-separated list of ISO 2-letter country codes; a fatal error is raised if any is missing.             | none          |
| `--priority-keys <keys>`   | Comma-separated top-level keys to sort ahead of the rest, in the order given.                                                                             | none          |
| `--help`                   | Show usage instructions.                                                                                                                                  |               |
| `--revision`               | Show the GitHub URL of the revision this binary was built from.                                                                                           |               |

`--revision` prints a link to the repository as it stood at the exact commit
the running binary was compiled from, e.g. `https://github.com/dboresjo/msgman/tree/<sha>`. The commit and the `origin` remote URL are both read from the git checkout at build time, so a binary
built from a fork links back to that fork rather than a hardcoded upstream
repository. If the working tree had uncommitted changes at build time, the
output is suffixed with `(dirty: built with uncommitted changes)`; if no
`origin` remote could be read at build time (e.g. a tarball checkout, or a CI
job with no remote configured), it falls back to printing the bare commit SHA
with a note that the repository URL is unknown. If the binary was built with
AI support (see `--with-ai` below), the linked-in providers are listed in
brackets after the revision, e.g.
`https://github.com/dboresjo/msgman/tree/<sha> [claude, openai]`.

`msgman` discovers language files by matching every filename directly inside
`--path` against `--file-pattern`; the text captured in place of `$1` is that
file's language code. With the defaults, a project with `conf/messages.en` and
`conf/messages.cy` is treated as having master language `en` and translation
`cy`.

### Configuration file (.msgman)

`--master`, `--file-pattern`, `--path`, `--require` and `--priority-keys` can
each also be set in a `.msgman` properties file, as `master`, `file-pattern`,
`path`, `require` and `priority-keys` respectively (the latter two as
comma-separated lists, e.g. `require = cy,fr`). The command line switch takes
precedence over `.msgman` when both are set for the same option; the
built-in default from the table above applies when neither is.

`.msgman` is searched for in the following locations, and files found in more
than one are merged, with the most local taking priority per key:

* `.msgman` in the current directory
* `$HOME/.msgman`
* `/etc/msgman`

The format is flat `key = value` properties: `#` starts a comment, and dotted
keys scope a setting to something else, e.g. a per-provider AI setting like
`openai.model = ...`.

`--translate`'s AI provider settings (`provider`, `<provider>.model`,
`<provider>.fallback-key`, `stealth`, `translation-context`) are configured
through this same file; see [TRANSLATION.md](TRANSLATION.md) for those.

### Examples

Check that every messages file in `conf/` is canonical and fully translated,
and that Welsh and French translations specifically exist (suitable for CI):

```
msgman verify --require cy,fr
```

Reformat every messages file in place, add language-code-prefixed placeholders
(e.g. `cy: `) for any translation that's missing, and drop any translation
left over for a key the master no longer has:

```
msgman format --fix
```

Generate any missing translations using the AI provider configured in
`.msgman` (requires a binary built with `--with-ai`):

```
msgman format --translate
```

Use a different directory and filename convention:

```
msgman verify --path app/messages --file-pattern messages_$1.properties
```

## Exit codes

* `0` Success.
* `1` A messages file is malformed, the master (or required) language
  file could not be found, a file has a duplicate key with conflicting values
  (`format`) or any duplicate key at all (`verify`), or (for `verify`) a file
  is not canonical or a translation is missing.
* `2` Invalid command line arguments.
* `3` AI translation was attempted and failed (network error, rate limit, malformed response, or a placeholder-validation failure).

## Development

To build without installing, or to install by hand:

```
sbt nativeLink
cp target/scala-3.3.7/msgman /usr/local/bin/msgman
```

Built with [Scala Native](https://scala-native.org/). Tests are written with
[munit](https://scalameta.org/munit/) and run as a native binary via
`sbt test`. The project targets 100% statement and branch coverage, measured
with [sbt-scoverage](https://github.com/scoverage/sbt-scoverage), using the
`coverage` command to enable instrumentation for that run only (a plain
`sbt test` or `sbt nativeLink` is never instrumented, and does not need
OpenSSL):

```
sbt clean coverage test coverageReport
```

Coverage instrumentation needs OpenSSL's `libcrypto` available at build and
link time (development headers/libs, e.g. the `libssl-dev` package on
Debian/Ubuntu), to satisfy `java.security.SecureRandom` on Scala Native.

The HTML coverage report is written to
`target/scala-3.3.7/scoverage-report/index.html`.
