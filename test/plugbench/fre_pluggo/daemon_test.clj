(ns plugbench.fre-pluggo.daemon-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [plugbench.fre-pluggo.daemon :as daemon]))

(defn- state [sorted]
  (let [calls (atom [])]
    {:calls       calls
     :fre         (fn [& args]
                    (swap! calls conj (vec args))
                    (if (= ["--sorted"] args) (str/join "\n" sorted) ""))
     :max-payload 1024
     :adds        (atom 0)}))

(deftest visits-are-added-to-the-store
  (let [s (state [])]
    (is (= "ok" (daemon/handle s {:subject "cmd.add.frecent" :data "file://h/a#line=1"})))
    (is (= [["--add" "file://h/a"]] @(:calls s)))))

(deftest blank-visits-are-refused
  (let [s (state [])]
    (is (str/starts-with? (daemon/handle s {:subject "cmd.add.frecent" :data ""}) "ERROR: "))
    (is (empty? @(:calls s)))))

(deftest the-store-is-truncated-now-and-then
  (let [s (state [])]
    (dotimes [_ daemon/adds-between-truncations]
      (daemon/handle s {:subject "cmd.add.frecent" :data "x://y"}))
    (is (= ["--truncate" (str daemon/kept-items)] (last @(:calls s))))))

(deftest everything-is-listed-most-frecent-first
  (is (= "file://h/b\nfile://h/a\n"
         (daemon/handle (state ["file://h/b" "file://h/a"]) {:subject "cmd.get.frecent" :data ""}))))

(deftest listing-can-be-limited-to-what-starts-with-a-prefix
  (is (= "file://h/d/b\n"
         (daemon/handle (state ["file://h/db" "file://h/d/b" "file://h/a"])
                        {:subject "cmd.get.frecent" :data "file://h/d/"}))))

(deftest listings-are-cut-short-to-fit-in-a-message
  (let [s (assoc (state ["aaaa" "bbbb" "cccc"]) :max-payload 11)]
    (is (= "aaaa\nbbbb\n" (daemon/handle s {:subject "cmd.get.frecent" :data ""})))))
