#!/usr/bin/env python3
"""Generate src/main/resources/io/github/shellci/shell/shellcheck-rules.json.

Usage: gen_shellcheck_rules.py <shellcheck-source-dir> <shellcheck-wiki-dir> <output-json>

Reads every check emitted by the ShellCheck sources (with its level) and pairs it
with the title of the matching wiki page.
"""
import json
import pathlib
import re
import subprocess
import sys

LEVEL_RANK = {"style": 0, "info": 1, "warning": 2, "error": 3}

# Analyzer helpers: `warn id 2086 "msg"`, `err (getId t) 1234 $ "msg"`, `infoWithFix id 2086 "msg"`, `warnMsg id 3010 "msg"`
HELPER = re.compile(r'\b(err|warn|info|style)(?:WithFix|Msg)?\s+(?:\([^()]*\)|[A-Za-z_.\']+)\s+([0-9]{4})\b\s*\$?\s*(?:"((?:[^"\\]|\\.)*)")?')
# Parser / other: `parseProblemAt pos ErrorC 1035 "msg"`, `makeComment WarningC id 2155 "msg"`
SEVERITY_C = re.compile(r'\b(ErrorC|WarningC|InfoC|StyleC)\b[^"\n]{0,60}?\b([0-9]{4})\b\s*\$?\s*(?:"((?:[^"\\]|\\.)*)")?')
LEVEL_OF = {"err": "error", "warn": "warning", "info": "info", "style": "style",
            "ErrorC": "error", "WarningC": "warning", "InfoC": "info", "StyleC": "style"}


def revision(src):
    """Short git revision of the ShellCheck checkout, or "" when unknown (not a checkout, or git missing)."""
    try:
        return subprocess.run(["git", "-C", str(src), "rev-parse", "--short", "HEAD"],
                              capture_output=True, text=True).stdout.strip()
    except OSError:
        return ""


def main(src_dir, wiki_dir, out_file):
    src = pathlib.Path(src_dir)
    wiki = pathlib.Path(wiki_dir)
    checks = {}
    for hs in sorted(src.rglob("*.hs")):
        text = hs.read_text(encoding="utf-8", errors="replace")
        for pattern in (HELPER, SEVERITY_C):
            for m in pattern.finditer(text):
                level = LEVEL_OF[m.group(1)]
                code = int(m.group(2))
                if not 1000 <= code <= 3999:
                    continue
                entry = checks.setdefault(code, {"level": level, "message": m.group(3)})
                if LEVEL_RANK[level] > LEVEL_RANK[entry["level"]]:
                    entry["level"] = level
                if not entry["message"] and m.group(3):
                    entry["message"] = m.group(3)

    # Codes documented in the wiki but not found in the sources: mostly retired checks, possibly
    # emitted through call forms the regexes miss. Keep them as rules, off by default.
    for page in wiki.glob("SC[0-9][0-9][0-9][0-9].md"):
        code = int(page.stem[2:])
        if code not in checks:
            checks[code] = {"level": "warning", "message": None, "wikiOnly": True}

    rules = []
    for code in sorted(checks):
        title = None
        page = wiki / f"SC{code}.md"
        if page.exists():
            for line in page.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if line:
                    title = re.sub(r"^#+\s*", "", line)
                    break
        if not title:
            title = checks[code]["message"] or f"ShellCheck SC{code}"
        title = re.sub(r"\s+", " ", title.replace("`", "")).strip()
        # drop a sentence-ending period, but keep ellipses such as "$(...)" or "backticked `...`."
        title = re.sub(r"(?<!\.)\.$", "", title).rstrip()
        if len(title) > 190:
            title = title[:187] + "..."
        rules.append({
            "key": f"SC{code}",
            "name": f"SC{code}: {title}" if not title.startswith(f"SC{code}") else title,
            "level": checks[code]["level"],
            "wiki": page.exists(),
            "wikiOnly": checks[code].get("wikiOnly", False),
            "defaultActive": not checks[code].get("wikiOnly", False) and checks[code]["level"] != "style",
        })

    rev = revision(src)
    pathlib.Path(out_file).parent.mkdir(parents=True, exist_ok=True)
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump({"shellcheckRevision": rev, "rules": rules}, f, indent=1)
        f.write("\n")
    by_level = {}
    for r in rules:
        by_level[r["level"]] = by_level.get(r["level"], 0) + 1
    print(f"{len(rules)} rules written to {out_file}: {by_level}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
