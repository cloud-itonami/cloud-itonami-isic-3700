(ns sewerops.advisor-test
  (:require [clojure.test :refer [deftest is testing]]
            [sewerops.advisor :as advisor]
            [sewerops.store :as store]))

(def db (store/seed-db))

(deftest every-op-proposal-is-always-propose-effect
  (testing "the advisor NEVER drafts a direct-actuation :effect -- always :propose"
    (doseq [op [:log-system-record :schedule-maintenance :flag-safety-concern :order-supplies]]
      (let [p (advisor/infer db {:op op :facility-id "sewer-network-1" :patch {}})]
        (is (= :propose (:effect p)) (str op " must always propose, never actuate"))
        (is (= op (:op p)))
        (is (= "sewer-network-1" (:facility-id p)))
        (is (<= 0.0 (:confidence p) 1.0))
        (is (seq (:cites p)))))))

(deftest unrecognized-op-is-a-safe-noop
  (testing "an op outside the closed allowlist yields a safe zero-confidence :propose noop -- never a fabricated actuation"
    (let [p (advisor/infer db {:op :operate-pump :facility-id "sewer-network-1" :patch {}})]
      (is (= :propose (:effect p)))
      (is (zero? (:confidence p))))))

(deftest safety-concern-confidence-passes-through-patch
  (testing "a caller-supplied confidence on a safety-concern proposal is honored (the governor, not the advisor, is what always escalates this op)"
    (let [p (advisor/infer db {:op :flag-safety-concern :facility-id "sewer-network-1" :patch {:concern "overflow" :confidence 0.99}})]
      (is (= 0.99 (:confidence p))))))

(deftest order-supplies-proposal-carries-estimated-cost
  (testing "a patch's :estimated-cost flows through to the proposal's :value so the governor's cost-threshold check can see it"
    (let [p (advisor/infer db {:op :order-supplies :facility-id "sewer-network-1"
                               :patch {:item "replacement-pump-unit" :estimated-cost 18000}})]
      (is (= 18000 (get-in p [:value :estimated-cost]))))))

(deftest out-of-scope-hook-drafts-a-detectably-poisoned-proposal
  (testing "the :out-of-scope? test hook drafts content the governor's scope-exclusion scan must catch -- proves the failure mode is real and testable end to end"
    (let [p (advisor/infer db {:op :schedule-maintenance :facility-id "sewer-network-1" :patch {} :out-of-scope? true})]
      (is (= :propose (:effect p)))
      (is (re-find #"(?i)pump control" (str (:summary p) (:rationale p)))))))

(deftest mock-advisor-routes-through-infer
  (let [a (advisor/mock-advisor)
        p (advisor/-advise a db {:op :order-supplies :facility-id "sewer-network-1" :patch {:item "chlorine-tablets"}})]
    (is (= :order-supplies (:op p)))
    (is (= :propose (:effect p)))))

(deftest trace-carries-decision-grounded-fields
  (let [request {:op :log-system-record :facility-id "sewer-network-1"}
        proposal (advisor/infer db request)
        t (advisor/trace request proposal)]
    (is (= :log-system-record (:op t)))
    (is (= "sewer-network-1" (:facility-id t)))
    (is (= (:confidence proposal) (:confidence t)))))
