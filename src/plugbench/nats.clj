(ns plugbench.nats
  "Just enough of the NATS client protocol for a pluggo: connecting with a
  token, subscribing, publishing, and requests."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   (java.io BufferedInputStream BufferedOutputStream ByteArrayOutputStream InputStream OutputStream)
   (java.net Socket SocketTimeoutException URI)
   (java.nio.charset StandardCharsets)
   (java.util UUID)))

(def default-url "nats://127.0.0.1:4222")

(defn- utf8-bytes ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- utf8-string
  ([^bytes b] (String. b StandardCharsets/UTF_8))
  ([^bytes b offset length] (String. b (int offset) (int length) StandardCharsets/UTF_8)))

(defn- read-control-line
  "The next CRLF-terminated line, without the CRLF, or nil at end of stream."
  [^InputStream in]
  (let [buf (ByteArrayOutputStream.)]
    (loop [previous -1]
      (let [b (.read in)]
        (cond
          (neg? b)                         nil
          (and (= previous 13) (= b 10))   (let [line (.toByteArray buf)]
                                             (utf8-string line 0 (dec (alength line))))
          :else                            (do (.write buf b)
                                               (recur b)))))))

(defn- read-payload ^bytes [^InputStream in n]
  (let [payload (.readNBytes in (int n))]
    (when (< (alength payload) n)
      (throw (ex-info "NATS connection closed in the middle of a message" {})))
    (.readNBytes in 2)
    payload))

(defn- parse-headers
  "Status and headers from a NATS/1.0 header block."
  [^String block]
  (let [[status-line & lines] (str/split-lines block)
        [_ status description] (re-matches #"NATS/1\.0(?: (\d+)(?: (.*))?)?" status-line)]
    (cond-> {:headers (into {}
                            (keep (fn [line]
                                    (when-let [[_ k v] (re-matches #"([^:]+):\s*(.*)" line)]
                                      [k v])))
                            lines)}
      status      (assoc :status (parse-long status))
      description (assoc :description description))))

(defn read-op
  "The next operation from the server, as a map with an :op, or nil at end of
  stream."
  [^InputStream in]
  (when-let [line (read-control-line in)]
    (let [[op & args] (str/split line #"\s+" 2)
          args (some-> (first args) str/trim)]
      (case (str/upper-case op)
        "INFO" {:op :info, :info (json/parse-string args true)}
        "PING" {:op :ping}
        "PONG" {:op :pong}
        "+OK"  {:op :ok}
        "-ERR" {:op :err, :message (str/replace (or args "") #"^'|'$" "")}
        "MSG"  (let [fields (str/split args #"\s+")
                     [subject sid reply] fields
                     size (parse-long (last fields))]
                 {:op      :msg
                  :subject subject
                  :sid     sid
                  :reply   (when (= 4 (count fields)) reply)
                  :data    (utf8-string (read-payload in size))})
        "HMSG" (let [fields (str/split args #"\s+")
                     [subject sid reply] fields
                     header-size (parse-long (nth fields (- (count fields) 2)))
                     size (parse-long (last fields))
                     payload (read-payload in size)]
                 (merge {:op      :msg
                         :subject subject
                         :sid     sid
                         :reply   (when (= 5 (count fields)) reply)
                         :data    (utf8-string payload header-size (- size header-size))}
                        (parse-headers (utf8-string payload 0 header-size))))
        {:op :unknown, :line line}))))

(defn- write! [{:keys [^OutputStream out]} & parts]
  (locking out
    (doseq [part parts]
      (.write out (if (bytes? part) ^bytes part (utf8-bytes (str part)))))
    (.flush out)))

(defn- next-op
  "The next operation which isn't housekeeping, answering pings on the way."
  [conn]
  (loop []
    (let [op (read-op (:in conn))]
      (case (:op op)
        nil           (throw (ex-info "NATS connection closed" {}))
        :ping         (do (write! conn "PONG\r\n")
                          (recur))
        (:ok :info)   (recur)
        :err          (throw (ex-info (str "NATS: " (:message op)) {}))
        op))))

(defn connect
  "Connects to the NATS server at :url, by default $NATS_URL or else the local
  one, with :token, by default $NATS_TOKEN."
  [{:keys [url token name]
    :or   {url   (or (System/getenv "NATS_URL") default-url)
           token (System/getenv "NATS_TOKEN")}}]
  (let [uri (URI. url)
        port (if (pos? (.getPort uri)) (.getPort uri) 4222)
        socket (Socket. (.getHost uri) (int port))
        conn {:socket socket
              :in     (BufferedInputStream. (.getInputStream socket))
              :out    (BufferedOutputStream. (.getOutputStream socket))}
        info (read-op (:in conn))]
    (when-not (= :info (:op info))
      (.close socket)
      (throw (ex-info (str "not a NATS server: " url) {})))
    (write! conn
            "CONNECT "
            (json/generate-string (cond-> {:verbose       false
                                           :pedantic      false
                                           :lang          "clojure"
                                           :version       "0.1.0"
                                           :protocol      1
                                           :headers       true
                                           :no_responders true}
                                    name                   (assoc :name name)
                                    (not (str/blank? token)) (assoc :auth_token token)))
            "\r\nPING\r\n")
    (loop []
      (case (:op (next-op conn))
        :pong nil
        (recur)))
    (assoc conn
           :info (:info info)
           :sids (atom 0))))

(defn close [{:keys [^Socket socket]}]
  (.close socket))

(defn max-payload [conn]
  (get-in conn [:info :max_payload] (* 1024 1024)))

(defn subscribe
  "Subscribes to subject, returning the subscription id."
  [conn subject]
  (let [sid (str (swap! (:sids conn) inc))]
    (write! conn "SUB " subject " " sid "\r\n")
    sid))

(defn unsubscribe [conn sid]
  (write! conn "UNSUB " sid "\r\n"))

(defn publish
  ([conn subject data]
   (publish conn subject nil data))
  ([conn subject reply data]
   (let [payload (utf8-bytes (or data ""))]
     (write! conn "PUB " subject (when reply (str " " reply)) " " (alength payload) "\r\n" payload "\r\n"))))

(defn next-msg
  "Waits for the next message on any subscription."
  [conn]
  (let [op (next-op conn)]
    (if (= :msg (:op op))
      op
      (recur conn))))

(defn request
  "Sends data to subject, returning the data of the reply, or throwing if there
  isn't one within timeout-ms."
  [conn subject data timeout-ms]
  (let [^Socket socket (:socket conn)
        inbox (str "_INBOX." (str/replace (str (UUID/randomUUID)) "-" ""))
        sid (subscribe conn inbox)]
    (publish conn subject inbox data)
    (.setSoTimeout socket (int timeout-ms))
    (try
      (let [msg (loop []
                  (let [msg (next-msg conn)]
                    (if (= sid (:sid msg)) msg (recur))))]
        (when (= 503 (:status msg))
          (throw (ex-info (str "nothing is answering " subject) {:subject subject})))
        (:data msg))
      (catch SocketTimeoutException _
        (throw (ex-info (str "timed out waiting for " subject) {:subject subject})))
      (finally
        (.setSoTimeout socket 0)
        (unsubscribe conn sid)))))
