(ns odg.expression
  (:require clojure.java.io
            clojure.string
            [clojure.core.reducers :as r]
            criterium.core
            [biotools.fpkm-tracking :as fpkm-tracking]
            [odg.util :as util]
            [odg.db :as db]
            [odg.batch :as batch]
            [odg.stats :as stats]
            [biotools.gtf :as gtf]
            [taoensso.timbre :as timbre]
            [odg.db-handler :as dbh]
            [incanter.stats :as istats]
            [me.raynes.fs :as fs]
            [clojure.math.combinatorics :as combo])
  (:import
    (org.neo4j.unsafe.batchinsert BatchInserter
                                  BatchInserters
                                  BatchInserterIndexProvider
                                  BatchInserterIndex)))

(timbre/refer-timbre)

(set! *warn-on-reflection* true)

(def total (atom 0))

(defn count-correlation
  []
  (swap! total + 1)
  (when (zero? (rem @total 1000000))
    (println @total "correlations recorded")
    (info @total "correlations recorded")))

(defn- -filter
  [f entry]
  (some 
    f
    (map :FPKM (vals (:conditions entry)))))

(defn- -get-correlations-xloc
  [[x y]]
  [(:gene_id x) (:gene_id y) (stats/pearson (:FPKMS x) (:FPKMS y)) (istats/spearmans-rho (:FPKMS x) (:FPKMS y))])

(defn- -get-correlations-refid
  [[x y]]
  [(:nearest_ref_id x) (:nearest_ref_id y) (stats/pearson (:FPKMS x) (:FPKMS y)) (istats/spearmans-rho (:FPKMS x) (:FPKMS y))])

(defn -worker
  ([pearson-correlation-absolute-cutoff x] (-worker pearson-correlation-absolute-cutoff x -get-correlations-xloc))
  ([pearson-correlation-absolute-cutoff x correlations-fn]
    (r/foldcat
      ;(r/filter (fn [y] (> (util/dabs (nth y 2)) pearson-correlation-absolute-cutoff))
      (r/filter (fn [y] ; Only look at positive correlations again -- TODO: Make configurable
                  (or 
                    (> (nth y 2) pearson-correlation-absolute-cutoff)
                    (> (nth y 3) pearson-correlation-absolute-cutoff))) ; Spearman
                (r/map correlations-fn x)))))
              
;  (filter
;    #(> (util/dabs (nth % 2)) pearson-correlation-absolute-cutoff)
;    (map -get-pearson x)))

; TODO: Support importing isoform.fpkm_tracking files!
(defn- -parse-gtf-xloc-translations
  [file]
  (with-open [rdr (clojure.java.io/reader file)]
    (let [lines (group-by :gene_id (gtf/parse-reader-reducer rdr))]
      (apply 
        merge
        (doall
          (for [xloc (keys lines)]
            {
             xloc
             (let [line (first (get lines xloc))]
               (if (:gene_name line)
                 (:gene_name line)
                 (util/remove-transcript-id 
                   (:oid 
                     (first 
                       (get lines xloc))))))}
            ))))))

(defn condition-nodes-map
  [conditions]
  
  (info "Creating / updating condition nodes")
  
  (dbh/submit-batch-job-and-wait
    {:indices ["main"]
     :nodes-update-or-create 
           (for [condition conditions]
             [{:id condition
               :note "Autogenerated"}
              [(batch/dynamic-label "ExpressionCondition")]])
     })
  
  (info "Getting condition node IDs")
  
  (into 
    {}
    (dbh/batch-get-data
      {:index "main"
       :action :query
       :query conditions})))

; TODO: Need to make cli fn
(defn parse
  [config species version file gtf-file]

  ; First get the GTF entries so we can map XLOC to the real gene id's
  
  (info "Processing expression for " species " " version)

  (with-open [rdr (clojure.java.io/reader file)]
    (let [
          ;; Items from config
          fpkm-minimum (read-string (get-in config [:global :fpkm_minimum]))
          
          pearson-correlation-absolute-cutoff (get-in config [:global :pearson_correlation_absolute_cutoff])

          ; Function to filter out entries whose FPKM's are below a minimum threshold
          ; as defined in the config.json file
          ; -filter is its own function as well!
          filter-fn        (partial -filter (fn [x] (> x fpkm-minimum)))
          
          ; All the expression data, filtered and lazy
          expression-data    (filter filter-fn (fpkm-tracking/parse-reader rdr))
          
          condition-nodes    (condition-nodes-map (keys (:conditions (first expression-data))))
          
          ;; Expression specific stuff
          
          ; Get translation table for XLOC to oid for cufflinks
          translations (-parse-gtf-xloc-translations gtf-file)
          
          ]
      
      (doseq [batch (partition-all 100000 expression-data)]
        (dbh/submit-batch-job
          {:indices [(batch/convert-name species version)]
           :rels (doall
                   (for [exp batch
                         condition-expression (filter #(> (:FPKM (val %)) fpkm-minimum) (:conditions exp))
                         :let [node (get translations (:gene_id exp))
                               condition-node (get condition-nodes (key condition-expression))
                               fpkm (:FPKM (val condition-expression))
                               ]]
                     [(:EXPRESSED db/rels) node condition-node {:FPKM fpkm}]))}
          ))
      
      ; Store expression correlations
      (info "Storing expression correlations")
      
      (if (fs/exists? (str "data/results/expression_correlations/" 
                             (batch/convert-name species version) "_expression_correlation.tsv"))
        (with-open [rdr (clojure.java.io/reader 
                          (str "data/results/expression_correlations/" 
                               (batch/convert-name species version) "_expression_correlation.tsv"))]
          (doseq [batch (partition-all 1000000 (line-seq rdr))]
            (dbh/submit-batch-job
              {:indices [(batch/convert-name species version)]
               :rels (doall
                       (for [[x y pearson spearman] (map (fn [x] (clojure.string/split x #"\t")) batch)]
                         [(:EXPRESSION_CORRELATION db/rels) 
                          x 
                          y 
                          {"pearson_correlation" (read-string pearson) "spearman_correlation" (read-string spearman)}]
                         ))})))
        (warn "Correlation files not found -- Not stored for " species " " version))
              
      (info "Expression import complete: " species " " version))))

;(defn parse-cli
;  [config opts args]
;  
;  ; [config species version file gtf-file]
;  
;  (batch/connect (get-in config [:global :db_path]) (:memory opts) batch/fast-shutdown)
;  (parse config (:species opts) (:version opts) (first args) (second args)))
;
; Calculate to a file -- do not write to the database yet!
(defn calculate-correlations
  [config species version file gtf-file]
  
  (info "Calculating correlations...")
  
  (profile 
    :info :calculate-correlations
    
    (with-open [rdr (clojure.java.io/reader file)]
      (let [
            ;; Items from config
            fpkm-minimum (read-string 
                           (get-in config [:global :fpkm_minimum]))
            
            pearson-correlation-absolute-cutoff (read-string 
                                                  (get-in config [:global :pearson_correlation_absolute_cutoff]))

            ;; Expression specific stuff
            ; Function to filter out entries whose FPKM's are below a minimum threshold
            ; as defined in the config.json file
            ; -filter is its own function as well!
            filter-fn (fn [y] (-filter (fn [x] (> x fpkm-minimum)) y))

            ; All the expression data, filtered and lazy
            expression-data (filter filter-fn (fpkm-tracking/parse-reader rdr))
            ; expression-data (fpkm-tracking/parse-reader rdr)

            ; Get translation table for XLOC to oid for cufflinks
            translations (p :get-translations (-parse-gtf-xloc-translations gtf-file))
            
            translate (if 
                        (re-find #"isoforms" file) identity
                        (fn [x] (get translations x)))
            
            worker-fn     (fn [xs] (-worker pearson-correlation-absolute-cutoff (into [] xs)
                                            (if 
                                              (re-find #"iso" file) -get-correlations-refid
                                              -get-correlations-xloc)))

            ; Lazy seq for correlation calculation
            correlations  (pmap ; previously pmap
                                worker-fn
                                (partition-all
                                  1000000
                                  (combo/combinations expression-data 2)))
            
            ]
        
        (info "Storing expression correlations to a file")
        (with-open [wrtr (clojure.java.io/writer 
                           (str 
                             "data/results/expression_correlations/" 
                             (batch/convert-name species version)
                             (when (re-find #"iso" file) "_isoforms")
                             "_expression_correlation.tsv"))]
          
          (doseq [batch correlations]
            (doseq [[x y pearson spearman] batch]
              (count-correlation)
              (.write wrtr 
                (clojure.string/join 
                  "\t"
                  [(translate x)
                   (translate y)
                   (float pearson)
                   (float spearman)]))
              (.write wrtr "\n"))))
        
        
        )))
  (info "Expression correlations stored."))


