# Changelog

Maintained by [release-please](https://github.com/googleapis/release-please) from
[Conventional Commits](https://www.conventionalcommits.org); do not edit released sections by hand.

## [1.0.0](https://github.com/muratcanozdemir/sonar-shell-ci-plugin/releases/tag/v1.0.0) (2026-11-12)

First release, for SonarQube Community Build 26.11 and later (plugin API 14.1).

### Features

* **Shell:** *Shell* language (`.sh`, `.bash`, `.ksh`, `.dash`) with line and comment metrics, and every ShellCheck
  0.11.0 check as a rule: 524 rules, 446 active in the *Shell & CI way* profile. ShellCheck runs during analysis, or its
  JSON reports are imported (`sonar.shellcheck.reportPaths`).
* **CI pipelines:** 13 security and correctness rules for GitHub Actions, GitLab CI and Azure Pipelines:
  `ScriptInjection`, `UntrustedCheckout`, `HardcodedSecret`, `SecretsToJson`, `UnsecureCommands`, `UnpinnedReference`,
  `UnpinnedImage`, `RemoteScriptExecution`, `WriteAllPermissions`, `MissingPermissions`, `SecretsInherit`,
  `CheckoutPersistCredentials` and `RunShellCheck` (ShellCheck on the scripts inside pipeline files, reported at the
  exact YAML line).
* **Releases:** each release carries a CycloneDX SBOM, checksums, and signed SLSA provenance and SBOM attestations.
