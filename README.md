# Shell & CI plugin for SonarQube

Static analysis of **shell scripts** and **CI pipelines** (GitHub Actions, GitLab CI, Azure Pipelines) for
SonarQube Community Build.

On the free Community Build, shell scripts and CI pipeline security are not covered out of the box, and the old
ShellCheck plugin from the Marketplace no longer runs on current versions. This plugin fills that gap:

- **Shell:** every [ShellCheck](https://www.shellcheck.net) check as a SonarQube rule (524 rules, 446 active by default).
- **CI pipelines:** 13 rules for pipeline security and correctness, including ShellCheck on the scripts *inside* your
  pipeline files, reported at the exact line in the YAML.

## Rules

### Shell scripts

Files ending in `.sh`, `.bash`, `.ksh` or `.dash` are analyzed with ShellCheck. Its levels map as follows:

| ShellCheck level | SonarQube type | Impact | Active by default |
|---|---|---|---|
| error | Bug | Reliability / High | yes |
| warning | Bug | Reliability / Medium | yes¹ |
| info | Code Smell | Maintainability / Low | yes |
| style | Code Smell | Maintainability / Info | no |

¹ Except the 42 deprecated rules: checks documented in the ShellCheck wiki that ShellCheck no longer emits. They are kept so existing issues stay attached to a rule, but are not active.

Line-of-code and comment metrics are computed even when ShellCheck is not installed. A project's `.shellcheckrc` and
`# shellcheck disable=...` comments are honored.

### CI pipelines

| Rule | Type | GitHub Actions | GitLab CI | Azure Pipelines |
|---|---|:-:|:-:|:-:|
| Untrusted data should not be expanded into pipeline scripts (`ScriptInjection`) | Vulnerability | ✓ | | ✓ |
| Privileged workflows should not check out untrusted pull request code (`UntrustedCheckout`) | Vulnerability | ✓ | | |
| Secrets should not be hardcoded in pipeline variables (`HardcodedSecret`) | Vulnerability | ✓ | ✓ | ✓ |
| Secrets should not be serialized all at once with `toJSON(secrets)` (`SecretsToJson`) | Vulnerability | ✓ | | |
| Insecure workflow commands should not be re-enabled (`UnsecureCommands`) | Vulnerability | ✓ | | |
| Shell scripts in pipeline steps should not have ShellCheck issues (`RunShellCheck`) | Bug | ✓ | ✓ | ✓ |
| External actions, workflows, includes and components without a commit SHA (`UnpinnedReference`) | Security Hotspot | ✓ | ✓ | |
| Container images (including `uses: docker://` steps) without a pinned version (`UnpinnedImage`) | Security Hotspot | ✓ | ✓ | ✓ |
| Executing downloaded scripts without verification, e.g. `curl … \| sh` (`RemoteScriptExecution`) | Security Hotspot | ✓ | ✓ | ✓ |
| `permissions: write-all` (`WriteAllPermissions`) | Security Hotspot | ✓ | | |
| Relying on default `GITHUB_TOKEN` permissions (`MissingPermissions`) | Security Hotspot | ✓ | | |
| `secrets: inherit` to an external reusable workflow (`SecretsInherit`) | Security Hotspot | ✓ | | |
| Persisting the job token after checkout (`CheckoutPersistCredentials`, off by default) | Security Hotspot | ✓ | | ✓ |

Every rule has a description with noncompliant and compliant examples in the SonarQube UI. Rules are tagged with the
platforms they apply to (`github-actions`, `gitlab-ci`, `azure-pipelines`).

Rule parameters:

- `UnpinnedReference.trustedOwners`: GitHub owners and GitLab groups you trust to reference by tag or branch, e.g.
  `actions,my-org`.
- `UnpinnedImage.requireDigest`: also report version tags. By default only untagged and `latest` images are reported.
- `RunShellCheck.minimumLevel` (default `warning`) and `RunShellCheck.excludedCodes` (default `SC2154`).

How pipeline scripts are checked with ShellCheck:

- Template syntax replaced before the shell runs (`${{ }}`, `$[ ]`, Azure `$(Macro.Name)`, GitLab `$[[ inputs.x ]]`) is
  masked, so ShellCheck sees a plain word and column positions stay exact.
- The shell is worked out the way the CI system does it:
  - **GitHub Actions:** `shell:` and `defaults.run.shell`. Windows runners default to pwsh and are skipped.
  - **Azure Pipelines:** `bash:`, `Bash@3` and `AzureCLI@2` with `scriptType: bash` run in bash. `script:` and
    `CmdLine@2` run in bash except on Windows pools, where they run in cmd and are skipped.
  - **GitLab CI:** jobs on Alpine/BusyBox images are checked as POSIX `sh`, so bash-isms like `[[ ]]` are reported.
    A job's `before_script` and `script` are checked together, as one shell session.

## Requirements

- SonarQube Community Build **26.11** or later (plugin API 14.1). SonarQube Server is not tested.
- **ShellCheck** on the machine that runs the scanner, for the shell rules and `RunShellCheck`. The rules are
  generated from ShellCheck 0.11.0, the recommended version. Without ShellCheck, shell files get metrics only and a
  warning is logged. You can also import ShellCheck reports instead (see below).

## Installation

1. Download `sonar-shell-ci-plugin-<version>.jar` from a
   [release](https://github.com/muratcanozdemir/sonar-shell-ci-plugin/releases), or build it (Java 17+; the Maven
   wrapper downloads Maven): `./mvnw package`, which writes `target/sonar-shell-ci-plugin-<version>.jar`.
2. Copy the JAR to `<sonarqube>/extensions/plugins/` and restart SonarQube.

Each release also carries a CycloneDX SBOM of the JAR (`.cdx.json`), `SHA256SUMS`, and two signed in-toto
attestations of the JAR as Sigstore bundles: SLSA build provenance and the SBOM. Verify a downloaded JAR with the
GitHub CLI (online against the attestations API, or offline-capable with `--bundle <file>.sigstore.json`):

```bash
gh attestation verify sonar-shell-ci-plugin-<version>.jar --repo muratcanozdemir/sonar-shell-ci-plugin \
  --signer-workflow muratcanozdemir/sonar-shell-ci-plugin/.github/workflows/release.yaml
gh attestation verify sonar-shell-ci-plugin-<version>.jar --repo muratcanozdemir/sonar-shell-ci-plugin \
  --predicate-type https://cyclonedx.org/bom
```

The **Shell & CI way** quality profiles for the *Shell* and *CI Pipelines* languages are created and set as default.

## Analysis

Nothing to configure: pipeline files are found by path, shell scripts by suffix. Make sure ShellCheck is on the
scanner's `PATH`, for example in GitHub Actions:

```yaml
- run: sudo apt-get install -y shellcheck
- uses: SonarSource/sonarqube-scan-action@<sha>
```

Distribution packages can be older than ShellCheck 0.11.0. Checks they lack are then simply not reported;
`.github/workflows/ci.yaml` shows how to install the 0.11.0 release instead.

### Properties

| Property | Default | Description |
|---|---|---|
| `sonar.shell.file.suffixes` | `.sh,.bash,.ksh,.dash` | Shell script suffixes |
| `sonar.shellcheck.path` | `shellcheck` | ShellCheck executable. When it cannot be run, shell files get metrics and imported reports only, and pipeline scripts are not checked |
| `sonar.shellcheck.skip` | `false` | Never run ShellCheck: shell files only get imported reports, pipeline scripts are not checked |
| `sonar.shellcheck.reportPaths` | | ShellCheck reports to import (`shellcheck -f json1 ...` or `-f json`) |
| `sonar.shellcheck.timeoutSeconds` | `300` | Timeout of one ShellCheck run |
| `sonar.ci.githubActions.patterns` | `**/.github/workflows/*.yml,…,**/action.yml,…` | GitHub Actions workflows and action metadata |
| `sonar.ci.gitlabCi.patterns` | `**/.gitlab-ci.yml,…,**/.gitlab/ci/**/*.yml,…` | GitLab CI files |
| `sonar.ci.azurePipelines.patterns` | `**/azure-pipelines.yml,**/*.azure-pipelines.yml,**/.azure-pipelines/**/*.yml,**/.azuredevops/**/*.yml,…` | Azure Pipelines files |

To analyze pipeline files with other names, add patterns, e.g.
`-Dsonar.ci.azurePipelines.patterns=**/azure-pipelines.yml,pipelines/**/*.yml`.

## Design notes

- **Pipeline files keep their YAML language.** Community Build 26.11 indexes `*.yml` as YAML, and a file can only have one
  language. The *CI Pipelines* language therefore claims no files. Its sensor selects pipeline files by path and
  reports issues on them whatever their language. As a consequence, pipeline files do not add to lines of code.
- **Hidden directories** (`.github/`, `.gitlab/`, `.azure-pipelines/`) are indexed by the scanner and processed by
  both sensors.
- The ShellCheck rule list is generated from the ShellCheck sources and wiki by `tools/gen_shellcheck_rules.py`,
  for **ShellCheck 0.11.0** (revision `aac0823`), the version used by CI and the scanner image. Checks from other
  ShellCheck versions that are unknown to the plugin are logged and ignored.

## Limitations

- Folded (`>`) and multi-line quoted scripts are not checked by ShellCheck: their lines do not map back to the source.
- GitLab `!reference` tags, `extends:` and Azure templates are not resolved: each file is analyzed as written.
- Script injection in GitLab CI is not reported: GitLab passes variables to jobs through the environment, so they are
  not pasted into the script text.

## Development

```bash
./mvnw clean verify                # build, run the Java tests, write target/site/jacoco/jacoco.xml
scripts/test-tools.sh              # Python tests of tools/ with coverage: target/python-coverage.xml
scripts/osv-scan.sh                # known vulnerabilities (osv.dev) in the dependencies; fails on any

# local SonarQube Community Build with the plugin mounted
docker run -d --name sonarqube -p 9000:9000 \
  -v "$PWD/.sonarqube/plugins:/opt/sonarqube/extensions/plugins" sonarqube:community
cp target/sonar-shell-ci-plugin-*.jar .sonarqube/plugins/ && docker restart sonarqube

# analyze with a scanner image that bundles ShellCheck (docker/scanner.Dockerfile)
export SONAR_TOKEN=<analysis token>
./mvnw dependency:copy-dependencies && scripts/scan.sh   # this repository, with test results and coverage
scripts/scan-samples.sh                                   # every sample project
scripts/setup-samples.sh                                  # samples profile and failing quality gate (admin token)
```

Always build with `clean` after moving or renaming classes: otherwise stale classes from `target/classes` end up in
the plugin JAR. Tests that need ShellCheck are skipped when it is not installed.

`samples/` holds one deliberately vulnerable project per platform, each with examples for its rules:

| Sample | SonarQube project |
|---|---|
| `samples/github-actions` | `shell-ci-sample-github-actions` |
| `samples/gitlab-ci` | `shell-ci-sample-gitlab-ci` |
| `samples/azure-pipelines` | `shell-ci-sample-azure-pipelines` |
| `samples/shell` | `shell-ci-sample-shell` |

`scripts/setup-samples.sh` prepares them on the server. It needs an admin user token in `SONAR_ADMIN_TOKEN` or
`.sonarqube/admin-token`, and only adds what is missing, so it can be re-run at any time:

- a *Shell & CI samples* CI Pipelines quality profile that extends *Shell & CI way* with every CI rule, so rules that
  are off by default (`CheckoutPersistCredentials`) are reported on their examples too;
- a *Shell & CI samples* quality gate on overall code. The samples never change, so the default gate, which only
  checks new code, would always pass them. This gate fails them on purpose: security issues, unreviewed security
  hotspots, reliability rating worse than A.

To refresh the ShellCheck rules after a ShellCheck release, generate them from the release tag and the wiki as it was
at that release (a newer wiki documents checks the release does not have yet), then bump `SHELLCHECK_VERSION` and its checksum
in `docker/scanner.Dockerfile`, `.github/workflows/ci.yaml` and `.github/workflows/release.yaml`:

```bash
version=v0.11.0
git clone -q --depth 1 --branch "$version" https://github.com/koalaman/shellcheck.git /tmp/sc
git clone -q https://github.com/koalaman/shellcheck.wiki.git /tmp/scw
git -C /tmp/scw checkout -q "$(git -C /tmp/scw rev-list -1 --before="$(git -C /tmp/sc log -1 --format=%cI)" HEAD)"
python3 tools/gen_shellcheck_rules.py /tmp/sc /tmp/scw src/main/resources/io/github/shellci/shell/shellcheck-rules.json
```

### Releasing

Versions and the changelog are automatic. Commits on `main` follow
[Conventional Commits](https://www.conventionalcommits.org): `fix:` makes a patch release, `feat:` a minor one, and
`feat!:` or a `BREAKING CHANGE:` footer a major one. Other types (`docs:`, `test:`, `build:`, `ci:`, `chore:`,
`refactor:`) do not release anything by themselves.

On every push to `main`, `.github/workflows/release-please.yaml` runs
[release-please](https://github.com/googleapis/release-please), which keeps a release pull request up to date: it
bumps the version in `pom.xml`, `sonar-project.properties` and `.release-please-manifest.json` and writes
`CHANGELOG.md`. **Merging that pull request is the release:** it tags `vX.Y.Z`, creates the GitHub release with the
changelog as notes, and calls `.github/workflows/release.yaml` with the tag. That workflow builds and tests the tag,
scans the SBOM with osv-scanner, attests provenance and SBOM, verifies both attestations (signer workflow, ref,
commit, GitHub-hosted runner), and attaches the JAR, SBOM, attestation bundles and checksums to the release.

Pushing a tag `vX.Y.Z` by hand still runs `release.yaml` on its own, as before release-please: it builds that tag and
creates the release (generated notes), or attaches the assets to an existing release that has none. Use it to rebuild
a release whose run failed or that was deleted. Assets already on a release are never replaced.

```bash
git tag -a v1.0.0 -m v1.0.0 && git push origin v1.0.0
```

Between releases, `pom.xml` carries the version of the last release, so a JAR built locally from `main` has that
version but is not that release: only JARs from the releases page are, and only those verify with
`gh attestation verify`.

release-please needs the repository setting *Actions → General → Allow GitHub Actions to create and approve pull
requests*. CI does not run on pull requests opened with the workflow token; close and reopen the release pull request
to run it.

### Layout

```
src/main/java/io/github/shellci/
  shell/      Shell language, ShellCheck rules, runner and sensor
  ci/         CI Pipelines language, rules, sensor, Platform definitions
  ci/model/   YAML tree navigation per platform (GitHub, GitLab, Azure)
  ci/checks/  one class per rule
  ci/scripts/ extraction of pipeline scripts with source position mapping
  common/     metrics and location helpers
```

## License

[MIT](LICENSE)
