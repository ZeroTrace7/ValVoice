# ValVoice – Pending OCR Work & Future Improvements

**Status:** Deferred
**Date:** June 2026

---

## Purpose
This document records all remaining manual tests and future OCR improvements that were intentionally deferred after completing the ValVoice Core Infrastructure v1.0 hardening phase. The infrastructure layer is considered stable and code-frozen. No changes should be made to infrastructure components until the remaining manual tests are executed and verified.

---

## Infrastructure Status

**Completed and Verified:**
- VN startup parity
- RiotLocalApiPoller → OCR startup order
- Supplier-based identity resolution
- game_name identity fix
- Self-message ownership detection
- Null identity safety
- OCR restart backoff
- Uptime-based crash counter reset
- Exact restart semantics
- Automatic window recovery
- Build verification

**Current Status:**
`ValVoice Core Infrastructure v1.0`
`STATUS: STABLE`
`CODE FREEZE: ACTIVE`

**Do not modify:**
- `OcrChatClient.java`
- `Program.cs`
- `CaptureManager.cs`
- `RiotLocalApiPoller.java`
- `ValVoiceBackend.java`
- `WindowDetector.cs`
- Restart and recovery mechanisms
*until all remaining manual tests have been completed.*

---

## Remaining Manual Tests

### Test 1 – Long Gameplay Stability Test
**Purpose:** Prove that the infrastructure remains stable during real-world gaming sessions.
**Procedure:**
1. Launch ValVoice
2. Launch Valorant
3. Play normally for 1-2 hours
4. Send messages periodically: `SELF1`, `SELF2`, `SELF3`
5. Open and close chat repeatedly
6. Alt-tab frequently
7. Leave the game idle for several minutes
8. Continue normal gameplay
**Expected:** Crash Count: 0/5, Restarts: 0, DEGRADED: Never

### Test 2 – Window Recovery Test
**Procedure:**
1. Launch ValVoice
2. Launch Valorant, Enter game
3. Close Valorant, Wait, Reopen Valorant
**Expected Log Sequence:** `window_lost` -> `window_searching` -> `window_found` -> `capture resumed`

### Test 3 – Extended Idle Test
**Purpose:** Verify background stability.
**Procedure:** Launch ValVoice, Launch Valorant, Leave system idle for 20-30 minutes
**Verify:** No memory spikes, CPU spikes, or hidden restart loops.

---

## OCR Improvement Roadmap (Deferred)
*Important: Do not implement any of these improvements until infrastructure testing has been completed.*

### Phase 1 – Measurement First
Before changing OCR, collect logs and measure:
- OCR mutations
- Sender mutations
- Duplicate emissions
- OCR confidence behavior
- Frequency of incorrect reads
Determine whether OCR problems are frequent enough to justify architectural changes. No fuzzy logic should be implemented before measurements are collected.

### Phase 2 – Improve OCR Source Pipeline
**Goal:** Improve OCR quality at the image source instead of compensating in Java.
**Preferred pipeline:** `Crop` -> `Scale x2` -> `Grayscale` -> `Sharpen` -> `OCR`
**Potential improvements:**
1. Scale chat crop by 2x
2. Convert to grayscale
3. Apply sharpening
4. Measure results
5. Introduce adaptive thresholding only if required

### Phase 3 – Adaptive Thresholding (Only If Needed)
If scaling and sharpening are insufficient, investigate Adaptive Thresholding, as Valorant chat backgrounds are translucent and dynamically colored.

### Phase 4 – Multi-Frame OCR Voting
**Purpose:** Reduce transient OCR errors.
**Proposed approach:** Chat change detected -> Wait until animation settles -> Capture multiple OCR samples -> Cluster similar results -> Select consensus result -> Emit final message.
**Guidelines:** Rolling sample buffer, Min 3 samples, Consensus ~60%, Hard timeout ~800ms.

### Phase 5 – OCR Line State Machine
**Lifecycle:** `PENDING` -> `STABLE` -> `FINALIZED` -> `EMITTED`
**Benefits:** Eliminates duplicate narration, reduces transient errors, supports multi-frame consensus.

### Phase 6 – Duplicate Suppression Improvements
**Potential future improvements:** Position-aware buffering, Consensus before emission, Rolling line state management.

### Phase 7 – Fuzzy Ownership Matching (Last Resort)
Only consider fuzzy matching if measurements prove frequent OCR mutations, source-level improvements fail, and multi-frame voting leaves errors.
**Possible approach:** Levenshtein Distance or Jaro-Winkler Similarity.

### Phase 8 – Investigate Valorant Narrator OCR
**Future Research Task:** Study VN OCR implementation in detail to evaluate replacing or borrowing parts of the current OCR pipeline.

---

## Current Priority Order
**Priority 1:** Complete remaining manual infrastructure tests.
**Priority 2:** Collect OCR measurements.
**Priority 3:** Improve image preprocessing.
**Priority 4:** Investigate adaptive thresholding.
**Priority 5:** Investigate multi-frame voting.
**Priority 6:** Study VN OCR implementation.
**Priority 7:** Consider fuzzy matching only if all previous stages fail.

**Next Phase:** OCR Research and Incremental Quality Improvements
