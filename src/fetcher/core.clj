(ns fetcher.core
  "Core HTTP request/response implementation."
  (:import (org.apache.http HttpRequest HttpEntityEnclosingRequest HttpResponse Header))
  (:import (org.apache.http.util EntityUtils))
  (:import (org.apache.http.entity ByteArrayEntity))
  (:import (org.apache.http.client HttpClient RedirectStrategy))
  (:import (org.apache.http.client.methods HttpGet HttpHead HttpPut HttpPost HttpDelete
                                           HttpUriRequest))
  (:import (org.apache.http.client.params CookiePolicy ClientPNames))
  (:import (org.apache.http.impl.client DefaultHttpClient DefaultRedirectStrategy))
  (:import (org.apache.http.params BasicHttpParams HttpConnectionParams
                                   HttpParams HttpProtocolParams))
  (:import (org.apache.http.conn.scheme PlainSocketFactory Scheme
                                        SchemeRegistry SocketFactory))
  (:import (org.apache.http.impl.client DefaultRedirectStrategy))
  (:import (org.apache.http.client.methods HttpUriRequest))
  (:import (org.apache.http.conn.ssl SSLSocketFactory))
  (:import (org.apache.http.impl.conn.tsccm ThreadSafeClientConnManager))
  (:import (java.util.concurrent TimeUnit))
  (:import (java.net URL))
  (:require [clojure.contrib.logging :as log] [clojure.string :as str]))

(defn if-pos [v]
  (if (and v (pos? v)) v))

(defn parse-url [^String url]
  (let [url-parsed (URL. url)]
    {:scheme (.getProtocol url-parsed)
     :server-name (.getHost url-parsed)
     :server-port (if-pos (.getPort url-parsed))
     :uri (.getPath url-parsed)
     :query-string (.getQuery url-parsed)}))

(defn path-redirect-strategy
  "Returns a RedirectStrategy that stores the redirect path in the provided
   atom as a nested vector of the form [[status-code-0 url-0] ... [status-code-n url-n]]."
  [a]
  (proxy [DefaultRedirectStrategy] []
    (^HttpUriRequest getRedirect [^org.apache.http.HttpRequest request
                                  ^org.apache.http.HttpResponse response
                                  ^org.apache.http.protocol.HttpContext context]
                     (let [^HttpUriRequest redirect-req (proxy-super
                                                         getRedirect
                                                         request response context)
                           status (-> response
                                      .getStatusLine
                                      .getStatusCode
                                      str
                                      keyword)
                           redirect-url (-> redirect-req
                                            .getRequestLine
                                            .getUri)]
                       (swap! a conj [status redirect-url])
                       redirect-req))))

(def default-params
  {ClientPNames/COOKIE_POLICY CookiePolicy/BROWSER_COMPATIBILITY
   ClientPNames/HANDLE_REDIRECTS true
   ClientPNames/MAX_REDIRECTS 10
   ClientPNames/ALLOW_CIRCULAR_REDIRECTS true
   ClientPNames/REJECT_RELATIVE_REDIRECT false})

(defn config-client
  [^HttpClient c {:keys [params ^RedirectStrategy redirect-strategy]}]
  (let [^HttpParams client-params (.getParams c)]
    (doseq [[pk pv] params]
      (.setParameter client-params pk pv)))
  (.setRedirectStrategy c redirect-strategy)
  c)

(defn pooled-http-client
  "A threadsafe, single client using connection pools to various hosts."
  ([] (pooled-http-client {:ttl 120
                           :max-total-conns 200
                           :max-per-route 10
                           :params default-params
                           :redirect-strategy (DefaultRedirectStrategy.)}))
  ([{:keys [ttl max-total-conns max-per-route]}]
     (let [psf (PlainSocketFactory/getSocketFactory)
           ssf (SSLSocketFactory/getSocketFactory)
           schemes (doto (SchemeRegistry.)
                     (.register (Scheme. "http" psf 80))
                     (.register (Scheme. "https" ssf 443)))
           mgr (doto (ThreadSafeClientConnManager. schemes (long ttl) TimeUnit/SECONDS)
                 (.setMaxTotal max-total-conns)
                 (.setDefaultMaxPerRoute max-per-route))]
       (config-client (DefaultHttpClient. mgr)
		      {:params default-params
                          :redirect-strategy (DefaultRedirectStrategy.)}))))

(defn basic-http-client
  ([] (basic-http-client {:params default-params
                          :redirect-strategy (DefaultRedirectStrategy.)}))
  ([conf]
     (config-client (DefaultHttpClient.) conf)))

(defn- parse-headers [^HttpResponse http-resp]
  (into {} (map (fn [^Header h] [(.toLowerCase (.getName h)) (.getValue h)])
                (iterator-seq (.headerIterator http-resp)))))

(defn strip-query-string [query-string]
  (str/join  "&"
	     (sort
	      (remove
	       (fn [^String x] (.startsWith x "utm_"))
	       (str/split query-string #"&" )))))

(defn build-url [{:keys [scheme,
			 server-name,
			 server-port
			 uri,
			 query-string]}]
  (str scheme "://" server-name
       (when server-port
	 (str ":" server-port))
       uri
       (when (and query-string
		  (not (.isEmpty ^String query-string)))
	 (str "?" query-string))))

(defn resolved-url [{:keys [redirects,url] :as fetched}]
  (let [resolved (or
		  (->> redirects
			(filter (comp #{:301 :302 :303} first))
			last
			second)
		  url)]
     (build-url
      (update-in (parse-url resolved)
		 [:query-string] strip-query-string))))

(defn request
  "Executes the HTTP request corresponding to the given Ring request map and
   returns the Ring response map corresponding to the resulting HTTP response."
  ([^HttpClient http-client {:keys [request-method scheme server-name server-port uri query-string
                                    headers content-type character-encoding body]
			     :as components}]
     (try
       (let [http-url (build-url components)
             ^HttpRequest
             http-req (case request-method
                            :get    (HttpGet. http-url)
                            :head   (HttpHead. http-url)
                            :put    (HttpPut. http-url)
                            :post   (HttpPost. http-url)
                            :delete (HttpDelete. http-url))
             redirects (atom [])
             ^RedirectStrategy redirect-strategy (path-redirect-strategy redirects)]
         (.setRedirectStrategy http-client redirect-strategy)
         (if (and content-type character-encoding)
           (.addHeader http-req "Content-Type"
                       (str content-type "; charset=" character-encoding)))
         (if (and content-type (not character-encoding))
           (.addHeader http-req "Content-Type" content-type))
         (.addHeader http-req "Connection" "close")
         (doseq [[header-n header-v] headers]
           (.addHeader http-req header-n header-v))
         (when body
           (let [http-body (ByteArrayEntity. body)]
             (.setEntity ^HttpEntityEnclosingRequest http-req http-body)))
         (let [^HttpResponse http-resp (.execute http-client http-req) ]
	   {:status (.getStatusCode (.getStatusLine http-resp))
	    :headers (parse-headers http-resp)
	    :body  (when-let [ent (.getEntity http-resp)]
		     (.getContent ent))		
	    :url http-url
	    :redirects @redirects}))))
  ([config]
     (request (basic-http-client) config)))
