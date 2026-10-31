(ns plugbench.fre-pluggo.main
  (:require
   [babashka.cli :as cli]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [plugbench.fre-pluggo.daemon :as daemon]
   [plugbench.fre-pluggo.item :as item]
   [plugbench.nats :as nats]))

(def help (str/triml "
Syntax:
  fre-pluggo SUBCOMMAND [OPTIONS...]

Subcommands:
  add [-w DIR] ITEM...        Record a visit to each ITEM, a URL or a path
                              relative to DIR or else the working directory.

  list [-w DIR] [-p] [ITEM]   Print what has been visited, most frecent first,
                              or only what starts with ITEM.  With -p, print
                              only this host's files, as paths.

  daemon [--store FILE]       Keep the visits in FILE, answering cmd.add.frecent
                              and cmd.get.frecent.
"))

(def timeout-ms 3000)

(defn- fail [& message]
  (binding [*out* *err*]
    (println (str "fre-pluggo: " (apply str message))))
  (System/exit 1))

(defn hostname []
  (str/trim (:out (p/shell {:out :string} "uname" "-n"))))

(defn- reply-or-fail [reply]
  (if (str/starts-with? reply "ERROR: ")
    (fail (subs reply 7))
    reply))

(defn- with-connection [f]
  (let [conn (try
               (nats/connect {:name "fre-pluggo"})
               (catch Exception e
                 (fail "connecting to NATS: " (ex-message e))))]
    (try
      (f conn)
      (catch clojure.lang.ExceptionInfo e
        (fail (ex-message e)))
      (finally
        (nats/close conn)))))

(defn- add [{:keys [opts args]}]
  (let [host (hostname)
        dir (or (:w opts) (str (fs/cwd)))]
    (with-connection
      (fn [conn]
        (doseq [arg args]
          (reply-or-fail (nats/request conn daemon/add-subject (or (item/->url host dir arg) "") timeout-ms)))))))

(defn- list-items [{:keys [opts args]}]
  (when (> (count args) 1)
    (fail "list takes at most one ITEM"))
  (let [host (hostname)
        dir (or (:w opts) (str (fs/cwd)))
        prefix (if-let [arg (first args)] (item/->url host dir arg) "")]
    (with-connection
      (fn [conn]
        (let [reply (reply-or-fail (nats/request conn daemon/get-subject prefix timeout-ms))]
          (doseq [url (str/split-lines reply)
                  :let [out (if (:p opts) (item/local-path host url) url)]
                  :when out]
            (println out)))))))

(defn- run-daemon [{:keys [opts]}]
  (daemon/run {:store (or (:store opts) (daemon/default-store))}))

(defn- usage [_]
  (print help)
  (flush)
  (System/exit 1))

(def table
  [{:cmds ["add"]    :fn add        :spec {:w {:coerce :string}}}
   {:cmds ["list"]   :fn list-items :spec {:w {:coerce :string}, :p {:coerce :boolean}}}
   {:cmds ["daemon"] :fn run-daemon :spec {:store {:coerce :string}}}
   {:cmds []         :fn usage}])

(defn -main [& args]
  (cli/dispatch table args)
  (shutdown-agents))
