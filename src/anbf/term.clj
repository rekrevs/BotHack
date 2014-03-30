; An implementation of a Terminal plugin for JTA without a GUI
; It interprets terminal escape sequences, cursor movement etc. using the vt320 emulation in JTA, keeps a representation of the screen in memory for querying and publishes redraw events for a higher-level interpretation elsewhere.
; Similar in structure to the JTA Terminal.java except it doesn't have the GUI-related stuff

(ns anbf.term
  (:require [clojure.pprint :as pprint]
            [anbf.delegator :refer :all]
            [clojure.tools.logging :as log])
  (:import (de.mud.jta FilterPlugin PluginBus)
           (de.mud.terminal vt320 VDUDisplay VDUBuffer)
           (de.mud.jta.event TelnetCommandRequest SetWindowSizeRequest
                             TerminalTypeListener LocalEchoListener
                             OnlineStatusListener)
           (java.io IOException))
  (:gen-class 
    :name anbf.NHTerminal
    :extends de.mud.jta.Plugin
    :implements [de.mud.jta.FilterPlugin Runnable]
    :state state
    :init init
    :post-init post-init))

; this class is instantiated by the JTA framework so the delegator has to be injected later
(defn set-delegator [this delegator]
  (swap! (.state this) assoc :delegator delegator)
  this)

(defn -getFilterSource [this source]
  (:source @(.state this)))

(defn -setFilterSource [this source]
  (swap! (.state this) assoc :source source))

(defn -read [this b]
  (.read (:source @(.state this)) b))

(defn -write [this b]
  (.write (:source @(.state this)) b))

; an immutable representation of a terminal window contents
; character attributes underline or blink etc. are not represented, only the foreground colors (affected by boldness) are important for NetHack
(defrecord Frame
  [lines ; vector of 24 Strings representing text on each row of the terminal
   colors ; vector of 24 vectors of keywords representing the FG color for the corresponding character (80 per line)
   cursor-x ; cursor position
   cursor-y])

(def colors [nil :red :green :brown :blue :magenta :cyan :gray ; non-bold
             :bold :orange :bright-green :yellow :bright-blue :bright-magenta :bright-cyan :white]) ; bold

(def ^:private fg-color-mask 0x1e0)
(def ^:private boldness-mask 0x1)

(defn- unpack-colors
  "for an int[] (row) of JTA attributes make a vector of FG colors (represented by keywords or nil for black)"
  [attrs]
  (map #(as-> % bits
              (bit-and fg-color-mask bits)
              (if (zero? bits) 0 (dec (bit-shift-right bits 5)))
              (+ bits (* 8 (bit-and boldness-mask %))) ; modify by boldness
              (colors bits))
       (take 80 attrs)))

(defn- unpack-line
  "Turns char[] of possibly null values into a String where the nulls are replaced by spaces."
  [line]
  (apply str (replace {(char 0) \space} line)))

(defn- frame-from-buffer
  "Makes an immutable snapshot (Frame) of a JTA terminal buffer (takes only last 24 lines)."
  [buf]
  ;(println "Terminal: drawing whole new frame")
  (Frame. (vec (map unpack-line ; turns char[][] into a vector of Strings
                    (take-last 24 (.charArray buf))))
          (vec (map unpack-colors
                    (take-last 24 (.charAttributes buf))))
          (.getCursorColumn buf)
          (.getCursorRow buf)))

(defn- changed-rows
  "Returns a lazy sequence of index numbers of updated rows in the buffer according to a JTA byte[] of booleans, assuming update[0] is false (only some rows need to update)"
  [update]
  (filter #(->> % inc (nth update) true?)
          (range 24)))

(defn- update-frame
  "Returns an updated frame snapshot as modified by a redraw (only some rows may need to update, as specified by update[])."
  [f newbuf updated-rows]
  (if (nth (.update newbuf) 0) ; if update[0] == true, all rows need to update
    (frame-from-buffer newbuf)
    (Frame. (reduce #(assoc %1 %2 (-> newbuf .charArray (nth %2) unpack-line))
                    (:lines f)
                    updated-rows)
            (reduce #(assoc %1 %2 (-> newbuf .charAttributes (nth %2) unpack-colors))
                    (:colors f)
                    updated-rows)
            (.getCursorColumn newbuf)
            (.getCursorRow newbuf))))

(defn print-colors [f]
  (println "Colors:")
  (doall (map #(if (every? nil? %)
                 (println nil)
                 (println %))
              (:colors f))))

(defmethod print-method Frame [f w]
  (.write w "==== <Frame> ====\n")
  (pprint/write (:lines f) :stream w)
  (.write w (format "\nCursor: %s %s\n" (:cursor-x f) (:cursor-y f)))
  (.write w "=================\n"))

(defn -init [bus id]
  [[bus id] (atom
              {:source nil ; source FilterPlugin
               :emulation nil ; vt320/VDUBuffer/VDUInput
               :display nil ; VDUDisplay
               :frame nil ; the last (current) display frame
               :delegator nil})]) ; ANBF delegator for event propagation

(defn -run [this]
  (log/debug "Terminal: reader started")
  (let [state @(.state this)
        buffer (byte-array 256)]
    (try 
      (loop []
        ;(println "Terminal: about to .read()")
        (let [n (.read (:source state) buffer)] ; blocking read
          (if (pos? n)
            ; latin1 is the default JTA swears by
            (.putString (:emulation state) (String. buffer 0 n "latin1")))
          (if-not (neg? n) ; -1 would mean the stream is dead
            (recur))))
      (catch IOException e
        ;(println "Terminal: reader IOException")
        )))
  (log/debug "Terminal: reader broke out of loop, ending"))

(defn -post-init [this-terminal bus id]
  (let [state (.state this-terminal)
        emulation (proxy [vt320] []
                    ; ignore setWindowSize()
                    ; ignore beep()
                    (write [b]
                      (-write this-terminal b))
                    (sendTelnetCommand [cmd]
                      (.broadcast bus (TelnetCommandRequest. cmd))))
        display (reify VDUDisplay
                  (redraw [this-display]
                    ;(println "Terminal: redraw called")
                    ;(def x emulation)
                    ;(println "Rows to update:" (changed-rows (.update emulation)))
                    (redraw @(:delegator @state)
                            (:frame (swap! state update-in [:frame]
                                           update-frame emulation
                                           (changed-rows (.update emulation)))))
                    (java.util.Arrays/fill (.update emulation) false))
                  (updateScrollBar [_])
                  (setVDUBuffer [this-display buffer]
                    (.setDisplay buffer this-display))
                  (getVDUBuffer [this-display]
                    (:emulation @state)))]
    (.setVDUBuffer display emulation)
    (swap! state
           assoc :emulation emulation
                 :display display
                 :frame (frame-from-buffer emulation))
    (doto bus
      (.registerPluginListener (reify TerminalTypeListener
                                 (getTerminalType [_]
                                   (.getTerminalID emulation))))
      (.registerPluginListener (reify LocalEchoListener
                                 (setLocalEcho [_ echo]
                                   (.setLocalEcho emulation echo))))
      (.registerPluginListener (reify OnlineStatusListener
                         ; the reader thread is going to stop itself on IO error
                                 (offline [_]
                                   (log/debug "Terminal: offline"))
                                 (online [_]
                                   (log/debug "Terminal: online")
                                   (.start (Thread. this-terminal))))))))