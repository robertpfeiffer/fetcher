(ns fetcher.core
  (:require [http.async.client :as c]
            [http.async.client.request :as async-req]
            [work.core :as work]
            [work.cache :as cache]
            [clojure.contrib.logging :as log])
  (:use fetcher.handler))

(defn status-check
  "Check if status code is 304, abort if so."
  [_ status]
  (log/debug (format "Status check, status is %s" status))
  (if (= 304 (:code status))
    [status :abort]
    [status :continue]))

;;; Need a closure to capture the feed-url.
(defn dispatch-generator
  "Return a fn to handle a completed response."
  [k u response-callback]
  (fn [state]
    (let [code (-> (c/status state) :code)
          headers (if (not= 304 code)
                    (c/headers state)
                    nil)
          body (if (ok? code)
                 (c/string state)
                 nil)]
      (response-callback k
                         u
                         code
                         headers
                         body)
      [true :continue])))

(defn fetch
  "Fetch a feed for updates.  Responses are handled asynchronously by the provided callback.

  The callback should accept five arguments: k, u, response code, headers, and body."
  [[k u & [headers]] put-done]
  (let [callbacks (merge async-req/*default-callbacks*
                         {:status status-check
                          :completed (dispatch-generator k u put-done)
                          :error (fn [_ t] (log/error (format "Error processing request for %s." k) t))})
        req (async-req/prepare-request :get
                                       u
                                       :headers headers)
        resp (apply async-req/execute-request
                    req
                    (apply concat callbacks))]
    (log/debug (format "Fetching %s -> %s." k u))
    resp))

(defn fetch-pool
  [fetch-fn get-work put-done]
  (work/queue-work fetch-fn
                   get-work
                   put-done
                   (work/available-processors)
                   :async))