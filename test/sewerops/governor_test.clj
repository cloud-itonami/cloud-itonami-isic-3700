(ns sewerops.governor-test
  "Pure unit tests of `sewerops.governor/check` against hand-built
  proposals -- the fast, focused complement to `governor-contract-
  test`'s full-graph integration coverage."
  (:require [clojure.test :refer [deftest is testing]]
            [sewerops.governor :as gov]
            [sewerops.store :as store]))

(def facility-1 {:facility-id "sewer-network-1" :name "Riverside Sewer Network" :registered? true :verified? true})
(def facility-3 {:facility-id "sewer-network-3" :name "Hillside Lift Station" :registered? true :verified? false})

(defn- clean-proposal
  ([op facility-id] (clean-proposal op facility-id {}))
  ([op facility-id value]
   {:op op :facility-id facility-id :summary "s" :rationale "routine back-office coordination"
    :cites [facility-id] :effect :propose :value value :confidence 0.85}))

(deftest facility-unregistered-is-hard
  (testing "no facility record at all -> HARD hold"
    (let [s (store/mem-store {"sewer-network-1" facility-1})
          verdict (gov/check {} nil (clean-proposal :log-system-record "unknown-facility") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:facility-unverified} (map :rule (:violations verdict)))))))

(deftest facility-unverified-is-hard
  (testing "facility registered but not yet verified -> HARD hold"
    (let [s (store/mem-store {"sewer-network-3" facility-3})
          verdict (gov/check {} nil (clean-proposal :log-system-record "sewer-network-3") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:facility-unverified} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-hard
  (testing "any :effect other than :propose is a HARD, un-overridable block"
    (let [s (store/mem-store {"sewer-network-1" facility-1})
          verdict (gov/check {} nil (assoc (clean-proposal :order-supplies "sewer-network-1") :effect :commit) s)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest op-outside-allowlist-is-hard
  (testing "an op outside the closed four-op allowlist is a scope violation"
    (let [s (store/mem-store {"sewer-network-1" facility-1})
          verdict (gov/check {} nil (clean-proposal :operate-pump "sewer-network-1") s)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest pump-valve-control-content-is-hard-and-permanent
  (testing "a proposal whose rationale touches pump/valve-control scope is HARD-blocked regardless of op/confidence"
    (let [s (store/mem-store {"sewer-network-1" facility-1})
          poisoned (assoc (clean-proposal :schedule-maintenance "sewer-network-1")
                          :rationale "adjusted pump control and valve actuation timing"
                          :confidence 0.99)
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest public-health-authority-content-is-hard
  (testing "a proposal touching public-health-authority discharge decisions (discharge permit / boil-water notice / effluent discharge decision) is HARD-blocked"
    (let [s (store/mem-store {"sewer-network-1" facility-1})
          poisoned (assoc (clean-proposal :order-supplies "sewer-network-1")
                          :summary "recommend boil water notice pending review")
          verdict (gov/check {} nil poisoned s)]
      (is (true? (:hard? verdict)))
      (is (some #{:scope-excluded} (map :rule (:violations verdict)))))))

(deftest safety-flag-always-escalates
  (testing ":flag-safety-concern ALWAYS escalates, even at maximum confidence"
    (let [s (store/mem-store {"sewer-network-1" facility-1})
          verdict (gov/check {} nil (assoc (clean-proposal :flag-safety-concern "sewer-network-1") :confidence 1.0) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (false? (:ok? verdict))))))

(deftest low-confidence-escalates
  (testing "confidence below the floor escalates a proposal that is otherwise clean"
    (let [s (store/mem-store {"sewer-network-1" facility-1})
          verdict (gov/check {} nil (assoc (clean-proposal :log-system-record "sewer-network-1") :confidence 0.4) s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:ok? verdict))))))

(deftest high-cost-supply-order-escalates
  (testing "an :order-supplies proposal above the cost threshold ALWAYS escalates, even at maximum confidence"
    (let [s (store/mem-store {"sewer-network-1" facility-1})
          verdict (gov/check {} nil
                              (assoc (clean-proposal :order-supplies "sewer-network-1"
                                                      {:estimated-cost 18000})
                                     :confidence 1.0)
                              s)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (false? (:ok? verdict))))))

(deftest low-cost-supply-order-does-not-escalate-on-cost-alone
  (testing "an :order-supplies proposal below the cost threshold, clean and confident, is ok"
    (let [s (store/mem-store {"sewer-network-1" facility-1})
          verdict (gov/check {} nil
                              (clean-proposal :order-supplies "sewer-network-1" {:estimated-cost 500})
                              s)]
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict)))
      (is (false? (:high-stakes? verdict)))
      (is (true? (:ok? verdict))))))

(deftest hard-violation-wins-over-escalate
  (testing "a HARD violation on an always-escalate op still reports hard?, not merely escalate?"
    (let [s (store/mem-store {})
          verdict (gov/check {} nil (clean-proposal :flag-safety-concern "unknown-facility") s)]
      (is (true? (:hard? verdict)))
      (is (false? (:escalate? verdict)) "hard? wins -- escalate? is false when hard? is true"))))

(deftest happy-path-every-non-escalating-op-is-clean
  (testing "each of the three non-always-escalate ops, clean input, registered+verified facility -> ok"
    (let [s (store/mem-store {"sewer-network-1" facility-1})]
      (doseq [op [:log-system-record :schedule-maintenance :order-supplies]]
        (let [verdict (gov/check {} nil (clean-proposal op "sewer-network-1") s)]
          (is (false? (:hard? verdict)) (str op " should have no hard violations"))
          (is (false? (:escalate? verdict)) (str op " should not escalate when clean and confident"))
          (is (true? (:ok? verdict)) (str op " should be ok")))))))
