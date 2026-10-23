# Development Workflow

How changes get from a local branch into `main` in the booking-api project.

## 1. Feature branch development

Never commit directly to `main`. Branch from an up-to-date `main`:

```bash
git switch main
git pull
git switch -c feature/<short-name>
```

Name branches `<type>/<short-name>`. Existing branches use `ci/` and `docs/` (for example `ci/github-actions`, `docs/pr-review-practice`); use `feature/` or `fix/` for application work.

Before coding, read `features/<feature>/intent.md`. For complex or high-risk work (security, concurrency, data model changes), write and get approval for `features/<feature>/spec.md` and `plan.md` first. Keep each branch to one feature or fix.

## 2. Run Maven tests before a PR

Database tests use Testcontainers PostgreSQL, so Docker Desktop must be running. Run from the repository root:

| Task | PowerShell | Git Bash |
|---|---|---|
| Compile | `.\mvnw.cmd compile` | `./mvnw compile` |
| All tests | `.\mvnw.cmd test` | `./mvnw test` |

CI runs `./mvnw -B -ntp verify`, so `verify` is the closest local match if you want to reproduce it. CI also runs the Git hook tests (`python -I .claude/hooks/test_protect_git.py`); run them locally if you changed anything under `.claude/hooks/`.

Open a PR only when compile and tests pass. If a test fails or is skipped, say so in the PR description.

## 3. Commit changes

- Check what you are about to commit with `git status` and `git diff`.
- Stage files explicitly (`git add <path>`). Never commit `.env` or other secrets.
- Do not commit generated content (`target/`, `.idea/`, `HELP.md`).
- Prefer short, prefixed messages like the recent history:
  - `fix: validate normalized customer email length`
  - `ci: add automated Maven and hook verification`
  - `chore: strengthen Claude Code command guardrails`

## 4. Open a GitHub Pull Request

```bash
git push -u origin <branch-name>
```

Then open a PR on GitHub with `main` as the base branch. The description should cover:

- **What and why**: the change and the `intent.md` it relates to.
- **Testing**: the Maven commands run and their results.
- **Notes**: schema changes, new dependencies or config changes, if any.

The `CI` workflow (`.github/workflows/ci.yml`) runs automatically on pull requests targeting `main`, and on pushes to `main` and manual dispatch. It runs the Git hook tests, then `./mvnw -B -ntp verify` on Ubuntu with Java 25. Check that the run passes and fix failures before asking for review.

## 5. Code review and approval

- Request a review and respond to every comment.
- Make fixes as new commits on the same branch and push them. The PR updates automatically and CI re-runs.
- Reviewers should check the change against the feature's `intent.md` acceptance criteria and test coverage, and against the conventions in `CLAUDE.md` (thin controllers, DTOs instead of entities, no secrets or personal data logged).
- Merge once the review is resolved and CI has passed. This is the expected practice; the repository does not document branch protection or a required number of approvals.

## 6. Merge into `main`

- Merge through the GitHub PR page, not locally. PR #1 was merged with a merge commit.
- Optionally delete the branch after merging.
- Update your local copy:

```bash
git switch main
git pull
git branch -d <branch-name>
```
