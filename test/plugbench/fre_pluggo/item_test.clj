(ns plugbench.fre-pluggo.item-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [plugbench.fre-pluggo.item :as item]))

(deftest paths-become-file-urls-on-the-host
  (is (= "file://h/etc/hosts" (item/->url "h" "/" "/etc/hosts")))
  (is (= "file://h/etc/hosts" (item/->url "h" "/etc" "hosts")))
  (is (= "file://h/etc/hosts" (item/->url "h" "/etc/ssl" "../hosts"))))

(deftest directories-end-with-a-slash
  (let [dir (str (fs/create-temp-dir))]
    (is (= (str "file://h" dir "/") (item/->url "h" "/" dir)))
    (is (= (str "file://h" dir "/") (item/->url "h" dir ".")))))

(deftest urls-are-left-alone
  (is (= "https://example.com/a b" (item/->url "h" "/" "https://example.com/a b"))))

(deftest a-place-in-a-file-is-the-file
  (is (= "file://h/a/b.c" (item/normalize "file://h/a/b.c#line=4.2"))))

(deftest escaped-file-paths-are-unescaped
  (is (= "file://h/a b+c" (item/normalize "file://h/a%20b+c"))))

(deftest blank-and-multi-line-items-are-not-visits
  (is (nil? (item/normalize "  ")))
  (is (nil? (item/normalize "file://h/a\nfile://h/b"))))

(deftest only-this-hosts-files-have-local-paths
  (is (= "/a/b" (item/local-path "h" "file://h/a/b")))
  (is (nil? (item/local-path "h" "file://other/a/b")))
  (is (nil? (item/local-path "h" "https://h/a/b"))))
