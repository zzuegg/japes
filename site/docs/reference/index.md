# Reference

Dense, look-up-oriented pages. If you already know what you're doing and
just need to remember the syntax, start here.

The tutorials teach; the reference indexes. Each page is optimised for
ctrl-F.

## Pages

| Page | What it gives you |
|---|---|
| [System parameters cheat sheet](cheat-sheet.md) | Every annotation and service type you can declare on an `@System` method, with a one-line semantics column and a tier-1 flag. |
| [Tier-1 vs tier-2 dispatch](tier-fallbacks.md) | The exhaustive list of shapes that drop a system from the generated fast path to the reflective fallback, with fixes. |
| [FAQ](faq.md) | Short answers to the questions that come up most often on Discord and GitHub issues. |

## Conventions

- `C` — a user-defined component record (`record Position(...) { }`).
- `E` — a user-defined event record.
- `R` — any resource class (not required to be a record).
- `T` — a relation record type registered via `WorldBuilder.addRelation(T.class)`.

## See also

- [Getting started](../getting-started/index.md) — installation, first
  world, quick-start snippet.
- [Tutorials](../tutorials/index.md) — chapter-length walkthroughs of
  every feature listed in the cheat sheet.
- [Benchmarks](../benchmarks/index.md) — methodology, JMH numbers, and
  the reasoning behind the two-tier dispatch design.
