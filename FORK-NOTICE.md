# Fork notice — InkStax SMS layer

This repository is **InkStax's fork of Fossify Messages**, published
under the GNU General Public License v3.0, exactly as upstream
requires. The upstream `LICENSE` file and all copyright notices are
preserved unmodified.

- **Upstream:** https://github.com/FossifyOrg/Messages
- **Pinned base:** release **1.10.2**, commit
  `194fc3a117dca658389e0719ae83cdeb8145b542`
  (`chore(release): v1.10.2 (26)`), tagged here as
  `fork-base-1.10.2`. Every commit at or below that tag is
  unmodified upstream history.
- **Why the fork exists:** the InkStax SMS layer. The fork stays a
  **separate APK/process** and talks to the InkStax core only
  through a bridge seam (one seam, three transport bindings:
  local / X52 / cloud-as-design-intent); it is never linked into
  the core or the shell. All changes made for InkStax are
  committed and published in this repository.
- **Discipline:** bridge code lives in its own module; upstream
  touchpoints are kept to ≤5 and tagged; no tenant IDs, endpoint
  credentials, or `google-services.json` are ever committed —
  channel credentials are build-injected at packaging time.
- **Track of record:** InkStax SMS track (`~/workspace/sms-dev/`
  in the owner's workspace), home thread "Keep SMS discussion in
  this thread". Design of record: director rounds 3–4 for the
  SMS layer (2026-10-28) — Fossify fork over the earlier
  capcom6-profile plan, posture by device class, GMS-free local
  build invariant, dual ack + heartbeat failure honesty, email
  as mirror/alert only (never a transport).

---

## inkPilot provenance (added 2026-11-03)

This repository is **inkPilot**, an independent copy of
`TattooStax/inkstax-sms`, created 2026-11-03 with its Git history,
branches, tags, `LICENSE`, and copyright notices preserved.

- **Copied from:** https://github.com/TattooStax/inkstax-sms
- **Source commit copied:**
  `137ad28390e0a543bf47789fdf508b99993ec5df` (source `main`,
  verified live at copy time, 2026-11-03)
- **This repository:** https://github.com/TattooStax/inkPilot
- The source repository is unchanged. Ordinary pushes from an
  inkPilot working copy target inkPilot only. GitHub's fork
  mechanism cannot fork a repository into its own owner, so this
  is an independent repository copy, not a GitHub fork object.
- The Fossify upstream notice above remains accurate for the
  imported history: upstream is Fossify Messages, pinned base
  release 1.10.2 at `194fc3a117dca658389e0719ae83cdeb8145b542`,
  tagged `fork-base-1.10.2`, under GPL-3.0. The original upstream
  README is preserved as `README-UPSTREAM.md`.

**Direction change, inkPilot only:** inkPilot is a single native
Android APK (SMS · Phone · Contacts · Studio · Calendar, with the
agent in a side drawer) — see `docs/INKPILOT-PLAN.md`. That
single-APK direction supersedes, **for inkPilot only**, the source
project's rule (stated in the historical notice above) that the SMS
layer stays a separate APK/process talking to a core through a
bridge seam. The historical notice is preserved unedited above as
the record of the source project; it does not govern inkPilot.
