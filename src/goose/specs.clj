(ns goose.specs
  (:require
    [goose.client :as c]
    [goose.defaults :as d]
    [goose.utils :as u]
    [goose.worker :as w]

    [clojure.spec.alpha :as s]
    [clojure.spec.test.alpha :as st]
    [clojure.string :as string]
    [taoensso.nippy :as nippy]
    [taoensso.carmine.connections :refer [IConnectionPool]]))

; ========== Qualified Function Symbols ==============
(defn- resolvable-fn? [func] (fn? @(resolve func)))
(s/def ::fn-sym (s/and qualified-symbol? resolve resolvable-fn?))

; ========== Redis ==============
; Valid Redis URL patterns:
; #1: redis://username:password@hostname:0-65353
; #2: redis://hostname:0-65353
(s/def :goose.specs.redis/url #(re-matches #"redis://.+:[0-9]{1,5}" %))
(s/def :goose.specs.redis/pool-opts
  (s/or :none #(= :none %)
        :map #(map? %)
        :iconn-pool #(satisfies? IConnectionPool %)))

; ============== Brokers ==============
(s/def ::redis
  (s/keys :req-un [:goose.specs.redis/url]
          :opt-un [:goose.specs.redis/pool-opts]))
(s/def ::broker-opts
  (s/or :redis (s/keys :req-un [::redis])))

; ============== Queue ==============
(defn- unprefixed? [queue] (not (string/starts-with? queue d/queue-prefix)))
(defn- not-protected? [queue] (not (string/includes? d/protected-queues queue)))
(defn- len-below-1000? [queue] (< (count queue) 1000))
(s/def ::queue
  (s/and string? len-below-1000? unprefixed? not-protected?))

; ============== Retry Opts ==============
(s/def ::max-retries nat-int?)
(s/def ::retry-delay-sec-fn-sym
  (s/and ::fn-sym
         #(pos-int? (@(resolve %) 0))))
(s/def ::retry-queue (s/nilable ::queue))
(s/def ::handler-fn-sym
  (s/and ::fn-sym
         #(some #{2} (u/arities %))))
(s/def ::error-handler-fn-sym ::handler-fn-sym)
(s/def ::death-handler-fn-sym ::handler-fn-sym)
(s/def ::skip-dead-queue boolean?)
(s/def ::retry-opts
  (s/keys :req-un [::max-retries ::retry-delay-sec-fn-sym ::skip-dead-queue
                   ::error-handler-fn-sym ::death-handler-fn-sym]
          :opt-un [::retry-queue]))

; ============== Statsd Opts ==============
(s/def :goose.specs.statsd/enabled? boolean?)
(s/def :goose.specs.statsd/host string?)
(s/def :goose.specs.statsd/port pos-int?)
(s/def :goose.specs.statsd/sample-rate double?)
(s/def :goose.specs.statsd/tags map?)
(s/def ::statsd-opts
  (s/keys :req-un [:statsd/enabled?]
          :opt-un [:statsd/host :statsd/port
                   :statsd/sample-rate :statsd/tags]))

; ============== Client ==============
(defn- serializable? [arg]
  (try (= arg (nippy/thaw (nippy/freeze arg)))
       (catch Exception _ false)))
(s/def ::args-serializable? serializable?)
(s/def ::client-opts (s/keys :req-un [::broker-opts ::queue]
                             :opt-un [::retry-opts]))

; ============== Worker ==============
(s/def ::threads pos-int?)
(s/def ::graceful-shutdown-sec pos-int?)
(s/def ::scheduler-polling-interval-sec pos-int?)
(s/def ::worker-opts (s/keys :req-un [::broker-opts ::queue ::threads
                                 ::scheduler-polling-interval-sec
                                 ::graceful-shutdown-sec ::statsd-opts]))

; ============== FDEFs ==============
(s/fdef c/perform-async
        :args (s/cat :opts ::client-opts
                     :execute-fn-sym ::fn-sym
                     :args (s/* ::args-serializable?)))

(s/fdef c/perform-at
        :args (s/cat :opts ::client-opts
                     :date-time inst?
                     :execute-fn-sym ::fn-sym
                     :args (s/* ::args-serializable?)))

(s/fdef c/perform-in-sec
        :args (s/cat :opts ::client-opts
                     :sec int?
                     :execute-fn-sym ::fn-sym
                     :args (s/* ::args-serializable?)))

(s/fdef w/start
        :args (s/cat :opts ::worker-opts))

(def ^:private fns-with-specs
  [`c/perform-async
   `c/perform-at
   `c/perform-in-sec
   `w/start])

(defn instrument []
  (st/instrument fns-with-specs))

(defn unstrument []
  (st/unstrument fns-with-specs))
