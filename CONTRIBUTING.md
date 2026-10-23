# Contributing

Changes are intended to reach `main` through a pull request.

1. **Branch** - create a branch from the latest `main` (for example `feature/<name>` or `docs/<name>`).
2. **Pull request** - push the branch and open a pull request targeting `main`.
3. **CI** - GitHub Actions runs the checks below on every pull request.
4. **Review** - a reviewer checks the change and requests updates if needed.
5. **Merge** - merge into `main` once checks pass and the review is approved.

## What is enforced

Passing checks and human approval are *required* only where branch protection or repository rules for `main` are configured to demand them. Otherwise, treat steps 3-5 as team conventions.

## CI verification

CI (`.github/workflows/ci.yml`) uses Python 3.14 and Java 25 (Temurin) and runs:

```sh
python -I .claude/hooks/test_protect_git.py
sh ./mvnw -B -ntp verify
```

To reproduce locally, use Java 25 and Docker Desktop (tests use Testcontainers PostgreSQL).

| Shell | Maven verify |
|---|---|
| PowerShell | `.\mvnw.cmd -B -ntp verify` |
| Git Bash | `./mvnw -B -ntp verify` |
