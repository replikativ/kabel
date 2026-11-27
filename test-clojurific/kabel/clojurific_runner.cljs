(ns kabel.clojurific-runner
  (:require [cljs.test :as t :refer-macros [run-tests]]
            [kabel.binary-table-test]
            [kabel.cbor-test]
            [kabel.fressian-test]
            [kabel.integration-test]
            [kabel.transit-test]
            [kabel.transport-test]))

;; Fail if an async test never reports completion. The integration peer can
;; keep a socket open after the suite finishes, so exit on the final report.
(set! (.-exitCode js/process) 1)
(defmethod t/report [::t/default :end-run-tests] [summary]
  (.exit js/process (if (t/successful? summary) 0 1)))

(run-tests 'kabel.binary-table-test 'kabel.transport-test 'kabel.cbor-test
           'kabel.fressian-test 'kabel.transit-test 'kabel.integration-test)
