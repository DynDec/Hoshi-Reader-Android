# Hoshi Reader Android Agent Guide

Hoshi Reader Android is a native Android reader built with Kotlin and Jetpack Compose.
This file covers how agents work in this repository and how docs are maintained;
code entry points and implementation contracts are in the architecture doc.

## Getting started

- Start every task by checking Git status, and preserve unrelated changes. Before
  changing App code, read the [common implementation contracts](docs/ARCHITECTURE.md#common-implementation-contracts),
  then use the architecture index to find the relevant domain constraints, owners,
  code and tests, and make the change through the owning component. If a task
  moves a boundary, say so in the task first.
- Base everyday work on the request and the Android architecture contracts; trace
  interactions, state flow and edge behavior through the current code and tests.
  When fixing a bug, find the root cause first.
- iOS feature ports go through the `sync-ios-upstream` process and
  `docs/IOS_UPSTREAM_SYNC_QUEUE.md`; ports and parity work follow the matching
  implementation in `reference/Hoshi-Reader-iOS`.
- For Android platform capabilities (permissions, SAF, WebView, Media3,
  WorkManager, Google/Jetpack APIs, packaging, installation, background work),
  check the official documentation for the current recommended approach first.

## This fork and upstream merges

This repository (`origin`, DynDec) is a fork of
[HuangAntimony/Hoshi-Reader-Android](https://github.com/HuangAntimony/Hoshi-Reader-Android)
(`upstream`). The fork's application ID is `moe.requited.hoshi`.

- Merge upstream with one `git merge --no-ff upstream/main` on a
  `codex/merge-upstream-*` branch, then merge that branch into `main` after
  validation. Do not rebase or squash upstream history.
- **The fork's features and decisions take precedence over upstream changes.**
  When a conflict touches fork-specific behavior, keep the fork's behavior and
  adapt upstream's change around it; take upstream's version only where it does
  not alter fork behavior. If upstream's change cannot coexist with a fork
  feature, keep the fork feature and report what upstream change was dropped or
  adapted.
- Fork-specific behavior is listed in
  [Fork-specific contracts](docs/ARCHITECTURE.md#fork-specific-contracts).
  After resolving conflicts, re-check each listed behavior, even in files
  that merged without conflicts, because upstream changes elsewhere can still
  break it.
- Fork decisions that also win over upstream:
  - The fork's short `docs/VALIDATION.md`, its test policy, and this English
    `AGENTS.md`. Fold in only new, generally useful upstream rules, rewritten to
    fit.
  - Tests the fork deleted stay deleted; resolve modify/delete conflicts on them
    as delete. New upstream tests are kept only if they meet the
    [test bar](docs/VALIDATION.md#when-to-add-a-test).
  - Release and preview workflows stay verbatim from upstream and inert, since
    the fork does not publish APKs. Do not adapt their app-ID checks.
  - `.github/FUNDING.yml` stays removed.
- Keep fork-only facts in the fork-specific sections instead of scattering
  them through upstream text, to keep future conflicts small.
- Before committing the merge, run the full completion checks and confirm no
  conflict markers remain (`git diff --check`, `rg '^(<<<<<<<|>>>>>>>)'`).

## Document responsibilities

These are the tracked docs. Each kind of information lives in one doc; other
places link to it.

| Document | Audience and purpose | Update when |
| --- | --- | --- |
| [AGENTS.md](AGENTS.md) | Agent entry point, doc governance and repository-wide rules | Working practices, doc responsibilities or maintenance rules change |
| [README.md](README.md) / [README.zh-CN.md](README.zh-CN.md) | Project introduction, installation and usage for people | Information users need to get started changes |
| [CHANGELOG.md](docs/CHANGELOG.md) / [CHANGELOG_ARCHIVE.md](docs/CHANGELOG_ARCHIVE.md) | Readable version history; the archive keeps older releases | User-visible App behavior changes, or a release |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Current code map, owners and implementation contracts, including errors, state/concurrency, storage, lifecycle and security boundaries | Owners, cross-layer constraints, data/API contracts or lookup entry points change |
| [VALIDATION.md](docs/VALIDATION.md) | Completion checks, the bar for new tests, device data protection and general test pitfalls | Check commands, the test bar or cross-task pitfalls change. No per-feature acceptance checklists, test inventories or measurement logs |
| [ARCHITECTURE_REFACTORING.md](docs/ARCHITECTURE_REFACTORING.md) | Unresolved architecture debt and future direction | A new gap is confirmed, or a goal is completed or narrowed |
| [IOS_UPSTREAM_SYNC_QUEUE.md](docs/IOS_UPSTREAM_SYNC_QUEUE.md) | Current iOS upstream parity queue | An upstream evaluation or slice status changes |
| [TRANSLATING.md](docs/TRANSLATING.md) | How to contribute translations | The translation process changes |

## Document maintenance rules

- Maintain each doc according to its trigger above. Prefer revising in place,
  merging duplicates and deleting stale content.
- Long-lived information must be specific, verified and reusable across sessions.
  Local implementation details and behavior cases live in code and tests;
  one-off investigations, run results and unverified items stay in the task or PR.
- The refactoring and sync queues keep only remaining work and trade-offs that
  affect future decisions; remove completed items.
- When restructuring docs, update navigation and links. Keep product facts in the
  English and Chinese READMEs consistent.

### Changelog style

- Record App changes ordinary users can notice. Each entry is one sentence saying
  what users get or which problem was fixed; combine entries for the same feature.
- Put user-visible changes under `[Unreleased]`. Fold follow-up tweaks to an
  unreleased feature into its original Added/Changed entry; use Fixed only for
  problems users could hit in a released version.
- When implementing or fixing an issue with a user-visible change, reference the
  issue in the entry.

## Workspace, validation and commits

- Work in the current workspace by default; use Git worktrees only when the user
  asks. Small low-risk fixes may go on `main`; larger features, cross-module
  refactors or risky changes use a `codex/` branch.
- Keep plans in the conversation or built-in plan state; create or commit plan
  documents only when the user asks.
- Before declaring completion, run the matching checks and device data protections
  in `docs/VALIDATION.md`, and report results and anything not verified. The agent
  performs device/UI verification with the available tools.
- Tests are not a required output of a task. Add one only when it substantially
  protects behavior that is likely to break, costly when broken, and not already
  covered (see the [test bar](docs/VALIDATION.md#when-to-add-a-test)). Prefer
  extending existing tests; do not add a test for every change, error path or
  UI detail.
- After finishing and passing the necessary checks, commit the task's changes
  unless the user asks not to or to leave them uncommitted. Use Conventional
  Commits, with a closing keyword when implementing or fixing a GitHub issue.
