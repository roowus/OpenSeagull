# Maintaining the docs — a standing rule

**The docs are part of the change.** A commit that changes behaviour without updating
`docs/ARCHITECTURE.md` (or the README, when the change is user-visible) is an incomplete
commit, in both directions:

- New behaviour that the docs don't describe is undocumented for every future reader.
- The docs describing something the code no longer does are *worse* than no docs: this codebase
  leans on measured facts (numbers, orderings, failure modes) and a stale fact here reads as
  authoritative.

## What triggers an update

| Change | Update |
|---|---|
| New hosted-game capability / new gate / new interception point | `ARCHITECTURE.md` §3 or §8 |
| Session, identity, payload, or write-back semantics | `ARCHITECTURE.md` §§5–7 |
| New manifest wire values, new components, new permissions | `ARCHITECTURE.md` §4 + `README.md` if user-visible |
| Any measured number quoted in docs becomes wrong | fix it everywhere; say what changed |
| A design constraint gets *disproven* by trying it (like the wrapper-subclass attempt) | record why it failed in the relevant section — dead ends are documentation too |
| New source file with architectural weight | add it to the right section's narrative |

## How to write like the rest of this repo

- State the **why**, not just the what; name the failure each decision prevents.
- Measured beats argued. If you measured it, quote the number. If you didn't, don't invent one.
- Keep KDoc as the per-file detail layer; keep ARCHITECTURE.md as the cross-file map. When they
  disagree, fix whichever is wrong — then check the other wasn't relying on it.

## Quick self-check before committing

```
Does my diff change any behaviour described in docs/ARCHITECTURE.md or README.md?
If yes → same commit updates them.
```
