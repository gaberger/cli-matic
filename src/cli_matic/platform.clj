(ns cli-matic.platform
  "
  ## Platform-specific functions for the JVM.

  If running on ClojureScript, we can have a different file for JS.

  BTW, in this NS, we avoid using Spec / Orchestra.

  ")

(defn read-env
  "Reads an environment variable.
  If undefined, returns nil."
  [var]
  (System/getenv var))

(defn exit-script
  "Terminates execution with a return value."
  [retval]
  (System/exit retval))

(defn add-shutdown-hook
  "Add a shutdown hook. If `nil`, simply ignores it.

  The shutdown hook is run in a new thread.

  "
  [fnToCallOnShutdown]

  (if (ifn? fnToCallOnShutdown)
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. fnToCallOnShutdown))))

;
; Conversions
;

(defn parseInt
  "Converts a string to an integer. "
  [s]
  (Integer/parseInt s))

(defn parseFloat
  "Converts a string to a float."
  [s]
  (Float/parseFloat s))

(defn asDate
  "Converts a string in format yyyy-mm-dd to a
  Date object; if conversion
  fails, returns nil."
  [s]
  (try
    (.parse
     (java.text.SimpleDateFormat. "yyyy-MM-dd") s)
    (catch Throwable t
      nil)))
