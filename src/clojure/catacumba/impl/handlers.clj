(ns catacumba.impl.handlers
  (:refer-clojure :exclude [send])
  (:require [clojure.java.io :as io]
            [futura.stream :as stream]
            [futura.promise :as p]
            [catacumba.utils :as utils]
            [catacumba.impl.helpers :as helpers]
            [catacumba.impl.http :as http])
  (:import ratpack.handling.Handler
           ratpack.handling.Context
           ratpack.http.Request
           ratpack.http.Response
           ratpack.http.Headers
           ratpack.http.TypedData
           ratpack.http.MutableHeaders
           ratpack.util.MultiValueMap
           org.reactivestreams.Publisher
           java.util.concurrent.CompletableFuture
           io.netty.buffer.Unpooled
           io.netty.buffer.ByteBuf
           java.io.InputStream
           java.io.BufferedReader
           java.io.InputStreamReader
           java.io.BufferedInputStream
           java.util.Map))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocol Definition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ISend
  (send [data response] "Send data."))

(defprotocol IHandlerResponse
  (handle-response [_ context] "Handle the ratpack handler response."))

(defprotocol IResponseGetter
  (get-response* [_] "Get the response."))

(defprotocol IRequestGetter
  (get-request* [_] "Get the request."))

(defprotocol IHeadersGetter
  (get-headers* [_] "Get headers."))

(defprotocol IHeadersSetter
  (set-headers [_ headers] "Set the headers."))

(defprotocol IStatusCodeSetter
  (set-status [_ status] "Set the status code."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocol Implementations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(extend-protocol IHandlerResponse
  String
  (handle-response [data ^Context context]
    (let [response (get-response* context)]
      (send data response)))

  clojure.lang.IPersistentMap
  (handle-response [data ^Context context]
    (let [^Response response (get-response* context)
          {:keys [status headers body]} data]
      (when status
        (.status response ^long status))
      (when headers
        (set-headers response headers))
      (send body response)))

  catacumba.impl.http.Response
  (handle-response [data ^Context context]
    (let [^Response response (get-response* context)]
      (.status response ^long (:status data))
      (set-headers response (:headers data))
      (send (:body data) response)))

  clojure.core.async.impl.channels.ManyToManyChannel
  (handle-response [data ^Context context]
    (let [^Response response (get-response* context)]
      (.status response 200)
      (send data response)))

  Publisher
  (handle-response [data ^Context context]
    (let [^Response response (get-response* context)]
      (.status response 200)
      (send data response)))

  futura.promise.Promise
  (handle-response [data ^Context context]
    (let [^Response response (get-response* context)]
      (.status response 200)
      (send data response)))

  CompletableFuture
  (handle-response [data ^Context context]
    (handle-response (p/promise data) context)))

(extend-protocol ISend
  String
  (send [data ^Response response]
    (.send response data))

  clojure.core.async.impl.channels.ManyToManyChannel
  (send [data ^Response response]
    (-> (stream/publisher data)
        (send response)))

  futura.promise.Promise
  (send [data ^Response response]
    (-> (stream/publisher data)
        (send response)))

  CompletableFuture
  (send [data ^Response response]
    (-> (stream/publisher data)
        (send response)))

  Publisher
  (send [data ^Response response]
    (->> (stream/publisher (map helpers/bytebuffer) data)
         (.sendStream response)))

  InputStream
  (send [data ^Response response]
    (let [^bytes buffer (byte-array 1024)
          ^ByteBuf buf (Unpooled/buffer (.available data))]
      (loop [index 0]
        (let [readed (.read data buffer 0 1024)]
          (when-not (= readed -1)
            (.writeBytes buf buffer 0 readed)
            (recur (+ index readed)))))
      (.send response buf))))

(extend-protocol IStatusCodeSetter
  Context
  (set-status [^Context ctx ^long status]
    (set-status (.getResponse ctx) status))

  Response
  (set-status [^Response response ^long status]
    (.status response status)))

(extend-protocol IResponseGetter
  Context
  (get-response* [^Context ctx]
    (.getResponse ctx))

  Response
  (get-response* [rsp]
    rsp))

(extend-protocol IRequestGetter
  Context
  (get-request* [^Context ctx]
    (.getRequest ctx))

  Request
  (get-request* [req]
    req))

(extend-protocol IHeadersGetter
  Context
  (get-headers* [^Context ctx]
    (let [^Request request (get-request* ctx)]
      (get-headers* request)))

  Request
  (get-headers* [^Request request]
    (let [^Headers headers (.getHeaders request)
          ^MultiValueMap headers (.asMultiValueMap headers)]
      (persistent!
       (reduce (fn [acc key]
                 (let [values (.getAll headers key)
                       key (.toLowerCase key)]
                   (reduce #(utils/assoc-conj! %1 key %2) acc values)))
               (transient {})
               (.keySet headers))))))

(extend-protocol IHeadersSetter
  Context
  (set-headers [^Context ctx headers]
    (let [^Response request (get-response* ctx)]
      (set-headers request headers)))

  Response
  (set-headers [^Response response headers]
    (let [^MutableHeaders headersmap (.getHeaders response)]
      (loop [headers headers]
        (when-let [[key vals] (first headers)]
          (.set headersmap (name key) vals)
          (recur (rest headers)))))))

(declare get-body)

(extend-protocol io/IOFactory
  TypedData
  (make-reader [d opts]
    (BufferedReader. (InputStreamReader. (.getInputStream d) (:encoding opts "UTF-8"))))
  (make-writer [d opts]
    (throw (UnsupportedOperationException. "Cannot open as Reader.")))
  (make-input-stream [d opts]
    (BufferedInputStream. (.getInputStream d)))
  (make-output-stream [d opts]
    (throw (UnsupportedOperationException. "Cannot open as Reader.")))

  Context
  (make-reader [ctx opts]
    (io/make-reader (get-body ctx) opts))
  (make-writer [ctx opts]
    (io/make-writer (get-body ctx) opts))
  (make-input-stream [ctx opts]
    (io/make-input-stream (get-body ctx) opts))
  (make-output-stream [ctx opts]
    (io/make-output-stream (get-body ctx) opts))

  Request
  (make-reader [req opts]
    (io/make-reader (get-body req) opts))
  (make-writer [req opts]
    (io/make-writer (get-body req) opts))
  (make-input-stream [req opts]
    (io/make-input-stream (get-body req) opts))
  (make-output-stream [req opts]
    (io/make-output-stream (get-body req) opts)))


(defn- build-request
  [^Request request]
  (let [local-address (.getLocalAddress request)
        remote-address (.getRemoteAddress request)]
    {:server-port (.getPort local-address)
     :server-name (.getHostText local-address)
     :remote-addr (.getHostText remote-address)
     :uri (str "/" (.getPath request))
     :query-string (.getQuery request)
     :scheme :http
     :request-method (keyword (.. request getMethod getName toLowerCase))
     :headers (get-headers* request)
     :content-type (.. request getBody getContentType getType)
     :content-length (Integer/parseInt (.. request getHeaders (get "Content-Length")))
     :character-encoding (.. request getBody getContentType (getCharset "utf-8"))
     :body (.. request getBody getInputStream)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-request
  "Helper for obtain the current request instance
  from provided Context instance."
  [i]
  (get-request* i))

(defn get-response
  "Helper for obtain the current response instance
  from provided Context instance."
  [i]
  (get-response* i))

(defn get-body
  "Helper for obtain a object that represents a request
  body. The returned object implements the IOFactory
  protocol that allows use it with `clojure.java.io`
  functions and `slurp`."
  [^Context context]
  (let [^Request request (get-request* context)]
    (.getBody request)))

(defn get-headers
  "Get request headers.

  This is a polymorphic function and accepts
  Context instances as request."
  [request]
  (get-headers* request))

(defn set-headers!
  "Set response headers.

  This is a polymorphic function and accepts
  Context instances as response."
  [response headers]
  (set-headers response headers))

(defn set-status!
  "Set response status code."
  [response status]
  (set-status response status))

(defn send!
  "Send data to the client."
  [^Context context data]
  (let [response (get-response context)]
    (send data response)))

(defn ratpack-adapter
  "Adapt a function based handler into ratpack
  compatible handler instance."
  [handler]
  (reify Handler
    (^void handle [_ ^Context context]
      (let [response (handler context)]
        (when (satisfies? IHandlerResponse response)
          (handle-response response context))))))

(defn ring-adapter
  "Adapt the ratpack style handler to ring compatible handler."
  [handler]
  (reify Handler
    (^void handle [_ ^Context context]
      (let [request (build-request (get-request context))
            response (handler request)]
        (when (satisfies? IHandlerResponse response)
          (handle-response response context))))))
