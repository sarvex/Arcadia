(ns arcadia.packages
  (:require [clojure.string :as s]
            [clojure.edn :as edn]
            [arcadia.internal.leiningen :as lein]
            [arcadia.config :as config])
  (:import Newtonsoft.Json.JsonTextReader
           Newtonsoft.Json.Linq.JObject
           [Arcadia ProgressBar Shell]
           UnityEditor.EditorUtility
           UnityEngine.Debug
           [System.Diagnostics Process]
           [System.Xml XmlWriter XmlWriterSettings]
           [System.Text StringBuilder]
           [System.IO DirectoryInfo Directory File Path FileMode StreamReader]))

;;;; XML/CSPROJ Wrangling

(def ^:dynamic *xml-writer* nil)

(def settings
  (let [s (XmlWriterSettings.)]
    (set! (.Indent s) true)
    (set! (.OmitXmlDeclaration s) true)
    s))

(defmacro doc [& body]
  `(let [sb# (StringBuilder.)]
     (binding [*xml-writer* (XmlWriter/Create sb# settings)]
       (.WriteStartDocument *xml-writer*)
       ~@body
       (.WriteEndDocument *xml-writer*)
       (.Close *xml-writer*)
       (.ToString sb#))))

(defmacro elem [name & body]
  `(do
     (.WriteStartElement *xml-writer* ~name)
     ~@body
     (.WriteEndElement *xml-writer*)))

(defmacro attr [name value]
  `(.WriteAttributeString *xml-writer* ~name ~value))

(defmacro string [value]
  `(.WriteString *xml-writer* ~value))

(defn coords->csproj [data]
  (doc
   (elem "Project"
         (attr "Sdk" "Microsoft.NET.Sdk")
         (elem "PropertyGroup"
               (elem "OutputType" (string "Exe"))
               (elem "TargetFramework" (string "net461")))
         (elem "ItemGroup"
               (doseq [d data]
                 (elem "PackageReference"
                       (attr "Include" (str (first d)))
                       (attr "Version" (str (or (:nuget/version (last d))
                                                (throw (ex-info "Expected :nuget/version key in dependency map"
                                                                {:id (first d)
                                                                 :dependency d
                                                                 :dependencies data})))))))))))

;;;; NuGet & JSON wrangling

(def nuget-exe-path (Path/Combine "Assets" "Arcadia" "Infrastructure" "NuGet.exe"))
(def external-packages-folder (Path/Combine "Arcadia" "Libraries"))
(def internal-packages-folder (Path/Combine "Assets" "Arcadia" "Libraries"))
(def external-package-files-folder (Path/Combine external-packages-folder "Files"))
(def package-lock-folder (Path/Combine external-packages-folder "obj"))
(def package-lock-file (Path/Combine package-lock-folder "project.assets.json"))

(defn restore
  ([coords] (restore coords (fn [])))
  ([coords donefn]
   (let [csproj-xml (coords->csproj coords)
         csproj-file (Path/Combine external-packages-folder  (str (gensym "arcadia-packages" ) ".csproj"))]
     (Directory/CreateDirectory external-packages-folder)
     (spit csproj-file csproj-xml)
     (ProgressBar/Start {:title "Restoring Packages" :info "" :progress 0})
     (Shell/MonoRun nuget-exe-path
                    (str "restore " csproj-file " -PackagesDirectory " external-package-files-folder)
                    {:output (fn [s] (swap! ProgressBar/State assoc :info (.Trim s)))
                     :error (fn [s]
                              (ProgressBar/Stop)
                              (Debug/LogError s))
                     :done (fn []
                             (File/Delete csproj-file)
                             (donefn))}))))


(defn get-json-keys
  [^System.Collections.Generic.IDictionary|`2[System.String,Newtonsoft.Json.Linq.JToken]| o]
  (when o
    (.get_Keys o)))

(defn cp-r-info [from to]
  (doseq [dir (.GetDirectories from)]
    (cp-r-info dir (.CreateSubdirectory to (.Name dir))))
  (doseq [file (.GetFiles from)]
    (.CopyTo file (Path/Combine (.FullName to) (.Name file)) true)))

(defn cp-r [from to]
  (cp-r-info (DirectoryInfo. from)
             (DirectoryInfo. to)))

(defn install [destination]
  (let [json-path package-lock-file
        json (JObject/Parse (slurp json-path :encoding "utf8"))
        obj (.SelectToken json "$.targets['.NETFramework,Version=v4.6.1']")
        to-copy (apply hash-set
                        (mapcat (fn [k]
                                  (let [name+version (s/lower-case k)
                                        compile-obj (.. obj (GetValue k) (GetValue "compile"))
                                        runtime-obj (.. obj (GetValue k) (GetValue "runtime"))
                                        compile-files (remove #(= "_._" (Path/GetFileName %)) (get-json-keys compile-obj))
                                        runtime-files (remove #(= "_._" (Path/GetFileName %)) (get-json-keys runtime-obj))]
                                    (concat
                                     [(Path/Combine name+version "content")]
                                     (when runtime-obj
                                       (map #(Path/Combine name+version %) runtime-files))
                                     (when compile-obj
                                       (map #(Path/Combine name+version %) compile-files)))))
                                (get-json-keys obj)))
        total (count to-copy)
        current (volatile! 0)]
    (Directory/CreateDirectory destination)
    (doseq [f to-copy]
      (let [full-source (Path/Combine external-package-files-folder f)]
        (swap! ProgressBar/State assoc
               :title "Installing Packages"
               :info f
               :progress (float (/ @current total)))
        (vswap! current inc)
        (cond
          (Directory/Exists full-source)
          (cp-r (Path/Combine external-package-files-folder f) destination)
          (File/Exists full-source)
          (File/Copy full-source
                     (Path/Combine destination (Path/GetFileName f))
                     true))))
    (ProgressBar/Stop)))

(defn clean [dir]
  (let [di (DirectoryInfo. dir)]
    (doseq [file (.EnumerateFiles di)]
      (.Delete file))
    (doseq [file (.EnumerateDirectories di)]
      (.Delete file true))))

(defn lein-coords->deps-map [coord]
  (hash-map (first coord) {:nuget/version (last coord)}))

(defn restore-from-config []
  (clean internal-packages-folder)
  (if (Directory/Exists package-lock-folder)
    (Directory/Delete package-lock-folder true))
  (let [user-deps (:dependencies (config/config))
        lein-deps (->> (lein/all-project-data)
                       (mapcat #(get-in % [::lein/defproject ::lein/dependencies]))
                       (map lein-coords->deps-map)
                       (apply merge))
        dependencies (->> (merge user-deps lein-deps))]
    (restore dependencies (fn [] (install internal-packages-folder)))))

(defn clean-libraries []
  (clean internal-packages-folder))

(defn clean-cache []
  (clean external-packages-folder))

;;;; package & publish

(def external-publish-folder (Path/Combine external-packages-folder "Publish"))

(defn nuspec [data dependencies skip-content?]
  (doc
   (elem "package"
         (elem "metadata"
               (doseq [[k v] data]
                 (elem (name k) (string (str v))))
               (when dependencies
                 (elem "dependencies"
                       (doseq [d dependencies]
                         (elem "dependency"
                               (attr "id" (str (first d)))
                               (attr "version" (str (or (:nuget/version (last d))
                                                        (throw (ex-info "Expected :nuget/version key in dependency map"
                                                                        {:id (first d)
                                                                         :dependency d
                                                                         :dependencies dependencies}))))))))))
         (when-not skip-content?
           (elem "files"
                 (elem "file"
                       (attr "src" "content/**/*.clj")
                       (attr "target" "content")))))))

(defn pack [dir
            {:keys [id version source aot metadata dependencies framework]
             :or {framework "net46"}}
            donefn]
  (when-not id (throw (ex-info "missing required key" {:key :id})))
  (when-not version (throw (ex-info "missing required key" {:key :version})))
  (when (and (not source)
             (not aot))
    (throw (ex-info "missing required key" {:key [:source :aot]})))
  (when-not (Directory/Exists external-publish-folder)
    (Directory/CreateDirectory external-publish-folder))
  (let [temp-dir (Path/Combine external-publish-folder (str id "." version (gensym "-publish")))
        nuspec-path (Path/Combine temp-dir (str id "." version ".nuspec"))
        nuspec (nuspec
                (merge {:id id :version version} metadata)
                dependencies
                (nil? source))]
    (Directory/CreateDirectory temp-dir)
    (when aot
      (let [lib-folder (Path/Combine temp-dir "lib" framework)]
        (Directory/CreateDirectory (Path/Combine temp-dir "lib"))
        (Directory/CreateDirectory lib-folder)
        (binding [*compile-path* lib-folder]
          (doseq [ns aot]
            (compile ns)))))
    (when source
      (let [content-folder (Path/Combine temp-dir "content")]
        (Directory/CreateDirectory content-folder)
        (doseq [path source]
          (cp-r path content-folder))))
    (spit nuspec-path nuspec)
    (Shell/MonoRun nuget-exe-path
                   (str "pack " nuspec-path " -OutputDirectory " dir)
                   {:output (fn [s] (swap! ProgressBar/State assoc :info (.Trim s)))
                    :error (fn [s]
                             (ProgressBar/Stop)
                             (Debug/LogError s))
                    :done donefn})))

(defn push [path donefn]
  (Shell/MonoRun nuget-exe-path
                 (str "push " path " -Source nuget.org")
                 {:output (fn [s] (swap! ProgressBar/State assoc :info (.Trim s)))
                  :error (fn [s]
                           (ProgressBar/Stop)
                           (Debug/LogError s))
                  :done donefn}))

(defn publish [{:keys [id version] :as spec}]
  (pack external-publish-folder spec
        (fn []
          (push (Path/Combine external-publish-folder (str id "." version ".nupkg"))
                (fn []
                  ;; TODO clean up here
                  )))))

;;;; config / api key management

(def config-path (Path/Combine external-packages-folder "NuGet.config"))

(def default-config
  (doc
   (elem "configuration"
         (elem "packageSources"
               (elem "add"
                     (attr "key" "nuget.org")
                     (attr "value" "https://api.nuget.org/v3/index.json")
                     (attr "protocolVersion" "3"))))))

(defn ensure-config []
  (when-not (File/Exists config-path)
    (spit config-path default-config)))

(defn reset-config []
  (ensure-config)
  (File/Delete config-path)
  (spit config-path default-config))

(defn set-api-key [key]
  (ensure-config)
  (Shell/MonoRun nuget-exe-path
                 (str "setApiKey " key " -ConfigFile " config-path)
                 {:output (fn [s] (swap! ProgressBar/State assoc :info (.Trim s)))
                  :error (fn [s]
                           (ProgressBar/Stop)
                           (Debug/LogError s))}))

;;;; high level

(defn ->package-map [path]
  (cond
    (.EndsWith path "deps.edn")
    (let [project-data (-> path
                           (slurp :enc "utf8")
                           edn/read-string)
          project-dir (-> path
                          Path/GetDirectoryName)]
      {:dependencies
       (:deps project-data)
       :source (->> project-data
                    :paths
                    (map #(Path/Combine project-dir %))
                    vec)})
    (.EndsWith path "project.clj")
    (let [project-data (-> path
                           Path/GetDirectoryName
                           lein/project-data)]
      {:dependencies
       (->> project-data
            ::lein/defproject
            ::lein/dependencies
            (map lein-coords->deps-map)
            (apply merge))
       :source
       (lein/project-data-loadpath project-data)})
    :else
    (throw (ex-info "Unsupported project file"
                    {:file path}))))

(defn publish-project [path]
  (-> path ->package-map publish))
