# cloud-itonami-isic-3700

Open Business Blueprint for **ISIC Rev.4 3700**: Sewerage — an ISIC
Wave 3 (utilities) operations-coordination actor per ADR-2607121000.
Back-office and coordination workflow for municipal sewerage/
wastewater-collection-system operations, modeled closely on
`cloud-itonami-isic-0510`'s (Mining of hard coal) governed-actor
discipline.

**Maturity: `:implemented`** — SewerOpsAdvisor ⊣ SewerageOpsGovernor as
a langgraph-clj StateGraph (`intake → advise → govern → decide →
commit/hold`, human-approval interrupt). All source `.cljc` (portable
to JVM / ClojureScript / GraalVM), no JVM-only interop.

## CRITICAL: Scope Exclusions

This actor **DOES NOT** and **NEVER WILL**:

- **Direct pump/valve-equipment control** — actuating, opening/closing, or otherwise directly operating pump or valve equipment
- **Public-health-authority discharge decisions** — discharge-permit issuance, boil-water-notice issuance, or effluent-discharge authorization

This actor **only** coordinates back-office operations: system-record
logging (flow-rate/inspection/maintenance data), maintenance
scheduling (pipe-inspection/pump-maintenance/cleaning), safety-concern
flagging (overflow/contamination/public-health, always routed to a
human), and equipment/chemical-treatment supply-order coordination.
Every proposal the advisor drafts carries `:effect :propose` — never a
direct actuation — and `sewerops.governor` independently re-scans
every proposal's content for the excluded scope areas above,
regardless of op or confidence.

## Operations

Closed proposal-op allowlist (`sewerops.governor/allowed-ops`), all
`:effect :propose`:

- `:log-system-record` — flow-rate/inspection/maintenance data logging
- `:schedule-maintenance` — pipe-inspection/pump-maintenance/cleaning scheduling proposal
- `:flag-safety-concern` — surface an overflow/contamination/public-health concern — **ALWAYS escalates**
- `:order-supplies` — equipment/chemical-treatment procurement proposal — **escalates above a cost threshold**

**HARD invariants** (always `:hold`, never human-overridable):

1. **Facility unverified** — the target sewer-system-segment/
   treatment-facility record must exist AND be independently
   confirmed `:registered?`/`:verified?` in the store before any
   proposal for it may commit or even escalate. Never trusts a
   proposal's own claim about the facility — re-derived from the
   facility's own store record, the same "ground truth, not
   self-report" discipline every sibling actor's governor uses.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/value
   touches pump/valve-equipment-control or public-health-authority
   discharge-decision territory, is a permanent, un-overridable block.
   Evaluated unconditionally on every proposal.

**ESCALATE** (always human sign-off, when the governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- `:order-supplies` above `sewerops.governor/supply-cost-threshold`
  (currently 5000, currency-unit-agnostic) — always, regardless of
  rollout phase or confidence.
- Low advisor confidence (`< 0.6`).

## Rollout phases (`sewerops.phase`)

Phase 0 (read-only) → 1 (system-record logging, approval-gated) → 2
(adds maintenance scheduling + supply ordering, approval-gated) → 3
(supervised auto: system-record/maintenance/supply-order may
auto-commit when governor-clean and confident). `:flag-safety-concern`
is deliberately absent from every phase's `:auto` set — a permanent
structural fact, not a rollout milestone still to come — matching
`sewerops.governor`'s own `always-escalate-ops` independently. A
high-value `:order-supplies` proposal still escalates even at phase 3,
because the governor's own verdict already marks it `:escalate?`
before the phase gate runs — the phase gate can only add caution,
never remove an escalation.

## Development

```bash
clojure -M:test   # run the full suite
clojure -M:run    # walk the demo scenarios (sewerops.sim)
clojure -M:lint    # clj-kondo
```

AGPL-3.0-or-later, forkable by any qualified operator. Part of cloud-itonami.
