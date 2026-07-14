(ns sewerops.governor-contract-test
  "The governor contract as executable end-to-end tests, driven through
  the full langgraph-clj `sewerops.operation` StateGraph (intake ->
  advise -> govern -> decide -> commit | hold | request-approval). The
  single invariant under test:

    SewerOpsAdvisor never commits a proposal the SewerageOpsGovernor
    would reject, `:flag-safety-concern` ALWAYS interrupts for human
    sign-off (never auto, at any phase), a high-value `:order-supplies`
    proposal ALWAYS interrupts for human budget sign-off (never auto,
    at any phase), and every decision (commit OR hold) leaves exactly
    one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [sewerops.advisor :as advisor]
            [sewerops.store :as store]
            [sewerops.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator-phase-1 {:actor-id "op-1" :actor-role :shift-supervisor :phase 1})
(def operator-phase-3 {:actor-id "op-1" :actor-role :shift-supervisor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected}} {:thread-id tid :resume? true}))

(deftest clean-system-log-auto-commits-at-phase-3
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-system-record :facility-id "sewer-network-1"
                   :patch {:flow-rate-mgd 4.2}} operator-phase-3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/coordination-log db))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest clean-system-log-needs-approval-at-phase-1
  (testing "phase 1 has an empty :auto set -- every write escalates for human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :log-system-record :facility-id "sewer-network-1"
                                   :patch {:flow-rate-mgd 0.1}} operator-phase-1)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/coordination-log db))))))))

(deftest maintenance-and-low-cost-supply-auto-commit-clean-at-phase-3
  (let [[db actor] (fresh)]
    (exec-op actor "t3a" {:op :schedule-maintenance :facility-id "sewer-network-1" :patch {:equipment "belt"}} operator-phase-3)
    (exec-op actor "t3b" {:op :order-supplies :facility-id "sewer-network-1" :patch {:item "chlorine-tablets" :estimated-cost 500}} operator-phase-3)
    (is (= [:schedule-maintenance :order-supplies] (mapv :op (store/coordination-log db))))
    (is (= 2 (count (store/ledger db))))))

(deftest high-cost-supply-order-escalates-even-at-phase-3
  (testing "an :order-supplies proposal above the cost threshold ALWAYS interrupts for human budget sign-off -- never auto, even at supervised-auto phase 3"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t4" {:op :order-supplies :facility-id "sewer-network-1"
                                  :patch {:item "replacement-pump-unit" :estimated-cost 18000}} operator-phase-3)]
      (is (= :interrupted (:status r1)) "pauses for human budget sign-off even when governor-clean and high-confidence")
      (testing "approve -> commit, coordination record written"
        (let [r2 (approve! actor "t4")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/coordination-log db))))
          (is (= :order-supplies (:op (first (store/coordination-log db))))))))))

(deftest safety-concern-always-escalates-then-human-decides
  (testing "a clean, high-confidence safety-concern flag still ALWAYS interrupts for human sign-off -- never auto, at any phase"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t5" {:op :flag-safety-concern :facility-id "sewer-network-1"
                                  :patch {:concern "manhole overflow" :confidence 0.99}} operator-phase-3)]
      (is (= :interrupted (:status r1)) "pauses for human sign-off even when governor-clean and high-confidence")
      (testing "approve -> commit, coordination record written"
        (let [r2 (approve! actor "t5")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/coordination-log db))))
          (is (= :flag-safety-concern (:op (first (store/coordination-log db))))))))))

(deftest safety-concern-rejected-by-human-is-held-not-committed
  (let [[db actor] (fresh)
        _ (exec-op actor "t6" {:op :flag-safety-concern :facility-id "sewer-network-1"
                               :patch {:concern "minor"}} operator-phase-3)
        r2 (reject! actor "t6")]
    (is (= :hold (get-in r2 [:state :disposition])))
    (is (= [] (store/coordination-log db)) "no commit on rejection")
    (is (= 1 (count (store/ledger db))))))

(deftest unregistered-facility-is-held-and-unoverridable
  (testing "an unregistered facility -> HOLD, settles immediately, never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :log-system-record :facility-id "sewer-network-9"
                                   :patch {:flow-rate-mgd 0.1}} operator-phase-3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:facility-unverified} (-> (store/ledger db) first :basis)))
      (is (= [] (store/coordination-log db))))))

(deftest unverified-facility-is-held
  (testing "sewer-network-3 exists but is registered? true / verified? false -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :log-system-record :facility-id "sewer-network-3"
                                   :patch {:flow-rate-mgd 0.1}} operator-phase-3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:facility-unverified} (-> (store/ledger db) first :basis)))
      (is (= [] (store/coordination-log db))))))

(deftest direct-actuation-effect-is-held-and-unoverridable
  (testing "an advisor that drafts a non-:propose :effect is HARD-blocked, never reaches request-approval"
    (let [[db _actor] (fresh)
          rogue-advisor (reify advisor/Advisor
                          (-advise [_ st req] (assoc (advisor/infer st req) :effect :commit)))
          actor2 (op/build db {:advisor rogue-advisor})
          res (exec-op actor2 "t9" {:op :order-supplies :facility-id "sewer-network-1"
                                    :patch {:item "chlorine-tablets"}} operator-phase-3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:effect-not-propose} (-> (store/ledger db) first :basis)))
      (is (= [] (store/coordination-log db))))))

(deftest scope-excluded-proposal-is-held-and-permanent
  (testing "a proposal that drifts into pump/valve-control scope -> HARD hold, never reaches request-approval, at ANY confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t10" {:op :schedule-maintenance :facility-id "sewer-network-1"
                                    :out-of-scope? true :patch {}} operator-phase-3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:scope-excluded} (-> (store/ledger db) first :basis)))
      (is (= [] (store/coordination-log db))))))

(deftest op-outside-allowlist-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t11" {:op :operate-pump :facility-id "sewer-network-1"
                                  :patch {}} operator-phase-3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:op-not-allowed} (-> (store/ledger db) first :basis)))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-system-record :facility-id "sewer-network-1" :patch {:flow-rate-mgd 1}} operator-phase-3)
      (exec-op actor "b" {:op :log-system-record :facility-id "sewer-network-9" :patch {:flow-rate-mgd 1}} operator-phase-3)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
