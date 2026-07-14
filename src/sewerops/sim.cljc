(ns sewerops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean system-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs
  the same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a maintenance-scheduling request and a low-value
  supply-ordering request (also auto-commit clean at phase 3), then a
  HIGH-VALUE supply order (always escalates for human budget sign-off,
  even at phase 3), then a safety-concern flag (ALWAYS escalates, at
  any phase -- approve, then commit), then HARD-hold scenarios: an
  unregistered facility, a facility registered but not yet verified, a
  proposal whose own `:effect` is not `:propose`, and a proposal that
  has drifted into the permanently-excluded pump/valve-control scope."
  (:require [langgraph.graph :as g]
            [sewerops.advisor :as advisor]
            [sewerops.store :as store]
            [sewerops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "sewerage-shift-supervisor-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        operator-phase-1 {:actor-id "op-1" :actor-role :shift-supervisor :phase 1}
        operator-phase-3 {:actor-id "op-1" :actor-role :shift-supervisor :phase 3}
        actor (op/build db)]

    (println "== log-system-record sewer-network-1 (phase 1, escalates -- human approves) ==")
    (println (exec-op actor "t1" {:op :log-system-record :facility-id "sewer-network-1"
                                  :patch {:flow-rate-mgd 4.2 :shift "day"}} operator-phase-1))
    (println (approve! actor "t1"))

    (println "== log-system-record sewer-network-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-system-record :facility-id "sewer-network-1"
                                  :patch {:flow-rate-mgd 4.3 :shift "night"}} operator-phase-3))

    (println "== schedule-maintenance sewer-network-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-maintenance :facility-id "sewer-network-1"
                                  :patch {:equipment "lift-pump-3" :window "2026-07-20"}} operator-phase-3))

    (println "== order-supplies sewer-network-1, low-value (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :order-supplies :facility-id "sewer-network-1"
                                  :patch {:item "chlorine-tablets" :estimated-cost 1200}} operator-phase-3))

    (println "== order-supplies sewer-network-1, HIGH-VALUE (always escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :order-supplies :facility-id "sewer-network-1"
                                 :patch {:item "replacement-pump-unit" :estimated-cost 18000}} operator-phase-3)]
      (println r)
      (println "-- human budget sign-off --")
      (println (approve! actor "t5")))

    (println "== flag-safety-concern sewer-network-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-safety-concern :facility-id "sewer-network-1"
                                 :patch {:concern "manhole overflow observed" :confidence 0.95}} operator-phase-3)]
      (println r)
      (println "-- human shift supervisor reviews & approves --")
      (println (approve! actor "t6")))

    (println "== log-system-record sewer-network-9 (unregistered facility -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-system-record :facility-id "sewer-network-9"
                                  :patch {:flow-rate-mgd 0.1}} operator-phase-3))

    (println "== log-system-record sewer-network-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :log-system-record :facility-id "sewer-network-3"
                                  :patch {:flow-rate-mgd 0.1}} operator-phase-3))

    (println "== order-supplies sewer-network-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer db req) :effect :commit)))})]
      (println (exec-op actor-direct "t9" {:op :order-supplies :facility-id "sewer-network-1"
                                           :patch {:item "chlorine-tablets"}} operator-phase-3)))

    (println "== schedule-maintenance sewer-network-1, advisor drifts into pump-control scope -> HARD hold, permanent ==")
    (println (exec-op actor "t10" {:op :schedule-maintenance :facility-id "sewer-network-1"
                                   :out-of-scope? true
                                   :patch {}} operator-phase-3))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
