# Feature intent: Customer Registration

Project context: see root `intent.md` (sections 2, 4.1, 5.1 and 6).

## 1. Objective

A new customer can register with a name, email and password. Registration always creates a Customer account.

## 2. Out of scope

Login, seeded accounts (Admin, Managers), password reset or change, password rules beyond non-empty, email verification, and any other role-based access.

## 3. Behaviour

**Input**
- `name`, `email` and `password` are all required.
- `name` and `email` must be non-empty after trimming surrounding whitespace, and at most 32 characters each.
- `email` must have a valid email format.
- `password` must be non-empty. There are no other password rules.
- Duplicate names are allowed.

**Role**
- Registration always creates a Customer.
- A role supplied in the request is ignored. It is neither honoured nor treated as an error.

**Email uniqueness**
- Emails are unique across all accounts, matched case-insensitively and ignoring surrounding whitespace.
- Seeded Admin and Manager emails count as used, so they cannot be registered as customers.
- Emails are stored and compared in a normalised form (trimmed, case-insensitive).
- A duplicate email is refused with the simple message: "email is already registered".
- Invalid-input refusals are distinct from the duplicate-email refusal. Their exact wording is left for the specification.

**Password handling**
- The password is secured with a secure password hash before it is stored. The plain password is never stored.
- Neither the password nor its hash is returned in any response.

**Result**
- On success, the response returns the new account's details except the password (for example id, name, email, role).
- Registration does not log the customer in and issues no session or token.

**Concurrency**
- If two registrations with the same email arrive at the same moment, at most one account is created. The other gets the "email is already registered" refusal.

## 4. Acceptance criteria

1. A valid name, email and password creates a Customer account and returns its details without the password.
2. The created account's role is always Customer, even if the request supplies another role.
3. Registering an email already in use is refused with "email is already registered". This holds regardless of letter case or surrounding whitespace.
4. A seeded Admin or Manager email cannot be registered.
5. A missing, blank or over-32-character name or email is refused.
6. An email with an invalid format is refused.
7. An empty password is refused.
8. Two customers may share the same name.
9. The stored password is a secure hash that differs from the supplied password. Neither the password nor its hash is returned anywhere.
10. Simultaneous registrations with the same email create at most one account.
11. Registration does not log the customer in.

## 5. Open items

- None. The 32-character maximum is confirmed for both name and email.

## 6. GitHub contribution workflow

All changes reach `main` through a pull request:

1. **Feature branch** - create a branch from the latest `main` (for example `feature/<name>` or `docs/<name>`).
2. **Pull request** - push the branch and open a pull request targeting `main`.
3. **CI checks** - GitHub Actions builds and tests the change; all checks must pass.
4. **Review** - at least one reviewer checks the change and requests updates if needed.
5. **Merge** - once checks pass and review is approved, merge the pull request into `main`.
