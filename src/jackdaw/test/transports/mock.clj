(ns jackdaw.test.transports.mock
  (:require
   [clojure.stacktrace :as stacktrace]
   [clojure.tools.logging :as log]
   [jackdaw.test.journal :as j]
   [jackdaw.test.transports :as t :refer [deftransport]]
   [jackdaw.test.serde :refer [byte-array-serializer byte-array-deserializer
                               apply-serializers apply-deserializers serde-map]]
   [manifold.stream :as s]
   [manifold.deferred :as d])
  (:import
   (org.apache.kafka.common.record TimestampType)
   (org.apache.kafka.clients.consumer ConsumerRecord)))

(set! *warn-on-reflection* false)

;; Unfortunately the terminology used in the test-machine clashes a bit with
;; the terminology used by the TopologyTestDriver so this looks a bit
;; confusing.
;;
;; While reviewing this code, bear in mind that the test machine has it's
;; own producer and consumer. The test-machine's producer is for producing
;; test input. The consumer is for consuming test output. On the other hand
;; when you feed records into the TopologyTestDriver, they are `ConsumerRecords`
;; (we're basically injecting the `ConsumerRecord` directly). This logic
;; is inverted on the other end where the test machine consumes the driver's
;; output `ProducerRecord`s directly
;;
;; For this reason, we try to make things a bit more meaningful by using
;; terms like "input-record" and "output-record".

(defn set-headers [consumer-record headers]
  (let [record-headers (.headers consumer-record)]
    (doseq [[k v] headers]
      (.add record-headers k v)))
  consumer-record)

(defn with-input-record
  "Creates a kafka ConsumerRecord to be fed directly into a topology source
  node by the TopologyTestDriver"
  [_topic-config]
  (fn [m]
    (let [record (ConsumerRecord. (get-in m [:topic :topic-name])
                                  (int -1)
                                  (long -1)
                                  (:timestamp m)
                                  TimestampType/CREATE_TIME,
                                  (long ConsumerRecord/NULL_CHECKSUM)
                                  (if-let [k (:key m)]
                                    (count k)
                                    0)
                                  (if-let [v (:value m)]
                                    (count v)
                                    0)
                                  (:key m)
                                  (:value m))]
      (set-headers record (:headers m))
      (assoc m :input-record record))))

(defn with-output-record
  [_topic-config]
  (fn [r]
    {:topic (.topic r)
     :key (.key r)
     :value (.value r)
     :partition (or (.partition r) -1)
     :headers (reduce (fn [header-map header]
                        (assoc header-map
                               (.key header)
                               (.value header))) {} (.headers r))}))

(defn- poller
  "Returns a function for polling the results of a TopologyTestDriver

   The returned function closes over the supplied `messages` channel
   and `topic-config` to produce a function that can be invoked with
   a TopologyTestDriver to gather any output generated by the topology
   under test"
  [messages topic-config]
  (fn [driver]
    (let [fetch (fn [[k t]]
                  {:topic k
                   :output (loop [collected []]
                             (if-let [o (.readOutput driver (:topic-name t)
                                                     byte-array-deserializer
                                                     byte-array-deserializer)]
                               (recur (conj collected o))
                               collected))})
          topic-batches (->> topic-config
                             (map fetch)
                             (remove #(empty? (:output %)))
                             (map :output))]
      (try
        (when-not (empty? topic-batches)
          (s/put-all! messages (apply concat topic-batches)))
        (catch Throwable e
          (log/error (Throwable->map e))
          (s/put! messages {:error e}))))))

(defn mock-consumer
  [driver topic-config deserializers]
  (let [continue? (atom true)
        messages  (s/stream 1 (comp
                               (map (with-output-record topic-config))
                               (map #(apply-deserializers deserializers %))
                               (map #(assoc % :topic (j/reverse-lookup topic-config (:topic %))))))

        started?  (promise)
        poll      (poller messages topic-config)]

    {:process (d/loop [cont? @continue?]
                (d/chain cont? (fn [d]
                                 (when-not (realized? started?)
                                   (log/info "started mock consumer: %s" {:driver driver})
                                   (deliver started? true))

                                 (if @continue?
                                   (d/future
                                     (poll driver)
                                     (Thread/sleep 100)
                                     (d/recur @continue?))
                                   (d/future
                                     (s/close! messages)
                                     (log/infof "stopped mock consumer: %s" {:driver driver}))))))
     :started? started?
     :messages messages
     :continue? continue?}))

(defn mock-producer
  [driver topic-config serializers on-input]
  (let [build-record (with-input-record topic-config)
        messages (s/stream 1 (map (fn [x]
                                    (try
                                      (-> (apply-serializers serializers x)
                                          (build-record))
                                      (catch Exception e
                                        (let [trace (with-out-str
                                                             (stacktrace/print-cause-trace e))]
                                          (log/error e trace))
                                        (assoc x :serialization-error e))))))

        _ (log/infof "started mock producer: %s" {:driver driver})

        process (d/loop [message (s/take! messages)]
                  (d/chain message
                    (fn [{:keys [input-record ack serialization-error] :as message}]
                      (cond
                        serialization-error  (do (deliver ack {:error :serialization-error
                                                               :message (.getMessage serialization-error)})
                                                 (d/recur (s/take! messages)))

                        input-record         (do (on-input input-record)
                                                 (deliver ack {:topic (.topic input-record)
                                                               :partition (.partition input-record)
                                                               :offset (.offset input-record)})
                                                 (d/recur (s/take! messages)))

                        :else (do
                                (log/infof "stopped mock producer: %s" {:driver driver}))))))]

    {:messages messages
     :process process}))

(deftransport :mock
  [{:keys [driver topics]}]
  (let [serdes        (serde-map topics)
        test-consumer (mock-consumer driver topics (get serdes :deserializers))
        record-fn     (fn [input-record]
                        (try
                          (.pipeInput driver input-record)
                          (catch Exception e
                            (let [trace (with-out-str
                                          (stacktrace/print-cause-trace e))]
                              (log/error e trace))
                            (throw e))))
        test-producer (when @(:started? test-consumer)
                        (mock-producer driver topics (get serdes :serializers)
                                       record-fn))]
    {:consumer test-consumer
     :producer test-producer
     :serdes serdes
     :topics topics
     :exit-hooks [(fn []
                    (.close driver)
                    (s/close! (:messages test-producer))
                    (reset! (:continue? test-consumer) false)
                    @(:process test-producer)
                    @(:process test-consumer))]}))
