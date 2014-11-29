(ns almonds.resource
  (:require [slingshot.slingshot :refer [throw+]]
            [clojure.pprint :refer [pprint]]
            [cheshire.core :refer [generate-string]]
            [camel-snake-kebab.core :as kebab :refer [->CamelCase]]
            [clojure.data.json :refer [write-str]]
            [amazonica.aws.ec2 :as aws-ec2]
            [clojure.set :refer [difference intersection]]
            [clojure.data :as data]
            [schema.core :as schema]))

;; (defmulti resource-factory :type)
;; (defmethod resource-factory :default [_]
;;   (throw+ {:operation "Trying to create a new resource using resource factory"
;;            :msg "Either :type was not given or is an incorrect value."} ))

(defmulti create :almonds-type)
(defmulti sanitize :almonds-type)
(defmulti retrieve-all :almonds-type)
(defmulti validate :almonds-type)
(defmulti aws-id :almonds-type)
(defmulti delete :almonds-type)

(defprotocol VpnConnection
  (is-up? [resource] "Returns true if the VPN Connection is up")
  (is-static? [resource] "Returns true if the connection is static")
  (has-route? [resource route] "Returns true if it has the route"))

(defprotocol VirtualPrivateGateway
  (is-attached? [resource] "Returns true if the gateway is attached to a VPC."))

(defprotocol RouteTable
  (route-propogation? [route-table virtual-private-gateway] "Returns true if the route propogationfor the virtual provate gateway for the route table is turned on."))

(defn uuid [] (java.util.UUID/randomUUID))

(def index (atom {}))
(def pushed-state (atom {}))

(def resource-types [:customer-gateway])

(def resource-schema {:almonds-tags schema/Uuid :almonds-type (apply schema/enum resource-types) schema/Keyword schema/Int})

(defn validate-schema [m]
  (schema/validate resource-schema m))

(comment (validate-schema {:almonds-tags (uuid) :almonds-type :customer-gateway :bgp-asn 655}))

(defn print-me [v]
  (println v)
  v)

(defn clear-all []
  (map #(reset! % {}) [index pushed-state])
  nil)

(defn unstage [& args]
  (reset! index {}))

(defn clear-pull-state []
  (reset! pushed-state {}))

(defn almonds->aws-tags [tags]
  (let [if-keyword (fn[v]
                     (if (keyword? v) (print-str v) v))]
    (mapv (fn [[k v]] {:key (if-keyword k) :value (if-keyword v)}) tags)))

(defn aws->almonds-tags [coll]
  (reduce (fn[m {:keys [key value]}]
            (merge m {(read-string key) (read-string value)}))
          {}
          coll))

(defn name-to-id [name] (kebab/->kebab-case-string name))

(defn id->name [id] (kebab/->Camel_Snake_Case_String id))

(defn tags->name [coll]
  (->> coll
       (map (fn[k] (if (keyword? k) k (print-str k))))
       (map kebab/->Camel_Snake_Case_String)
       (clojure.string/join " : ")))

(tags->name [:a :b-c 2])

(defn almonds-tags [{:keys [almonds-tags almonds-type tags] :or {tags {}}}]
  (merge {:almonds-tags (print-str almonds-tags)
          :almonds-type (print-str almonds-type)
          "Name" (tags->name almonds-tags)}
         tags))

(comment
  (almonds->aws-tags {:hi "abc" "Name4" "qwe"})
  (aws->almonds-tags [{:key ":name", :value "qwe"} {:key ":almonds-tags", :value "[:a :b]"}])
  (almonds-tags {:almonds-type :customer-gateway :almonds-tags [:a :b] :tags {"Name2" "hi"}})
  (id->name :g3))

(defn create-tags [resource-id tags]
  (aws-ec2/create-tags {:resources [resource-id] :tags tags}))

(defn has-tag? [k v]
  (fn[{:keys [tags]}]
    (some (fn[{:keys [key value]}]
            (when (= key k)
              (= value v)))
          tags)))

(defn has-tag-key? [k]
  (fn[{:keys [tags]}]
    (some (fn[{:keys [key value]}]
            (when (= key k)
              true))
          tags)))

((has-tag-key? ":almonds-tags") {:a 2 :tags [{:key ":almonds-tags" :value "[:a :b]"}
                                             {:key ":almonds-type" :value "dev-stack"}]})

(defn get-tag [k coll]
  (-> (filter (fn [{:keys [key]}]
                (= key k))
              coll)
      first
      :value
      read-string))

(get-tag "almonds-tags" [{:key "almonds-tags" :value "hello"}])

(defn ->almond-map [m]
  (let [{:keys [almonds-tags almonds-type]} (aws->almonds-tags (:tags m))]
    (merge m {:almonds-tags almonds-tags :almonds-type almonds-type})))

(->almond-map {:a 2 :tags [{:key ":almonds-tags" :value "[:a :b]"}
                           {:key ":almonds-type" :value "dev-stack"}]})

(defn get-pushed-resource [resource-type]
  (->> {:almonds-type resource-type}
       (retrieve-all)
       (filter (has-tag-key? ":almonds-tags"))
       (filter (has-tag-key? ":almonds-type"))
       (map ->almond-map)))

(comment (get-pushed-resource :customer-gateway))

(defn pull []
  (->> (mapcat #(get-pushed-resource %) resource-types)
       (reset! pushed-state)))

(defn contains-set? [set1 set2]
  (if (= (intersection set1 set2) set2)
    true false))

(contains-set? #{:a :b :c} #{})

(defn filter-resources [coll & args]
  (filter (fn [{:keys [almonds-tags]}]
            (contains-set? (into #{} almonds-tags)
                           (into #{} args)))
          coll))

(comment (filter-resources @pushed-state))

(defn pushed-resources-raw [& args]
  (when-not (seq @pushed-state) (pull))
  (apply filter-resources @pushed-state args))

(defn pushed-resources [& args]
  (->> (apply pushed-resources-raw args)
       (map sanitize)))

(defn pushed-resources-ids [& args]
  (->> (apply pushed-resources-raw args)
       (map (fn [{:keys [almonds-tags almonds-type]}]
              {:almonds-tags almonds-tags :almonds-type almonds-type}))))

(defn staged-resources [& args]
  (when (seq @index)
    (apply filter-resources (vals @index) args)))

(defn staged-resources-ids [& args]
  (->> (apply staged-resources args)
       (map (fn [{:keys [almonds-tags almonds-type]}]
              {:almonds-tags almonds-tags :almonds-type almonds-type}))))

(comment (staged-resources))

(comment  (pushed-resources-raw 1)

          (sanitize {:almonds-type :customer-gateway,
                     :almonds-tags [:sandbox-stack :web-tier :sync-box 1],
                     :customer-gateway-id "cgw-b2e604db",
                     :state "available",
                     :type "ipsec.1",
                     :ip-address "125.12.14.111",
                     :bgp-asn "6500",
                     :tags
                     [{:value "Sandbox_Stack : Web_Tier : Sync_Box : 1", :key "Name"}
                      {:value "[:sandbox-stack :web-tier :sync-box 1]", :key ":almonds-tags"}
                      {:value ":customer-gateway", :key ":almonds-type"}]}))


;; (sanitize {:bgp-asn "6500"
;;            :tags [{:value "[:sandbox-stack :web-tier :sync-box]", :key "Name"}
;;                   {:value ":customer-gateway", :key ":almonds-type"}
;;                   {:value "[:sandbox-stack :web-tier :sync-box]", :key ":almonds-tags"}]})

(defn diff-resources [staged pushed]
  (let [d (->> (data/diff (into #{} staged)
                          (into #{} pushed))
               butlast
               (map (fn[coll] (map :almonds-tags coll)))
               (map #(into #{} %))
               (zipmap [:to-create :to-delete]))]
    (merge d {:inconsistent (last (data/diff (:to-delete d)
                                             (:to-create d)))})))

(comment (diff-resources (seq [{:almonds-tags [:g1] :a 1} {:almonds-tags [:g2] :a 2} {:almonds-tags [:g3] :a 2}])
                         (seq [{:almonds-tags [:g2] :a 2} {:almonds-tags [:g1] :a 2} {:almonds-tags [:g4] :a 2}])))

(defn sanitize-resources []
  (->> @pushed-state
       (map ->almond-map)
       (map sanitize)))

(defn has-value?
  "Expects a collection of maps. Returns true if any map in the collection has a matching key/value pair."
  [coll k v]
  (when
      (seq (filter (fn[m] (= (m k) v)) coll))
    true))

(defn exists? [{:keys [almonds-tags]}]
  "Given a resource returns true if has been created with the provider. The resource should implement the retrieve-raw method of Resource and should have an :id."
  (when (seq (apply pushed-resources almonds-tags)) true))

(comment  (exists? {:almonds-tags [:sandbox-stack :web-tier :sync-box 1]}))

(defn validate-all [& fns]
  (fn [resource]
    (every? true? ((apply juxt fns) resource))))

(defn to-json [m]
  (generate-string m {:key-fn (comp name ->CamelCase)}))

(defn stage [coll]
  (->> coll
       (map (fn [resource]
              (when (validate resource)
                (swap! index
                       merge
                       {(:almonds-tags resource) resource}))))))

(defn diff-ids [& args]
  (-> (diff-resources (apply staged-resources args)
                      (apply pushed-resources args))))

(comment (diff-ids 1))

(defn diff [& args]
  (let [{:keys [inconsistent to-delete to-create]} (diff-resources (apply staged-resources args)
                                                                   (apply pushed-resources args))]
    {:to-create (mapcat #(apply staged-resources %) to-create)
     :inconsistent (mapcat #(apply staged-resources %) inconsistent)
     :to-delete (mapcat #(apply pushed-resources %) to-delete)}))

(comment (pushed-resources-raw 1)
         (staged-resources))

(comment (diff 1))

(defn create-resources [coll]
  (doseq [v coll]
    (create v)))

(defn delete-resources [coll]
  (doseq [v coll]
    (delete v)))

(defn push-without-pull [& args]
  (let [{:keys [to-create to-delete]} (apply diff args)]
    (create-resources to-create)
    (delete-resources to-delete)))

(defn push [& args]
  (apply push-without-pull args)
  (pull))

(comment (stage :c [{:almonds-type :customer-gateway :almonds-tags "b" :a 2}]))
