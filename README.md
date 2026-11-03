# inkPilot

inkPilot is the working name for a separate, entirely local Android
tattoo-studio assistant.

One native Android APK for a tattoo studio — SMS, phone, contacts,
studio albums, and a shared local calendar — with an on-device agent
that drafts replies for staff approval. No required cloud AI or
backend.

**Status: repository setup only.** This repository was created by
copying `TattooStax/inkstax-sms` in full — history, branches, and
tags preserved. The new runtime and redesign described in
[`docs/INKPILOT-PLAN.md`](docs/INKPILOT-PLAN.md) are **not implemented
yet**. Nothing in the plan should be read as an existing feature.

## Provenance

- **Source repository:** https://github.com/TattooStax/inkstax-sms
- **Source commit copied:** `137ad28390e0a543bf47789fdf508b99993ec5df`
  (source `main` at the time of copying, 2026-11-03; verified live
  before the copy, not assumed from the earlier reviewed checkpoint,
  which it matched)
- **Upstream:** Fossify Messages — see [`README-UPSTREAM.md`](README-UPSTREAM.md)
  and [`FORK-NOTICE.md`](FORK-NOTICE.md). The upstream `LICENSE`
  (GPL-3.0) and all copyright notices are preserved unmodified.
- The source repository is unchanged by this copy. Ordinary pushes
  from an inkPilot working copy target `TattooStax/inkPilot` only.

## Inherited vs. planned — do not confuse them

**Inherited today (real, in the code):** the Fossify Messages SMS
application copied from `inkstax-sms` — SMS/MMS messaging and the
InkStax SMS transport work built on it — with its full history,
under GPL-3.0. That is what actually runs if you build this
repository now.

**Planned, not yet implemented:** everything below — the five-tab
studio shape, Studio albums, the local calendar, the side drawer,
and the on-device LFM3 agent. No part of the plan has been built,
and the current screens are the inherited app's screens.

## Direction in one page

- Five bottom tabs: **SMS · Phone · Contacts · Studio · Calendar**
- Soul, agent conversation, memory administration, behavior, activity,
  and model settings live in a contextual side drawer — not as tabs.
- Design: warm ivory, charcoal, restrained oxblood accents; small
  traditional tattoo icons; plain, spacious screens with one main
  list/grid and one primary action.
- Studio: starter albums Finished Work, Outlines, Client References,
  plus artist-created albums; originals preserved locally, album
  membership does not duplicate files.
- Calendar: one authoritative local calendar shared by staff and
  agent.
- Agent: built for the LFM3 family with on-device inference, starting
  with qualifying LFM3-1.7B for customer-service text and tools.
- First functional milestone: incoming SMS → identify/create contact →
  retrieve local memory → generate held draft locally → staff
  approval → carrier send → accurate native conversation history.
- Phone is normal native calling first; AI cellular conversations
  are a later milestone that needs a proven audio-access path.

Full direction, design rules, known gaps, and milestones:
[`docs/INKPILOT-PLAN.md`](docs/INKPILOT-PLAN.md)

## File-size ratchet — from day one

This project starts with a ratchet so GBT (and every other
contributor) begins in a structured way and files cannot quietly
balloon:

- Every tracked text file has a recorded line-count baseline in
  [`.file-size-ratchet.json`](.file-size-ratchet.json).
- A file may **shrink or stay the same freely**. It may not grow past
  its baseline unless the same change also updates the baseline with
  a stated reason — growth is a deliberate, reviewed act, not drift.
- New files are allowed; the check records them and they become part
  of the baseline on their first ratchet pass.
- Hard cap: no source file over **1,000 lines** without an explicit
  exception recorded in the baseline file. Files already over the cap
  in the imported source are listed as legacy exceptions at their
  imported size — they may only shrink from there.
- Check locally: `scripts/check-file-size-ratchet.sh`
- Update the baseline deliberately:
  `scripts/check-file-size-ratchet.sh --update`
  and explain the growth in the commit message.
- CI runs the same check on every push and pull request
  (`.github/workflows/file-size-ratchet.yml`).

See [`docs/FILE-SIZE-RATCHET.md`](docs/FILE-SIZE-RATCHET.md) for the
full rule.

## Working here

1. Read [`docs/INKPILOT-PLAN.md`](docs/INKPILOT-PLAN.md) before
   starting work. It is the direction of record for inkPilot.
2. Stop at the plan's milestone boundaries. The repository-setup
   change deliberately implements **no** new runtime or redesign.
3. Keep the ratchet green. If a change needs a file to grow, say why.
4. Preserve history, `LICENSE`, and copyright notices.
