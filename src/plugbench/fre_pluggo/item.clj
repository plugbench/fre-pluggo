(ns plugbench.fre-pluggo.item
  "Items are URLs.  Local files are file://host/path URLs, as the plumber
  makes them, so that hosts sharing a NATS server can share the store.  Their
  paths are kept unescaped, and directories end with a slash."
  (:require
   [clojure.string :as str])
  (:import
   (java.io File)
   (java.nio.file Paths)))

(defn url? [s]
  (boolean (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" s)))

(defn- percent-decode
  "Decodes %XX escapes only; a + is left alone, as it is in a path."
  [^String s]
  (if-not (str/includes? s "%")
    s
    (let [bytes (java.io.ByteArrayOutputStream.)]
      (loop [i 0]
        (if (>= i (count s))
          (.toString bytes "UTF-8")
          (let [c (.charAt s i)]
            (if-let [hex (and (= c \%)
                              (<= (+ i 3) (count s))
                              (re-matches #"[0-9a-fA-F]{2}" (subs s (inc i) (+ i 3))))]
              (do (.write bytes (Integer/parseInt hex 16))
                  (recur (+ i 3)))
              (do (.write bytes (.getBytes (str c) "UTF-8"))
                  (recur (inc i))))))))))

(defn normalize
  "The item as stored, or nil if it can't be one.  Visits to a place in a file
  are visits to the file."
  [item]
  (let [item (str/trim (or item ""))]
    (cond
      (str/blank? item)                nil
      (re-find #"[\r\n]" item)         nil
      (str/starts-with? item "file:")  (-> item (str/replace #"#.*" "") percent-decode)
      :else                            item)))

(defn path->url
  "A file URL for path on host, relative to dir.  Directories end with a slash,
  so that one is a prefix of the URLs of only the things in it."
  [host dir path]
  (let [file (File. (str (.normalize (.resolve (Paths/get dir (make-array String 0)) ^String path))))
        path (str file)]
    (str "file://" host
         path
         (when (and (.isDirectory file) (not (str/ends-with? path "/")))
           "/"))))

(defn ->url
  "The URL for something given on a command line, a URL or a path, or nil for
  nothing."
  [host dir s]
  (cond
    (str/blank? s) nil
    (url? s)       s
    :else          (path->url host dir s)))

(defn local-path
  "The path of a URL for a file on host, or nil if it isn't one."
  [host url]
  (let [prefix (str "file://" host "/")]
    (when (str/starts-with? url prefix)
      (subs url (dec (count prefix))))))
