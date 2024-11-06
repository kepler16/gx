(ns k16.gx.graph
  (:require
   [clojure.string :as str]
   [k16.gx.node :as gx.node]
   [k16.gx.traversal :as gx.traversal]))

(defn- try-subvec [v start end]
  (if (> (count v) end)
    (subvec v start end)
    v))

(defn- expand-ref-paths [ref-paths known-refs]
  (into #{}
        (mapcat
         (fn [ref-path]
           (let [ref-len (count ref-path)]
             (filter (fn [ref]
                       (= ref-path (try-subvec ref 0 ref-len)))
                     known-refs))))
        ref-paths))

(defn ref-graph [graph]
  (let [ref-graph
        (into {}
              (comp
               (filter (fn filter-refs [[_ node]]
                         (gx.node/is-node? node)))
               (map (fn into-paths [[k ref|inst]]
                      [k (gx.node/refs ref|inst)]))
               (map (fn remap-nearest [[k ref-paths]]
                      [k (into #{} (map #(gx.traversal/find-outer-node graph %)) ref-paths)])))
              (gx.traversal/path-seq graph))

        ref-keys (keys ref-graph)]

    (into {}
          (map (fn expand [[k ref-paths]]
                 [k (expand-ref-paths ref-paths ref-keys)]))
          ref-graph)))

(defn find-cyclic-refs [refs-graph]
  (letfn [(visit [node visited stack]
            (cond
              (stack node) (conj stack node)
              (visited node) nil
              :else (some #(visit % (conj visited node) (conj stack node))
                          (refs-graph node))))]
    (reduce
     (fn [cycles node]
       (if-let [cycle (visit node #{} #{})]
         (conj cycles cycle)
         cycles))
     #{}
     (keys refs-graph))))

(defn- group-dependency-stages
  [ref-graph]
  (let [stage
        (into #{}
              (comp
               (filter (fn [[_ ref-paths]]
                         (not
                          (some
                           (fn [ref-path]
                             (get ref-graph ref-path))
                           ref-paths))))
               (map first))
              ref-graph)

        remaining
        (reduce
         (fn [nodes [node-key node]]
           (if-not (contains? stage node-key)
             (let [deps (apply disj node stage)]
               (assoc nodes node-key deps))
             nodes))
         {}
         ref-graph)]

    (when (seq stage)
      (into [(-> stage sort vec)]
            (group-dependency-stages remaining)))))

(defn- sort-dependency-order
  [graph]
  (->> graph
       ref-graph
       group-dependency-stages))

(defn reduce-ordered
  ([reducer graph] (reduce-ordered reducer graph {}))
  ([reducer graph opts]
   (let [stages (cond-> (sort-dependency-order graph)
                  (= :reverse (:order opts)) reverse)]
     (loop [stage (first stages)
            remaining-stages (rest stages)
            acc graph]
       (let [result (reducer acc stage)
             next-stage (first remaining-stages)]
         (if (and (not (reduced? result))
                  next-stage)
           (recur next-stage
                  (rest remaining-stages)
                  result)
           (unreduced result)))))))

(defn cyclic-refs->errors [cyclic-refs]
  (->> cyclic-refs
       (reduce
        (fn [acc refs]
          (reduce
           (fn [acc ref]
             (update acc ref
                     (fn [errors]
                       (conj (or errors #{})
                             (str "Contains cyclic references with "
                                  (str/join ", " (disj refs ref)))))))
           acc
           refs))
        {})
       (map (fn [[k v]]
              [k (vec v)]))
       (into {})))
