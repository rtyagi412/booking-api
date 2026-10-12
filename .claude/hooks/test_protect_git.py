"""Tests for protect-git.py. Run: py -3 -I .claude/hooks/test_protect_git.py

Only simulated hook JSON is used; no Git command is ever executed.
"""
import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path

HOOK = Path(__file__).with_name("protect-git.py")
SETTINGS = Path(__file__).parent.parent / "settings.json"
spec = importlib.util.spec_from_file_location("protect_git", HOOK)
protect_git = importlib.util.module_from_spec(spec)
spec.loader.exec_module(protect_git)

BLOCKED = [
    "git push --force", "git push -f", "git push origin main --force", "git push -f origin main",
    "git push --force-with-lease", "git push --force-with-lease=main origin main", "git push -uf origin main",
    "git push --forc", "git push origin +main", "git push origin :old-branch", "git push --delete origin x",
    "git push --mirror",
    "git reset --hard", "git reset --hard HEAD~1", "git reset HEAD~1 --hard", "git reset --har",
    "git reset --merge",
    "git clean -fd", "git clean -f", "git clean -fdx", "git clean -xdf", "git clean --force", "git clean",
    "git branch -D feature", "git branch -D", "git branch -vD feature", "git branch --delete --force x",
    "git branch -d -f x", "git branch -df x", "git branch -fd x",
    # variants
    "GIT push --force","git.exe push -f", "/usr/bin/git push -f", "'git' push '-f'", 'g""it reset --hard',
    "git -C . push -f", "git -c color.ui=never reset --hard", "git --no-pager clean -fd",
    "git --git-dir=.git reset --hard", "FOO=1 git push -f", "env GIT_TRACE=1 git push -f",
    "sudo git reset --hard", "command git push -f", "xargs git push -f", "find . -exec git clean -fd {} ;",
    # compound / operators
    "git status && git push --force", "git status; git reset --hard", "git log | git clean -fd",
    "git status || git branch -D x", "git status\ngit push -f", "echo hi & git push -f",
    "(git push -f)", "{ git push -f; }", "echo $(git reset --hard)",
    'echo "$(git push -f)"', "x=$(git clean -fd)", "git status 2>&1 && git push -f",
    "git push -f > out.txt", "git push -f>out.txt", "if true; then git reset --hard; fi",
    "for b in a b; do git branch -D $b; done",
    # nested shells / eval
    "bash -c 'git push -f'", 'sh -lc "git reset --hard"', "eval 'git clean -fd'",
    "bash -c \"echo hi; git push --force\"", "sudo bash -c 'git reset --hard'",
    "cmd /c git push -f", 'cmd /c "git reset --hard"',
    "powershell -Command git push -f", 'pwsh -NoProfile -Command "git reset --hard"',
    'powershell -c "git status; git clean -fd"', "iex 'git push -f'", "Invoke-Expression 'git reset --hard'",
    "Start-Process git -ArgumentList 'push','--force'", "Start-Process git -ArgumentList 'reset --hard'",
    "cat <<EOF\n$(git reset --hard)\nEOF", "bash <<EOF\ngit push -f\nEOF", "bash <<< 'git push -f'",
    # fail closed
    "git push $FLAGS", "git reset $MODE", "git $SUB --hard", "$g push --force", "$(echo git) push --force",
    'git "unterminated', "git push --force $(", "git -c alias.x='reset --hard' x", "git --bogus-option push",
    "git branch $(echo -D) x", "git clean *",
]

ALLOWED = [
    "git status", "git diff", "git diff --stat HEAD~1", "git log --oneline -n 5", "git show HEAD",
    "git branch", "git branch -a", "git branch --list 'feat*'", "git branch -d merged-branch",
    "git branch -m old new", "git clean -n", "git clean -nfd", "git clean --dry-run -fd",
    "git push", "git push origin main", "git push -u origin feature", "git push --set-upstream origin x",
    "git push --dry-run", "git push --follow-tags", "git reset", "git reset HEAD file.txt",
    "git reset --soft HEAD~1", "git reset --mixed HEAD~1", "git reset --keep HEAD~1", "git reset --help",
    "git commit -m 'fix'", "git add .", "git checkout -b new-branch", "git pull --rebase", "git fetch --all",
    "git stash list", "git -C . status", "git -c color.ui=never log", "git --no-pager diff",
    "git config user.name", "git remote -v",
    # quoted / harmless text containing git commands
    'echo "git push --force"', "echo 'git reset --hard'", 'echo "git clean -fd" | cat',
    'git commit -m "docs: never run git reset --hard"', "git commit -m 'avoid git push -f'",
    'git commit -m "revert: git branch -D is dangerous"', "grep -r 'git push -f' .",
    "grep 'git clean -fd' README.md", "echo git push --force", 'echo "a && git push -f"',
    "echo hi # git push -f", "# git reset --hard",
    "git status 2>&1", "git log > log.txt", "git diff 2>/dev/null | head",
    "bash -c 'echo \"git push -f\"'", "bash -c 'git status'", "powershell -Command \"git status\"",
    "pwsh -NoProfile -File script.ps1", "Write-Output 'git reset --hard'", "ls -la", ".\\mvnw.cmd test",
    "./mvnw test -Dtest=Foo", "docker compose up -d", "echo $HOME", "git log --format=%H -- src/*.java",
    "& $mvn test", "$cmd arg1",
    "Start-Process git -ArgumentList 'status'", "xargs git status",
]

# Bash only: backslash escapes, backtick substitution and heredocs are not PowerShell syntax.
BASH_ONLY_BLOCKED = [
    "git reset --\\hard", "git reset --ha\\rd", "echo `git reset --hard`", 'echo "`git clean -fd`"',
]
BASH_ONLY_ALLOWED = [
    "git commit -m \"$(cat <<'EOF'\nfix: explain git reset --hard and git push -f\n\nbody (with parens\nEOF\n)\"",
    "git commit -m \"$(cat <<EOF\nnote about git clean -fd\nEOF\n)\"",
    "cat <<'EOF'\ngit push -f\nEOF",
    "cat <<-'EOF'\n\tgit reset --hard\n\tEOF",
]
# PowerShell: backslash is literal and backtick is the escape character, so these do not run Git.
POWERSHELL_ONLY_ALLOWED = ["echo `git reset --hard`", r"git -C C:\repo status"]


def run_hook(tool_name, command, raw=None):
    payload = raw if raw is not None else json.dumps(
        {"hook_event_name": "PreToolUse", "tool_name": tool_name, "tool_input": {"command": command}})
    return subprocess.run([sys.executable, "-I", str(HOOK)], input=payload,
                          capture_output=True, text=True, timeout=30)


class ClassificationTests(unittest.TestCase):
    def test_blocked_commands(self):
        for tool in ("Bash", "PowerShell"):
            for cmd in BLOCKED:
                with self.subTest(tool=tool, cmd=cmd):
                    self.assertIsNotNone(protect_git.evaluate(tool, cmd), "should be blocked")

    def test_allowed_commands(self):
        for tool in ("Bash", "PowerShell"):
            for cmd in ALLOWED:
                with self.subTest(tool=tool, cmd=cmd):
                    self.assertIsNone(protect_git.evaluate(tool, cmd), "should be allowed")

    def test_bash_only_syntax(self):
        for cmd in BASH_ONLY_BLOCKED:
            with self.subTest(cmd=cmd):
                self.assertIsNotNone(protect_git.evaluate("Bash", cmd))
        for cmd in BASH_ONLY_ALLOWED:
            with self.subTest(cmd=cmd):
                self.assertIsNone(protect_git.evaluate("Bash", cmd))

    def test_powershell_only_syntax(self):
        for cmd in POWERSHELL_ONLY_ALLOWED:
            with self.subTest(cmd=cmd):
                self.assertIsNone(protect_git.evaluate("PowerShell", cmd))

    def test_powershell_quoted_exe_path(self):
        self.assertIsNotNone(protect_git.evaluate(
            "PowerShell", '& "C:\\Program Files\\Git\\cmd\\git.exe" push -f'))

    def test_powershell_backtick_escape(self):
        self.assertIsNotNone(protect_git.evaluate("PowerShell", "git reset --ha`rd"))

    def test_powershell_encoded_command(self):
        import base64
        enc = base64.b64encode("git push --force".encode("utf-16-le")).decode()
        self.assertIsNotNone(protect_git.evaluate("PowerShell", f"powershell -EncodedCommand {enc}"))
        safe = base64.b64encode("git status".encode("utf-16-le")).decode()
        self.assertIsNone(protect_git.evaluate("PowerShell", f"powershell -enc {safe}"))

    def test_reason_is_informative(self):
        self.assertIn("force", protect_git.evaluate("Bash", "git push -f"))
        self.assertIn("reset --hard", protect_git.evaluate("Bash", "git reset --hard"))
        self.assertIn("git clean", protect_git.evaluate("Bash", "git clean -fd"))
        self.assertIn("git branch -D", protect_git.evaluate("Bash", "git branch -D x"))
        self.assertIn("cannot safely determine", protect_git.evaluate("Bash", "git push $X"))


CHECKOUT_RESTORE_BLOCKED = [
    "git checkout -- .", "git checkout -- src/main/java/Example.java", "git checkout .",
    "git checkout -f main", "git checkout --force main",
    "git restore .", "git restore src/main/java/Example.java", "git restore --worktree .",
    "git restore --source=HEAD .", "git restore --staged --worktree .",
    # option order, combined/short forms, abbreviations, paths after a revision
    "git checkout main -- src/Example.java", "git checkout HEAD -- .", "git checkout ./src", "git checkout -fb x",
    "git checkout --forc main", "git checkout -b x -f",
    "git restore -S -W .", "git restore -SW .", "git restore --worktree --staged .", "git restore --wor .",
    "git restore --source HEAD~1 --staged --worktree src/Example.java", "git -C . restore .",
    "git status && git checkout -- .", "bash -c 'git restore .'",
    # patch mode and conflict-side checkout
    "git checkout -p", "git checkout --patch", "git checkout -p main", "git checkout --pat", "git checkout -pq",
    "git checkout --ours -- file", "git checkout --theirs -- file", "git checkout --ours file",
    "git checkout --theirs file", "git checkout --our file", "git checkout --the file",
    "git checkout --patch HEAD -- src/Example.java", "git -C . checkout -p",
    # fail closed on unresolved arguments
    "git checkout $TARGET", "git restore $FILES",
]
CHECKOUT_RESTORE_ALLOWED = [
    "git checkout -b feature/customer-booking", "git checkout main", "git checkout feature/customer-booking",
    "git checkout -b x main", "git checkout -B x", "git checkout --detach main",
    "git checkout --orphan new-root", "git checkout --track origin/feature", "git checkout -q main",
    "git restore --staged src/main/java/Example.java", "git restore -S src/Example.java",
    "git restore --staged .", "git restore --source=HEAD --staged .",
    "git status", "git diff",
    'echo "git checkout -- ."', "git commit -m 'never run git restore .'", "grep 'git checkout -f' README.md",
]


class CheckoutRestoreTests(unittest.TestCase):
    def test_destructive_checkout_restore_blocked(self):
        for tool in ("Bash", "PowerShell"):
            for cmd in CHECKOUT_RESTORE_BLOCKED:
                with self.subTest(tool=tool, cmd=cmd):
                    self.assertIsNotNone(protect_git.evaluate(tool, cmd), "should be blocked")

    def test_safe_checkout_restore_allowed(self):
        for tool in ("Bash", "PowerShell"):
            for cmd in CHECKOUT_RESTORE_ALLOWED:
                with self.subTest(tool=tool, cmd=cmd):
                    self.assertIsNone(protect_git.evaluate(tool, cmd), "should be allowed")

    def test_reason_is_informative(self):
        self.assertIn("git checkout", protect_git.evaluate("Bash", "git checkout -- ."))
        self.assertIn("git restore", protect_git.evaluate("Bash", "git restore ."))

    def test_exit_codes_via_simulated_payload(self):
        for tool in ("Bash", "PowerShell"):
            for cmd in ("git checkout -- .", "git restore --worktree ."):
                with self.subTest(tool=tool, cmd=cmd):
                    r = run_hook(tool, cmd)
                    self.assertEqual(r.returncode, 2, r.stderr)
                    self.assertIn("BLOCKED by protect-git hook", r.stderr)
            for cmd in ("git checkout -b feature/customer-booking", "git restore --staged src/Example.java"):
                with self.subTest(tool=tool, cmd=cmd):
                    r = run_hook(tool, cmd)
                    self.assertEqual((r.returncode, r.stdout, r.stderr), (0, "", ""))


DELETE_BLOCKED = [
    "rm -rf src/", "rm -r src/", "rm -rf .git", "rm -rf .claude", "rm -rf .", "rm -rf ..",
    "rm -rf src/../.git", "rm -rf ./target/../src", "rm -rf src target",
    "Remove-Item . -Recurse -Force",
    "Remove-Item $path -Recurse -Force", "rmdir /s /q src",
    # encompassing / protected content, other spellings
    "rm -rf /", "rm -fr src", "rm -R src", "rm --recursive --force src", "rm -rf -- src", "rm -rf src/main/java",
    "rm pom.xml", "rm mvnw", "rm mvnw.cmd", "rm docker-compose.yml", "rm src/main/resources/application.yaml",
    "rm -rf .git/hooks", "rm -rf ./.claude/hooks", "rm -rf ../booking-api", "rm -rf features target/../.git",
    "rm -rf mytarget", "rm -rf not-target", "rm -rf target-backup", "rm -rf /tmp/target", "rm -rf ../target",
    "rm -rf features", "rm -rf ~", "rm -rf ~/x",
    "rm -rf target/*", "rm -rf $TARGET", "rm -rf $(pwd)", "rm -rf *", "rm -rf $HOME/target",
    "rm -rf target/../.", "sudo rm -rf src", "xargs rm -rf", "find . -exec rm -rf {} +", "command rm -rf .git",
    "echo hi && rm -rf src", "bash -c 'rm -rf src'", "cd .. && rm -rf target", "cd src; rm -rf x",
    "ri -r src", "Remove-Item -Path src -Recurse", "Remove-Item -LiteralPath .git -Recurse -Force",
    "Remove-Item -Rec src", "Remove-Item src,target -Recurse", "Remove-Item -Path:src -Recurse",
    "del /s src", "rd /s /q .git", "rmdir /s /q .claude", "rmdir /s /q features",
    "cmd /c rmdir /s /q src", 'cmd /c "rd /s /q .git"', "rmdir /s /q ..",
]
# PowerShell only: in Bash a backslash is an escape character, so these paths mean something else.
DELETE_POWERSHELL_ONLY_BLOCKED = [
    r"Remove-Item .\src -Recurse -Force", r"Remove-Item .\.git -Recurse -Force",
    r"Remove-Item .\.claude -Recurse -Force", r"Remove-Item .\featu* -Recurse",
    r"Remove-Item C:\ -Recurse -Force", r"Remove-Item src\main\java\X.java",
]
DELETE_POWERSHELL_ONLY_ALLOWED = [r"Remove-Item .\target -Recurse -Force"]
DELETE_ALLOWED = [
    "rm -rf target", "rm -rf target/", "rm -rf ./target", "rm -r target", "rm -rf target/classes",
    "rm -rf -- target", "rm -rf ./target/./classes", "rm -rf features/../target",
    "Remove-Item target -Recurse", "Remove-Item -Path target -Recurse -Force",
    "rmdir /s /q target", "rd /s /q target", "cmd /c rmdir /s /q target",
    "rm notes.tmp", "rm -f build.log", "rm /tmp/scratch.txt", "rm features/draft.tmp",
    "Remove-Item scratch.tmp", "Remove-Item scratch.tmp -Force", "del scratch.tmp",
    "rm -rf target && ./mvnw package",
    # quoted data and harmless commands
    'echo "rm -rf src"', "echo 'Remove-Item . -Recurse'", "git commit -m 'never run rm -rf .git'",
    "grep 'rm -rf' README.md", "ls src", "cat pom.xml", "rm", "mv notes.tmp old.tmp", r".\mvnw.cmd clean",
]


class DeletionTests(unittest.TestCase):
    def test_powershell_backslash_paths(self):
        for cmd in DELETE_POWERSHELL_ONLY_BLOCKED:
            with self.subTest(cmd=cmd):
                self.assertIsNotNone(protect_git.evaluate("PowerShell", cmd), "should be blocked")
        for cmd in DELETE_POWERSHELL_ONLY_ALLOWED:
            with self.subTest(cmd=cmd):
                self.assertIsNone(protect_git.evaluate("PowerShell", cmd), "should be allowed")

    def test_protected_or_ambiguous_deletion_blocked(self):
        for tool in ("Bash", "PowerShell"):
            for cmd in DELETE_BLOCKED:
                with self.subTest(tool=tool, cmd=cmd):
                    self.assertIsNotNone(protect_git.evaluate(tool, cmd), "should be blocked")

    def test_safe_cleanup_allowed(self):
        for tool in ("Bash", "PowerShell"):
            for cmd in DELETE_ALLOWED:
                with self.subTest(tool=tool, cmd=cmd):
                    self.assertIsNone(protect_git.evaluate(tool, cmd), "should be allowed")

    def test_reason_is_informative(self):
        self.assertIn("protected", protect_git.evaluate("Bash", "rm -rf src"))
        self.assertIn("target", protect_git.evaluate("Bash", "rm -rf features"))
        self.assertIn("cannot safely determine", protect_git.evaluate("Bash", "rm -rf $X"))

    def test_exit_codes_via_simulated_payload(self):
        for tool in ("Bash", "PowerShell"):
            for cmd in ("rm -rf src/", "rm -rf .git", "Remove-Item $path -Recurse -Force", "rmdir /s /q src"):
                with self.subTest(tool=tool, cmd=cmd):
                    r = run_hook(tool, cmd)
                    self.assertEqual(r.returncode, 2, r.stderr)
                    self.assertIn("BLOCKED by protect-git hook", r.stderr)
            for cmd in ("rm -rf target", "Remove-Item target -Recurse -Force", "rm scratch.tmp"):
                with self.subTest(tool=tool, cmd=cmd):
                    r = run_hook(tool, cmd)
                    self.assertEqual((r.returncode, r.stdout, r.stderr), (0, "", ""))


DELETE_FLAG_NOT_RECURSIVE = [  # not a recursive request; the unprotected target is a single path
    "rm -Force features", "rm -Force scratch.tmp", "rm -f features", "rm -fv features",
    "rm -- -rf features",  # after '--' both words are file names
    "rm -rF features",  # 'F' is not an rm option, so rm rejects the whole command and deletes nothing
]
DELETE_FLAG_RECURSIVE = [  # valid recursive spellings, still blocked on an unprotected non-target directory
    "rm -r features", "rm -R features", "rm -rf features", "rm -fr features", "rm -fR features",
    "rm -rv features", "rm -vr features", "rm -Rf features", "rm --recursive features", "rm --rec features",
    "rm -Recurse features", "rm -recurse features", "rm -rec features", "rm -Recurse -Force features",
    "rm -Force -Recurse features", "rm -f -r features",
]
DELETE_NEWLY_PROTECTED_BLOCKED = [
    "rm -rf .mvn", "rm -r .mvn/", "rm -rf .mvn/wrapper", "rm .mvn/wrapper/maven-wrapper.properties",
    "rm -rf ./.mvn", "rm -rf features/../.mvn", "rm -rf target/../.mvn",
    "rm CLAUDE.md", "rm README.md", "rm -f README.md", "rm -f CLAUDE.md", "rm -rf CLAUDE.md", "rm -rf README.md",
    "rm ./README.md", "rm features/../README.md", "rm -- CLAUDE.md",
    "Remove-Item README.md", "Remove-Item CLAUDE.md -Force", "Remove-Item .mvn -Recurse -Force",
    "Remove-Item -Path .mvn/wrapper -Recurse", "Remove-Item -LiteralPath README.md",
    "del CLAUDE.md", "del README.md", "rmdir /s /q .mvn", "rd /s /q .mvn", "cmd /c rmdir /s /q .mvn",
    "sudo rm CLAUDE.md", "bash -c 'rm -rf .mvn'", "echo hi && rm README.md",
]
DELETE_NEWLY_PROTECTED_ALLOWED = [  # similarly named, but not the protected paths
    "rm features/README.md", "rm CLAUDE.md.bak", "rm README.md.tmp", "rm target/README.md", "rm -rf target/.mvn",
    "rm mvn.log", "cat README.md", "grep -n x CLAUDE.md", "echo 'rm README.md'",
]


class DeletionRefinementTests(unittest.TestCase):
    def check(self, commands, expect_blocked):
        for tool in ("Bash", "PowerShell"):
            for cmd in commands:
                with self.subTest(tool=tool, cmd=cmd):
                    reason = protect_git.evaluate(tool, cmd)
                    if expect_blocked:
                        self.assertIsNotNone(reason, "should be blocked")
                    else:
                        self.assertIsNone(reason, "should be allowed")

    def test_non_recursive_flags_not_misread_as_recursive(self):
        self.check(DELETE_FLAG_NOT_RECURSIVE, expect_blocked=False)

    def test_valid_recursive_flags_still_detected(self):
        self.check(DELETE_FLAG_RECURSIVE, expect_blocked=True)

    def test_mvn_claude_md_and_readme_protected(self):
        self.check(DELETE_NEWLY_PROTECTED_BLOCKED, expect_blocked=True)

    def test_similarly_named_paths_not_protected(self):
        self.check(DELETE_NEWLY_PROTECTED_ALLOWED, expect_blocked=False)

    def test_powershell_backslash_paths(self):
        for cmd in (r"Remove-Item .\.mvn -Recurse -Force", r"Remove-Item .\README.md", r"del .\CLAUDE.md"):
            with self.subTest(cmd=cmd):
                self.assertIsNotNone(protect_git.evaluate("PowerShell", cmd), "should be blocked")


class HookProcessTests(unittest.TestCase):
    """End to end: simulated hook JSON on stdin, real exit codes and stderr."""

    def test_blocked_exit_code_and_message(self):
        for tool in ("Bash", "PowerShell"):
            r = run_hook(tool, "git push --force origin main")
            self.assertEqual(r.returncode, 2, r.stderr)
            self.assertIn("BLOCKED by protect-git hook", r.stderr)
            self.assertIn("force", r.stderr)
            self.assertEqual(r.stdout, "")

    def test_allowed_exit_code(self):
        for tool in ("Bash", "PowerShell"):
            r = run_hook(tool, "git status")
            self.assertEqual((r.returncode, r.stdout, r.stderr), (0, "", ""))

    def test_other_tools_ignored(self):
        r = run_hook("Edit", None, raw=json.dumps(
            {"tool_name": "Edit", "tool_input": {"file_path": "a", "new_string": "git push -f"}}))
        self.assertEqual(r.returncode, 0)

    def test_fail_closed_on_bad_input(self):
        for raw in ("", "not json", "[]", json.dumps({"tool_name": "Bash", "tool_input": {}}),
                    json.dumps({"tool_name": "Bash"}),
                    json.dumps({"tool_name": "Bash", "tool_input": {"command": 5}})):
            with self.subTest(raw=raw):
                self.assertEqual(run_hook("Bash", None, raw=raw).returncode, 2)

    def test_unparseable_command_blocks_with_explanation(self):
        r = run_hook("Bash", 'git commit -m "oops')
        self.assertEqual(r.returncode, 2)
        self.assertIn("cannot safely determine", r.stderr)


class SettingsTests(unittest.TestCase):
    def test_hook_registered(self):
        settings = json.loads(SETTINGS.read_text(encoding="utf-8"))
        groups = settings["hooks"]["PreToolUse"]
        group = next(g for g in groups if "protect-git.py" in json.dumps(g))
        self.assertEqual(set(group["matcher"].split("|")), {"Bash", "PowerShell"})
        handler = group["hooks"][0]
        self.assertEqual(handler["type"], "command")
        self.assertEqual(handler["command"], "py")
        self.assertTrue(handler["args"][-1].endswith("/.claude/hooks/protect-git.py"))
        self.assertTrue((Path(__file__).parent / "protect-git.py").exists())


if __name__ == "__main__":
    unittest.main(verbosity=1)
