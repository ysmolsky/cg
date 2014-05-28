(ns cg.core
  [:use cg.ecs]
  [:use cg.comps]
  [:require [cg.site :as s]]
  [:require [cg.astar :as astar]]
  [:require [quil.core :as q]])

(def ui
  {:window-border 10
   :tile-size 17
   :text-size 15
   :char-color 200
   :ui-color 40
   :wall-color 50
   :background-color 25
   :foreground-color 200
   })

(declare pos2pix pix2pos epos2pix pos-middle tiles)

(def update-sleep-ms 33)
(def running (atom true))

(def scene {
            :dw [(speed 10)
                 (position 32.0 32.0)
                 (controllable)
                 (renderable "D")
                 (job-ready)
                 ]
            :beast [;;(health)
                    (position 15 15)
                    (renderable "b")]})

(defn new-job [w kind x y]
  (load-entity w kind [(job kind)
                       (position x y)
                       (renderable "X")
                       (free)]))

(defn new-stone [w x y]
  (load-entity w :stone [(stone :gabbro)
                         (position x y)
                         (renderable "✶")]))

(def world (atom (load-scene (new-ecs) scene)))

;;; MAP management

(def site-size 100)
(def site (s/generate site-size 0.4))

;;; State

;;; paused - if the game is paused
;;; mouse-actions - what does action of mouse have effect on. possible
;;; values: :move-to :dig :build-wall

(def game {:world (atom (load-scene (new-ecs) scene))
           :viewport (atom [0 0])
           :paused (atom false)
           :mouse-action (atom :move-to)
           :update-time (atom 0)})

;;; path finding

(defn get-cell-cost [cells xy] 10)

(defn filter-nbr [xy]
  (s/passable? @(s/place site xy)))

(s/form! site [32 32] :floor)

;;; just for test
;; (time (prn "path" (astar/path [1 1] [32 32] 1 site get-cell-cost filter-nbr)))

;;; view port

(defn vp-width []
  (tiles (q/width)))
  
(defn vp-height []
  (tiles (q/height)))

(defn vp []
  (let [[vp-x vp-y] @(game :viewport)]
    [vp-x
     vp-y
     (vp-width)
     (vp-height)]))

(defn round-coords [e comp]
  [(Math/round (-> e comp :x))
   (Math/round (-> e comp :y))])

(defn coords [e comp]
  [(-> e comp :x)
   (-> e comp :y)])

(defn bound
  "returns n bound to limit [0-b]"
  [b n]
  (if (neg? n)
    0
    (if (>= n b)
      b
      n)))

(defn bordering? [[x1 y1] [x2 y2]]
  (and (>= 1 (Math/abs (- x1 x2)))
       (>= 1 (Math/abs (- y1 y2)))))

;;; Systems


(defn move [e time]
  (let [v (e :velocity)
        t-norm (/ time 1000)
        dx (* (v :x) t-norm)
        dy (* (v :y) t-norm)]
    (-> e
        (update-in [:position :x] + dx)
        (update-in [:position :y] + dy))))

(defn system-move [w time]
  (update-comps w (node :move) move time))

;;; Guide System

(defn distance
  "distance between points"
  ([x1 y1 x2 y2]
     (let [dx (- x2 x1)
           dy (- y2 y1)]
       (distance dx dy)))
  ([dx dy]
     (Math/sqrt (+ (* dx dx)
                   (* dy dy)))))

(defn project-speed
  "calculates projected speed on x and y from x1, y2 to x2, y2 and absolute speed"
  [x1 y1 x2 y2 speed]
  (let [dx (- x2 x1)
        dy (- y2 y1)
        dist (distance dx dy)]
    (if (< dist 0.2)
      [0 0]
      (let [relation (/ (float speed)
                        dist)
            vx (* relation dx)
            vy (* relation dy)]
        [vx vy]))))

;;; TODO refactor this piece
(defn guide
  "calculates velocity based on position, next point and speed"
  [e time]
  (let [points (-> e :path :points)
        next-point (peek points)]
    (if (nil? next-point)
      (rem-c e :path)
      (let [p (e :position)
            s (-> e :speed :pixsec)
            [vx vy] (project-speed (p :x) (p :y) (next-point 0) (next-point 1) s)]
        (if (= vx 0)
          (-> e
              (update-in [:path :points] pop)
              (set-c (velocity 0.0 0.0)))
          (set-c e (velocity vx vy)))))))

(defn system-guide [w time]
  (update-comps w (node :guide) guide time))

(defn path-find-add [e time]
  (let [[ex ey] (round-coords e :position)
        [x y] (round-coords e :destination)
        new-path (astar/path [ex ey] [x y] 11 site get-cell-cost filter-nbr)
        ;new-path {:xys [[x y]]}
        ]
    (prn "path" x y ex ey new-path)
    (if (empty? (new-path :xys))
      (rem-c e :destination)
      (-> e
          (set-c (path (new-path :xys)))
          (rem-c :destination)))))

(defn system-path-find [w time]
  (update-comps w (node :path-find) path-find-add time))



(defn find-reachable
  "tries to find free cell next to cells specified by ids and return [id [x y]]
   where x,y - coords of found free cell. otherwise returns nil"
  [w ids entity]
  (when-not (empty? ids)
    (let [id (first ids)
          target (get-e w id)
          [tx ty] (round-coords target :position)]
      (let [free-nbrs (filter filter-nbr (astar/neighbors site-size [tx ty]))]
        (if (empty? free-nbrs)
          (recur w (rest ids) entity)
          [id (first free-nbrs) [tx ty]])))))

;;; TODO: refactor next function

(defn assign-jobs
  [w time]
  (let [ids (get-cnames-ids w (node :free-worker))
        jobs (get-cnames-ids w (node :free-job))]
    (if-not (or (empty? ids)
                (empty? jobs))
      (let [worker-id (first ids)
            worker (get-e w worker-id)]
        ;; find unoccupied neighbors and check if worker can get to them
        (if-let [[job-id [x y] [tx ty]] (find-reachable w jobs worker)]
          (do
            (prn :job-assigned job-id worker-id tx ty x y)
            (-> w 
                (update-entity job-id rem-c :free)
                (update-entity worker-id rem-c :job-ready)
                (update-entity worker-id set-c (job-dig tx ty job-id))
                (update-entity worker-id set-c (destination (float x) (float y))))))))))

(defn system-assign-jobs
  "take free workers and find next (closest?) jobs for them"
  [w time]
  (let [res (assign-jobs w time)]
    (if res
      res
      w)))

(defn add-with-prob [w probability f & args]
  (if (< (rand) probability)
    (apply f w args)
    w))

(defn try-job
  "takes world and id of worker who has a job-dig and tries to perform the job.
   if job is completed then remove job property from worker and destroy job entity"
  [w id job-kind time]
  (let [e (get-e w id)
        e-xy (round-coords e :position)
        job-xy (coords e job-kind)
        {job-id :id
         progress :progress} (-> e job-kind)]
    ;; (prn :job-do job-kind e-xy job-xy progress)
    (if (bordering? e-xy job-xy)
      (if (< progress 0)
        (do
          (s/dig! site job-xy)
          (-> w
              (update-entity id rem-c job-kind)
              (update-entity id set-c (job-ready))
              (rem-e job-id)
              (add-with-prob 0.5 new-stone (job-xy 0) (job-xy 1))))
        (update-entity w id #(update-in %1 [job-kind :progress] - time)))
      w)))

(defn system-dig
  [w time]
  ;;(update-comps w [:job-dig] try-dig time w)
  (let [ids (get-cnames-ids w [:job-dig])]
    (reduce #(try-job %1 %2 :job-dig time) w ids)))



;;; Quil handlers

(defn bound-viewport
  [[x y] [dx dy]]
  (let [w (vp-width)
        h (vp-height)]
    [(bound (- site-size w) (+ x dx))
     (bound (- site-size h) (+ y dy))]))

(def key-to-scroll {\w [0 -1]
                    \s [0 1]
                    \a [-1 0]
                    \d [1 0]})

(def scroll-amount 5)

(defn on-key
  "Handles key presses. Returns new state of the world"
  [w key]
  ;;(set-val w 0 :health :count key)
  (cond
   (= \space key) (swap! (game :paused) not)
   (= \f key) (reset! (game :mouse-action) :dig)
   (= \g key) (reset! (game :mouse-action) :move-to)
   (= \b key) (reset! (game :mouse-action) :build-wall)
   :else (let [delta (map #(* % scroll-amount) (key-to-scroll key [0 0]))]
           (swap! (game :viewport) bound-viewport delta)
           ;;(prn delta @(game :viewport))
           ))
  w)

(defmulti on-mouse-designate (fn [_ action _ _] action))

(defmethod on-mouse-designate :dig [w action x y]
  (if (= :diggable (:form @(s/place site [x y])))
    (new-job w :dig x y)
    w))

(defn on-mouse
  [w x y e]
  ;; (prn x y e)
  (let [ids (get-cnames-ids w [:controllable])
        x (pos-middle (pix2pos x))
        y (pos-middle (pix2pos y))
        [vp-x vp-y width height] (vp)
        real-x (+ vp-x x)
        real-y (+ vp-y y)
        in-field (and (>= x 0)
                      (>= y 0)
                      (< x width)
                      (< y height))
        action @(game :mouse-action)]
    (prn real-x real-y e action)
    (if in-field
      (cond
       (= action :move-to) (update-entities w ids set-c (destination real-x real-y))
       (#{:dig :build-wall} action) (on-mouse-designate w action real-x real-y)
       :else w))))

(defn on-tick
  "Handles ticks of the world, delta is the time passes since last tick"
  [w time]
  (do
    (-> w
        (system-move time)
        (system-guide time)
        (system-path-find time)
        (system-assign-jobs time)
        (system-dig time)
        )))




;;; RENDERING STUFF

(defn tiles [size]
  (let [tile-size (ui :tile-size)]
    (quot (- size (* tile-size 2))
          tile-size)))

(defn pos2pix [position]
  (+ (ui :tile-size) (* position (ui :tile-size))))

(defn epos2pix
  "converts entity position to pixels on the screen"
  [position]
  (pos2pix (+ 0.5 position)))

(defn pix2pos [pixel]
  (/ (float (- pixel (ui :tile-size)))
     (ui :tile-size)))

(defn pos-middle [position]
  (quot position 1))

(defn draw-ents [[vp-x vp-y w h] ents]
  (doseq [e ents]
    (let [m (e :position)
          r (e :renderable)
          x (- (m :x) vp-x)
          y (- (m :y) vp-y)]
      (if (and (< 0 x w)
               (< 0 y h))
        (q/text (r :char)
                (- (epos2pix x) 4)
                (+ (epos2pix y) 6))))))

(defn draw-tile-bg [passable x y]
  (when (not passable)
    (q/rect (pos2pix x)
            (pos2pix y)
            (ui :tile-size)
            (ui :tile-size)))
  )

(defn draw-site [[vp-x vp-y w h]]
  (doseq [x (range w)
          y (range h)]
    (let [c @(s/place site [(+ vp-x x) (+ vp-y y)])]
      (draw-tile-bg (s/passable? c) x y))))

(defn draw-world [w viewport]
  ;(q/text (str (get-cname-ids w :renderable)) 10 390)
  (q/fill (ui :wall-color))
  (draw-site viewport)
  (q/fill (ui :char-color))
  (draw-ents viewport (get-cnames-ents w (node :render)))
  )

(defn draw
  []
  (let [w (vp-width)
        h (vp-height)
        viewport (vp)]
    (q/background-float (ui :background-color))
    
    ;; draw grid
    (q/stroke-weight 1)
    (q/stroke-float (ui :ui-color))
    (doseq [x (range (+ 1 w))]
      (q/line (pos2pix x) (pos2pix 0)
              (pos2pix x) (pos2pix h)))
    (doseq [y (range (+ 1 h))]
      (q/line (pos2pix 0) (pos2pix y)
              (pos2pix w) (pos2pix y)))

    ;; (q/text-size (ui :text-size))
    (q/text-font (q/state :font-monaco))

    (when @(game :paused)
      (q/text "pause" (pos2pix 0) (pos2pix (inc h))))

    (q/text (str @(game :update-time)) (pos2pix 6) (pos2pix (inc h)))
    (q/text (str @(game :mouse-action)) (pos2pix 9) (pos2pix (inc h)))
    
    (let [w @(game :world)]
      (draw-world w viewport))))



;;; ticks thread

(defn averager [v1 v2]
  (int (/ (+ v1 v2) 2)))

(defn err-handler-fn [ag ex]
  (println "agent error: " ex 
           "\nvalue " @ag))
 
(def updater (agent nil :error-handler err-handler-fn))

(defn updating [_]
  (when @running
    (send-off *agent* #'updating))
  (let [start (. System (nanoTime))
        new-world (if @(game :paused)
                    (game :world)
                    (swap! (game :world) on-tick update-sleep-ms))
        elapsed (/ (double (- (. System (nanoTime)) start)) 1000000.0)]

    (swap! (game :update-time) averager (/ (float 1000) (max update-sleep-ms elapsed)))
    
    (if (> elapsed update-sleep-ms)
      (prn "elapsed:" elapsed)
      (. Thread (sleep (- update-sleep-ms elapsed)))))
  nil)

(send-off updater updating)



;;; quil handlers 

(defn key-press []
  (swap! (game :world) on-key (q/raw-key)))

(defn mouse
  "Possible events: :down :up :drag :move :enter :leave."
  [event]
  (swap! (game :world) on-mouse (q/mouse-x) (q/mouse-y) event))



(defn setup []
  (q/set-state! :font-monaco (q/create-font "Monaco" (ui :text-size) true))
  (q/smooth)
  (q/frame-rate 30))

(q/sketch
 :title "ECS prototype"
 :size [1000 700]
 :setup setup
 :draw draw
 :key-pressed key-press
 :mouse-pressed #(mouse :down)
 :on-close (fn [] (do
                    (reset! running false)
                    )))
