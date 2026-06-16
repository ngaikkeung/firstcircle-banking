# Documentation

Technical documentation for the First Circle banking service. Diagrams are written in
[Mermaid](https://mermaid.js.org/) and render natively on GitHub/GitLab and most Markdown
viewers.

| Document | What it covers |
|---|---|
| [System overview](system-overview.md) | High-level architecture, layering, ports & adapters, component responsibilities |
| [Entity / data model](entities.md) | The domain objects, their fields, relationships, and invariants (class diagram) |
| [Operation flows](operation-flows.md) | Step-by-step sequence diagrams for create / deposit / withdraw / transfer / balance, plus concurrency and idempotency flows |
| [Ledger design](ledger.md) | Double-entry model, signed entries, per-currency balancing, contra accounts, worked examples |
| [Money movement](money-movement.md) | Where money enters, leaves, and moves; conservation; FX rounding residue |

> The diagrams intentionally mirror the code in `src/main/java/com/firstcircle/banking/`. If the
> code changes, update the matching diagram here.
