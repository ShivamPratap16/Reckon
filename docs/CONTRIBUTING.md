# Contributing to Reckon

## Branch Protection (one-time admin setup — requires repo admin)

Enable these settings on GitHub at **Settings → Branches → Add rule** for the `main` branch:

| Setting | Value |
|---|---|
| Branch name pattern | `main` |
| Require a pull request before merging | ✅ enabled |
| Required approvals | 1 (or 0 for solo project) |
| Require status checks to pass before merging | ✅ enabled |
| Required status checks | `build` (the GitHub Actions CI job) |
| Require branches to be up to date before merging | ✅ enabled |
| Require linear history | ✅ enabled (squash merges keep history clean) |
| Do not allow bypassing the above settings | ✅ enabled |
| Allow force pushes | ❌ disabled |
| Allow deletions | ❌ disabled |

### Via GitHub CLI (if you have a PAT with `repo` scope)

```bash
gh api repos/ShivamPratap16/Reckon/branches/main/protection \
  --method PUT \
  --field required_status_checks='{"strict":true,"contexts":["build"]}' \
  --field enforce_admins=true \
  --field required_pull_request_reviews='{"required_approving_review_count":1}' \
  --field restrictions=null \
  --field required_linear_history=true \
  --field allow_force_pushes=false \
  --field allow_deletions=false
```

---

## Contribution Workflow

```
main  ←──────────────────────── squash-merge ──────────────────────────────
  └─ feat/my-feature ─── commit ─── push ─── PR ─── CI green ─── review ──┘
```

1. **Branch** off `main`:
   ```bash
   git checkout main && git pull
   git checkout -b feat/your-feature
   ```

2. **Develop** — commit small, logical units.

3. **Run the full gate locally** before pushing:
   ```bash
   ./gradlew spotlessApply   # auto-format
   ./gradlew build           # spotlessCheck + detekt + 81 tests + jacoco gate
   ```

4. **Push and open a PR**:
   ```bash
   git push -u origin feat/your-feature
   gh pr create --fill
   ```

5. **Wait for CI** — the `build` GitHub Actions job must pass (all gates, all 81 Testcontainers tests).

6. **Review + merge** — squash-merge to keep `main` linear.

---

## What CI checks (on every push/PR)

- `spotlessCheck` — ktlint formatting (run `./gradlew spotlessApply` to auto-fix)
- `detekt` — static analysis (new findings not in the baseline will block)
- `test` — all 81 Testcontainers integration tests
- `jacocoTestCoverageVerification` — LINE coverage must be ≥ 75%

---

## Local development

```bash
# Start infrastructure:
docker compose up -d

# Run the app:
./gradlew bootRun

# Run all tests:
./gradlew test

# Auto-format + full gate:
./gradlew spotlessApply && ./gradlew build
```
