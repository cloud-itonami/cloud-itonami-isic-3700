(ns sewerops.store-contract-test
  "The Store contract as executable tests."
  (:require [clojure.test :refer [deftest is testing]]
            [sewerops.store :as store]))

(deftest seed-db-read-parity
  (let [s (store/seed-db)]
    (is (= "Riverside Sewer Network" (:name (store/facility s "sewer-network-1"))))
    (is (true? (:registered? (store/facility s "sewer-network-1"))))
    (is (true? (:verified? (store/facility s "sewer-network-1"))))
    (is (true? (:registered? (store/facility s "sewer-network-3"))))
    (is (false? (:verified? (store/facility s "sewer-network-3"))) "seeded as registered but not yet verified")
    (is (nil? (store/facility s "no-such-facility")))
    (is (= ["sewer-network-1" "sewer-network-2" "sewer-network-3"] (mapv :facility-id (store/all-facilities s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/coordination-log s)))))

(deftest mem-store-honors-explicit-facilities-map
  (let [s (store/mem-store {"a" {:facility-id "a" :registered? true :verified? true}})]
    (is (some? (store/facility s "a")))
    (is (nil? (store/facility s "b"))))
  (testing "an empty facilities map means unregistered everywhere"
    (let [s (store/mem-store {})]
      (is (nil? (store/facility s "sewer-network-1"))))))

(deftest commit-record-appends-to-coordination-log
  (let [s (store/seed-db)]
    (store/commit-record! s {:op :log-system-record :facility-id "sewer-network-1" :value {:flow-rate-mgd 4.2}})
    (store/commit-record! s {:op :schedule-maintenance :facility-id "sewer-network-1" :value {:equipment "belt"}})
    (is (= 2 (count (store/coordination-log s))))
    (is (= [:log-system-record :schedule-maintenance] (mapv :op (store/coordination-log s))))))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/seed-db)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest with-facilities-replaces-directory-when-non-empty
  (let [s (store/mem-store {"x" {:facility-id "x" :registered? true :verified? true}})]
    (store/with-facilities s {"y" {:facility-id "y" :registered? true :verified? true}})
    (is (nil? (store/facility s "x")))
    (is (some? (store/facility s "y"))))
  (testing "an empty replacement is a no-op (never silently wipes the directory)"
    (let [s (store/mem-store {"x" {:facility-id "x" :registered? true :verified? true}})]
      (store/with-facilities s {})
      (is (some? (store/facility s "x"))))))
