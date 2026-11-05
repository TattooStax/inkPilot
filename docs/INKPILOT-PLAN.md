# inkPilot — Plan

Direction of record for inkPilot. The build phase is authorized.
The repository preserves `TattooStax/inkstax-sms` history from
`137ad28390e0a543bf47789fdf508b99993ec5df`. Implementation now includes
standalone identity, the reusable core, SQLite business storage,
LFM3 JNI inference, a five-tab native UI and checked staff-approved
SMS sending. The emulator pipeline verifies the integrated local loop;
physical-carrier and hardware acceptance remain separate evidence gates.

**Current beta verdict (2026-11-05): not qualified.** Selected OnePlus preview checks pass, but the same candidate has not passed hosted shell/carrier proof, useful local-model qualification, real recipient SMS/MMS, complete recovery, or the manual Phone call matrix. The [v11 source-specific checkpoint](BETA-EVIDENCE-V11-2026-11-05.md) records newer media and physical evidence separately from [earlier same-day evidence](BETA-EVIDENCE-2026-11-05.md). Its roughly 45% estimate is a subjective engineering rubric, not measured feature completeness or release acceptance.

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

1. **Finish the one-shell ingress matrix.** Isolated cross-package image-share,
   private-copy retention, corrupt/valid image preflight and two-process media
   restart tests now pass on their own source trees. The broader shell still
   has large-text tab and asynchronous calendar/notification timing failures;
   the combined media tree needs an exact-source hosted verdict. Diagnose
   these before full UI acceptance. Repeat cold/warm routes, Back, lock,
   media and all five tabs on the exact OnePlus candidate.
2. **Qualify local replies before enabling them.** The integrated guard
   quarantines older ON state and stops worker/direct RAW drafting while the
   bounded route is unqualified. Its pinned-model offline emulator loop passed
   on an isolated source. Exact production selector QAD matched 0/24 expected
   labels and Q4_K_M matched 5/13 before stopping. A safer QAD prompt returned
   REVIEW for all 24, including 16 ordinary leads. A separately preregistered
   36-case bare-field QAD holdout got 0/18 useful ordinary replies and seven
   generation errors, failing its utility/error thresholds; it is not the
   production selector. Keep drafting disabled until a useful fixed renderer,
   blind staff scoring, source grounding and 20 offline phone turns pass. The
   production tool registry has no agent-loop caller.
3. **Verify durable Studio workflow on one tree.** Reviewed intake, staff
   edits, picker recovery and truthful plaintext export are in the combined
   source. New image-copy and preflight tests pass on isolated media branches;
   the combined source still needs the same-tree shell gate. The v10
   receipt-policy migration makes old RAW drafts historical and unclaimable
   in the local v11 tree; run its exact-source hosted tests before acceptance.
   Finish contact edit/navigation, calendar reminder reconciliation and
   migration/reopen/send/dismissal tests. The ZIP exports Studio rows and
   originals; it has no tested restore path or native SMS/MMS history.
4. **Finish native transport and physical proof.** Focused role-before-claim,
   active-SIM scheduled send and SMS-only direct-reply tests passed on isolated
   hosted branches. Combine them with native Delete/Read verification,
   multipart callback receipts, optional bridge posture and zero new Detekt
   debt. A v8 OnePlus preview submitted one manual SMS, and a separate v10
   preview submitted one image MMS with the intended caption, image part,
   recipient and SIM in a native sent-provider row. Neither recipient receipt
   is confirmed. Pin one identified candidate and record real SMS/MMS
   recipient delivery, role rollback,
   ambiguous-send handling and process-death behavior.
5. **Qualify manual calls independently.** A v8 OnePlus preview placed an
   outgoing call that reached offhook, but the Phone canvas had no reachable
   in-call controls. Fix its call-state refresh/return route, then run the
   incoming/outgoing, locked-screen, denied/revoked alert, SIM and audio-route
   matrix. A sideloaded default Dialer lacks a supported cellular audio stream
   for a local agent; investigate app-owned VoIP/gateway media for the later
   AI voice milestone. See [the voice path decision](PHONE-VOICE-FEASIBILITY-2026-11-05.md).

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
