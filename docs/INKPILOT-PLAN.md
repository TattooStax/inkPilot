# inkPilot — Plan

Direction of record for inkPilot. This document describes what
inkPilot is to become. **None of the new runtime or redesign below
is implemented yet** — the repository was set up by copying
`TattooStax/inkstax-sms` (source commit
`137ad28390e0a543bf47789fdf508b99993ec5df`), and work stops at
repository creation, source import, and this documentation until a
later, separately authorized build phase.

inkPilot is the working name for a separate, entirely local Android
tattoo-studio assistant.

## Product shape

One native Android APK with five bottom tabs:

**SMS · Phone · Contacts · Studio · Calendar**

Move Soul, agent conversation, memory administration, behavior,
activity, and model settings into a contextual side drawer. Those
surfaces are contextual tools, not primary navigation.

### Superseded direction — read this first

The single-APK direction above **supersedes the source project's
split-APK requirement only for inkPilot**. In `inkstax-sms`, the SMS
layer is a separate APK/process that talks to a core only through a
bridge seam (see the historical text preserved in
`FORK-NOTICE.md`). That rule was written for that project and stays
true there. For inkPilot, SMS, Phone, Contacts, Studio, Calendar,
and the agent are one APK.

Historical documentation is preserved, not rewritten. Where an
imported document states the split-APK rule, it describes the source
project's history; this plan is the controlling direction for
inkPilot going forward.

## Design

- Warm ivory, charcoal, restrained oxblood accents.
- Small traditional tattoo icons.
- Plain, spacious screens: one main list/grid and one primary action.
- Details open when tapped.
- No crowded dashboards, decorative portraits, repeated status cards,
  or oversized artwork.

## Studio

- Starter albums: **Finished Work**, **Outlines**, **Client References**.
- Artists can create and rename additional albums.
- Import camera photos, device images, and MMS attachments.
- Link images to artists, clients, and appointments.
- Preserve originals locally; album membership should not duplicate
  files.

## Calendar

- One authoritative local calendar shared by staff and agent.
- Appointments and tasks assigned to individual artists.
- Working hours, time off, conflict checks, local reminders, and
  appointment status.
- External bookings are not automatically known — the local calendar
  is the truth inside the app, and staff should not assume it sees
  bookings made elsewhere.

## Agent and memory

- Build for the LFM3 family using on-device inference.
- Start by qualifying LFM3-1.7B for customer-service text and tools.
- Qualify vision/audio variants separately.
- Keep structured records and editable, plain-language memory locally.
- Reuse useful InkStax checked actions, draft handling, and
  persistence where they fit this shape.
- Staff can review, correct, disable, or forget memories.
- No required cloud AI or backend.

## First functional milestone

> Incoming SMS → contact lookup/create → local memory retrieval →
> local reply draft → staff approval → carrier send → accurate
> conversation history.

That chain, working end to end and verified at the receiving end,
is the first proof. Features outside the chain come after it.

## Known gaps to verify

These are open questions, not solved problems. Each must be verified
on device before it is claimed to work:

- MMS agent intake
- Agent-sent messages appearing in native threads
- Explicit SIM selection
- Restart recovery without duplicate sends

## File-size ratchet

inkPilot starts with a file-size ratchet (see
`docs/FILE-SIZE-RATCHET.md` and the repository `README.md`):

- A baseline line count is recorded for every tracked text file in
  `.file-size-ratchet.json` at repository setup.
- Files may shrink freely; growth past baseline is a deliberate,
  reviewed act that updates the baseline with a reason.
- No source file over 1,000 lines without a recorded exception;
  imported files already over the cap are legacy exceptions that
  may only shrink.

Work in small, structured steps, in files that stay small. Split a
file before adding to one that is near its baseline or the cap.

## Boundaries for the setup phase

- Repository creation, source import, and planning documentation
  only.
- Stop before implementing the new runtime or redesign.
- Do not describe planned features as already implemented.
- Leave `TattooStax/inkstax-sms` unchanged.
