(ns com.breezeehr.open-api.client.general-dynamic
  (:require
    [com.breezeehr.open-api.definition :refer [spec-methods
                                               get-openapi-spec]]
    [aleph.http :as http]
    [cheshire.core]))

(defn fast-select-keys [map ks]
  (->
    (reduce
      (fn [acc k]
        (if-some [val (find map k)]
          (conj! acc val)
          acc))
      (transient {})
      ks)
    persistent!
    (with-meta (meta map))))


(defn make-path-fn [{:strs [parameters path operationId]
                     :as   method-discovery}]
  (let [path-params  (into {}
                           (comp
                             (filter (comp #(= % "path") #(get % "in")))
                             (map #(get % "name"))
                             (map (juxt keyword #(str "{" % "}"))))
                           parameters)]
    (fn [vals]
      (reduce-kv
        (fn [acc parameter-key match]
          (let [nv (get vals parameter-key)]
            (assert nv (str "your input " (pr-str vals) " must contains key " parameter-key " for " operationId " path."))
            (clojure.string/replace
              acc
              match
              nv)))
        path
        path-params))))

(defn make-key-sel-fn [{:strs [parameters path operationId]
                        :as   method-discovery}]
  (let [query-ks     (into []
                           (comp
                             (filter (comp #(= % "query") #(get % "in")))
                             (map (comp keyword #(get % "name"))))
                           parameters)]
    (fn [m]
      (fast-select-keys m query-ks))))
(defn augment-request [request {:keys [pool get-token-fn]
                                :as client}]
  (cond-> request
    pool (assoc :pool pool)
    get-token-fn (assoc-in [:headers :authorization] (str "Bearer " (get-token-fn)))))
(defn new-operation-request-fn [{:strs [servers parameters httpMethod operationId] :as method-discovery}]
  (let [init-map     {:method httpMethod
                      :as     :json}
        path-fn      (make-path-fn method-discovery)
        key-sel-fn (make-key-sel-fn method-discovery)
        body-params  (into []
                           (comp
                             (filter (comp #(= % "body") #(get % "in"))))
                           parameters)
        request      (first body-params)
        baseUrl (get (first servers) "url")]
    (assert baseUrl)
    {:id          operationId
     :description (get method-discovery "description")
     :request     (fn [client op]
                    ;;(prn method-discovery)
                    (-> init-map
                        (assoc :url (str baseUrl (path-fn op)))

                        (assoc :query-params (key-sel-fn op)
                               :save-request? true
                               :throw-exceptions false)
                        (augment-request client)
                        (cond->
                          request (assoc :body (let [enc-body (:request op)]
                                                 (assert enc-body (str "Request cannot be nil for operation " (:op op)))
                                                 (cheshire.core/generate-string enc-body))))))}))

(defn init-client [config]
  config)

(defn add-operation-support [client api-discovery]
  (assoc client ::ops (into {}
                            (map (juxt
                                   #(keyword (get % "operationId"))
                                   new-operation-request-fn))
                            (spec-methods api-discovery))))

(defn operation-dynamic-client [config base-uri path]
  (let [client (init-client config)
        api-discovery
        (get-openapi-spec client base-uri path)]
    (add-operation-support client api-discovery)))

(defn ops [client]
  (run!
    (fn [[id {:keys [description]}]]
      (println "* " id)
      (print description)
      (println \newline))
    (->> client ::ops
         (sort-by key))))

(defn request [client {:keys [op] :as operation}]
  (let [opfn (-> client ::ops (get op) :request)]
    (assert opfn (str op
                      " is not implemented in "
                      (:api client)
                      (:version client)))
    (opfn client operation)))

(defn invoke [client {:keys [op] :as operation}]
  (let [r (request client operation)]
    (http/request r)))


(comment
  (def base-url "http://127.0.0.1:8001")

  (def api-data (get-openapi-spec {} base-url "/openapi/v2"))


  (keys api-data)
  api-data
  (def kube-op-client (operation-dynamic-client {} base-url "/openapi/v2"))
  )


