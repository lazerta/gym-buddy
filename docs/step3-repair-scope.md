# Step 3 audit repairs — scope and verification

Baseline: e17f5f9c5a711d6980b4a9f8917c6a4dad3c9c3a. Canonical Drive documents 00–06 and frozen 08 remain authoritative. This change does not revise that authority or declare physical release acceptance.

## Repairs

- F01: Selection cannot capture an equipment context until its save is acknowledged. Failed saves retain the editor and previous committed context.
- F02: Checkpoint writes return success/failure. Pending edits remain drafts; stale callbacks cannot overwrite newer status. Failed drafts are explicitly retryable and block Next/Finish until saved.
- F03: One equipment editor is rendered independently of day, search, Favorite or Recent section.
- F04: The single Rest focus no longer truncates execution summary data. All supported recurring observations and completed-delivery response associations are retained and reconstructed from Room.
- C01: Resistance kind and measurement mode are separate from basis/source. Room 8→9 leaves historical metadata UNKNOWN. A load carried from a plan is marked CARRIED_FROM_PLAN, not claimed as independently observed USER_ENTERED actual performance. Export schema 2 carries these fields and interpretation limits.
- C02: Android elapsedRealtime anchors measure Rest duration, including suspend. A persisted boot identifier validates the anchor after a process restart. Reboot/legacy/unknown boot uses a single wall-time reconstruction labelled estimated, then monotonic ticks.
- Returned GPT interpretations have an optional inline Summary save path, explicitly attached to this execution's completed sets. No network client, new mandatory screen or model identity inference.

## Verification boundaries

Controller/domain tests exercise callback order and conservative summary policy. Real Room tests exercise injected SQL failures and genuine historical migrations. Native runtime tests exercise acknowledged failure propagation. Compose instrumentation renders actual production controls. MainActivity is actually recreated with ActivityScenario. The process-recovery shell gate force-stops the OS process without graceful runtime disposal, checks changed PIDs, then verifies REST and committed active-set evidence recovery. These are distinct from Step 4's graceful close/reopen scenario.

Controlled pose fixtures enter at the estimator-output boundary. The existing black-image MediaPipe transport smoke proves transport/native invocation only. Neither is real-human-video accuracy evidence. Runtime resource tests and all existing harness component gates remain blocking; the external harness reference model is not substituted for Kotlin.

## Capabilities that must stay explicit

The current three profiles retain validated-in-code angular, ROM-proxy, bilateral and phase/invalid-attempt evidence. Live assistance is NOT_ASSESSED. Body-local wrist-path and torso-motion form interpretations are not supplied by this patch: absent metrics remain absent/UNKNOWN, never normal or zero. Implementing or claiming those broader canonical profile capabilities still requires observation/geometry tests and independent real-video validation; the fields must not be invented merely to make a checklist green. This is an explicit capability limitation, not a canonical scope amendment or a claim that every first-release acceptance item is closed.

Completed audio delivery is represented by its actual terminal delivery state. Existing cue response windows use emitted cue time; summaries describe associations and do not infer that speech completion caused an improvement. A future delivery-timing efficacy claim needs its own timestamped contract and validation.

## Non-goals

No MVVM/ViewModel, exercise expansion, new tracking thresholds, calibration-threshold relaxation, mandatory cloud service, API key, or raw-personal-media commit. Real human video and personal phone/tripod/headphone/gym acceptance remain separately NOT_RUN until actually measured.
