(ns plugbench.fre-pluggo.daemon
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [plugbench.fre-pluggo.item :as item]
   [plugbench.nats :as nats]))

(def add-subject "cmd.add.frecent")
(def get-subject "cmd.get.frecent")

(def kept-items 10000)
(def adds-between-truncations 100)

(defn default-store []
  (str (fs/path (or (System/getenv "XDG_STATE_HOME")
                    (fs/path (fs/home) ".local" "state"))
                "fre-pluggo"
                "store.json")))

(defn fre-store
  "A function which runs fre with arguments against the store file, returning
  its output."
  [store]
  (fs/create-dirs (fs/parent store))
  (fn [& args]
    (let [{:keys [exit out err]} (apply p/shell {:out :string, :err :string, :continue true}
                                        "fre" "--store_name" store args)]
      (when-not (zero? exit)
        (throw (ex-info (str "fre: " (str/trim err)) {:args args})))
      out)))

(defn- fit
  "As many of the lines as fit into max-bytes, joined."
  [lines max-bytes]
  (loop [[line & more] lines
         size 0
         kept []]
    (let [size' (when line (+ size (count (.getBytes ^String line "UTF-8")) 1))]
      (if (or (nil? line) (> size' max-bytes))
        (str/join (map #(str % "\n") kept))
        (recur more size' (conj kept line))))))

(defn handle
  "The reply to a message.  fre runs fre against the store."
  [{:keys [fre max-payload adds]} {:keys [subject data]}]
  (condp = subject
    add-subject (if-let [item (item/normalize data)]
                  (do
                    (fre "--add" item)
                    (when (zero? (mod (swap! adds inc) adds-between-truncations))
                      (fre "--truncate" (str kept-items)))
                    "ok")
                  (str "ERROR: not something to visit: " (pr-str data)))
    get-subject (let [prefix (or (item/normalize data) "")]
                  (-> (fre "--sorted")
                      str/split-lines
                      (->> (remove str/blank?)
                           (filter #(str/starts-with? % prefix)))
                      (fit max-payload)))
    (str "ERROR: not a subject this answers: " subject)))

(defn run [{:keys [store]}]
  (let [conn (nats/connect {:name "fre-pluggo"})
        state {:fre         (fre-store store)
               :max-payload (nats/max-payload conn)
               :adds        (atom 0)}]
    (nats/subscribe conn add-subject)
    (nats/subscribe conn get-subject)
    (loop []
      (let [msg (nats/next-msg conn)
            reply (try
                    (handle state msg)
                    (catch Exception e
                      (str "ERROR: " (ex-message e))))]
        (when (str/starts-with? reply "ERROR: ")
          (binding [*out* *err*]
            (println reply)))
        ;; The plumber visits without waiting for an answer.
        (when (:reply msg)
          (nats/publish conn (:reply msg) reply))
        (recur)))))
