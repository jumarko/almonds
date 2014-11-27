(ns almonds.customer-gateway
  (:require [amazonica.aws.ec2 :as aws-ec2]
            [almonds.resource :as r :refer [validate create delete sanitize retrieve-raw-all aws-id retrieve-resource]]))

(def type-id :customer-gateway)

;; (def response-id-ks [:customer-gateway :customer-gateway-id])

;; (defrecord CustomerGateway [id-tag stack-id bgp-asn ip-address]
;;   Resource
;;   (retrieve-raw-all [this]
;;     (:customer-gateways (aws-ec2/describe-customer-gateways)))
;;   (sanitize [this]
;;     (dissoc this :state :customer-gateway-id :type :tags))
;;   (create [this]
;;     (let [request {:type "ipsec.1" :bgp-asn bgp-asn :public-ip ip-address}]
;;       (let [response (aws-ec2/create-customer-gateway request)]
;;         (r/add-tags (get-in response response-id-ks)
;;                     (merge this {:tags []})))))
;;   (aws-id [this]
;;     (:customer-gateway-id (r/retrieve-resource)))
;;   (delete [this]
;;     (aws-ec2/delete-customer-gateway {:customer-gateway-id (r/aws-id this)}))
;;   (validate [this] true)
;;   (cf [this]
;;     (r/to-json {id-tag {:type "AWS::EC2::CustomerGateway"
;;                       :properties {:bgp-asn bgp-asn
;;                                    :ip-address ip-address
;;                                    :tags [{:key "Name"
;;                                            :value id-tag}
;;                                           {:key "id-tag"
;;                                            :value id-tag}]}}})))

(defmacro defresource
  [{:keys [resource-type response-id-ks create-map create-fn validate-fn sanitize-ks describe-fn aws-id-key delete-fn]}]
  `(do
     (defmethod validate ~resource-type [m#]
       (~validate-fn m#))
     (defmethod create ~resource-type [m#]
       (let [response# (~create-fn (~create-map m#))]
         (r/add-tags (get-in response# ~response-id-ks)
                     (merge m# {:tags []}))))
     (defmethod sanitize ~resource-type [m#]
       (apply dissoc m# ~sanitize-ks))
     (defmethod retrieve-raw-all ~resource-type [_#]
       (-> (~describe-fn) vals first))
     (defmethod aws-id ~resource-type [m#]
       (~aws-id-key (retrieve-resource m#)))
     (defmethod delete ~resource-type [m#]
       (~delete-fn {~aws-id-key (aws-id m#)}))))



(macroexpand '(defresource  {:resource-type :customer-gateway
                             :response-id-ks [:customer-gateway :customer-gateway-id]
                             :create-map #(hash-map :type "ipsec.1" :bgp-asn (:bgp-asn %) :public-ip (:ip-address %))
                             :create-fn aws-ec2/create-customer-gateway
                             :validate-fn (constantly true)
                             :sanitize-ks [:state :customer-gateway-id :type :tags]
                             :describe-fn aws-ec2/describe-customer-gateways
                             :aws-id-key :customer-gateway-id
                             :delete-fn aws-ec2/delete-customer-gateway}))

;; (defmethod r/create :customer-gateway [m]
;;   (let [request {:type "ipsec.1" :bgp-asn (:bgp-asn m) :public-ip (:ip-address m)}]
;;     (let [response (aws-ec2/create-customer-gateway request)]
;;       (r/add-tags (get-in response response-id-ks)
;;                   (merge m {:tags []})))))

;; (defmethod r/validate :customer-gateway [m]
;;   true)

;; (defmethod r/sanitize :customer-gateway [m]
;;   (dissoc m :state :customer-gateway-id :type :tags))

;; (defmethod r/retrieve-raw-all :customer-gateway [_]
;;   (:customer-gateways (aws-ec2/describe-customer-gateways)))

;; (defmethod aws-id :customer-gateway [m]
;;   (:customer-gateway-id (r/retrieve-resource)))

;; (defmethod r/delete :customer-gateway [m]
;;   (aws-ec2/delete-customer-gateway {:customer-gateway-id (r/aws-id m)}))
