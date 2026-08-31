(ns kagi.itonami.inline
  "Compile-time resource inlining for the Worker build.

  `jp-go-dds.page/->page` takes the stylesheet as a string so it can stay a
  pure function; on the JVM the caller slurps it. A Worker has no filesystem,
  and the stylesheet lives in a git dependency rather than in this repository,
  so it is read from the classpath while shadow-cljs compiles and shipped as a
  literal. One source of truth, one copy in the bundle, no vendored duplicate
  to drift the next time jp-go-dds is bumped.

  `.cljc` rather than `.clj`: a new production `.clj` is refused workspace-wide
  (ADR-2608201300). Macros run on the JVM either way, so the whole namespace
  sits inside one reader conditional."
  #?(:clj (:require [clojure.java.io :as io])))

#?(:clj
   (defmacro inline-resource
     "The contents of classpath resource `path`, as a literal.

  Throws at compile time when it is missing. Returning nil would ship a Worker
  that serves every page unstyled, and an unstyled page reads as a CSS bug
  rather than as the build failure it is."
     [path]
     (if-let [r (io/resource path)]
       (slurp r)
       (throw (ex-info (str "resource not on the classpath: " path
                            " — the Worker cannot be built without it")
                       {:path path})))))

#?(:clj
   (defmacro inline-file
     "The contents of a file at `path`, relative to the project root, as a
  literal.

  `blueprint.edn` lives at the repository root because that is where every
  other cloud-itonami blueprint lives and where tooling looks for it. Copying
  it under `resources/` to make it classpath-visible would create a second
  blueprint that is authoritative for the Worker and stale everywhere else, so
  the one copy is read from where it belongs. Same compile-time failure: a
  missing blueprint is a build error, not an endpoint that quietly serves
  nothing."
     [path]
     (let [f (io/file path)]
       (if (.exists f)
         (slurp f)
         (throw (ex-info (str "file not found: " path
                              " — the Worker cannot be built without it")
                         {:path path}))))))
