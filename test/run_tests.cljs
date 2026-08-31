(ns run-tests
  "nbb entry point for the pure namespaces.

  The `.cljc` tests also run on the JVM through `clojure -M:test`; this is the
  fast path that needs no dependency resolution, and it is what the fleet gate
  runs (`:nbb-test`). `:end-run-tests` exits non-zero on failure — without it
  nbb reports the failures and exits 0, which is the shape of a test suite that
  can never fail a build."
  (:require [cljs.test :as t]
            [kagi.itonami.classify-test]
            [kagi.itonami.decide-test]
            [kagi.itonami.ledger-test]))

(defmethod t/report [::t/default :end-run-tests] [m]
  (println "\n" (:test m) "tests," (:pass m) "assertions,"
           (:fail m) "failures," (:error m) "errors")
  (when-not (t/successful? m) (js/process.exit 1)))

(t/run-tests 'kagi.itonami.classify-test
             'kagi.itonami.decide-test
             'kagi.itonami.ledger-test)
