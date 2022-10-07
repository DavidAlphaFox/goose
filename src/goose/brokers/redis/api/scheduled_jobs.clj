(ns goose.brokers.redis.api.scheduled-jobs
  {:no-doc true}
  (:require
    [goose.brokers.redis.commands :as redis-cmds]
    [goose.defaults :as d]
    [goose.job :as job]))

(defn size [conn]
  (redis-cmds/sorted-set-size conn d/prefixed-schedule-queue))

(defn find-by-pattern [conn match? limit]
  (redis-cmds/find-in-sorted-set conn d/prefixed-schedule-queue match? limit))

(defn find-by-id [conn id]
  (let [
        limit 1
        match? (fn [job] (= (:id job) id))]
    (first (find-by-pattern conn match? limit))))

(defn prioritise-execution [conn job]
  (let [sorted-set d/prefixed-schedule-queue]
    (when (redis-cmds/sorted-set-score conn sorted-set job)
      (redis-cmds/move-jobs-from-sorted-set-to-ready-queue conn sorted-set (list job) job/ready-queue))))

(defn delete [conn job]
  (= 1 (redis-cmds/del-from-sorted-set conn d/prefixed-schedule-queue job)))

(defn purge [conn]
  (= 1 (redis-cmds/del-keys conn [d/prefixed-schedule-queue])))
