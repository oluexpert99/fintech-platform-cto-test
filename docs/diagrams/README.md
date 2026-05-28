# Architecture diagrams

Source files for the three diagrams referenced from [`../ARCHITECTURE.md`](../ARCHITECTURE.md).

All diagrams are authored in **[Mermaid](https://mermaid.js.org/)** (text-based, version-controlled, renders natively on GitHub). PNG / SVG exports for the final PDF deliverable are generated from these sources.

## Contents

| File | Purpose | Section in ARCHITECTURE.md |
|---|---|---|
| [`system-architecture.mmd`](system-architecture.mmd) | The full platform — clients → gateway → services → data + bus + identity + observability. **What runs in compose vs what is documented-only** is colour-coded. | [§ Part 1](../ARCHITECTURE.md#part-1--overall-architecture) |
| [`transfer-sequence.mmd`](transfer-sequence.mmd) | The hot-path sequence of a `POST /transfer`: JWT validation, idempotency lookup, the single Mongo transaction (debit + credit + 2 journal lines + outbox row), and the **async** outbox publisher loop. Includes the idempotent-replay branch and the failure-handling note. | [§ Part 5](../ARCHITECTURE.md#part-5--transactionservice-implementation-plan), [ADR-0002](../decisions/0002-idempotency-and-exactly-once.md) |
| [`event-flow.mmd`](event-flow.mmd) | Transactional outbox → Kafka → per-group consumers, each with its own inbox-pattern dedupe; retry topic + DLT paths; ops metrics. | [§ Part 6](../ARCHITECTURE.md#part-6--event-driven-architecture), [ADR-0002](../decisions/0002-idempotency-and-exactly-once.md), [ADR-0004](../decisions/0004-event-schema-and-evolution.md) |

## Viewing the diagrams

### On GitHub
Mermaid blocks are rendered natively. View the `.mmd` file's raw contents or paste them into a `.md` fenced as ` ```mermaid `.

### Locally with VS Code
Install the **Markdown Preview Mermaid Support** extension. Open any `.mmd` file or a Markdown file that embeds the source in a `mermaid` code fence.

### In the browser
Paste the source into the [Mermaid Live Editor](https://mermaid.live/). Useful for fast iteration and direct PNG/SVG export.

## Regenerating PNG / SVG exports

The final PDF deliverable needs raster exports. Use the official Mermaid CLI:

```bash
# one-shot, no global install needed
npx -y @mermaid-js/mermaid-cli \
  --input  docs/diagrams/system-architecture.mmd \
  --output docs/diagrams/system-architecture.png \
  --backgroundColor white \
  --width  2400 --height 1600

npx -y @mermaid-js/mermaid-cli \
  --input  docs/diagrams/transfer-sequence.mmd \
  --output docs/diagrams/transfer-sequence.png \
  --backgroundColor white \
  --width  2200 --height 2800

npx -y @mermaid-js/mermaid-cli \
  --input  docs/diagrams/event-flow.mmd \
  --output docs/diagrams/event-flow.png \
  --backgroundColor white \
  --width  2400 --height 1600
```

For SVG (preferred for the PDF — vector, scales cleanly), swap the `--output` extension to `.svg`.

The first invocation downloads a headless Chromium (~150 MB) under `~/.cache/`; subsequent runs are fast.

## Authoring conventions

These conventions keep the diagrams legible across the three views:

- **Colour key** — used consistently across all three diagrams:

| Colour | Meaning |
|---|---|
| 🟩 Green | Application service we **implement** for this submission |
| 🟨 Yellow | Edge / cross-cutting (gateway, outbox publisher) |
| 🟦 Blue | Client / external |
| 🟪 Purple | Event bus (Kafka) / schema registry |
| 🟥 Red | Data store / dead-letter |
| ⬜ Grey | Async consumers we **document only** + observability backplane |

- **Solid arrows** = synchronous request/response or write.
- **Dashed arrows** = asynchronous / side-channel (metrics, logs, config, polling).
- **Subgraphs** group components by tier so the eye can find the boundary in <2 s.

## Why Mermaid (not PlantUML / draw.io)

- **Source-first.** The diagrams are diffable, reviewable, and refactorable like code.
- **Renders on GitHub** out of the box — no rendering pipeline required for the online view.
- **Zero install cost** for the reader; one-line install for the writer.
- **Exports cleanly** to PNG and SVG via the same CLI for the final PDF deliverable.

If a diagram becomes too complex for Mermaid (e.g., the full k8s topology with mesh and policies), we'll switch *that one* to draw.io or Excalidraw and export to PNG — but the three diagrams in this folder stay in Mermaid.
