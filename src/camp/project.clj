(ns camp.project
  "Functions for doing things with the project."
  (:require [clojure.walk :as walk]
            [clojure.pprint :as pp]
            [camp.core :refer [debug warn]]
            [camp.io :as io])
  (:import [System.IO Path Directory]))

(declare unquote-project)

(defn- unquote-step
  "We want to allow some code inside the project file, but we don't want errors
  when the project file uses a symbol that is not defined yet. For example:

  (defproject ...
  :deps [[SomeDep \"0.0.0\"]])

  In this case, SomeDep is not a symbol that needs to be defined."
  [arg]
  (cond
    ;; seq starting with unquote? elide the unquote and let it get
    ;; eval'd.
    (and (seq? arg) (= `unquote (first arg)))
    (second arg)

    ;; Other seq or symbol? quote it
    (or (seq? arg) (symbol? arg))
    (list 'quote arg)

    ;; not seq or symbol? recurse
    :else
    (let [result (unquote-project arg)]
      (if-let [m (meta arg)]
        ;; clojure.walk strips metadata
        (with-meta result m)
        result))))

(defn- unquote-project
  "Carefully unquotes/quotes things in the project."
  [args]
  (walk/walk unquote-step identity args))

;; Default project attributes. Actually specified values are merged into this,
;; replacing defaults.
(def project-defaults
  {:name ""
   :version "0.1.0-SNAPSHOT"
   :safe-name ""
   :target-framework "net45"
   :nuget-repository "https://nuget.org/api/v2"
   :deploy-repository "https://nuget.org/api/v2"
   :root (io/current-directory)
   :packages-path "packages"
   :source-paths ["src"]
   :targets-path "targets"
   :dependencies [['Clojure "1.6.0.1"]]})

(defmacro defproject
  "Defines a project."
  [project-name version & args]
  `(let [args# ~(merge project-defaults
                       (apply hash-map (unquote-project args))
                       {:name (str project-name)
                        :version version
                        :root (io/directory *file*)})]
     (def ~'project args#)))

(defn eval-project [file-name]
  (binding [*ns* (find-ns 'camp.project)]
    (debug "Loading project" file-name)
    (load-file file-name))
  (let [project (resolve 'camp.project/project)]
    (debug "ns-unmapping project")
    (ns-unmap 'camp.project 'project)
    (with-open [w (System.IO.StringWriter.)]
      (pp/pprint @project w)
      (debug "Project:")
      (debug w))
    @project))

(defn read-project
  "Read the project file in the current directory or some parent directory."
  ([] (read-project "project.clj"))
  ([project-file-name]
   (loop [dir (Directory/GetCurrentDirectory)]
     (let [proj-file (io/file dir project-file-name)
           parent (Path/GetDirectoryName dir)]
       (cond
         (io/file-exists? proj-file)
         (do
           (debug "found project:" proj-file)
           (eval-project proj-file))

         (nil? parent)
         (debug "no project file found")

         :otherwise
         (do
           (debug "No" project-file-name "in" dir)
           (recur (Path/GetDirectoryName dir))))))))

(defn resolve-relative-path
  "Given a path, if it's not rooted, combine it with the project's root path."
  [{:keys [root] :as project} & maybe-relative-path]
  (let [path (apply io/file maybe-relative-path)]
    (if (io/rooted? path)
      path
      (io/file root path))))

(defn find-target
  [{:keys [targets-path] :as proj} name]
  (let [targets (resolve-relative-path proj targets-path)]
    (->> [(io/file targets (str name ".dll"))]
         (filter io/file-exists?)
         first)))

(defn resolve-assembly
  [proj name]
  (if-let [file (find-target proj name)]
    (do
      (debug "Resolved" file)
      (System.Reflection.Assembly/LoadFrom file))
    (warn "Failed to resolve" name)))

(defn resolve-assembly-delegate
  [proj]
  (gen-delegate
   ResolveEventHandler [_ event-args]
   (resolve-assembly proj (.Name event-args))))

(defmacro with-assembly-resolution
  "Eval forms with the AppDomain setup to resolve assemblies from
  the targets folder."
  [proj & forms]
  `(let [resolver# (resolve-assembly-delegate ~proj)]
     (.add_AssemblyResolve (AppDomain/CurrentDomain) resolver#)
     (try
       ~@forms
       (finally
         (.remove_AssemblyResolve (AppDomain/CurrentDomain) resolver#)))))
