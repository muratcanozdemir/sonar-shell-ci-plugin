# SonarScanner CLI with ShellCheck, so the Shell & CI plugin for SonarQube can run ShellCheck during analysis.
# The base image is pinned to a digest (it is published for amd64 only, hence the x86_64 ShellCheck build).
FROM sonarsource/sonar-scanner-cli:12.4.0.4321_8.4.0@sha256:6e6eca4d552695a98fb7dab84f0c1bd0885ebbfe272654d1552e24bd7f452d51

# Keep in step with .github/workflows/ci.yaml, release.yaml and the rules in shellcheck-rules.json (see README).
ARG SHELLCHECK_VERSION=v0.11.0
ARG SHELLCHECK_SHA256=b7af85e41cc99489dcc21d66c6d5f3685138f06d34651e6d34b42ec6d54fe6f6

USER root
RUN dnf install -y -q tar gzip \
 && curl -fsSLo /tmp/shellcheck.tar.gz "https://github.com/koalaman/shellcheck/releases/download/${SHELLCHECK_VERSION}/shellcheck-${SHELLCHECK_VERSION}.linux.x86_64.tar.gz" \
 && echo "${SHELLCHECK_SHA256}  /tmp/shellcheck.tar.gz" | sha256sum -c - \
 && tar -xzf /tmp/shellcheck.tar.gz -C /tmp \
 && install -m 0755 "/tmp/shellcheck-${SHELLCHECK_VERSION}/shellcheck" /usr/local/bin/shellcheck \
 && rm -rf /tmp/shellcheck.tar.gz "/tmp/shellcheck-${SHELLCHECK_VERSION}" \
 && dnf clean all \
 && shellcheck --version
USER scanner-cli
