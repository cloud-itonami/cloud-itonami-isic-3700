(ns sewerops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave3 utilities rollout): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`sewerops.operation` -> `sewerops.governor` -> `sewerops.store`)
  through a scenario adapted from this repo's own `sewerops.sim` demo
  driver (`clojure -M:dev:run`, confirmed by actually running it before
  this file was written -- facility ids match `sewerops.store/demo-data`
  exactly, and every disposition it produces (commit / escalate+approve /
  HARD hold, and the exact `:rule` on each hold) matches
  `sewerops.governor`'s own documented checks precisely, so it was safe
  to reuse rather than author from scratch), trimmed to a representative
  subset (three clean phase-3 auto-commits, one high-value supply order
  that always escalates for budget sign-off and is approved by a human
  shift supervisor, one always-escalate safety-concern lifecycle
  approved by a human, and three distinct HARD-hold reasons that never
  reach a human) and rendered deterministically -- no invented numbers,
  no timestamps in the page content, byte-identical across reruns
  against the same seed.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [sewerops.store :as store]
            [sewerops.advisor :as advisor]
            [sewerops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :shift-supervisor :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real facility ids from
  `sewerops.store/demo-data` and real op keywords from
  `sewerops.governor/allowed-ops`:

  sewer-network-1 (registered AND verified) walks the clean phase-3
  auto-commit path: `:log-system-record` (flow-rate logging),
  `:schedule-maintenance` (lift-pump maintenance window) and a
  low-value `:order-supplies` (chlorine tablets under the cost
  threshold) are all governor-clean, high-confidence, and members of
  phase 3's `:auto` set -- they auto-commit with no human in the loop.
  sewer-network-1 also places a HIGH-VALUE `:order-supplies`
  (replacement pump unit, estimated-cost 18000 > supply-cost-threshold
  5000) -- this ALWAYS escalates for human budget sign-off regardless
  of phase, and is approved by a human shift supervisor. sewer-network-1
  also flags a `:flag-safety-concern` (manhole overflow) -- this op is
  ALWAYS in `governor/always-escalate-ops` and is deliberately absent
  from every phase's `:auto` set (`sewerops.phase`), so it escalates
  regardless of phase or confidence and is approved by a human shift
  supervisor.

  Then three DISTINCT HARD-hold reasons, none of which ever reach a
  human (a human approver cannot override a HARD violation):
    - sewer-network-3 (seeded `:registered? true :verified? false` --
      Hillside Lift Station, recertification lapsed):
      `:log-system-record` HARD-holds on `:facility-unverified` -- the
      governor independently re-derives registration/verification from
      the facility's own store record, never from the proposal's
      self-reported facility-id.
    - sewer-network-1, advisor attempts direct actuation (`:effect
      :commit` instead of `:propose`): `:order-supplies` HARD-holds on
      `:effect-not-propose` -- every proposal's `:effect` MUST be
      `:propose`; any other value is, by construction, a claim to
      directly actuate/commit outside governance.
    - sewer-network-1, advisor drifts into the permanently-excluded
      pump/valve-equipment-control / public-health-authority-discharge
      scope (`:out-of-scope?` flag on the request, the same failure-mode
      hook `sewerops.sim` exercises): `:schedule-maintenance` HARD-holds
      on `:scope-excluded` -- this actor's charter structurally excludes
      that territory, evaluated unconditionally on every proposal
      regardless of op or confidence.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; sewer-network-1: clean flow-rate logging -- phase-3 auto-commit.
    (exec! actor "f1-log" {:op :log-system-record :facility-id "sewer-network-1"
                            :patch {:flow-rate-mgd 4.3 :shift "night"}})

    ;; sewer-network-1: clean lift-pump maintenance scheduling -- phase-3
    ;; auto-commit.
    (exec! actor "f1-maint" {:op :schedule-maintenance :facility-id "sewer-network-1"
                              :patch {:equipment "lift-pump-3" :window "2026-07-20"}})

    ;; sewer-network-1: clean low-value chemical-treatment supply order
    ;; -- phase-3 auto-commit (under supply-cost-threshold).
    (exec! actor "f1-sup-low" {:op :order-supplies :facility-id "sewer-network-1"
                                :patch {:item "chlorine-tablets" :estimated-cost 1200}})

    ;; sewer-network-1: HIGH-VALUE supply order -- ALWAYS escalates for
    ;; human budget sign-off, even at phase 3.
    (exec! actor "f1-sup-hi" {:op :order-supplies :facility-id "sewer-network-1"
                               :patch {:item "replacement-pump-unit" :estimated-cost 18000}})
    (approve! actor "f1-sup-hi")

    ;; sewer-network-1: overflow safety-concern flag -- ALWAYS escalates,
    ;; approved by a human shift supervisor.
    (exec! actor "f1-safety" {:op :flag-safety-concern :facility-id "sewer-network-1"
                               :patch {:concern "manhole overflow observed" :confidence 0.95}})
    (approve! actor "f1-safety")

    ;; sewer-network-3: registered but NOT verified (recertification
    ;; lapsed) -> HARD hold on :facility-unverified, never reaches a
    ;; human.
    (exec! actor "f3-log" {:op :log-system-record :facility-id "sewer-network-3"
                            :patch {:flow-rate-mgd 0.1}})

    ;; sewer-network-1: advisor attempts direct actuation (:effect
    ;; :commit instead of :propose) -> HARD hold on
    ;; :effect-not-propose, never reaches a human.
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                 (-advise [_ _ req]
                                                   (assoc (advisor/infer db req) :effect :commit)))})]
      (exec! actor-direct "f1-effect" {:op :order-supplies :facility-id "sewer-network-1"
                                        :patch {:item "chlorine-tablets"}}))

    ;; sewer-network-1: advisor drifts into pump/valve-control scope ->
    ;; HARD hold on :scope-excluded, permanent, never reaches a human.
    (exec! actor "f1-scope" {:op :schedule-maintenance :facility-id "sewer-network-1"
                              :out-of-scope? true :patch {}})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger facility-id]
  (last (filter #(= (:facility-id %) facility-id) ledger)))

(defn- status-cell [ledger facility-id]
  (let [f (last-fact-for ledger facility-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- facility-row [ledger {:keys [facility-id name registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc facility-id) (esc name)
          (if registered? "<span class=\"ok\">registered</span>" "<span class=\"critical\">unregistered</span>")
          (if verified? "<span class=\"ok\">verified</span>" "<span class=\"critical\">unverified</span>")
          (status-cell ledger facility-id)))

(defn- ledger-row [{:keys [t op facility-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc facility-id)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(defn- coordination-row [{:keys [op facility-id value payload]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name op)) (esc facility-id)
          (esc (pr-str (or payload value {})))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`sewerops.governor`/`sewerops.phase`) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-system-record</code></td><td><span class=\"ok\">phase-3 auto-commit when clean -- flow-rate/inspection/maintenance data logging</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &middot; independently re-scanned for pump/valve-equipment-control scope drift on every proposal</span></td></tr>"
   "        <tr><td><code>:order-supplies</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &amp; under cost threshold &middot; ALWAYS escalates for human budget sign-off above <code>supply-cost-threshold</code> (5000) regardless of phase &middot; :effect independently re-checked as :propose on every proposal</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never a member of any phase's :auto set -- an overflow/contamination/public-health concern always needs a human to look at it</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        facilities (store/all-facilities db)
        facility-rows (str/join "\n" (map (partial facility-row ledger) facilities))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        coordination-rows (str/join "\n" (map coordination-row (store/coordination-log db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-3700 &middot; sewerage</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Sewerage (ISIC 3700) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · pump/valve control and public-health discharge decisions always out of scope</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Facilities</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>sewerops.store</code> via <code>sewerops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Facility</th><th>Name</th><th>Registration</th><th>Verification</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     facility-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Committed coordination records</h2>\n"
     "    <p class=\"muted\">System-record logging, maintenance scheduling, supply-order and safety-concern proposals this actor actually committed to the SSoT this run.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Facility</th><th>Payload</th></tr></thead>\n"
     "      <tbody>\n"
     coordination-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (SewerageOpsGovernor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Facility registration/verification, proposal :effect and pump/valve-equipment-control &amp; public-health-authority-discharge scope exclusions are independently re-checked on every proposal, never trusted from the advisor's own claim; a safety concern always needs a human shift supervisor to look at it, at every rollout phase; a high-value supply order always escalates for budget sign-off regardless of phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Facility</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "committed coordination records )")))
