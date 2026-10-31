(ns plugbench.nats-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [plugbench.nats :as nats])
  (:import
   (java.io ByteArrayInputStream)))

(defn- ops [s]
  (let [in (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))]
    (take-while some? (repeatedly #(nats/read-op in)))))

(deftest messages-carry-their-subject-reply-and-data
  (is (= [{:op :msg, :subject "cmd.get.frecent", :sid "2", :reply "_INBOX.x", :data "file://h/a\r\nb"}]
         (ops "MSG cmd.get.frecent 2 _INBOX.x 13\r\nfile://h/a\r\nb\r\n"))))

(deftest messages-may-have-no-reply
  (is (= [nil] (map :reply (ops "MSG cmd.add.frecent 1 3\r\nabc\r\n")))))

(deftest payload-sizes-count-bytes-not-characters
  (is (= ["é" "x"] (map :data (ops "MSG a 1 2\r\né\r\nMSG a 1 1\r\nx\r\n")))))

(deftest messages-with-headers-carry-them-apart-from-the-data
  (let [headers "NATS/1.0\r\nBase: file://h/\r\n\r\n"
        [msg] (ops (str "HMSG a 1 r " (count headers) " " (+ 3 (count headers)) "\r\n" headers "abc\r\n"))]
    (is (= "abc" (:data msg)))
    (is (= "r" (:reply msg)))
    (is (= {"Base" "file://h/"} (:headers msg)))))

(deftest no-responders-arrives-as-a-status
  (let [headers "NATS/1.0 503\r\n\r\n"
        [msg] (ops (str "HMSG _INBOX.x 1 " (count headers) " " (count headers) "\r\n" headers "\r\n"))]
    (is (= 503 (:status msg)))
    (is (= "" (:data msg)))))

(deftest server-errors-lose-their-quotes
  (is (= [{:op :err, :message "Authorization Violation"}]
         (ops "-ERR 'Authorization Violation'\r\n"))))

(deftest housekeeping-operations-are-recognized
  (is (= [:ping :pong :ok] (map :op (ops "PING\r\nPONG\r\n+OK\r\n")))))
