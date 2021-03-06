(ns clj-gatling.simulation-test
  (:use clojure.test)
  (:require [clj-gatling.simulation :as simulation]
            [clj-time.core :as time]))

(def request-count (atom 0))

(defn counting-request [id context cb]
  (do
    (swap! request-count inc)
    (cb true)))

(defn successful-request [id context cb]
  (cb true (assoc context :to-next-request true)))

(defn read-return-value-from-context-request [id context cb]
  (cb (:to-next-request context) context))

(defn slow-request [sleep-time id context cb]
  (future (Thread/sleep sleep-time)
          (cb true)))

(defn failing-request [id context cb] (cb false))

(defn- fake-async-http [url id context callback]
  (future (Thread/sleep 50)
          (callback (= "success" url))))

(def scenario
  {:name "Test scenario"
   :requests [{:name "Request1" :fn successful-request}
              {:name "Request2" :fn failing-request}]})

(def context-testing-scenario
  {:name "Context testing scenario"
   :requests [{:name "Request1" :fn successful-request}
              {:name "Request2" :fn read-return-value-from-context-request}]})

(def counting-scenario
  {:name "Counting scenario"
   :requests [{:name "Request1" :fn counting-request}]})

(def scenario2
  {:name "Test scenario2"
   :requests [{:name "Request1" :fn (partial slow-request 50)}
              {:name "Request2" :fn failing-request}]})

(def timeout-scenario
  {:name "Timeout scenario"
   :requests [{:name "Request1" :fn (partial slow-request 2000)}]})

(def http-scenario
  {:name "Test http scenario"
   :requests [{:name "Request1" :http "success"}
              {:name "Request2" :http "fail"}]})

(defn get-result [requests request-name]
  (:result (first (filter #(= request-name (:name %)) requests))))

(deftest simulation-returns-result-when-run-with-one-user
  (let [result (first (simulation/run-simulation [scenario] 1))]
    (is (= "Test scenario" (:name result)))
    (is (= true (get-result (:requests result) "Request1")))
    (is (= false (get-result (:requests result) "Request2")))))

(deftest simulation-passes-context-through-requests-in-scenario
  (let [result (first (simulation/run-simulation [context-testing-scenario] 1))]
    (is (= true (get-result (:requests result) "Request2")))))

(deftest simulation-returns-result-when-run-with-http-requests
  (with-redefs [simulation/async-http-request fake-async-http]
    (let [result (first (simulation/run-simulation [http-scenario] 1))]
      (is (= "Test http scenario" (:name result)))
      (is (= true (get-result (:requests result) "Request1")))
      (is (= false (get-result (:requests result) "Request2"))))))

(deftest simulation-returns-result-when-run-with-multiple-scenarios-and-one-user
  (let [result (simulation/run-simulation [scenario scenario2] 1)]
    (is (= 2 (count result)))))

(deftest with-given-number-of-requests
  (let [result (simulation/run-simulation [scenario] 1 {:requests 2})]
    (is (= "Test scenario" (-> result first :name)))
    (is (= 2 (-> result first :requests count)))
    (is (= "Test scenario" (-> result second :name)))
    (is (= 2 (-> result second :requests count)))))

(deftest with-multiple-number-of-requests
  (reset! request-count 0)
  (let [result (simulation/run-simulation [counting-scenario] 100 {:requests 2000})]
    (is (= 2000 (->> result (map :requests) count))
    (is (= 2000 @request-count)))))

(deftest duration-given
  (let [result (simulation/run-simulation [scenario] 1 {:duration (time/millis 50)})]
    (is (not (empty? result)))))

(deftest fails-requests-when-they-take-longer-than-timeout
  (let [result (first (simulation/run-simulation [timeout-scenario] 1 {:timeout-in-ms 100}))]
    (is (= false (get-result (:requests result) "Request1")))))
