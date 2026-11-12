"""Tests for tools/gen_shellcheck_rules.py. Run with: python3 -m unittest discover -s tools/tests"""
import contextlib
import io
import json
import pathlib
import runpy
import sys
import tempfile
import unittest
from unittest import mock

TOOLS = pathlib.Path(__file__).resolve().parent.parent
SCRIPT = TOOLS / "gen_shellcheck_rules.py"
sys.path.insert(0, str(TOOLS))
import gen_shellcheck_rules as gen  # noqa: E402

ANALYTICS = '''
checkQuoting = do
    infoWithFix id 2086 "Double quote to prevent globbing and word splitting." $ fix
    warn (getId t) 2164 "Use 'cd ... || exit' in case cd fails."
    style id 2006 "Use $(...) notation instead of legacy backticks."
    err id 2006 "Upgraded: the most severe level wins."
    warnMsg id 3010 "[[ ]] is"
    info id 0042 "out of range: ignored"
    info id 2999
    info id 2999 "message found on a later call"
    parseProblemAt pos ErrorC 1035 "You need a space here."
    makeComment StyleC id 2001 "See if you can use ${variable//search/replace} instead."
'''


class GeneratorTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(self.tmp.name)
        self.src = root / "src"
        self.wiki = root / "wiki"
        self.out = root / "out" / "rules.json"
        (self.src / "ShellCheck").mkdir(parents=True)
        self.wiki.mkdir()
        (self.src / "ShellCheck" / "Analytics.hs").write_text(ANALYTICS)
        (self.src / "README.md").write_text('warn id 2222 "not a Haskell file"')
        self.page(2086, "\n\n## Double quote to prevent globbing and word splitting.\n\n### Problematic code:\n")
        self.page(2164, "# Use `cd ... || exit` in case `cd` fails.\n")
        self.page(1035, "SC1035 You need a space here\n")
        self.page(2001, "#   " + "x" * 200 + "\n")
        self.page(2039, "## In POSIX sh, something is undefined.\n")  # retired check: wiki only
        self.page(2008, "   \n")  # empty page: falls back to a generic title
        self.page(2006, "## Use `$(...)` notation instead of legacy backticked `` `...` ``.\n")
        self.page(1072, "## Unexpected ..\n")
        (self.wiki / "Home.md").write_text("# not a check\n")

    def tearDown(self):
        self.tmp.cleanup()

    def page(self, code, text):
        (self.wiki / f"SC{code}.md").write_text(text)

    def generate(self):
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            gen.main(str(self.src), str(self.wiki), str(self.out))
        data = json.loads(self.out.read_text())
        return data, {r["key"]: r for r in data["rules"]}, stdout.getvalue()

    def test_levels_from_sources(self):
        _, rules, _ = self.generate()
        self.assertEqual(rules["SC2086"]["level"], "info")
        self.assertEqual(rules["SC2164"]["level"], "warning")
        self.assertEqual(rules["SC2006"]["level"], "error")  # most severe of style/error
        self.assertEqual(rules["SC3010"]["level"], "warning")
        self.assertEqual(rules["SC1035"]["level"], "error")
        self.assertEqual(rules["SC2001"]["level"], "style")
        self.assertNotIn("SC0042", rules)
        self.assertNotIn("SC2222", rules)

    def test_titles(self):
        _, rules, _ = self.generate()
        # first non-blank wiki line, heading markers stripped, trailing period dropped
        self.assertEqual(rules["SC2086"]["name"], "SC2086: Double quote to prevent globbing and word splitting")
        self.assertEqual(rules["SC2164"]["name"], "SC2164: Use cd ... || exit in case cd fails")
        # ellipses are kept: only a single sentence-ending period is dropped
        self.assertEqual(rules["SC2006"]["name"], "SC2006: Use $(...) notation instead of legacy backticked ...")
        self.assertEqual(rules["SC1072"]["name"], "SC1072: Unexpected ..")
        # the wiki title already starts with the code
        self.assertEqual(rules["SC1035"]["name"], "SC1035 You need a space here")
        # too long: truncated
        self.assertEqual(len(rules["SC2001"]["name"]), len("SC2001: ") + 190)
        self.assertTrue(rules["SC2001"]["name"].endswith("..."))
        # no wiki page: message from the sources, possibly from a later call
        self.assertEqual(rules["SC3010"]["name"], "SC3010: [[ ]] is")
        self.assertEqual(rules["SC2999"]["name"], "SC2999: message found on a later call")
        self.assertEqual(rules["SC2008"]["name"], "SC2008: ShellCheck SC2008")

    def test_activation_and_wiki_flags(self):
        _, rules, _ = self.generate()
        self.assertTrue(rules["SC2086"]["defaultActive"])
        self.assertTrue(rules["SC2086"]["wiki"])
        self.assertFalse(rules["SC2001"]["defaultActive"], "style checks are off by default")
        self.assertFalse(rules["SC3010"]["wiki"])
        self.assertTrue(rules["SC2039"]["wikiOnly"])
        self.assertFalse(rules["SC2039"]["defaultActive"])
        self.assertEqual(rules["SC2039"]["level"], "warning")
        self.assertFalse(rules["SC2086"]["wikiOnly"])

    def test_output_file(self):
        data, rules, stdout = self.generate()
        self.assertEqual(data["shellcheckRevision"], "")  # not a git checkout (or no git at all)
        self.assertEqual([r["key"] for r in data["rules"]], sorted(rules))
        self.assertIn(f"{len(rules)} rules written to {self.out}", stdout)

    def test_revision_without_git(self):
        with mock.patch.object(gen.subprocess, "run", side_effect=FileNotFoundError("git")):
            self.assertEqual(gen.revision(self.src), "")

    def test_revision_of_a_checkout(self):
        completed = mock.Mock(stdout="1046c00\n")
        with mock.patch.object(gen.subprocess, "run", return_value=completed):
            self.assertEqual(gen.revision(self.src), "1046c00")

    def test_command_line(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT), str(self.src), str(self.wiki), str(self.out)]), \
                contextlib.redirect_stdout(io.StringIO()):
            runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertTrue(self.out.exists())

    def test_command_line_usage(self):
        with mock.patch.object(sys, "argv", [str(SCRIPT)]), self.assertRaises(SystemExit) as exit_:
            runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertIn("Usage", str(exit_.exception.code))


if __name__ == "__main__":
    unittest.main()
