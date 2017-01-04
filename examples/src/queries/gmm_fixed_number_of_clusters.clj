(ns queries.gmm-fixed-number-of-clusters
  (:require [anglican.runtime :refer :all]
            [anglican.emit :refer [defquery with-primitive-procedures]]
            [clojure.core.matrix :as m]
            [clojure.core.matrix.linear :as mlin]
            [helpers.gmm :refer [normalize move-to-unit-box gridify]]))

;; These are the hyperparameters for the model:
;; mu0		mean for the Gaussian prior over the cluster means
;; Sigma0	covariance for the Gaussian prior over the cluster means
(def hyperparameters {:mu-0 [0 0]
                      :Sigma-0 [[0.1 0] [0 0.1]]})

;; This is a hack in order to sample from a discrete distribution in such a way that it doesn't trigger adaptation of the proposal. This will not change the inference behaviour in particle-based algorithms since it is equivalent to having a bootstrap proposal
(defn sample-discrete [probs]
  (sample* (discrete probs)))

(with-primitive-procedures [normalize m/mmul m/identity-matrix sample-discrete mlin/norm]
  (defquery gmm-fixed-number-of-clusters [data hyperparameters]
    (let [num-clusters 3
          cluster-probs (normalize [1 1 1])
          means (repeatedly num-clusters
                            #(sample "mean" (mvn (:mu-0 hyperparameters) (:Sigma-0 hyperparameters))))
          means (sort-by mlin/norm means)
          vars [0.005 0.005 0.005]
          clusters (map #(let [cluster (sample-discrete cluster-probs)
                               mean (nth means cluster)
                               var (nth vars cluster)]
                           (observe (mvn mean (mmul var (identity-matrix 2))) %)
                           cluster)
                        data)]
      {:num-clusters num-clusters
       :clusters clusters
       :cluster-probs cluster-probs
       :means means
       :vars vars})))

(def num-data-points 100)
(def grid-dimensions [100 100])

;; Takes in samples from the prior, sorts them according to their norm, renumbers the time index and sample instance.
(defn COMPILE-combine-samples-fn [samples]
  (let [sorted-samples (sort-by (comp mlin/norm :value) samples)
        renumbered-samples (loop [res []
                                  smps sorted-samples]
                             (if (empty? smps)
                               res
                               (let [smp (first smps)
                                     fixed-smp (assoc smp
                                                 :time-index
                                                 (inc (count res)))
                                     fixed-smp (assoc fixed-smp
                                                 :sample-instance
                                                 (inc
                                                   (count
                                                     (filter #(= (:sample-address smp) (:sample-address %))
                                                             res))))]
                                 (recur (conj res fixed-smp)
                                        (rest smps)))))]
    renumbered-samples))

;; Takes in observes from the prior, puts the on the grid.
(defn COMPILE-combine-observes-fn [observes]
  (gridify (move-to-unit-box (mapv (comp vec :value) observes)) grid-dimensions))
(def COMPILE-query-args [(repeatedly num-data-points rand) hyperparameters])

;; Observations
(with-primitive-procedures [m/mmul m/identity-matrix]
  (defquery gmm-fixed [data cluster-probs means vars]
    (map #(let [cluster (sample "cluster" (discrete cluster-probs))
                mean (nth means cluster)
                var (nth vars cluster)]
            (observe (mvn mean (mmul var (identity-matrix 2))) %)
            cluster)
         data)))
(def smp (first (anglican.csis.prior/sample-from-prior gmm-fixed [(repeatedly num-data-points rand) [1 1 1] [[-0.3 -0.2] [0.1 0.1] [0.7 0.0]] [0.005 0.005 0.005]])))
(def data (map :value (:observes smp)))
(def ground-means (map :value (:samples smp)))

(def INFER-observe-embedder-input (gridify (move-to-unit-box data) grid-dimensions))
(def INFER-query-args [data hyperparameters])
