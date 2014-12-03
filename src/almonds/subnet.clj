;; (ns almonds.subnet
;;   (:require [amazonica.aws.ec2 :as aws-ec2]
;;             [almonds.resource :as r :refer [Resource]]))

;; (defrecord Subnet [almonds-tags zone cidr vpc tags]
;;   Resource
;;   (retrieve-all [this]
;;     (:subnets (aws-ec2/describe-subnets)))
;;   (create [this]
;;     (let [m (aws-ec2/create-subnet {:availability-zone zone :cidr-block cidr :vpc-id (r/aws-id vpc)})]
;;       (r/create-tags (get-in m [:subnet :subnet-id]) tags)))
;;   (tf [this]
;;     (r/to-json {"resource" {"aws_subnet" {almonds-tags
;;                                           {"vpc_id" (r/tf-id vpc)
;;                                            "cidr_block" cidr
;;                                            "availability_zone" zone}}}})))