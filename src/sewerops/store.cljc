(ns sewerops.store
  "SSoT for the ISIC-3700 municipal-sewerage OPERATIONS-COORDINATION
  actor, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every `cloud-itonami-isic-*` actor in this
  fleet uses.

  This actor coordinates the BACK OFFICE of a sewerage utility: system
  (flow-rate/inspection/maintenance) record logging, pipe-inspection/
  pump-maintenance/cleaning scheduling, overflow/contamination/public-
  health safety-concern flagging, and equipment/chemical-treatment
  supply-order coordination. It never touches pump/valve-equipment
  control (direct actuation) or any public-health-authority discharge
  decision -- see `sewerops.governor`'s `scope-exclusion-violations`, a
  HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `facilities` directory keyed by `:facility-id`
  STRING (never a keyword -- the coalops/isic-0510 scaffold's prior
  attempt keyed the seed map with keyword site-ids while every lookup
  used the string `:site-id` off the proposal, so `(get sites site-id)`
  silently missed on every call and masked itself as HARD
  site-unverified holds across 10 assertions; the same discipline is
  applied here from the start -- keyed consistently on the string).

  A registered/verified sewer-system-segment or treatment-facility
  record must exist before ANY proposal for it may ever commit or
  escalate -- `sewerops.governor`'s `facility-unverified-violations`
  re-derives this from the facility's own `:registered?`/`:verified?`
  fields, never from proposal self-report, the SAME 'ground truth, not
  self-report' discipline every sibling actor's own governor uses.

  The ledger stays append-only: which facility a proposal targeted,
  which operation, on what basis, committed/held/escalated and
  approved by whom is always a query over an immutable log.")

(defprotocol Store
  (facility [s facility-id] "Registered sewer-system-segment/treatment
    facility record, or nil.
    Facility map: {:facility-id .. :name .. :registered? bool :verified? bool}.")
  (all-facilities [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-facilities [s facilities] "replace/seed the facility directory (map facility-id->facility)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained facility directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:facilities
   {"sewer-network-1" {:facility-id "sewer-network-1" :name "Riverside Sewer Network"
                        :registered? true :verified? true}
    "sewer-network-2" {:facility-id "sewer-network-2" :name "Eastside Treatment Plant"
                        :registered? true :verified? true}
    "sewer-network-3" {:facility-id "sewer-network-3" :name "Hillside Lift Station (recertification lapsed)"
                        :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (facility [_ facility-id] (get-in @a [:facilities facility-id]))
  (all-facilities [_] (sort-by :facility-id (vals (:facilities @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-facilities [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s))

(defn seed-db
  "A MemStore seeded with the demo facility directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `facilities` map (facility-id
  string -> facility map) -- the primary test/dev entry point.
  `facilities` may be empty (an unregistered-everywhere store)."
  [facilities]
  (->MemStore (atom {:facilities (or facilities {}) :ledger [] :coordination-log []})))
