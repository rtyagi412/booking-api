#!/usr/bin/env python3
"""PreToolUse hook: block destructive Git commands issued through the Bash or PowerShell tool.

Reads the hook event JSON on stdin. Exit 0 = allow (no opinion), exit 2 = block
(stderr is shown to Claude). Any internal error or unparseable shell input also
blocks (fail closed). Standard library only.

Blocked: git push --force/-f/--force-with-lease/+refspec/--mirror/--delete/--prune/:ref,
         git reset --hard/--merge, git clean (unless dry-run), git branch -D / -d -f.
Quoted text such as `echo "git reset --hard"` is data and is not blocked.
"""
import base64
import json
import re
import sys
from dataclasses import dataclass, field

SHELL_TOOLS = {"Bash": "bash", "PowerShell": "ps"}
MAX_DEPTH = 6

ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*\+?=")
PREFIX_KEYWORDS = {"!", "if", "then", "else", "elif", "do", "while", "until", "time"}
SHELLS = {"bash": "bash", "sh": "bash", "zsh": "bash", "dash": "bash", "ksh": "bash",
          "pwsh": "ps", "powershell": "ps", "cmd": "ps"}
EVALS = {"eval", "invoke-expression", "iex"}
WRAPPERS = {"env", "sudo", "doas", "nohup", "nice", "ionice", "timeout", "command", "builtin",
            "exec", "xargs", "find", "start", "start-process", "saps", "invoke-command", "icm",
            "stdbuf", "setsid", "watch", "wsl", "busybox"}
START_PROCESS = {"start-process", "saps"}
START_PROCESS_PARAMS = {"-argumentlist", "-args", "-wait", "-nonewwindow", "-passthru"}
RISKY_SUBCOMMANDS = {"push", "reset", "clean", "branch"}
RISKY_WHEN_UNRESOLVED = RISKY_SUBCOMMANDS | {"--hard", "--force", "-f", "-d"}

GIT_GLOBAL_WITH_VALUE = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--super-prefix",
                         "--config-env", "--exec-path-value"}
GIT_GLOBAL_WITH_INLINE_VALUE = ("--git-dir=", "--work-tree=", "--namespace=", "--super-prefix=",
                                "--exec-path=", "--config-env=", "--list-cmds=", "--attr-source=")
GIT_GLOBAL_FLAGS = {"-p", "-P", "--paginate", "--no-pager", "--bare", "--no-replace-objects",
                    "--literal-pathspecs", "--glob-pathspecs", "--noglob-pathspecs",
                    "--icase-pathspecs", "--no-optional-locks", "--no-lazy-fetch",
                    "--exec-path", "--html-path", "--man-path", "--info-path", "--no-advice"}


class Unsafe(Exception):
    """The command cannot be analysed safely; the hook must block it."""


@dataclass
class Word:
    text: str
    dynamic: bool = False


@dataclass
class Segment:
    words: list = field(default_factory=list)
    stdin_script: bool = False


class Lexer:
    """Small shell lexer. Splits into simple-command segments, descending into
    command substitutions, and keeps quoted text as plain data."""

    def __init__(self, src, shell):
        self.s = src
        self.i = 0
        self.shell = shell
        self.segments = []

    @property
    def escape(self):
        return "\\" if self.shell == "bash" else "`"

    def run(self):
        self._commands(None)
        return self.segments

    def _commands(self, stop):
        s, n = self.s, len(self.s)
        st = {"words": [], "buf": [], "in_word": False, "quoted": False, "dyn": False,
              "skip": False, "script": False}
        paren = 0
        pending_heredocs = []

        def end_word():
            if not st["in_word"]:
                return
            text = "".join(st["buf"])
            if st["skip"]:
                st["skip"] = False
            elif text in ("{", "}") and not st["quoted"]:
                end_segment()
            else:
                st["words"].append(Word(text, st["dyn"]))
            st["buf"], st["in_word"], st["quoted"], st["dyn"] = [], False, False, False

        def end_segment():
            if st["words"]:
                self.segments.append(Segment(st["words"], st["script"]))
            st["words"], st["script"] = [], False

        def add(ch, quoted=False, dyn=False):
            st["buf"].append(ch)
            st["in_word"] = True
            st["quoted"] = st["quoted"] or quoted
            st["dyn"] = st["dyn"] or dyn

        while self.i < n:
            c = s[self.i]
            if stop == "`" and c == "`":
                end_word(); end_segment(); self.i += 1
                return
            if stop == ")" and c == ")" and paren == 0:
                end_word(); end_segment(); self.i += 1
                return
            if c == self.escape:
                nxt = s[self.i + 1] if self.i + 1 < n else ""
                if nxt == "\n":
                    self.i += 2
                elif nxt:
                    add(nxt, quoted=True); self.i += 2
                else:
                    self.i += 1
            elif c == "'":
                self.i += 1
                text = self._single_quoted()
                st["in_word"] = True; st["quoted"] = True
                st["buf"].append(text)
            elif c == '"':
                self.i += 1
                text, dyn = self._dquote('"')
                st["in_word"] = True; st["quoted"] = True
                st["buf"].append(text)
                st["dyn"] = st["dyn"] or dyn
            elif c == "$":
                nxt = s[self.i + 1] if self.i + 1 < n else ""
                if nxt == "(":
                    add("$", dyn=True); self.i += 2
                    self._commands(")")
                elif nxt == "{":
                    end = s.find("}", self.i)
                    if end < 0:
                        raise Unsafe("unterminated ${...} expansion")
                    add("$", dyn=True)
                    st["buf"].append(s[self.i + 1:end + 1]); self.i = end + 1
                else:
                    add("$", dyn=True); self.i += 1
            elif c == "`":  # bash only; in PowerShell the backtick is the escape character
                add("`", dyn=True); self.i += 1
                self._commands("`")
            elif c in " \t\r":
                end_word(); self.i += 1
            elif c == "\n":
                end_word(); end_segment(); self.i += 1
                for delim, quoted_delim, strip in pending_heredocs:
                    self._heredoc_body(delim, quoted_delim, strip)
                pending_heredocs = []
            elif c in ";&|":
                end_word(); end_segment()
                self.i += 2 if s[self.i + 1:self.i + 2] in ("&", "|") and c != ";" else 1
            elif c == "(":
                end_word(); end_segment(); paren += 1; self.i += 1
            elif c == ")":
                end_word(); end_segment(); paren = max(0, paren - 1); self.i += 1
            elif c in "<>":
                if st["in_word"] and not st["quoted"] and "".join(st["buf"]).isdigit():
                    st["buf"], st["in_word"] = [], False  # file descriptor prefix such as 2>
                else:
                    end_word()
                if s.startswith("<<<", self.i):
                    st["script"] = True; st["skip"] = True; self.i += 3
                elif s.startswith("<<", self.i) and self.shell == "bash":
                    self.i += 2
                    pending_heredocs.append(self._heredoc_delimiter())
                    st["script"] = True
                else:
                    self.i += 1
                    while self.i < n and s[self.i] in ">&|":
                        self.i += 1
                    st["skip"] = True
            elif c == "#" and not st["in_word"]:
                while self.i < n and s[self.i] != "\n":
                    self.i += 1
            else:
                dyn = c in "*?[" or (c == "{" and (st["in_word"] or s[self.i + 1:self.i + 2] not in (" ", "\t", "\n", "")))
                add(c, dyn=dyn); self.i += 1
        if stop is not None:
            raise Unsafe("unterminated command substitution")
        end_word(); end_segment()

    def _single_quoted(self):
        s = self.s
        out = []
        while True:
            end = s.find("'", self.i)
            if end < 0:
                raise Unsafe("unterminated single quote")
            out.append(s[self.i:end])
            self.i = end + 1
            if self.shell == "ps" and s[self.i:self.i + 1] == "'":  # '' is an escaped quote
                out.append("'"); self.i += 1
                continue
            return "".join(out)

    def _dquote(self, term):
        """Read double-quoted text (or a heredoc body when term is None).
        Expansions are scanned for nested commands. Returns (text, dynamic)."""
        s, n = self.s, len(self.s)
        out, dyn = [], False
        while True:
            if self.i >= n:
                if term is None:
                    return "".join(out), dyn
                raise Unsafe("unterminated double quote")
            c = s[self.i]
            if term and c == term:
                if self.shell == "ps" and s[self.i + 1:self.i + 2] == '"':
                    out.append('"'); self.i += 2
                    continue
                self.i += 1
                return "".join(out), dyn
            if c == self.escape:
                nxt = s[self.i + 1:self.i + 2]
                if self.shell == "ps" or nxt in ("$", "`", '"', "\\", "\n"):
                    if nxt != "\n":
                        out.append(nxt)
                    self.i += 2
                else:
                    out.append(c); self.i += 1
            elif c == "$":
                nxt = s[self.i + 1:self.i + 2]
                dyn = True
                out.append("$")
                if nxt == "(":
                    self.i += 2
                    self._commands(")")
                elif nxt == "{":
                    end = s.find("}", self.i)
                    if end < 0:
                        raise Unsafe("unterminated ${...} expansion")
                    out.append(s[self.i + 1:end + 1]); self.i = end + 1
                else:
                    self.i += 1
            elif c == "`" and self.shell == "bash":
                dyn = True
                self.i += 1
                self._commands("`")
            else:
                out.append(c); self.i += 1

    def _heredoc_delimiter(self):
        s, n = self.s, len(self.s)
        strip = s[self.i:self.i + 1] == "-"
        if strip:
            self.i += 1
        while self.i < n and s[self.i] in " \t":
            self.i += 1
        quoted, out = False, []
        while self.i < n and s[self.i] not in " \t\n;&|()<>":
            ch = s[self.i]
            if ch in "'\"":
                quoted = True
                end = s.find(ch, self.i + 1)
                if end < 0:
                    raise Unsafe("unterminated heredoc delimiter")
                out.append(s[self.i + 1:end]); self.i = end + 1
            elif ch == "\\":
                quoted = True
                out.append(s[self.i + 1:self.i + 2]); self.i += 2
            else:
                out.append(ch); self.i += 1
        if not out:
            raise Unsafe("cannot determine heredoc delimiter")
        return "".join(out), quoted, strip

    def _heredoc_body(self, delim, quoted_delim, strip):
        s, n = self.s, len(self.s)
        start = self.i
        body = s[start:]
        while self.i < n:
            line_start = self.i
            end = s.find("\n", line_start)
            end = n if end < 0 else end
            line = s[line_start:end].rstrip("\r")
            self.i = min(end + 1, n)
            if (line.lstrip("\t") if strip else line) == delim:
                body = s[start:line_start]
                break
        if not quoted_delim:  # unquoted heredoc bodies still run $(...) and backticks
            sub = Lexer(body, self.shell)
            sub._dquote(None)
            self.segments.extend(sub.segments)


def base_name(text):
    name = re.split(r"[\\/]", text)[-1].lower()
    return re.sub(r"\.(exe|cmd|bat|com)$", "", name)


def lex(command, shell):
    return Lexer(command, shell).run()


def check_command(command, shell, depth=0):
    """Return a human-readable block reason, or None if the command is allowed."""
    if depth > MAX_DEPTH:
        raise Unsafe("commands nested too deeply to analyse")
    for seg in lex(command, shell):
        reason = check_segment(seg, depth)
        if reason:
            return reason
    return None


def check_segment(seg, depth):
    words = seg.words
    i = 0
    while i < len(words) and (ASSIGN.match(words[i].text) or
                              (not words[i].dynamic and words[i].text.lower() in PREFIX_KEYWORDS)):
        i += 1
    if i >= len(words):
        return None
    cmd, rest = words[i], words[i + 1:]
    if cmd.dynamic:
        if any(w.text.lower() in RISKY_WHEN_UNRESOLVED for w in rest):
            raise Unsafe("the command name is built dynamically (variable or substitution) and "
                         "is followed by destructive-looking Git arguments")
        return None
    name = base_name(cmd.text)
    if name == "git":
        return check_git(rest)
    if name in SHELLS:
        if seg.stdin_script:
            raise Unsafe(f"script fed to '{name}' through stdin/heredoc cannot be analysed")
        return check_shell(name, rest, depth)
    if name in EVALS:
        return check_command(" ".join(w.text for w in rest), "bash" if name == "eval" else "ps", depth + 1)
    if name in WRAPPERS:
        return check_wrapper(name, rest, depth)
    return None


def check_wrapper(name, rest, depth):
    for j, w in enumerate(rest):
        target = base_name(w.text)
        if target == "git":
            args = rest[j + 1:]
            if name in START_PROCESS:
                flat = [t for a in args for t in re.split(r"[\s,]+", a.text) if t]
                args = [Word(t, any(a.dynamic for a in rest[j + 1:])) for t in flat
                        if t.lower() not in START_PROCESS_PARAMS]
            return check_git(args)
        if target in SHELLS:
            return check_shell(target, rest[j + 1:], depth)
    return None


def check_shell(name, args, depth):
    kind = SHELLS[name]
    texts = [a.text for a in args]
    payload = None
    if name == "cmd":
        for k, t in enumerate(texts):
            if t.lower() in ("/c", "/k", "/s"):
                payload = " ".join(texts[k + 1:])
                if t.lower() == "/s" and k + 1 < len(texts) and texts[k + 1].lower() in ("/c", "/k"):
                    payload = " ".join(texts[k + 2:])
                break
    elif kind == "bash":
        for k, t in enumerate(texts):
            if re.match(r"^-[A-Za-z]*c[A-Za-z]*$", t):
                if k + 1 >= len(texts):
                    raise Unsafe(f"'{name} {t}' has no command string")
                payload = texts[k + 1]
                break
    else:
        k = 0
        while k < len(texts):
            t = texts[k].lower()
            if t in ("-file", "-f"):
                return None  # script file contents cannot be inspected here
            if t.startswith("-") and len(t) >= 2 and "-encodedcommand".startswith(t) and t != "-":
                if k + 1 >= len(texts):
                    raise Unsafe("-EncodedCommand has no value")
                try:
                    payload = base64.b64decode(texts[k + 1]).decode("utf-16-le")
                except Exception:
                    raise Unsafe("could not decode -EncodedCommand value")
                break
            if t.startswith("-c") and "-command".startswith(t):
                payload = " ".join(texts[k + 1:])
                break
            if t in ("-executionpolicy", "-ep", "-windowstyle", "-w", "-workingdirectory", "-wd",
                     "-inputformat", "-outputformat", "-configurationname", "-version"):
                k += 2
            elif t.startswith("-"):
                k += 1
            else:
                payload = " ".join(texts[k:])
                break
    if payload is None:
        return None
    return check_command(payload, kind, depth + 1)


def short_flags(args):
    return "".join(a.text[1:] for a in args if a.text.startswith("-") and not a.text.startswith("--"))


def long_flag(args, full, min_len=3):
    """True if any argument is `full` or an unambiguous-style abbreviation of it."""
    for a in args:
        if a.text.startswith("--"):
            name = a.text.split("=", 1)[0]
            if len(name) >= min_len and full.startswith(name):
                return True
    return False


def check_git(args):
    i = 0
    while i < len(args):
        t = args[i].text
        if t in ("-c", "--config-env"):
            if i + 1 >= len(args) or "alias." in args[i + 1].text.lower():
                raise Unsafe("'git -c alias.*' can hide any Git command behind an alias")
            i += 2
        elif t in GIT_GLOBAL_WITH_VALUE:
            i += 2
        elif t.startswith(GIT_GLOBAL_WITH_INLINE_VALUE):
            if t.startswith("--config-env=") and "alias." in t.lower():
                raise Unsafe("'git --config-env=alias.*' can hide any Git command behind an alias")
            i += 1
        elif t in GIT_GLOBAL_FLAGS:
            i += 1
        elif t.startswith("-"):
            raise Unsafe(f"unrecognised global git option '{t}'")
        else:
            break
    if i >= len(args):
        return None
    sub, rest = args[i], args[i + 1:]
    if sub.dynamic:
        raise Unsafe("the git subcommand is built dynamically (variable or substitution)")
    sub_name = sub.text.lower()
    if sub_name not in RISKY_SUBCOMMANDS:
        return None
    if any(a.dynamic for a in rest):
        raise Unsafe(f"arguments to 'git {sub_name}' use variables, substitutions or globs, "
                     "so destructive flags cannot be ruled out")
    return {"push": check_push, "reset": check_reset, "clean": check_clean,
            "branch": check_branch}[sub_name](rest)


def check_push(args):
    flags = short_flags(args)
    if "f" in flags or any(a.text.split("=", 1)[0].startswith("--force") for a in args) \
            or long_flag(args, "--force"):
        return "git push with force (-f/--force/--force-with-lease) can overwrite remote history"
    if long_flag(args, "--mirror") or long_flag(args, "--delete") or "d" in flags \
            or long_flag(args, "--prune"):
        return "git push --mirror/--delete/--prune can delete remote refs"
    for a in args:
        if not a.text.startswith("-") and a.text.startswith(("+", ":")):
            return f"git push refspec '{a.text}' forces an update or deletes a remote ref"
    return None


def check_reset(args):
    if long_flag(args, "--hard") or long_flag(args, "--merge"):
        return "git reset --hard/--merge discards uncommitted changes"
    return None


def check_clean(args):
    if "n" in short_flags(args) or long_flag(args, "--dry-run"):
        return None
    return "git clean deletes untracked files; only a dry run (-n/--dry-run) is allowed"


def check_branch(args):
    flags = short_flags(args)
    force = "f" in flags or long_flag(args, "--force")
    delete = "d" in flags or long_flag(args, "--delete")
    if "D" in flags or (delete and force):
        return "git branch -D (force delete) can lose unmerged commits"
    return None


def evaluate(tool_name, command):
    """Return a block reason for a proposed command, or None to allow it."""
    shell = SHELL_TOOLS[tool_name]
    try:
        return check_command(command, shell)
    except Unsafe as exc:
        return f"cannot safely determine whether this command is destructive ({exc})"


def block(reason, command):
    shown = command if len(command) <= 300 else command[:300] + "..."
    print(f"BLOCKED by protect-git hook: {reason}.\n"
          f"Command: {shown}\n"
          "Destructive Git operations must be run by the user themselves; do not retry or "
          "rephrase this command. Ask the user if it is really needed.", file=sys.stderr)
    return 2


def main():
    try:
        event = json.load(sys.stdin)
        tool_name = event.get("tool_name")
        if tool_name not in SHELL_TOOLS:
            return 0
        command = (event.get("tool_input") or {}).get("command")
        if not isinstance(command, str):
            return block("hook input had no readable shell command", repr(command))
        reason = evaluate(tool_name, command)
    except Exception as exc:  # fail closed on any unexpected error
        return block(f"hook error ({type(exc).__name__}: {exc})", "<unavailable>")
    return block(reason, command) if reason else 0


if __name__ == "__main__":
    sys.exit(main())
