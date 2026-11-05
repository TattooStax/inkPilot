# inkPilot — Plan

Direction of record for inkPilot. The build phase is authorized.
The repository preserves `TattooStax/inkstax-sms` history from
`137ad28390e0a543bf47789fdf508b99993ec5df`. Implementation now includes
standalone identity, the reusable core, SQLite business storage,
LFM3 JNI inference, a five-tab native UI and checked staff-approved
SMS sending. The emulator pipeline verifies the integrated local loop;
physical-carrier and hardware acceptance remain separate evidence gates.

**Current beta verdict (2026-11-05): not qualified.** Selected OnePlus preview checks pass, but the same candidate has not passed hosted shell/carrier proof, semantic model qualification, real recipient SMS/MMS, complete recovery, or manual Phone-role acceptance. The [dated beta evidence and ordered gates](BETA-EVIDENCE-2026-11-05.md) records what passed and what remains open. The 2026-11-04 audit percentage is historical and does not measure today's completion.

inkPilot is the working name for a separate, entirely local Android
tattoo-studio assistant.

## Product shape

One native Android APK with five bottom tabs:

**SMS · Phone · Contacts · Studio · Calendar**

The five-tab shell owns every day-to-day screen. Opening a message keeps the
thread, SMS/MMS attachments, composer, delivery state, and held agent draft
inside the SMS canvas with the bottom navigation visible. New messages,
settings, notification taps, shortcuts, shares, forwarding, contact details,
and draft review must not reveal an inherited Fossify activity or leave the
inkPilot interface. Each entry route preserves Back navigation, app-lock
behavior, recipient identity, and unsent content. System role/permission
prompts and the Android call surface are explicit operating-system handoffs.
See [`INKPILOT-FULL-CODE-AUDIT-2026-11-04.md`](INKPILOT-FULL-CODE-AUDIT-2026-11-04.md)
for the source findings and ordered repair gates.

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

inkPilot must work without OpenFang. Later, an optional OpenFang
adapter may expose the same checked workflows through a versioned
contract. It must not introduce a second memory authority or bypass
staff approval. See [`ARCHITECTURE.md`](ARCHITECTURE.md).

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
  oversized artwork or illustrations, or slogans.

## Studio

- Starter albums: **Finished Work**, **Outlines**, **Client References**.
- Artists can create and rename additional albums.
- Import camera photos, device images, and MMS attachments.
- Link images to artists, customers, projects, and appointments.
- Preserve originals locally; album membership should not duplicate
  files.
- Removing an image from an album removes the membership only — it
  must not silently delete the original image.

## Calendar

- One authoritative local calendar used by both staff and agent.
- Appointments and tasks assigned to individual artists.
- Working hours, time off, local reminders, and appointment status.
- Conflict checks cover both artist conflicts and shared-room
  conflicts.
- External bookings are not automatically known — bookings made in
  Square or Google are not visible to the local calendar unless
  someone enters them. The local calendar is the truth inside the
  app, and staff should not assume it sees bookings made elsewhere.

## Agent and memory

- Build for the LFM3 family using on-device inference.
- Start by qualifying LFM3-1.7B for customer-service text and tools.
- Qualify vision/audio variants separately.
- Keep structured records and editable, plain-language memory
  locally, with sources, corrections, and review state attached —
  staff can see where a memory came from and whether it has been
  reviewed.
- Reuse useful InkStax checked actions, persistence, and held-draft
  contracts where they fit this shape.
- Avoid introducing competing databases for the same business
  records: one record, one home.
- Staff can review, correct, disable, or forget memories.
- No required cloud AI or backend — and no required hosted AI.

## Phone

- Normal native calling first. The Phone tab is a phone before it
  is anything else.
- AI cellular conversations are a later milestone, and only with a
  proven audio-access path. A speech model alone does not provide
  that access — do not design as if it does.

## First functional milestone

> Incoming SMS → identify/create contact → retrieve local memory →
> generate held draft locally → staff approval → carrier send →
> accurate native conversation history.

That chain, working end to end and verified at the receiving end,
is the first proof. Features outside the chain come after it.

## Evidence-driven repairs before beta

1. **Finish the one-shell ingress matrix.** The focused selected draft, notification route, Settings alias and all five tabs at 1.5× text plus landscape pass on hosted source `0311decd`. The cross-package cold-share case still cannot find a resumed shell with its exact URI; trace sender, alias, app lock and lifecycle, then verify its private body/grant. Repeat the full route matrix on the OnePlus when Victus reconnects.
2. **Ground the local reply.** Production currently makes one RAW generation from the latest inbound text and a reviewed contact note; it does not call the local tool registry or include prior thread turns. The unsupported consultation claim is now blocked with staff attention, but the pinned QAD run produced no useful accepted reply in that test. Keep the bounded selector disabled until its fixed corpus, blind staff scoring, consecutive offline phone turns and a verified source-backed reply path pass.
3. **Persist staff edits with the record.** Held reply edits on the implementation branch still live in preferences outside the Studio archive. An isolated v9 schema and exact-body claim candidate passed six Android data tests, but its new Detekt complexity finding and inherited staged-review failure block integration; a local refactor needs hosted confirmation. Complete the v8 reviewed-intake gate, then rerun the combined v9 edit, migration, reopen, send, dismissal and archive checks. Export remains incomplete until a tested restore path exists.
4. **Finish native transport and device proof.** Verify scheduled edit/alarm ordering, multipart callback reconciliation, optional bridge posture and direct reply lifecycle with zero new static-analysis debt. Then install one identified candidate on the OnePlus and record real SMS/MMS recipient delivery, default-role rollback and process-death behavior.
5. **Qualify manual calls independently.** Run the real incoming/outgoing, locked screen, denied/revoked alert, SIM and audio-route matrix. The source has no call capture, transcription, speech synthesis or AI answer loop; that remains a later milestone after a device-proven audio path.

## Known gaps to verify

These are open questions, not solved problems. Each must be verified
on device before it is claimed to work:

- MMS reaching agent intake
- Agent-sent messages appearing in native threads
- Explicit business-SIM selection
- Process-death (restart) recovery without duplicate sends
- Replacement of the previous split-app transport assumptions —
  the imported transport was designed for a separate SMS app
  talking across a bridge seam; what replaces those assumptions
  inside one APK must be decided and verified, not inherited
  by accident

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

## Build sequence and evidence gates

1. **Foundation (first slice):** separate installation identity,
   pure Kotlin held-draft coordinator and ports, behavioral tests,
   build CI, and ratchet enforcement. Implemented.
2. **One local draft loop:** persist contact, memory and draft state
   in the Android host; connect SMS intake; implement LFM inference;
   prove a held draft survives process death and never auto-sends.
   Implemented with actual SQLite/JNI adapters and a real-model emulator gate.
3. **Checked sending:** staff approval, revision recheck, explicit
   business SIM, native provider rows, carrier acceptance receipts and
   replay protection. Implemented; physical recipient delivery is still
   a manual hardware gate and is never inferred from carrier acceptance.
4. **Studio workflow:** five-tab shell, contact editing, albums,
   artist calendar and simple memory administration, built in small
   independently verified changes. Hours, time off, reminders,
   appointment editing and image record links are implemented in source;
   the complete lead-to-appointment device path remains unqualified.
5. **Optional integrations:** OpenFang adapter after the standalone
   loop passes; voice only after proving an audio-access path.

Do not describe a contract or a passing unit test as device proof.
Keep the source `TattooStax/inkstax-sms` repository unchanged.
See `DEVICE-ACCEPTANCE.md` for the distinction between automated proof
and the remaining real-phone acceptance work.
