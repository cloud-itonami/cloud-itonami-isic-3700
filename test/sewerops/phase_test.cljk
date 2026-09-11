(ns sewerops.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-safety-concern` must NEVER be a member of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [sewerops.phase :as phase]))

(deftest safety-concern-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a safety-concern flag"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-safety-concern))
          (str "phase " n " must not auto-commit :flag-safety-concern")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest system-logging-enabled-from-phase-1
  (is (contains? (:writes (get phase/phases 1)) :log-system-record))
  (is (not (contains? (:writes (get phase/phases 0)) :log-system-record))))

(deftest maintenance-and-supply-enabled-from-phase-2
  (doseq [op [:schedule-maintenance :order-supplies]]
    (is (contains? (:writes (get phase/phases 2)) op))
    (is (not (contains? (:writes (get phase/phases 1)) op)))))

(deftest safety-concern-enabled-only-from-phase-3
  (is (contains? (:writes (get phase/phases 3)) :flag-safety-concern))
  (is (not (contains? (:writes (get phase/phases 2)) :flag-safety-concern))))

(deftest phase-3-auto-commits-three-of-four-ops
  (testing ":flag-safety-concern is the only non-auto-eligible op at phase 3 -- always human sign-off"
    (is (= #{:log-system-record :schedule-maintenance :order-supplies}
           (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-system-record} :hold)))))

(deftest gate-escalate-always-wins-over-auto
  (testing "the phase gate never downgrades a governor escalate (e.g. high-cost supply order) back to commit"
    (is (= :escalate (:disposition (phase/gate 3 {:op :order-supplies} :escalate))))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-safety-concern} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-system-record} :commit))))
  (is (= :phase-disabled (:reason (phase/gate 0 {:op :log-system-record} :commit)))))

(deftest gate-auto-commits-a-clean-auto-eligible-write-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :log-system-record} :commit)))))

(deftest verdict->disposition-priority
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false})))
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true})))
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
