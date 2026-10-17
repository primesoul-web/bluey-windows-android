# Backlog — deliberately out of parity scope

The parity contract ([PARITY.md](PARITY.md)) targets the original's **main branch**. These ideas come from unmerged upstream PRs and open issues; none of them are part of parity, and each needs a deliberate decision before it is ever implemented.

## From upstream PR #5 — recover orphaned meeting-notes chunks

- Scan the client's audio folder on reconnect for `.m4a` chunks the host never acknowledged; re-upload them, using the file's creation time as the chunk start.
- Detect chunks that died mid-write (missing AAC footer) and resolve them (mark failed / quarantine) instead of retrying forever.
- Why it's interesting: pure robustness for all-day sessions — exactly our acceptance test 6 territory.
- Risk noted upstream: must not upload the chunk that is still being written.

## From upstream PR #7 — encryption + pairing (security hardening)

- TLS-PSK end-to-end encryption on the phone↔PC link, replacing plaintext TCP.
- Six-digit pairing codes with a temporary `_googlypair._tcp` service and key rotation.
- Also in that PR: background sessions that survive phone locking, an optional "Hey Bluey" wake phrase, a timestamped action audit log on the PC (with content redaction), automatic meeting summaries (`summary.md`), multi-display support, rebindable shortcuts, a "Check for Updates" mechanism, and session naming.
- Why it's out of scope: it changes the wire protocol and the trust model; upstream rated the PR high-risk and left it unmerged. If adopted later, it must sit behind a compatibility flag so the plain protocol keeps working.

## From upstream issue #2 — "No window :("

- First-run confusion: the app lives in the tray/menu bar and shows no window. A one-time hint ("Bluey is in your tray") would help.
- Cheap to add during M6 polish without violating parity (the original has no such hint).

## From upstream issue #6 — "make Android and Windows versions"

- That's this repository.
