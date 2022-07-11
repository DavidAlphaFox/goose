(ns goose.executor
  (:require
    [goose.defaults :as d]
    [goose.redis :as r]
    [goose.retry :as retry]
    [goose.statsd.statsd :as statsd]
    [goose.utils :as u]

    [clojure.tools.logging :as log]))

(defn- calculate-latency
  [job]
  (cond
    (:retry-at (:state job))
    [statsd/retry-latency (- (u/epoch-time-ms) (:retry-at (:state job)))]
    (:schedule job)
    [statsd/schedule-latency (- (u/epoch-time-ms) (:schedule job))]
    :else
    [statsd/execution-latency (- (u/epoch-time-ms) (:enqueued-at job))]))

(defn- execute-job
  [{:keys [redis-conn statsd-opts]} {:keys [id execute-fn-sym args] :as job}]
  (let [latency (calculate-latency job)]
    (try
      (statsd/run-job-and-send-metrics
        statsd-opts latency (str execute-fn-sym)
        #(apply (u/require-resolve execute-fn-sym) args))
      (log/debug "Executed job-id:" id)
      (catch Exception ex
        (retry/handle-failure redis-conn job ex)))))

(defn preservation-queue
  [id]
  (str d/in-progress-queue-prefix id))

(defn run
  [{:keys [thread-pool redis-conn prefixed-queue in-progress-queue]
    :as   opts}]
  (log/debug "Long-polling broker...")
  (u/while-pool
    thread-pool
    (u/log-on-exceptions
      (when-let [job (r/dequeue-and-preserve redis-conn prefixed-queue in-progress-queue)]
        (execute-job opts job)
        (r/remove-from-list redis-conn in-progress-queue job)))))
