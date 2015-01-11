(ns embang.map
  (:require [embang.colt.distributions :as dist])
  (:require [clojure.data.priority-map
             :refer [priority-map-keyfn-by]])
  (:use [embang.state :exclude [initial-state]]
        [embang.runtime :only [sample observe]]
        embang.inference))

;;;;; Maximum a Posteriori Estimation through Sampling

;; Uses MCTS and best-first search to find maximum a
;; posteriori estimate of program trace.

(derive ::algorithm :embang.inference/algorithm)

;;;; Particle state

(def initial-state
  "initial state for MAP estimation"
  (into embang.state/initial-state
        {::bandits {}
         ::trace []}))

;;;; Bayesian updating, for randomized probability matching

(defprotocol bayesian-belief
  "Bayesian belief"
  (bb-update [belief evidence]
    "updates belief based on the evidence")
  (bb-sample [belief]
    "returns a random sample from the belief distribution")
  (bb-as-prior [belief]
    "returns a belief for use as a prior belief")
  (bb-mode [belief]
    "returns the mode of belief"))

;;;; Mean reward belief

(defn mean-reward-belief
  "returns reification of bayesian belief
  about the mean reward of an arm"
  [sum sum2 cnt]
  ;; Bayesian belief about the mean reward (log-weight).
  ;; Currently, the normal distribution with empirical
  ;; mean and variance is used.
  (let [dist (delay 
               ;; The distribution object is lazy because 
               ;; the parameters are updated many times,
               ;; but the object itself is only used when
               ;; a value is sampled.
               (let [mean (/ sum cnt)
                     sd (Math/sqrt (/ (- (/ sum2 cnt) (* mean mean))
                                      cnt))] ; Var(E(X)) = Var(X)/n
                 (dist/normal-distribution mean sd)))]
    (reify bayesian-belief
      (bb-update [mr reward]
        (mean-reward-belief
          (+ sum reward) (+ sum2 (* reward reward)) (+ cnt 1.)))
      (bb-sample [mr] {:pre [(pos? cnt)]}
        (dist/draw @dist))
      (bb-as-prior [mr]
        ;; The current belief is converted to a prior belief
        ;; by setting the sample count to 1 (another small value
        ;; may give better results, and the best value can be 
        ;; allegedly derived; however, this is beyond the scope
        ;; of this research).
        (if (<= cnt 1) mr
          (mean-reward-belief (/ sum cnt) (/ sum2 cnt) 1.)))
      (bb-mode [mr] (/ sum cnt)))))

(def initial-mean-reward-belief
  "uninformative mean reward belief"
  (mean-reward-belief 0. 0. 0.))

;;;; Bandit

(defrecord multiarmed-bandit [arms new-arm-belief])

(def fresh-bandit
  "bandit with no arm pulls"
  (->multiarmed-bandit {} initial-mean-reward-belief))

;; Selects arms using open randomized probability matching.

(defn select-arm
  "selects an arm with the best core,
  returns the arm value"
  [bandit]
  ;; If the best arm happens to be a new arm,
  ;; return nil. checkpoint [::algorithm sample]
  ;; accounts for this and samples a new value
  ;; from the prior.
  (loop [arms (:arms bandit)
         best-score (bb-sample (:new-arm-belief bandit))
         best-value nil]
    (if-let [[[value belief] & arms] (seq arms)]
      (let [score (bb-sample belief)]
        (if (>= score best-score)
          (recur arms score value)
          (recur arms best-score best-value)))
      best-value)))

(defn update-bandit
  "updates bandit's belief"
  [bandit value reward]
  (let [bandit (if (contains? (:arms bandit) value) bandit
                 ;; otherwise, the arm is new:
                 (-> bandit
                     ;; initialize it with the prior belief,
                     (assoc-in [:arms value]
                               (bb-as-prior
                                 (:new-arm-belief bandit)))
                     ;; and update the new arm belief.
                     (update-in [:new-arm-belief]
                                bb-update reward)))]
    ;; Update the belief about the mean reward of the sampled arm.
    (update-in bandit [:arms value] bb-update reward)))

;;;; MAP inference

;;; State transformations

(defn backpropagate
  "back propagate reward to bandits"
  [state]
  (let [reward (get-log-weight state)]
    (loop [trace (state ::trace)
           bandits (state ::bandits)]
      (if (seq trace)
        (let [[[id value past-reward] & trace] trace]
          (recur trace
                 (update-in bandits [id]
                            ;; Bandit arms grow incrementally.
                            ;; Fresh bandit has no arms, every time
                            ;; an arm's value is sampled for the first
                            ;; time, a new arm is added.
                            (fnil update-bandit fresh-bandit)
                            value (- reward past-reward))))
        (assoc initial-state ::bandits bandits)))))

;;; Trace

;; The trace is a vector of tuples
;;   [bandit-id value past-reward]
;; where past reward is the reward accumulated 
;; before reaching this random choice.

;; Bandit id: different random choices should get different
;; ids, ideally structurally similar random choices should
;; get the same id, just like addresses in Random DB

(defn preceding-occurences
  "number of preceding occurences of the same
  andom choice in the trace"
  [smp trace]
  (count 
   (filter (fn [[[smp-id]]] (= smp-id (:id smp)))
           trace)))

(defn bandit-id [smp trace]
  "returns bandit id for the checkpoint"
  [(:id smp) (preceding-occurences smp trace)])

;;; Building G_prog subgraph 

(defmethod checkpoint [::algorithm embang.trap.sample] [_ smp]
  (let [state (:state smp)
        id (bandit-id smp (state ::trace))
        bandit ((state ::bandits) id)
        ;; Select a value ...
        value (or (and bandit (select-arm bandit))
                  ;; ... or sample a new one.
                  (sample (:dist smp)))
        ;; Past reward is the reward collected upto
        ;; the current sampling point.
        past-reward (get-log-weight state)
        ;; Update the weight and the trace.
        state (-> state
                  (add-log-weight (observe (:dist smp) value))
                  (update-in [::trace] conj [id value past-reward]))]
    ;; Finally, continue the execution.
    #((:cont smp) value state)))

;;; Best-first search

;; A node is a thunk.  Nodes are inserted into the open list
;; ordered by the distance estimate.  When a node is removed
;; from the open list, it is executed, and then dispatched
;; according to its type --- sample or result.

(defrecord node [comp f])

(def node-key "node ordering key" :f)

(def node-less "node order" <)

;; The open list is a priority queue; all nodes are
;; unique because edge costs are functions of path
;; prefix.

(defrecord open-list [next-key queue])

(def empty-open-list
  "empty open list"
  (->open-list 0 (priority-map-keyfn-by node-key node-less)))

(defn ol-insert
  "inserts node to the open list"
  [ol node]
  (-> ol
      (update-in [:queue] #(conj % [(:next-key ol) node]))
      (update-in [:next-key] inc)))

(defn ol-pop
  "removes first node from the open list,
  returns the node and the list without the node,
  or nil if the open list is empty"
  [ol]
  (when (seq (:queue ol))
    (let [[key node] (peek (:queue ol))
          ol (update-in ol [:queue] pop)]
      [node ol])))

;; On sample, the search continues.
;; On result, a sequence starting with the state
;; and followed by a lazy sequence of states of future
;; found estimates is returned.
;;
;; When the open list is empty, nil is returned.

(derive ::search :embang.inference/algorithm)

(defmethod checkpoint [::search embang.trap.sample] [_ smp]
  smp)

(defmulti expand 
  "expands checkpoint nodes"
  (fn [cpt ol] (type cpt)))

(defn next-node
  "pops and advances the next node in the open list"
  [ol]
  #(when-let [[node ol] (ol-pop ol)]
     ;; The result of the computation is either a sample
     ;; or a result node. `expand' is a multimethod that 
     ;; dispatches on the node type.
     (expand ((:comp node)) ol)))

(def number-of-h-draws
  "atom containing the number of draws from
  the belief to compute distance heuristic"
  (atom 1))

(defn distance-heuristic
  "returns distance heuristic given belief"
  [belief]
  ;; Number of draws controls the properties of the
  ;; heuristic.
  (cond
    ;;  When the number of draws is positive,
    ;; increasing the number makes heuristic more
    ;; conservative, that is the heuristic approaches
    ;; admissibility.
    (pos? @number-of-h-draws)
    (let [h (- (reduce max (repeatedly @number-of-h-draws
                                       #(bb-sample belief))))
          h (if (Double/isNaN h) 0 (max h 0.))]
      h)

    ;;  When the number is 0, 0. is always
    ;; returned, so that best-first becomes Dijkstra search
    ;; and will always return the optimal solution first
    ;; if the edge costs are non-negative (that is, if
    ;; nodes are discrete, or continuous but the distributions
    ;; are not too steep. 
    (zero? @number-of-h-draws) 0.

    ;; A negative number of draws triggers
    ;; computing the heuristic as the mode of the belief
    ;; rather than by sampling.
    (neg? @number-of-h-draws) (bb-mode belief)))

(defmethod expand embang.trap.sample [smp ol]
  ;; A sample node is expanded by inserting all of the
  ;; child nodes into the open list. The code partially
  ;; repeats the code of checkpoint [::algorithm sample].
  (let [state (:state smp)
        id (bandit-id smp (state ::trace))
        bandit ((state ::bandits) id)
        ol (reduce
             ;; For every child of the latent variable
             ;; in the constructed subgraph of G_prog:
             (fn [ol [value belief]]
               ;; Update the state and the trace ...
               (let [past-reward (get-log-weight state)
                     state (-> state
                               (add-log-weight (observe (:dist smp) value))
                               (update-in [::trace]
                                          conj [id value past-reward]))
                     ;; ... and compute cost estimate till
                     ;; the termination.
                     f (+ (- past-reward)
                          (distance-heuristic belief))]
                 ;; If the distance estimate is 
                 ;; a meaningful number, insert the node
                 ;; into the open list.
                 (if-not (Double/isNaN f)
                   (ol-insert ol
                              (->node
                                #(exec ::search (:cont smp) value state)
                                f))
                   ol)))
             ol (seq (:arms bandit)))]
    ;; Finally, remove and expand the next node 
    ;; from the open list.
    (next-node ol)))

(defmethod expand embang.trap.result [res ol]
  (cons (:state res)                    ; return the first estimate
        (lazy-seq                       ; and a lazy sequence of 
         (trampoline (next-node ol))))) ; future estimates

(defn maximum-a-posteriori
  "returns a sequence of end states
  of maximum a posteriori estimates"
  [prog begin-state]
  (trampoline
   (expand (exec ::search prog nil begin-state)
           empty-open-list)))

(defmethod infer :map
  [_ prog & {:keys [number-of-passes  ; times to branch G_prog
                    number-of-samples ; samples before branching
                    number-of-maps    ; MAP estimates per branch
                    number-of-h-draws ; random draws to compute h
                    output-format    
                    results]          ; a set of :predicts, :trace
             :or {number-of-passes 1
                  number-of-maps 1
                  results #{:predicts :trace}}}]

  ;; allows to change number-of-h-draws from the command line;
  ;; useful for experimenting
  (when number-of-h-draws
    (swap! embang.map/number-of-h-draws (fn [_] number-of-h-draws)))
    
  (dotimes [_ number-of-passes]
    (loop [isamples 0
           begin-state initial-state]

      ;; After each sample, the final rewards are
      ;; back-propagated to the bandits representing subsets
      ;; of random choices.
      (let [end-state (:state (exec ::algorithm prog nil begin-state))
            begin-state (if-not (Double/isNaN (get-log-weight end-state))
                          (backpropagate end-state)
                          begin-state)]
        (if-not (= isamples number-of-samples)
          (recur (inc isamples) begin-state)

          ;; The program graph is ready for MAP search.
          ;; Consume the sequence of end-states of MAP
          ;; estimates and print the predicts.
          (loop [imaps 0
                 end-states (maximum-a-posteriori prog begin-state)]
            (when-not (= imaps number-of-maps)
              (let [[end-state & end-states] end-states]
                (when end-state  ; Otherwise, all paths were visited.
                  (when (contains? results :predicts)
                    (print-predicts end-state output-format))
                  (when (contains? (set results) :trace)
                    ;; Prints the trace as a special predict.
                    (print-predict '$trace
                                   (map second (::trace end-state))
                                   (Math/exp (get-log-weight end-state))
                                   output-format))
                  (recur (inc imaps) end-states))))))))))