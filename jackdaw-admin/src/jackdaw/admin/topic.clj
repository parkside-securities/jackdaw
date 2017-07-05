(ns jackdaw.admin.topic
  (:require [clojure.tools.logging :as log]
            [jackdaw.admin
             [config :as c]
             [utils :as u :refer [map->properties]]
             [zk :as zk]])
  (:import kafka.admin.AdminUtils
           kafka.common.TopicAlreadyMarkedForDeletionException
           kafka.server.ConfigType
           org.apache.kafka.common.errors.UnknownTopicOrPartitionException
           scala.collection.JavaConversions))

(defn- select-topic
  [topics topic]
  (filter #(= (:topic %) (str topic))
          topics))

(defn create!
  "Makes a request to create topic and returns topic name.
  Returns topic name"
  [zk-utils topic partitions replication topic-config]
  (AdminUtils/createTopic zk-utils (name topic) partitions replication (u/map->properties topic-config) nil)
  (log/info (format "Created topic %s" topic))
  topic)

(defn delete!
  "Makes idempotent request to delete the topic and returns topic name.

  Idempotence is achieved by swallowing
  `org.I0Itec.zkclient.exception.ZkNodeExistsException` and
  `org.apache.kafka.common.errors.UnknownTopicOrPartitionException`."
  [zk-utils topic]
  (try (AdminUtils/deleteTopic zk-utils (name topic))
       (catch TopicAlreadyMarkedForDeletionException e)
       (catch UnknownTopicOrPartitionException e))
  (log/info (format "Requested deletion of topic %s." topic))
  topic)

(defn exists?
  "Verifies the existence of the topic"
  [zk-utils topic]
  (AdminUtils/topicExists zk-utils (name topic)))

(defn retry-exists?
  "Returns true if topic exists and retries if topic does not exist (default: num-retries=3).
  If topic does not exists after retries returns `false`."
  ([zk-utils topic]
   (retry-exists? zk-utils topic c/num-retries))
  ([zk-utils topic num-retries]
   (cond
     (exists? zk-utils topic) true
     (and
      (not (exists? zk-utils topic))
      (= num-retries 0)) false
     :else (do
             (log/info (format "Retrying time if topic %s exists. (%d retries left)" topic num-retries))
             (Thread/sleep c/wait-ms)
             (retry-exists? zk-utils topic (- num-retries 1))))))


(defn get-topics-with-partitions
  "Returns all topics with list of partition ids of partitions"
  [zk-utils]
  (map
   #(let [tpl (.asTuple %)] {:topic (._1 tpl) :partition-id (._2 tpl)})
   (JavaConversions/setAsJavaSet (.getAllPartitions zk-utils))))

(defn get-partitions-for-topic
  "Returns list of maps containing partition ids for topic
  Returns '() if no partitions were found for the topic"
  [zk-utils topic]
  (-> zk-utils
      get-topics-with-partitions
      (select-topic topic)))

(defn create-topics!
  "Create topics specified in topic-metadata if they do not exist."
  [zk-utils topic-metadata]
  (doseq [{topic-name :topic.metadata/name
           partitions :topic.metadata/partitions
           replication-factor :topic.metadata/replication-factor
           config :topic.metadata/config}
          topic-metadata]
    (if (exists? zk-utils topic-name)
      (log/debug (format "Topic %s already exists"
                         topic-name))
      (create! zk-utils
               topic-name
               (int partitions)
               (int replication-factor)
               config))))

(defn ensure-topics!
  "@Deprecated
  Used for backwards compatibility"
  [zk-utils topic-metadata]
  (create-topics! zk-utils topic-metadata))

(defn fetch-config
  [zk-utils topic]
  (AdminUtils/fetchEntityConfig zk-utils (ConfigType/Topic) topic))

(defn get-all-topics
  "Returns a set of all topics stored in Zookeeper"
  [zk-utils]
  (set (JavaConversions/seqAsJavaList (.getAllTopics zk-utils))))

(defn change-config!
  "Changes a topic configuration"
  ([zk-utils metadata]
   (change-config! zk-utils
                   (:topic.metadata/name metadata)
                   (:topic.metadata/config metadata)))
  ([zk-utils topic configs]
   (AdminUtils/changeTopicConfig zk-utils
                                 (name topic)
                                 (map->properties configs))))
