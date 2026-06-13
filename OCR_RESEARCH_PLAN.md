# ValVoice – OCR Research, Measurement & Future Architecture Plan

**Status:** Research Phase
**Date:** June 2026
**Prerequisite:** ValVoice Core Infrastructure v1.0 Stable

---

## Purpose
This document defines the complete OCR investigation roadmap for ValVoice after infrastructure stabilization. The purpose is to measure actual behavior, improve source quality before adding complexity, study VN OCR architecture, and adopt only proven, Vanguard-safe techniques. This phase is entirely evidence-driven.

---

## Current System State
**Infrastructure Status:** `ValVoice Core Infrastructure v1.0` (STABLE)
**Code Freeze Active On:** `OcrChatClient.java`, `Program.cs`, `CaptureManager.cs`, `WindowDetector.cs`, `RiotLocalApiPoller.java`, `ValVoiceBackend.java`, and all restart/recovery mechanisms.

---

## Guiding Principles
`Measure -> Analyze -> Improve Source Pipeline -> Measure Again -> Investigate Advanced Techniques -> Implement Only Proven Improvements`
*Never: Guess -> Implement Complexity -> Hope It Helps*

---

## Phase 1 – OCR Measurement & Evidence Collection
**Objective:** Determine how often OCR actually fails. Do not assume problems exist.
**Metrics To Collect:**
1. **Sender Mutation Frequency:** Total reads, unique mutations, mutation percentage.
2. **Self Message Detection:** Total self messages, correct detections, false positives/negatives.
3. **Duplicate Narration Frequency:** Single emissions vs multiple, time between duplicates.
4. **OCR Stability:** Recognition accuracy %, mutation rate %, miss rate %.
5. **Runtime Performance:** CPU, memory, frame timings, OCR latency, GC activity.
**Deliverable:** `ocr_measurement_report.md`

---

## Phase 2 – Source-Level OCR Improvements
**Philosophy:** Improve OCR quality at the source.
**Proposed Pipeline:** `Crop -> Scale x2 -> Grayscale -> Sharpen -> OCR`
**Measurement Procedure:** Implement one change at a time and measure.
**Deliverable:** `ocr_pipeline_benchmarks.md`

---

## Phase 3 – Adaptive Thresholding Investigation (Only if Phase 2 is insufficient)
**Reason:** Chat backgrounds are translucent and dynamically colored.
**Investigation:** Does it improve accuracy? Increase CPU cost? Reduce mutations?
**Deliverable:** `adaptive_threshold_research.md`

---

## Phase 4 – Multi-Frame OCR Consensus Research
**Objective:** Reduce transient OCR errors by clustering samples and choosing a consensus.
**Proposed Params:** Min samples: 3, Consensus threshold: 60%, Hard timeout: 800ms.
*Note: Consensus and duplicate suppression must share the same lifecycle.*
**Deliverable:** `ocr_temporal_consensus_research.md`

---

## Phase 5 – OCR Line Lifecycle Research
**Lifecycle:** `PENDING -> STABLE -> FINALIZED -> EMITTED`
**Deliverable:** `ocr_line_lifecycle_research.md`

---

## Phase 6 – Duplicate Suppression Research
**Investigate:** Position-aware buffering, line lifecycle integration, consensus-before-emission, replay prevention.
**Deliverable:** `duplicate_suppression_research.md`

---

## Phase 7 – Fuzzy Matching Investigation (Last Resort)
**Preconditions:** Measurements prove significant mutations AND source/consensus improvements fail.
**Techniques:** Levenshtein Distance, Jaro-Winkler Similarity.
**Safety Requirement:** `DistanceToSelf < DistanceToAnyKnownPlayer`
**Deliverable:** `fuzzy_matching_feasibility.md`

---

## Phase 8 – Valorant Narrator (VN) OCR Research
**Objective:** Complete source-backed audit of VN OCR (Capture system, Preprocessing, OCR Engine, Duplicate Prevention, Ownership Detection, Performance).
**Deliverable:** `vn_ocr_architecture_audit.md`

---

## Priority Order
1. **Priority 1:** Long-session infrastructure tests.
2. **Priority 2:** OCR measurements.
3. **Priority 3:** Source-level preprocessing.
4. **Priority 4:** Adaptive thresholding investigation.
5. **Priority 5:** Multi-frame OCR consensus research.
6. **Priority 6:** Duplicate suppression research.
7. **Priority 7:** VN OCR architecture audit.
8. **Priority 8:** Fuzzy matching feasibility.

**Primary Objective:** Understand the OCR system completely before modifying it. All future OCR work must remain evidence-driven, incremental, measurable, Vanguard-safe, and source-backed.
