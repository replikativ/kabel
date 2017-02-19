# Change Log

## 0.2.1
 - JSON serialization
 - experimental WAMP client

## 0.2.0
 - use tyrus java web-client
   + fixes stochastic reordering issues with http.async.client
   + android support, should work with clojure on android
 - decouple serialization from IO and provide transit
 - have a baseline serialization to always allow communication
 - minimize dependency footprint

### 0.1.9
    - factor start stop
    - support superv.async

### 0.1.8
    - small bugfixes

### 0.1.7
    - support node on client-side
    - add an aleph http client (server still missing)
    - add a generic callback middleware

### 0.1.5
    - use lightweight slf4j logging

### 0.1.4
    - expose consistent host ip

### 0.1.3
    - do not initialize http-client on compile time
      fixes aot uberjar compilation

### 0.1.2
    - properly close cljs client connection on initial error
    - add :sender peer-id to outgoing messages
    - add :connection url to incoming messages
