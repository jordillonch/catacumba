(ns catacumba.impl.streams
  (:require [clojure.core.async :refer [put! take! chan <! >! go close! go-loop onto-chan]])
  (:import org.reactivestreams.Publisher
           org.reactivestreams.Subscriber
           org.reactivestreams.Subscription
           io.netty.buffer.Unpooled))

(defprotocol IByteBuffer
  (as-byte-buffer [_] "Coerce to byte buffer."))

(extend-protocol IByteBuffer
  String
  (as-byte-buffer [s]
    (Unpooled/wrappedBuffer (.getBytes s "UTF-8"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Channel <-> Publisher adapter
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-subscription-for-chan
  [ch ^Subscriber subscriber]
  (let [demand (chan 48)]
    (go-loop []
      (when-let [demanded (<! demand)]
        (loop [demandseq (range (- demanded 1))]
          (let [val (<! ch)
                flag (first demandseq)]
            (if (nil? val)
              (do
                (.onComplete subscriber)
                (close! demand))
              (do
                (.onNext subscriber (as-byte-buffer val))
                (when-not (nil? flag)
                  (recur (rest demandseq)))))))
        (recur)))
    (reify Subscription
      (^void request [_ ^long n]
        (put! demand n))
      (^void cancel [_]
        (close! ch)))))

(defn chan->publisher
  "Converts a chan `ch` into the reactive
  streams publisher instance."
  [ch]
  (reify Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [^Subscription subscription (create-subscription-for-chan ch subscriber)]
        (.onSubscribe subscriber subscription)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ISeq <-> Publisher adapter
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-subscription-for-seq
  [s ^Subscriber subscriber]
  (let [state (agent s)]
    (reify Subscription
      (^void request [_ ^long n]
        (when-not (nil? @state)
          (send-off state (fn [state]
                            (loop [num (range n)
                                   state' state]
                              (if-let [value (first state')]
                                (if-let [i (first num)]
                                  (do
                                    (.onNext subscriber (as-byte-buffer value))
                                    (recur (rest num)
                                           (rest state')))
                                  state')
                                (do
                                  (.onComplete subscriber)
                                  nil)))))))
      (^void cancel [_]
        (send-off state (fn [s] nil))))))

(defn seq->publisher
  [s]
  (reify Publisher
    (^void subscribe [_ ^Subscriber subscriber]
      (let [^Subscription subscription (create-subscription-for-seq s subscriber)]
        (.onSubscribe subscriber subscription)))))