import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class VicBarcelonaTrafficSim extends JPanel {

    // ── Constantes de la carretera ─────────────────────────────────────────
    static final int ROAD_LEN = 180, LANES = 3;
    static final int E_VIC = 0, E_CENTELLES = 50, X_GRANOLLERS = 125, X_BARCELONA = 175;
    static final int SEG_SIZE = 8;
    static final int DEF_ENTRY_VIC = 30, DEF_ENTRY_CEN = 20, DEF_EXIT_GRAN = 12, DEF_EXIT_BCN = 20;
    static final int DEF_GAP = 2, DEF_TICK = 80, DEF_RAB_CAP = 6, DEF_RAB_EXIT = 10, DEF_PCT_EXIT = 35;

    // ── Estado de la simulacion ────────────────────────────────────────────
    volatile int entryVic = DEF_ENTRY_VIC, entryCen = DEF_ENTRY_CEN;
    volatile int exitGran = DEF_EXIT_GRAN, exitBcn = DEF_EXIT_BCN;
    volatile int gap = DEF_GAP, tickMs = DEF_TICK;
    volatile int rabCap = DEF_RAB_CAP, rabExit = DEF_RAB_EXIT, pctExit = DEF_PCT_EXIT;
    volatile boolean rabOn = true, lightsOn = true;

    // ── Clases internas ────────────────────────────────────────────────────
    static class Car {
        final int id; int lane, pos; boolean braking, wantsExit, inRab; int rabProg; final Color color;
        Car(int id, int pos, int lane, boolean we) {
            this.id = id; this.pos = pos; this.lane = lane; this.wantsExit = we;
            color = Color.getHSBColor((id * 0.071f) % 1f, 0.55f, 0.92f);
        }
    }

    static class Bucket {
        double tokens, rate;
        Bucket(double r) { rate = Math.max(0, r); }
        void tick(long dt) { tokens = Math.min(tokens + rate * dt / 60000.0, 8); }
        boolean consume() { if (tokens >= 1) { tokens--; return true; } return false; }
    }

    static class Light {
        int pos; String name; boolean red; int greenMs, redMs; long last = System.currentTimeMillis();
        Light(int p, String n, int g, int r) { pos = p; name = n; greenMs = g; redMs = r; }
        void update(long now) {
            if (red && now - last >= redMs)       { red = false; last = now; }
            else if (!red && now - last >= greenMs) { red = true;  last = now; }
        }
    }

    // ── Datos de simulacion ────────────────────────────────────────────────
    final Car[][] road = new Car[LANES][ROAD_LEN];
    final List<Car> rabCars = Collections.synchronizedList(new ArrayList<>());
    final ConcurrentLinkedQueue<Integer> qVic = new ConcurrentLinkedQueue<>(), qCen = new ConcurrentLinkedQueue<>();
    final AtomicInteger idGen = new AtomicInteger(1);
    final Bucket bGran = new Bucket(DEF_EXIT_GRAN), bBcn = new Bucket(DEF_EXIT_BCN), bRab = new Bucket(DEF_RAB_EXIT);
    final List<Light> lights = new ArrayList<>();
    volatile long exGran, exBcn, enVic, enCen, maxQV, maxQC;
    volatile int carsOn, bnSeg = -1;
    volatile String bnName = "", diagMsg = "Ajusta los parametros para empezar!";
    volatile Color diagColor = new Color(100, 200, 255);
    final double[] segD = new double[ROAD_LEN / SEG_SIZE + 1];
    static final int HIST = 200;
    final double[] histCars = new double[HIST];
    int hIdx; long lastH;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(3);
    volatile boolean running = true;
    long lastTick = System.currentTimeMillis(), simStart = System.currentTimeMillis();
    volatile long simSec;

    // ── Referencias a controles del panel derecho ─────────────────────────
    JSlider sVic, sCen, sGran, sBcn, sGap, sTick, sRabCap, sRabExit, sPct;
    JLabel lAdv;

    // ── Panel educativo inferior (Swing, no Graphics2D) ───────────────────
    JTextPane eduPane;

    // ── Constructor ───────────────────────────────────────────────────────
    public VicBarcelonaTrafficSim() {
        setBackground(new Color(30, 32, 40));
        setPreferredSize(new Dimension(1100, 430)); // Altura ajustada
        lights.add(new Light(X_GRANOLLERS - 5, "Pre-Granollers", 5000, 3500));
        lights.add(new Light(E_CENTELLES + 3, "Centelles", 6000, 2500));
    }

    // ── Hilo principal de simulacion ──────────────────────────────────────
    void start() {
        sched.scheduleAtFixedRate(() -> {
            if (!running) return;
            if (entryVic > 0 && Math.random() < entryVic / 750.0) qVic.add(idGen.getAndIncrement());
        }, 0, 80, TimeUnit.MILLISECONDS);
        sched.scheduleAtFixedRate(() -> {
            if (!running) return;
            if (entryCen > 0 && Math.random() < entryCen / 750.0) qCen.add(idGen.getAndIncrement());
        }, 0, 80, TimeUnit.MILLISECONDS);
        sched.scheduleAtFixedRate(() -> {
            if (!running) return;
            long now = System.currentTimeMillis(); long dt = now - lastTick; lastTick = now;
            simSec = (now - simStart) / 1000;
            try { tick(dt); } catch (Exception e) { e.printStackTrace(); }
            SwingUtilities.invokeLater(this::repaint);
        }, 0, tickMs, TimeUnit.MILLISECONDS);
    }

    synchronized void tick(long dt) {
        bGran.rate = exitGran; bBcn.rate = exitBcn; bRab.rate = rabExit;
        bGran.tick(dt); bBcn.tick(dt); bRab.tick(dt);
        if (lightsOn) { long now = System.currentTimeMillis(); for (Light l : lights) l.update(now); }
        entries(); movement(); if (rabOn) roundabout(); metrics(); bottleneck(); diagnostic();
        maxQV = Math.max(maxQV, qVic.size()); maxQC = Math.max(maxQC, qCen.size());
    }

    void entries() {
        Integer id = qVic.peek();
        if (id != null) for (int l = 0; l < LANES; l++) if (canPlace(l, E_VIC)) {
            qVic.poll(); road[l][E_VIC] = new Car(id, E_VIC, l, Math.random() * 100 < pctExit); enVic++; break;
        }
        id = qCen.peek();
        if (id != null) for (int l = 0; l < LANES; l++) if (canPlace(l, E_CENTELLES)) {
            qCen.poll(); road[l][E_CENTELLES] = new Car(id, E_CENTELLES, l, Math.random() * 100 < pctExit); enCen++; break;
        }
    }

    boolean canPlace(int l, int p) {
        if (p < 0 || p >= ROAD_LEN || road[l][p] != null) return false;
        for (int k = 1; k <= gap; k++) {
            if (p + k < ROAD_LEN && road[l][p + k] != null) return false;
            if (p - k >= 0 && road[l][p - k] != null) return false;
        }
        return true;
    }

    void movement() {
        for (int l = 0; l < LANES; l++) for (int i = ROAD_LEN - 1; i >= 0; i--) {
            Car c = road[l][i]; if (c == null) continue; c.braking = false;
            if (i >= X_BARCELONA) {
                if (bBcn.consume()) { road[l][i] = null; exBcn++; continue; } c.braking = true;
            }
            if (c.wantsExit && i >= X_GRANOLLERS - 3 && i <= X_GRANOLLERS) {
                if (rabOn) {
                    if (rabCars.size() < rabCap) { c.inRab = true; c.rabProg = 0; rabCars.add(c); road[l][i] = null; continue; }
                    c.braking = true;
                } else {
                    if (bGran.consume()) { road[l][i] = null; exGran++; continue; } c.braking = true;
                }
            }
            if (lightsOn) for (Light tl : lights) if (tl.red && i < tl.pos && i >= tl.pos - 4) c.braking = true;
            if (!c.braking) {
                int n = i + 1;
                if (n < ROAD_LEN && canFwd(l, n)) { road[l][i] = null; c.pos = n; road[l][n] = c; } else c.braking = true;
            }
            if (c.braking && road[l][i] == c) laneChange(l, i, c);
        }
    }

    boolean canFwd(int l, int p) {
        if (p < 0 || p >= ROAD_LEN || road[l][p] != null) return false;
        for (int k = 1; k <= gap; k++) if (p + k < ROAD_LEN && road[l][p + k] != null) return false;
        return true;
    }

    void laneChange(int l, int p, Car c) {
        for (int nl : new int[]{l - 1, l + 1}) {
            if (nl < 0 || nl >= LANES) continue;
            if (canPlace(nl, p)) { road[l][p] = null; c.lane = nl; road[nl][p] = c; return; }
        }
    }

    void roundabout() {
        synchronized (rabCars) {
            Iterator<Car> it = rabCars.iterator();
            while (it.hasNext()) {
                Car c = it.next(); c.rabProg++;
                if (c.rabProg >= 16 && (bRab.consume() || c.rabProg > 40)) { it.remove(); exGran++; }
            }
        }
    }

    void metrics() {
        int cnt = 0; Arrays.fill(segD, 0);
        for (int l = 0; l < LANES; l++) for (int i = 0; i < ROAD_LEN; i++) if (road[l][i] != null) {
            cnt++; int s = i / SEG_SIZE; if (s < segD.length) segD[s]++;
        }
        cnt += rabCars.size(); carsOn = cnt;
        for (int s = 0; s < segD.length; s++) segD[s] /= (SEG_SIZE * LANES);
        long now = System.currentTimeMillis();
        if (now - lastH >= 600) { histCars[hIdx % HIST] = carsOn; hIdx++; lastH = now; }
    }

    void bottleneck() {
        double mx = 0; int ms = -1;
        for (int s = 0; s < segD.length; s++) if (segD[s] > mx) { mx = segD[s]; ms = s; }
        if (mx > 0.40 && ms >= 0) {
            bnSeg = ms; int pos = ms * SEG_SIZE;
            if (pos >= X_GRANOLLERS - 12 && pos <= X_GRANOLLERS + 4) bnName = "Rotonda Granollers";
            else if (pos >= X_BARCELONA - 8) bnName = "Salida Barcelona";
            else if (pos >= E_CENTELLES - 4 && pos <= E_CENTELLES + 8) bnName = "Entrada Centelles";
            else bnName = "Tramo km " + (pos * 70 / ROAD_LEN);
        } else { bnSeg = -1; bnName = ""; }
    }

    void diagnostic() {
        double inR = entryVic + entryCen;
        double outR = exitBcn + (rabOn ? Math.min(exitGran, rabExit) : exitGran);
        if (bnSeg >= 0) {
            int pos = bnSeg * SEG_SIZE;
            if (pos >= X_GRANOLLERS - 12 && pos <= X_GRANOLLERS + 4) {
                diagMsg = "ATASCO en ROTONDA! El bloque synchronized tiene demasiada contention. Sube capacidad o baja entradas.";
                diagColor = new Color(255, 80, 80);
            } else if (pos >= X_BARCELONA - 8) {
                diagMsg = "ATASCO en BARCELONA! El consumidor de threads es lento. Sube la tasa de salida de Barcelona.";
                diagColor = new Color(255, 120, 60);
            } else {
                diagMsg = "CONGESTION en " + bnName + "! Los threads compiten por el recurso. Reduce entradas o aumenta salidas.";
                diagColor = new Color(255, 160, 40);
            }
        } else if (inR > outR * 1.3) {
            diagMsg = "CUIDADO: Entran " + (int)inR + "/min pero salen ~" + (int)outR + "/min. Productor > Consumidor!";
            diagColor = new Color(255, 200, 60);
        } else if (carsOn == 0 && inR == 0) {
            diagMsg = "Carretera vacia. Sube las entradas para empezar la simulacion.";
            diagColor = new Color(150, 150, 180);
        } else if (carsOn < 10) {
            diagMsg = "Trafico fluido. Sin contention significativa. Los threads circulan libremente.";
            diagColor = new Color(80, 220, 130);
        } else {
            diagMsg = "Trafico equilibrado. Entrada " + (int)inR + "/min alineada con salida. Sistema estable.";
            diagColor = new Color(100, 200, 255);
        }
        if (lAdv != null) {
            String html = "<html><body style='width:200px;padding:5px;'><b>CONSEJOS:</b><br>" + diagMsg + "</body></html>";
            SwingUtilities.invokeLater(() -> { lAdv.setText(html); lAdv.setForeground(diagColor); });
        }
    }

    // ── Panel educativo: actualiza el JTextPane inferior ──────────────────
    void explainAction(String param, int value) {
        if (eduPane == null) return;

        String titulo, queEs, siSubes, siBajas, codigoJava;
        Color accentColor;

        switch (param) {
            case "vic":
                titulo     = "Entrada Vic  —  PRODUCTOR de threads";
                accentColor = new Color(70, 200, 120);
                queEs      = "Vic genera nuevos threads (coches) al sistema a razon de " + value + "/min. "
                           + "En programacion concurrente es el PRODUCTOR del patron Producer-Consumer: "
                           + "una fuente continua de trabajo que llena una cola (BlockingQueue). "
                           + "Si el productor es mas rapido que el consumidor, la cola crece indefinidamente.";
                siSubes    = "Mas threads entran al sistema. Si Barcelona y Granollers no pueden absorberlos, "
                           + "la cola de espera (qVic) crecera y veras congestión en la carretera. "
                           + "Simula un servidor web bajo alta demanda: muchas peticiones entrando al pool.";
                siBajas    = "Menos carga de trabajo. El sistema respira. Con valor 0, el productor se detiene "
                           + "completamente: como pausar la generacion de tareas en un sistema de colas.";
                codigoJava = "// Hilo productor Vic:\nBlockingQueue<Tarea> cola = new LinkedBlockingQueue<>();\nwhile (running) {\n    cola.put(new Tarea());  // bloquea si cola llena\n    Thread.sleep(60_000 / entryVic);\n}";
                break;
            case "cen":
                titulo     = "Entrada Centelles  —  SEGUNDO PRODUCTOR en paralelo";
                accentColor = new Color(70, 160, 230);
                queEs      = "Centelles es un segundo hilo productor independiente que genera threads en paralelo a Vic. "
                           + "La suma de ambos (Vic + Centelles = " + (entryVic + value) + "/min) es la carga total del sistema. "
                           + "Ambos productores comparten la misma cola y el mismo recurso sin coordinacion explicita entre ellos.";
                siSubes    = "La carga total aumenta a " + (entryVic + value) + "/min. "
                           + "Dos productores rapidos contra consumidores lentos aceleran la saturacion. "
                           + "Prueba: Vic al maximo + Centelles al maximo = colapso garantizado.";
                siBajas    = "Reduces presion sobre el sistema. Con Centelles a 0, "
                           + "solo Vic produce. El sistema equivale a un productor unico.";
                codigoJava = "// Dos productores independientes:\nExecutorService prod = Executors.newFixedThreadPool(2);\nprod.submit(() -> { while(true) cola.put(tareaVic()); });\nprod.submit(() -> { while(true) cola.put(tareaCen()); });";
                break;

            case "bcn":
                titulo     = "Salida Barcelona  —  CONSUMIDOR principal (ThreadPoolExecutor)";
                accentColor = new Color(220, 80, 75);
                queEs      = "Barcelona absorbe el grueso del trafico: los threads que NO van a la rotonda. "
                           + "Representa el ThreadPoolExecutor principal de la aplicacion. "
                           + "Su velocidad (" + value + "/min) determina cuantas tareas puede completar por unidad de tiempo. "
                           + "Si entrada > salida de Barcelona + Granollers, el sistema se satura.";
                siSubes    = "El pool procesa mas rapido. Menos coches se acumulan al final de la carretera. "
                           + "Equivale a aumentar el numero de worker threads en el pool o reducir el tiempo de proceso. "
                           + "Con " + value + "/min y entrada de " + (entryVic + entryCen) + "/min: "
                           + (value + exitGran >= entryVic + entryCen ? "sistema EQUILIBRADO." : "aun insuficiente, necesitas mas salida.");
                siBajas    = "El consumidor se vuelve el cuello de botella. Los threads se acumulan esperando. "
                           + "En produccion: latencia creciente, timeouts, y finalmente RejectedExecutionException "
                           + "cuando la cola del pool se llena.";
                codigoJava = "ThreadPoolExecutor bcn = new ThreadPoolExecutor(\n    " + Math.max(1,value/10) + ",  // corePoolSize\n    " + Math.max(2,value/5) + ",  // maxPoolSize\n    60L, TimeUnit.SECONDS,\n    new LinkedBlockingQueue<>(1000)\n);";
                break;
            case "gran": // ACTUALIZADO CON TEXTO EN LENGUAJE NATURAL
                titulo     = "Salida Granollers  —  Efecto Embotellamiento (Backpressure)";
                accentColor = new Color(230, 160, 50);
                queEs      = "Si esta salida es muy lenta, los coches se amontonan dentro de la rotonda. "
                           + "Al llenarse, los coches que vienen de lejos se ven obligados a frenar. "
                           + "Es un mecanismo de defensa automatico: el atasco avisa 'hacia atras' de que no cabe nadie mas, "
                           + "evitando que todo el sistema colapse de golpe.";
                siSubes    = "La rotonda se vacia mas rapido. Menos cola de espera. "
                           + "Con " + value + "/min en Granollers, "
                           + (value > exitBcn ? "esta salida absorbe mas que Barcelona." : "Barcelona sigue siendo la salida principal.");
                siBajas    = "La rotonda se llena. Los coches forman cola esperando entrar. "
                           + "Esto simula un consumidor saturado: el backpressure frena a los que entran.";
                codigoJava = "// Cola acotada = backpressure automatico:\nBlockingQueue<Tarea> rotonda = new ArrayBlockingQueue<>(" + rabCap + ");\n// Si llena, put() bloquea al productor\nrotonda.put(tarea);  // backpressure aqui\n// Consumidor Granollers:\nTarea t = rotonda.take();  // bloquea si vacia";
                break;

            case "rabcap": // ACTUALIZADO CON TEXTO EN LENGUAJE NATURAL
                titulo     = "Rotonda  —  Control de aforo (Maximo " + value + " coches)";
                accentColor = new Color(190, 140, 255);
                queEs      = "Funciona como la barrera de un parking. Si solo caben " + value + " coches, "
                           + "el siguiente tiene que esperar fuera hasta que alguien salga. "
                           + "En programacion, esto evita que demasiadas tareas intenten usar lo mismo a la vez "
                           + "y provoquen un choque o error en el sistema.";
                siSubes    = "Mas coches pueden estar en la rotonda a la vez. "
                           + "La cola de espera desaparece o se reduce mucho. "
                           + "Con " + value + " plazas y " + pctExit + "% de desvio, "
                           + (value >= 10 ? "el flujo sera muy agil." : "puede haber algo de espera.");
                siBajas    = "El paso se vuelve mas estricto. "
                           + "Con valor 1, es como un puente de un solo carril: solo pasa uno a la vez. "
                           + "Veras la cola crecer visualmente ante la rotonda.";
                codigoJava = "Semaphore sem = new Semaphore(" + value + ");\n// Coche quiere entrar a la rotonda:\nsem.acquire();    // bloquea si esta llena\ntry {\n    cruzarRotonda();\n} finally {\n    sem.release(); // sale y libera plaza\n}";
                break;
            case "rabexit":
                titulo     = "Velocidad salida rotonda  —  duracion del trabajo DENTRO del lock";
                accentColor = new Color(160, 120, 230);
                int msOp = 60000 / Math.max(1, value);
                queEs      = "Determina cuanto tiempo tarda cada thread DENTRO de la seccion critica (~" + msOp + "ms/op). "
                           + "Es una de las reglas mas importantes en concurrencia: "
                           + "la seccion critica debe ser LO MAS CORTA POSIBLE. "
                           + "Cuanto mas tiempo este un thread dentro del lock, mas tiempo bloquea a los demas.";
                siSubes    = "El trabajo dentro del lock es mas rapido (~" + msOp + "ms). "
                           + "Los threads pasan por la rotonda rapidamente, reduciendo la cola de espera. "
                           + "Regla de oro: haz el minimo trabajo posible dentro de synchronized{}.";
                siBajas    = "El trabajo tarda mas (~" + msOp + "ms por thread). "
                           + "Aunque la capacidad de la rotonda sea alta, los threads acumulan tiempo de espera. "
                           + "Un lock lento es tan malo como un lock muy restrictivo.";
                codigoJava = "synchronized(lock) {\n    // Este bloque tarda ~" + msOp + "ms\n    // Regla: hacer MINIMO trabajo aqui\n    actualizarEstado();  // rapido\n    // NO hacer I/O, NO esperar, NO llamar a metodos lentos\n}";
                break;
            case "pct":
                titulo     = "% Threads desviados  —  probabilidad de usar la seccion critica";
                accentColor = new Color(140, 100, 210);
                queEs      = "El " + value + "% de los threads necesitan pasar por la rotonda (seccion critica). "
                           + "Simula qué fraccion de las tareas requieren acceso a un recurso compartido con lock. "
                           + "Cuanto mayor es este porcentaje, mayor es la CONTENTION en el sistema. "
                           + "La contention es la causa #1 de problemas de rendimiento en sistemas concurrentes.";
                siSubes    = "Mas threads compiten por el lock de la rotonda. "
                           + "Con " + value + "% y muchos threads activos, la cola ante la rotonda crece. "
                           + "Esto ilustra por que hay que minimizar el uso de recursos compartidos: "
                           + "cada acceso sincronizado es un punto de serializacion.";
                siBajas    = "Menos threads necesitan el lock. La mayoria pasa directo a Barcelona sin parar. "
                           + "Con " + value + "%, la contention es baja y el sistema fluye libremente. "
                           + "Principio clave: disenar para minimizar el estado compartido.";
                codigoJava = "// Solo el " + value + "% de tareas necesitan recurso compartido:\nif (Math.random() < " + String.format("%.2f", value / 100.0) + ") {\n    synchronized(recursoCompartido) {\n        leer_o_escribir();\n    }\n}\n// El resto ejecuta sin lock (mas rapido)";
                break;

            case "rab":
                titulo     = value == 1 ? "Rotonda ACTIVADA  —  seccion critica synchronized" : "Rotonda DESACTIVADA  —  sin seccion critica";
                accentColor = new Color(190, 140, 255);
                queEs      = value == 1
                    ? "La rotonda esta activa: los threads deben ADQUIRIR un permiso (Semaphore) para pasar. "
                      + "Esto serializa el acceso al recurso, garantizando que solo " + rabCap + " threads esten dentro a la vez. "
                      + "Con contention alta veras la cola formarse antes de la entrada."
                    : "La rotonda esta desactivada: paso libre sin lock. "
                      + "Equivale a codigo sin bloque synchronized. Maximo rendimiento, "
                      + "pero sin garantias de exclusion mutua. En codigo real esto podria causar race conditions.";
                siSubes    = value == 1
                    ? "Con la rotonda activa, observa como con entradas altas se forma cola. "
                      + "Sube la capacidad de la rotonda para reducir la espera."
                    : "Sin rotonda, todos los threads van directo a Barcelona. Maximo throughput sin control.";
                siBajas    = value == 1
                    ? "La seccion critica controla el acceso. Ajusta capacidad y velocidad de salida para equilibrar."
                    : "Desactivar la rotonda elimina el cuello de botella de la seccion critica.";
                codigoJava = value == 1
                    ? "// CON seccion critica:\nsem.acquire();\ntry {\n    accederRecursoCompartido();\n} finally {\n    sem.release();\n}"
                    : "// SIN seccion critica (peligroso en codigo real):\naccederRecursoCompartido(); // sin lock\n// Puede haber race conditions!";
                break;
            case "lights":
                titulo     = value == 1 ? "Semaforos ACTIVADOS  —  ReentrantLock explicito" : "Semaforos DESACTIVADOS  —  sin locks explicitos";
                accentColor = new Color(255, 200, 50);
                queEs      = value == 1
                    ? "Los semaforos simulan locks explicitos (ReentrantLock) en puntos concretos del codigo. "
                      + "A diferencia de la rotonda (Semaphore con capacidad), aqui el lock bloquea aunque no haya contention real. "
                      + "Esto demuestra que los locks tienen COSTE incluso cuando no hay espera: adquirir y liberar el lock toma tiempo."
                    : "Sin locks explicitos: los threads pasan sin detenerse. "
                      + "Ilustra la diferencia entre codigo con y sin sincronizacion. "
                      + "En sistemas reales, eliminar locks innecesarios mejora significativamente el rendimiento.";
                siSubes    = value == 1
                    ? "Activa los semaforos y observa como el trafico se ralentiza incluso sin congestion en la rotonda. "
                      + "El overhead de los locks es visible aunque la carretera este vacia."
                    : "Sin semaforos el flujo es libre. Compara el rendimiento con/sin locks activados.";
                siBajas    = value == 1
                    ? "Con locks activos, cada thread paga el coste de adquirir/liberar aunque no haya otros esperando."
                    : "Desactivar los locks elimina puntos de parada artificiales en el flujo.";
                codigoJava = value == 1
                    ? "ReentrantLock lock = new ReentrantLock();\nlock.lock();\ntry {\n    paso(); // avanza con lock adquirido\n} finally {\n    lock.unlock(); // SIEMPRE en finally\n}"
                    : "// Sin lock explicito:\npaso(); // directo, sin overhead\n// Mas rapido pero sin garantias de orden";
                break;

            case "gap":
                titulo     = "Distancia minima  —  tiempo de CPU por thread (Thread.sleep)";
                accentColor = new Color(255, 190, 70);
                queEs      = "La distancia minima entre coches simula el tiempo que un thread ocupa la CPU antes de ceder. "
                           + "Un gap de " + value + " equivale a ~" + (value * 50) + "ms entre ejecuciones. "
                           + "En sistemas reales, los threads alternan entre RUNNABLE y TIMED_WAITING. "
                           + "Thread.sleep() cede la CPU voluntariamente al scheduler del sistema operativo.";
                siSubes    = "Los threads se espacian mas en la carretera. Menor densidad, mas espacio entre coches. "
                           + "Simula threads que duermen mucho entre operaciones: baja utilizacion de CPU "
                           + "pero tambien menor throughput. Tipico en I/O-bound threads.";
                siBajas    = "Los threads se agolpan. Alta densidad en la carretera, mayor probabilidad de congestion. "
                           + "Simula CPU-bound threads que apenas ceden: alta utilizacion pero riesgo de starvation "
                           + "si no hay scheduler preemptivo.";
                codigoJava = "// Ceder CPU periodicamente:\nwhile (trabajando) {\n    hacerTarea();\n    Thread.sleep(" + (value * 50) + "); // cede CPU ~" + (value*50) + "ms\n    // Estado: TIMED_WAITING durante el sleep\n}";
                break;
            case "tick":
                titulo     = "Velocidad simulacion  —  frecuencia del scheduler (scheduleAtFixedRate)";
                accentColor = new Color(200, 160, 60);
                queEs      = "El tick define cada cuantos ms se ejecuta el bucle principal de simulacion (" + value + "ms). "
                           + "Equivale a la frecuencia con que el scheduler del SO reparte tiempo de CPU entre threads. "
                           + "Un tick bajo = alta frecuencia = mas resolución temporal. "
                           + "Un tick alto = scheduler lento = como un sistema muy cargado.";
                siSubes    = "La simulacion va mas LENTA (tick=" + value + "ms entre frames). "
                           + "Puedes ver cada movimiento con mas detalle. "
                           + "Simula un sistema con alta carga de CPU donde el scheduler tarda en dar turno a cada thread.";
                siBajas    = "La simulacion va mas RAPIDA. Los threads avanzan mas frecuentemente. "
                           + "Con tick muy bajo el sistema se acelera y es mas dificil observar patrones de contention.";
                codigoJava = "ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);\nsched.scheduleAtFixedRate(\n    this::bucklePrincipal,\n    0,          // inicial delay\n    " + value + ",       // periodo en ms\n    TimeUnit.MILLISECONDS\n);";
                break;

            default:
                titulo = "Simulador de Concurrencia Java";
                accentColor = new Color(100, 180, 255);
                queEs = "Mueve cualquier slider para ver aqui una explicacion detallada del concepto de hilos que representa.";
                siSubes = "";
                siBajas = "";
                codigoJava = "";
        }

        // ── Construir el HTML rico para el JTextPane ──
        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family:SansSerif; font-size:11px; background:#0e1018; color:#c8cce0; margin:10px;'>");

        // Titulo con color de acento
        String hex = String.format("#%02x%02x%02x", accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue());
        html.append("<div style='border-left:4px solid ").append(hex)
            .append("; padding-left:10px; margin-bottom:10px;'>")
            .append("<span style='color:").append(hex).append("; font-size:13px; font-weight:bold;'>")
            .append(titulo).append("</span></div>");

        // Tres columnas: Que es | Si subes | Si bajas
        html.append("<table width='100%' cellspacing='0' cellpadding='0'><tr valign='top'>");

        // Col 1: Que es
        html.append("<td width='38%' style='padding-right:12px;'>")
            .append("<div style='color:#64b4ff; font-weight:bold; font-size:10px; margin-bottom:4px;'>")
            .append("&#128218; QUE ES EN TERMINOS DE HILOS</div>")
            .append("<div style='color:#a8b4cc; line-height:1.5;'>").append(queEs).append("</div>")
            .append("</td>");

        // Separador
        html.append("<td width='1%' style='border-left:1px solid #1e2840;'></td>");

        if (!siSubes.isEmpty()) {
            // Col 2: Si subes
            html.append("<td width='28%' style='padding:0 12px;'>")
                .append("<div style='color:#50dc90; font-weight:bold; font-size:10px; margin-bottom:4px;'>")
                .append("&#9650; SI SUBES este valor</div>")
                .append("<div style='color:#a8b4cc; line-height:1.5;'>").append(siSubes).append("</div>")
                .append("</td>");

            // Separador
            html.append("<td width='1%' style='border-left:1px solid #1e2840;'></td>");

            // Col 3: Si bajas
            html.append("<td width='28%' style='padding:0 12px;'>")
                .append("<div style='color:#ff7850; font-weight:bold; font-size:10px; margin-bottom:4px;'>")
                .append("&#9660; SI BAJAS este valor</div>")
                .append("<div style='color:#a8b4cc; line-height:1.5;'>").append(siBajas).append("</div>")
                .append("</td>");
        }

        html.append("</tr></table>");

        // Codigo Java
        if (!codigoJava.isEmpty()) {
            html.append("<div style='margin-top:10px; background:#080c14; border:1px solid #1e2840; border-radius:4px; padding:8px;'>")
                .append("<span style='color:#555e80; font-size:9px; font-weight:bold;'>CODIGO JAVA EQUIVALENTE &nbsp;</span>")
                .append("<pre style='color:#90cce8; font-size:10px; margin:4px 0 0 0; font-family:Monospaced;'>")
                .append(codigoJava.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"))
                .append("</pre></div>");
        }

        html.append("</body></html>");

        final String finalHtml = html.toString();
        SwingUtilities.invokeLater(() -> {
            eduPane.setText(finalHtml);
            eduPane.setCaretPosition(0);
        });
    }

    // ── Pintura de la simulacion ───────────────────────────────────────────
    @Override
    protected synchronized void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        int W = getWidth(), H = getHeight(), M = 20;
        g.setPaint(new GradientPaint(0, 0, new Color(18, 20, 30), W, H, new Color(28, 35, 48)));
        g.fillRect(0, 0, W, H);

        // Titulo
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.setColor(new Color(100, 190, 255));
        g.drawString("SIMULADOR DE TRAFICO  C-17:  VIC >>> BARCELONA", M, 22);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(150, 150, 170));
        g.drawString(String.format("Tiempo: %02d:%02d", simSec / 60, simSec % 60), W - 130, 22);

        int roadX = M, roadW = W - 2 * M;
        double cellW = roadW / (double) ROAD_LEN;
        int[][] cities = {{E_VIC, 0}, {E_CENTELLES, 0}, {X_GRANOLLERS, 1}, {X_BARCELONA, 1}};
        String[] names = {"VIC", "CENTELLES", "GRANOLLERS", "BARCELONA"};
        String[] types = {"ENTRADA", "ENTRADA", "SALIDA", "SALIDA"};
        Color[] cols = {new Color(70,200,120), new Color(70,160,230), new Color(230,160,50), new Color(220,80,75)};

        // Etiquetas de ciudades
        for (int ci = 0; ci < 4; ci++) {
            int cx = (int)(roadX + cities[ci][0] * cellW);
            boolean isEntry = cities[ci][1] == 0;
            Color col = cols[ci];
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.setColor(col);
            FontMetrics fm1 = g.getFontMetrics();
            g.drawString(names[ci], cx - fm1.stringWidth(names[ci]) / 2, 42);
            g.setFont(new Font("SansSerif", Font.PLAIN, 9));
            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 180));
            FontMetrics fm2 = g.getFontMetrics();
            g.drawString(types[ci], cx - fm2.stringWidth(types[ci]) / 2, 54);
            if (isEntry) {
                int qs = (ci == 0) ? qVic.size() : qCen.size();
                if (qs > 0) {
                    g.setFont(new Font("Consolas", Font.BOLD, 11));
                    g.setColor(qs > 20 ? new Color(255, 90, 70) : new Color(255, 190, 70));
                    String qt = "Cola: " + qs;
                    FontMetrics fm3 = g.getFontMetrics();
                    g.drawString(qt, cx - fm3.stringWidth(qt) / 2, 66);
                }
            } else {
                long cnt = (ci == 2) ? exGran : exBcn;
                g.setFont(new Font("Consolas", Font.BOLD, 10));
                g.setColor(new Color(190, 190, 210));
                String ct = "Salidos: " + cnt;
                FontMetrics fm3 = g.getFontMetrics();
                g.drawString(ct, cx - fm3.stringWidth(ct) / 2, 66);
            }
        }

        int roadY = 92, laneH = 30, roadH = laneH * LANES;

        // Flechas de entrada/salida
        for (int ci = 0; ci < 4; ci++) {
            int cx = (int)(roadX + cities[ci][0] * cellW);
            boolean isEntry = cities[ci][1] == 0;
            Color col = cols[ci];
            g.setColor(col);
            g.setStroke(new BasicStroke(2.5f));
            if (isEntry) {
                g.drawLine(cx, 70, cx, roadY - 2);
                g.fillPolygon(new int[]{cx-4, cx+4, cx}, new int[]{roadY-7, roadY-7, roadY-1}, 3);
            } else {
                int bot = roadY + roadH + 2;
                g.drawLine(cx, bot, cx, bot + 16);
                g.fillPolygon(new int[]{cx-4, cx+4, cx}, new int[]{bot+11, bot+11, bot+17}, 3);
            }
            g.setStroke(new BasicStroke(1f));
        }

        // Semaforos
        if (lightsOn) for (Light tl : lights) {
            int sx = (int)(roadX + tl.pos * cellW);
            g.setColor(new Color(25, 25, 30));
            g.fillRoundRect(sx - 7, roadY - 14, 14, 13, 4, 4);
            g.setColor(tl.red ? new Color(255, 40, 40) : new Color(40, 255, 40));
            g.fillOval(sx - 4, roadY - 12, 9, 9);
            g.setColor(new Color(80, 80, 90));
            g.drawRoundRect(sx - 7, roadY - 14, 14, 13, 4, 4);
        }

        // Calor por segmento
        for (int s = 0; s < segD.length; s++) {
            float d = (float) Math.min(1, segD[s]);
            if (d > 0.08) {
                int sx = (int)(roadX + s * SEG_SIZE * cellW), sw = (int)(SEG_SIZE * cellW) + 1;
                Color hc = heat(d);
                g.setColor(new Color(hc.getRed(), hc.getGreen(), hc.getBlue(), 40));
                g.fillRect(sx, roadY - 2, sw, roadH + 4);
            }
        }

        // Carretera
        g.setColor(new Color(48, 50, 58));
        g.fillRoundRect(roadX - 2, roadY - 2, roadW + 4, roadH + 4, 8, 8);
        fillZone(g, roadX, roadY, cellW, roadH, E_VIC, 6, new Color(70,200,120,20));
        fillZone(g, roadX, roadY, cellW, roadH, E_CENTELLES, 6, new Color(70,160,230,20));
        fillZone(g, roadX, roadY, cellW, roadH, X_GRANOLLERS-2, 6, new Color(230,160,50,20));
        fillZone(g, roadX, roadY, cellW, roadH, X_BARCELONA-2, 6, new Color(220,80,75,20));

        // Lineas de carril
        for (int l = 0; l <= LANES; l++) {
            int y = roadY + l * laneH;
            if (l == 0 || l == LANES) { g.setColor(new Color(180,180,180,130)); g.setStroke(new BasicStroke(2f)); }
            else { g.setColor(new Color(255,255,255,50)); g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f,5f}, 0f)); }
            g.drawLine(roadX, y, roadX + roadW, y);
        }
        g.setStroke(new BasicStroke(1f));

        // Cuello de botella pulsante
        if (bnSeg >= 0) {
            long pulse = System.currentTimeMillis() % 1000;
            int a = (int)(25 + 35 * Math.sin(pulse * Math.PI / 500.0));
            int bx = (int)(roadX + bnSeg * SEG_SIZE * cellW), bw = (int)(SEG_SIZE * cellW) + 4;
            g.setColor(new Color(255, 40, 40, a));
            g.fillRoundRect(bx-2, roadY-2, bw, roadH+4, 6, 6);
            g.setColor(new Color(255, 70, 70, 100));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f,3f}, 0f));
            g.drawRoundRect(bx-2, roadY-2, bw, roadH+4, 6, 6);
            g.setStroke(new BasicStroke(1f));
        }

        // Coches
        for (int l = 0; l < LANES; l++) for (int i = 0; i < ROAD_LEN; i++) {
            Car c = road[l][i]; if (c == null) continue;
            int cx = (int)(roadX + i * cellW), cy = roadY + l * laneH + 5;
            int cw = (int) Math.max(8, cellW - 1), ch = laneH - 10;
            g.setColor(new Color(0, 0, 0, 25));
            g.fillRoundRect(cx+1, cy+1, cw, ch, 4, 4);
            g.setColor(c.braking ? c.color.darker() : c.color);
            g.fillRoundRect(cx, cy, cw, ch, 4, 4);
            if (c.braking) { g.setColor(new Color(255,25,25,200)); g.fillOval(cx,cy+1,3,3); g.fillOval(cx,cy+ch-4,3,3); }
            if (c.wantsExit && i > X_GRANOLLERS-25 && i < X_GRANOLLERS && System.currentTimeMillis()%600<300) {
                g.setColor(new Color(255,200,40,200)); g.fillOval(cx+cw-3,cy,3,3);
            }
        }

        int belowY = roadY + roadH + 4;

        // Km markers
        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g.setColor(new Color(90, 90, 110));
        for (int km = 0; km <= 70; km += 10) {
            int x = (int)(roadX + (km * ROAD_LEN / 70.0) * cellW);
            g.drawLine(x, belowY, x, belowY + 5);
            g.drawString("km" + km, x - 10, belowY + 15);
        }

        // Rotonda
        if (rabOn) {
            int rx = (int)(roadX + X_GRANOLLERS * cellW) + 50, ry = belowY + 30, sz = 44;
            float fill = (float) rabCars.size() / Math.max(1, rabCap);
            g.setColor(new Color(40, 42, 52));
            g.fillOval(rx-sz/2, ry-sz/2, sz, sz);
            g.setColor(heat(fill));
            g.setStroke(new BasicStroke(3f));
            g.drawOval(rx-sz/2, ry-sz/2, sz, sz);
            g.setStroke(new BasicStroke(1f));
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(Color.WHITE);
            String rt = rabCars.size() + "/" + rabCap;
            FontMetrics fmr = g.getFontMetrics();
            g.drawString(rt, rx - fmr.stringWidth(rt) / 2, ry + 4);
            synchronized (rabCars) {
                int n = rabCars.size();
                for (int i = 0; i < n; i++) {
                    double ang = 2 * Math.PI * i / Math.max(1, n);
                    int px = (int)(rx + (sz/2-8)*Math.cos(ang)), py = (int)(ry + (sz/2-8)*Math.sin(ang));
                    g.setColor(rabCars.get(i).color);
                    g.fillRoundRect(px-3, py-2, 6, 4, 2, 2);
                }
            }
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            g.setColor(new Color(190, 170, 240));
            g.drawString("ROTONDA", rx-sz/2-2, ry-sz/2-6);
            g.setFont(new Font("SansSerif", Font.ITALIC, 8));
            g.setColor(new Color(140, 140, 170));
            g.drawString("(seccion critica)", rx-sz/2-6, ry-sz/2+4);
            int exitX = (int)(roadX + X_GRANOLLERS * cellW);
            g.setColor(new Color(80, 80, 100));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(exitX, belowY+2, exitX, belowY+18);
            g.drawLine(exitX, belowY+18, rx-sz/2, ry);
            g.setStroke(new BasicStroke(1f));
        }

        // Etiqueta atasco
        if (bnSeg >= 0) {
            int bx = (int)(roadX + bnSeg * SEG_SIZE * cellW);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(new Color(255, 90, 90));
            g.drawString("ATASCO", bx - 8, belowY + 16);
        }

        // Diagnostico
        int diagY = belowY + 58, diagH = 48;
        drawBox(g, M, diagY, W-2*M, diagH);
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(diagColor);
        g.drawString("DIAGNOSTICO:", M+10, diagY+16);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(diagColor.brighter());
        FontMetrics fmd = g.getFontMetrics();
        int maxTW = W - 2*M - 24;
        if (fmd.stringWidth(diagMsg) > maxTW) {
            int cut = diagMsg.length();
            while (cut > 0 && fmd.stringWidth(diagMsg.substring(0, cut)) > maxTW) cut--;
            cut = diagMsg.lastIndexOf(' ', cut);
            if (cut > 0) { g.drawString(diagMsg.substring(0, cut), M+10, diagY+32); g.drawString(diagMsg.substring(cut+1), M+10, diagY+44); }
            else g.drawString(diagMsg, M+10, diagY+32);
        } else g.drawString(diagMsg, M+10, diagY+32);

        // Metricas
        int metY = diagY + diagH + 6, metH = 56;
        drawBox(g, M, metY, W-2*M, metH);
        g.setFont(new Font("Consolas", Font.PLAIN, 12));
        g.setColor(new Color(185, 200, 240));
        int c1 = M+10, c2 = M+(W-2*M)/3, c3 = M+2*(W-2*M)/3;
        g.drawString("Coches: " + carsOn, c1, metY+17);
        g.drawString("Entrados: " + (enVic+enCen), c1, metY+33);
        g.drawString("Salidos:  " + (exGran+exBcn), c1, metY+49);
        g.drawString("Cola Vic: " + qVic.size() + " (max " + maxQV + ")", c2, metY+17);
        g.drawString("Cola Cen: " + qCen.size() + " (max " + maxQC + ")", c2, metY+33);
        g.drawString("Rotonda:  " + rabCars.size() + "/" + rabCap, c2, metY+49);
        g.drawString("Salidos Gran: " + exGran, c3, metY+17);
        g.drawString("Salidos BCN:  " + exBcn, c3, metY+33);
        g.setColor(bnSeg >= 0 ? new Color(255,100,100) : new Color(100,255,100));
        g.drawString(bnSeg >= 0 ? "Cuello: " + bnName : "Sin atascos", c3, metY+49);

        // Graficas
        int gY = metY + metH + 6, gH = 44, gW = (W-2*M-6)/2;
        drawBox(g, M, gY, gW, gH);
        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g.setColor(new Color(130, 130, 150));
        g.drawString("Densidad por zona", M+6, gY+11);
        for (int s = 0; s < segD.length; s++) {
            int bx = M+4+(int)((gW-8.0)*s/segD.length), bw = Math.max(2,(int)((gW-8.0)/segD.length)-1), bh = (int)(segD[s]*(gH-16));
            g.setColor(heat((float) segD[s]));
            g.fillRect(bx, gY+gH-bh-2, bw, bh);
        }
        int g2X = M+gW+6, g2W = gW;
        drawBox(g, g2X, gY, g2W, gH);
        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g.setColor(new Color(130, 130, 150));
        g.drawString("Historico coches", g2X+6, gY+11);
        if (hIdx > 1) {
            int n = Math.min(hIdx, HIST); double mx = 1;
            for (int i = 0; i < n; i++) mx = Math.max(mx, histCars[(hIdx-n+i) % HIST]);
            g.setColor(new Color(80, 180, 255, 160));
            int px = -1, py = -1;
            for (int i = 0; i < n; i++) {
                int xx = g2X+4+(int)((g2W-8.0)*i/n);
                int yy = gY+gH-4-(int)(histCars[(hIdx-n+i)%HIST]/mx*(gH-18));
                if (px >= 0) g.drawLine(px, py, xx, yy);
                px = xx; py = yy;
            }
        }
    }

    void fillZone(Graphics2D g, int rx, int ry, double cw, int rh, int pos, int len, Color c) {
        g.setColor(c);
        g.fillRect((int)(rx + pos*cw), ry, (int)(len*cw), rh);
    }

    void drawBox(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(18, 22, 36, 200));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setColor(new Color(50, 65, 105, 60));
        g.drawRoundRect(x, y, w, h, 8, 8);
    }

    Color heat(float v) {
        v = Math.max(0, Math.min(1, v));
        if (v < 0.3f) return new Color((int)(v/0.3f*200), 210, 80);
        if (v < 0.6f) return new Color(240, (int)(210-(v-0.3f)/0.3f*130), 50);
        return new Color(255, (int)(80-(v-0.6f)/0.4f*70), (int)(50-(v-0.6f)/0.4f*40));
    }

    // ── main ──────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            VicBarcelonaTrafficSim sim = new VicBarcelonaTrafficSim();
            JFrame frame = new JFrame("Simulador Trafico C-17: Vic - Barcelona");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

            // Panel izquierdo: simulacion arriba + panel educativo abajo
            JPanel leftPanel = new JPanel(new BorderLayout());
            leftPanel.setBackground(new Color(18, 20, 30));

            // Panel educativo (JTextPane con HTML) en la parte inferior
            JTextPane edu = new JTextPane();
            edu.setContentType("text/html");
            edu.setEditable(false);
            edu.setBackground(new Color(14, 16, 24));
            edu.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            edu.setText("<html><body style='font-family:SansSerif; font-size:11px; color:#555e80; margin:12px;'>"
                + "<span style='color:#3a4060; font-size:13px; font-weight:bold;'>Panel educativo</span><br><br>"
                + "Mueve cualquier slider del panel derecho y aqui aparecera:<br>"
                + "&nbsp;&nbsp;&#128218; <b style='color:#64b4ff;'>Que concepto de hilos representa</b><br>"
                + "&nbsp;&nbsp;&#9650; <b style='color:#50dc90;'>Que pasa si subes el valor</b><br>"
                + "&nbsp;&nbsp;&#9660; <b style='color:#ff7850;'>Que pasa si lo bajas</b><br>"
                + "&nbsp;&nbsp;&#128196; <b style='color:#90cce8;'>Codigo Java equivalente</b>"
                + "</body></html>");
            sim.eduPane = edu;

            JScrollPane eduScroll = new JScrollPane(edu);
            eduScroll.setBorder(BorderFactory.createMatteBorder(2, 0, 0, 0, new Color(25, 30, 55)));
            eduScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            eduScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            eduScroll.setPreferredSize(new Dimension(0, 220));
            eduScroll.getViewport().setBackground(new Color(14, 16, 24));

            leftPanel.add(sim, BorderLayout.NORTH);
            leftPanel.add(eduScroll, BorderLayout.CENTER);

            // Panel derecho: controles
            JPanel ctrl = buildCtrl(sim);
            ctrl.setPreferredSize(new Dimension(400, 0));

            JPanel content = new JPanel(new BorderLayout());
            content.add(leftPanel, BorderLayout.CENTER);
            content.add(ctrl, BorderLayout.EAST);

            frame.setContentPane(content);
            frame.setSize(1600, 900);
            frame.setLocationRelativeTo(null);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setVisible(true);
            sim.start();
            frame.addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) { sim.running = false; sim.sched.shutdownNow(); }
            });
        });
    }

    // ── Panel de control derecho ──────────────────────────────────────────
    static class FillPanel extends JPanel implements javax.swing.Scrollable {
        FillPanel(LayoutManager lm) { super(lm); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle vr, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle vr, int o, int d) { return 60; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static JPanel buildCtrl(VicBarcelonaTrafficSim sim) {
        JPanel mainP = new JPanel(new BorderLayout());
        mainP.setBackground(new Color(22, 24, 34));

        final FillPanel scrollContent = new FillPanel(new GridBagLayout());
        scrollContent.setBackground(new Color(22, 24, 34));
        scrollContent.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));
        final int[] row = {0};

        // Cabecera
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(15, 17, 26));
        header.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel hTitle = new JLabel("Panel de Control");
        hTitle.setForeground(new Color(130, 200, 255));
        hTitle.setFont(new Font("SansSerif", Font.BOLD, 15));
        JLabel hSub = new JLabel("Hilos y Concurrencia — C17 Vic/Barcelona");
        hSub.setForeground(new Color(80, 90, 120));
        hSub.setFont(new Font("SansSerif", Font.ITALIC, 11));
        JPanel hTexts = new JPanel(new BorderLayout());
        hTexts.setOpaque(false);
        hTexts.add(hTitle, BorderLayout.NORTH);
        hTexts.add(hSub, BorderLayout.SOUTH);
        header.add(hTexts, BorderLayout.WEST);
        addRow(scrollContent, row, header);
        addRow(scrollContent, row, accentLine(new Color(40, 60, 100)));

        // Escenarios
        addRow(scrollContent, row, sectionHeader("RETOS INTERACTIVOS", "Provoca un error de hilos y arreglalo", new Color(80, 200, 130)));

        addRow(scrollContent, row, presetCard("1. Reto: El Cuello de Botella",
            "Problema: Capacidad de rotonda a 1 (Mutex). Solo pasa 1 coche. Solución: Sube 'Capacidad' a 15 para permitir paralelismo.",
            new Color(55,35,10), new Color(220,140,30), () -> {
                sim.entryVic=60; sim.entryCen=40; sim.exitGran=30; sim.exitBcn=60;
                sim.rabCap=1; sim.rabExit=20; sim.pctExit=40; sim.lightsOn=false; sim.rabOn=true;
                syncSliders(sim);
            }));

        addRow(scrollContent, row, presetCard("2. Reto: Servidor Saturado",
            "Problema: El Pool principal (Barcelona) procesa muy lento y la cola crece. Solución: Sube la salida de 'Barcelona' a 80.",
            new Color(60,25,20), new Color(220,70,50), () -> {
                sim.entryVic=80; sim.entryCen=40; sim.exitBcn=10; sim.exitGran=20;
                sim.rabCap=15; sim.rabExit=20; sim.pctExit=20; sim.lightsOn=false; sim.rabOn=true;
                syncSliders(sim);
            }));

        addRow(scrollContent, row, presetCard("3. Reto: Operacion lenta en Lock",
            "Problema: Entrar a la rotonda es rapido, pero salir tarda mucho (I/O lento). Solución: Sube 'Vel. proceso' a 40.",
            new Color(40,15,45), new Color(190,80,220), () -> {
                sim.entryVic=50; sim.entryCen=30; sim.exitBcn=50; sim.exitGran=30;
                sim.rabCap=20; sim.rabExit=2; sim.pctExit=60; sim.lightsOn=false; sim.rabOn=true;
                syncSliders(sim);
            }));

        addRow(scrollContent, row, presetCard("4. Reto: Exceso de recurso compartido",
            "Problema: Casi todos los hilos intentan usar la rotonda a la vez. Solución: Baja '% desviados' a 20 para evitar la contención.",
            new Color(15,40,55), new Color(40,150,220), () -> {
                sim.entryVic=50; sim.entryCen=30; sim.exitBcn=50; sim.exitGran=30;
                sim.rabCap=5; sim.rabExit=15; sim.pctExit=95; sim.lightsOn=false; sim.rabOn=true;
                syncSliders(sim);
            }));

        // Tutor en vivo (solo diagnostico de atasco, breve)
        addRow(scrollContent, row, sectionHeader("Explicacion en vivo", "Estado del sistema en tiempo real", new Color(120, 170, 255)));
        JPanel tutorPanel = new JPanel(new BorderLayout(10, 0));
        tutorPanel.setBackground(new Color(28, 32, 48));
        tutorPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0,3,0,0,new Color(80,130,220)),
            BorderFactory.createEmptyBorder(10,12,10,12)));
        sim.lAdv = new JLabel();
        sim.lAdv.setForeground(new Color(100, 200, 255));
        sim.lAdv.setFont(new Font("SansSerif", Font.PLAIN, 11));
        sim.diagnostic();
        JLabel tutorIcon = new JLabel("?");
        tutorIcon.setForeground(new Color(80, 130, 220));
        tutorIcon.setFont(new Font("SansSerif", Font.BOLD, 22));
        tutorPanel.add(tutorIcon, BorderLayout.WEST);
        tutorPanel.add(sim.lAdv, BorderLayout.CENTER);
        JPanel tutorWrap = new JPanel(new BorderLayout());
        tutorWrap.setOpaque(false);
        tutorWrap.setBorder(BorderFactory.createEmptyBorder(0,10,0,10));
        tutorWrap.add(tutorPanel, BorderLayout.CENTER);
        addRow(scrollContent, row, tutorWrap);
        addRow(scrollContent, row, vSpacer(8));

        // PRODUCTORES
        addRow(scrollContent, row, sectionHeader("PRODUCTORES — Entradas", "Generan nuevos threads (coches) al sistema", new Color(70,200,120)));
        addRow(scrollContent, row, conceptChip("Coche = Thread  |  Cola = BlockingQueue  |  Entrada saturada = productor bloqueado"));
        JPanel slidersIn = new JPanel(new GridBagLayout());
        slidersIn.setOpaque(false);
        slidersIn.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        sim.sVic = sliderRow(slidersIn, 0, "Vic", "threads/min desde Vic", 0, 150, sim.entryVic, new Color(70,200,120), v -> { sim.entryVic=v; sim.explainAction("vic",v); });
        sim.sCen = sliderRow(slidersIn, 1, "Centelles", "threads/min desde Centelles", 0, 150, sim.entryCen, new Color(70,160,230), v -> { sim.entryCen=v; sim.explainAction("cen",v); });
        addRow(scrollContent, row, slidersIn);
        addRow(scrollContent, row, vSpacer(8));

        // CONSUMIDORES
        addRow(scrollContent, row, sectionHeader("CONSUMIDORES — Salidas", "Velocidad a la que los threads abandonan el sistema", new Color(220,80,75)));
        addRow(scrollContent, row, conceptChip("Thread pool = salida  |  Saturacion = consumidor mas lento que el productor"));
        JPanel slidersOut = new JPanel(new GridBagLayout());
        slidersOut.setOpaque(false);
        slidersOut.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        sim.sBcn  = sliderRow(slidersOut, 0, "Barcelona", "capacidad del consumidor principal", 0, 80, sim.exitBcn, new Color(220,80,75), v -> { sim.exitBcn=v; sim.explainAction("bcn",v); });
        sim.sGran = sliderRow(slidersOut, 1, "Granollers", "velocidad de la salida secundaria", 0, 80, sim.exitGran, new Color(230,160,50), v -> { sim.exitGran=v; sim.explainAction("gran",v); });
        addRow(scrollContent, row, slidersOut);
        addRow(scrollContent, row, vSpacer(8));

        // SECCION CRITICA
        addRow(scrollContent, row, sectionHeader("SECCION CRITICA — Rotonda", "Simula un bloque synchronized o un Mutex", new Color(190,140,255)));
        addRow(scrollContent, row, conceptChip("Rotonda = synchronized  |  Capacidad = Semaphore(N)  |  Cola = threads bloqueados en el lock"));
        JPanel slidersRab = new JPanel(new GridBagLayout());
        slidersRab.setOpaque(false);
        slidersRab.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        sim.sRabCap  = sliderRow(slidersRab, 0, "Capacidad", "max threads en la seccion critica", 1, 40, sim.rabCap, new Color(190,140,255), v -> { sim.rabCap=v; sim.explainAction("rabcap",v); });
        sim.sRabExit = sliderRow(slidersRab, 1, "Vel. proceso", "velocidad de proceso dentro del lock", 1, 50, sim.rabExit, new Color(160,120,230), v -> { sim.rabExit=v; sim.explainAction("rabexit",v); });
        sim.sPct     = sliderRow(slidersRab, 2, "% desviados", "% de threads que usan la sec. critica", 0, 100, sim.pctExit, new Color(140,100,210), v -> { sim.pctExit=v; sim.explainAction("pct",v); });
        addRow(scrollContent, row, slidersRab);
        JPanel cbP1 = new JPanel(new BorderLayout());
        cbP1.setOpaque(false);
        cbP1.setBorder(BorderFactory.createEmptyBorder(2,14,4,10));
        JCheckBox cbRab = new JCheckBox("Activar rotonda (seccion critica)", sim.rabOn);
        styleCb(cbRab);
        cbRab.addActionListener(e -> { sim.rabOn = cbRab.isSelected(); sim.explainAction("rab", sim.rabOn ? 1 : 0); });
        cbP1.add(cbRab, BorderLayout.WEST);
        addRow(scrollContent, row, cbP1);
        addRow(scrollContent, row, vSpacer(8));

        // LOCKS Y FISICA
        addRow(scrollContent, row, sectionHeader("LOCKS Y FISICA", "Semaforos explicitos y parametros de simulacion", new Color(255,190,70)));
        addRow(scrollContent, row, conceptChip("Semaforo = ReentrantLock  |  Distancia = tiempo CPU  |  Tick = frecuencia del scheduler"));
        JPanel slidersPhys = new JPanel(new GridBagLayout());
        slidersPhys.setOpaque(false);
        slidersPhys.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        sim.sGap  = sliderRow(slidersPhys, 0, "Distancia min", "separacion entre threads (tiempo CPU)", 1, 8, sim.gap, new Color(255,190,70), v -> { sim.gap=v; sim.explainAction("gap",v); });
        sim.sTick = sliderRow(slidersPhys, 1, "Velocidad sim", "ms por tick (menor = mas rapido)", 20, 300, sim.tickMs, new Color(200,160,60), v -> { sim.tickMs=v; sim.explainAction("tick",v); });
        addRow(scrollContent, row, slidersPhys);
        JPanel cbP2 = new JPanel(new BorderLayout());
        cbP2.setOpaque(false);
        cbP2.setBorder(BorderFactory.createEmptyBorder(2,14,4,10));
        JCheckBox cbLt = new JCheckBox("Activar semaforos (ReentrantLock)", sim.lightsOn);
        styleCb(cbLt);
        cbLt.addActionListener(e -> { sim.lightsOn = cbLt.isSelected(); sim.explainAction("lights", sim.lightsOn ? 1 : 0); });
        cbP2.add(cbLt, BorderLayout.WEST);
        addRow(scrollContent, row, cbP2);
        addRow(scrollContent, row, vSpacer(8));

        // Botones
        addRow(scrollContent, row, accentLine(new Color(40,50,80)));
        JPanel ctrlBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        ctrlBar.setBackground(new Color(18, 20, 30));
        JButton bPause = actionBtn("Pausar", new Color(180,150,40));
        bPause.addActionListener(e -> {
            sim.running = !sim.running;
            bPause.setText(sim.running ? "Pausar" : "Seguir");
            bPause.setBackground(sim.running ? new Color(180,150,40) : new Color(50,160,80));
        });
        JButton bReset = actionBtn("Reiniciar", new Color(160,60,50));
        bReset.addActionListener(e -> {
            for (int l = 0; l < LANES; l++) Arrays.fill(sim.road[l], null);
            sim.rabCars.clear(); sim.qVic.clear(); sim.qCen.clear();
            sim.exGran = sim.exBcn = sim.enVic = sim.enCen = 0;
            sim.simStart = System.currentTimeMillis(); sim.simSec = 0;
            sim.repaint();
        });
        ctrlBar.add(bPause);
        ctrlBar.add(bReset);
        addRow(scrollContent, row, ctrlBar);

        // Glue final
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = row[0]++; gc.weighty = 1.0; gc.fill = GridBagConstraints.BOTH;
        scrollContent.add(Box.createGlue(), gc);

        JScrollPane scroll = new JScrollPane(scrollContent);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.getViewport().setBackground(new Color(22, 24, 34));
        scroll.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() { thumbColor = new Color(55,65,100); trackColor = new Color(22,24,34); }
            @Override protected JButton createDecreaseButton(int o) { JButton b = new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
            @Override protected JButton createIncreaseButton(int o) { JButton b = new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
        });
        mainP.add(scroll, BorderLayout.CENTER);
        return mainP;
    }

    // ── Helpers de UI ─────────────────────────────────────────────────────
    static void addRow(JPanel p, int[] row, JComponent c) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = row[0]++; gc.weightx = 1.0;
        gc.fill = GridBagConstraints.HORIZONTAL; gc.anchor = GridBagConstraints.NORTHWEST;
        p.add(c, gc);
    }

    static JPanel vSpacer(int h) {
        JPanel p = new JPanel(); p.setOpaque(false);
        p.setPreferredSize(new Dimension(0,h)); p.setMinimumSize(new Dimension(0,h));
        return p;
    }

    static JPanel accentLine(Color c) {
        JPanel l = new JPanel(); l.setBackground(c);
        l.setPreferredSize(new Dimension(0,1)); l.setMinimumSize(new Dimension(0,1));
        return l;
    }

    static JPanel sectionHeader(String title, String subtitle, Color accent) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBackground(new Color(26, 28, 40));
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(2,0,0,0,new Color(35,40,65)),
            BorderFactory.createEmptyBorder(9,10,7,10)));
        JLabel t = new JLabel(title);
        t.setForeground(accent); t.setFont(new Font("SansSerif", Font.BOLD, 12));
        JLabel s = new JLabel("<html><i><font color='#555870'>" + subtitle + "</font></i></html>");
        s.setFont(new Font("SansSerif", Font.ITALIC, 9));
        JPanel texts = new JPanel(new BorderLayout()); texts.setOpaque(false);
        texts.add(t, BorderLayout.NORTH); texts.add(s, BorderLayout.CENTER);
        JPanel bar = new JPanel(); bar.setBackground(accent); bar.setPreferredSize(new Dimension(4,0));
        p.add(bar, BorderLayout.WEST); p.add(texts, BorderLayout.CENTER);
        return p;
    }

    static JPanel conceptChip(String text) {
        JPanel wrap = new JPanel(new BorderLayout()); wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createEmptyBorder(0,10,0,10));
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(32, 36, 52));
        p.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1,1,1,1,new Color(45,55,90)),
            BorderFactory.createEmptyBorder(5,10,5,10)));
        JLabel l = new JLabel("<html><div style='color:#606ca8;font-size:9px'>" + text + "</div></html>");
        l.setFont(new Font("SansSerif", Font.PLAIN, 9));
        p.add(l, BorderLayout.CENTER); wrap.add(p, BorderLayout.CENTER);
        return wrap;
    }

    static JPanel presetCard(String title, String desc, Color bg, Color accent, Runnable action) {
        JPanel outer = new JPanel(new BorderLayout()); outer.setOpaque(false);
        outer.setBorder(BorderFactory.createEmptyBorder(2,10,2,10));
        JPanel card = new JPanel(new BorderLayout(8,0)); card.setBackground(bg);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0,4,0,0,accent),
            BorderFactory.createEmptyBorder(7,10,7,8)));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel texts = new JPanel(new BorderLayout()); texts.setOpaque(false);
        JLabel tl = new JLabel("<html><b>" + title + "</b></html>");
        tl.setForeground(Color.WHITE); tl.setFont(new Font("SansSerif",Font.BOLD,11));
        JLabel dl = new JLabel("<html><div style='color:#8898bb;font-size:9px'>" + desc + "</div></html>");
        dl.setFont(new Font("SansSerif",Font.PLAIN,9));
        texts.add(tl, BorderLayout.NORTH); texts.add(dl, BorderLayout.CENTER);
        JButton btn = new JButton(">");
        btn.setBackground(accent); btn.setForeground(Color.WHITE);
        btn.setFont(new Font("SansSerif",Font.BOLD,13));
        btn.setFocusPainted(false); btn.setBorderPainted(false); btn.setOpaque(true);
        btn.setPreferredSize(new Dimension(30,30));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> action.run());
        card.addMouseListener(new MouseAdapter() { public void mouseClicked(MouseEvent e) { action.run(); } });
        card.add(texts, BorderLayout.CENTER); card.add(btn, BorderLayout.EAST);
        outer.add(card, BorderLayout.CENTER); return outer;
    }

    static JSlider sliderRow(JPanel p, int rowIdx, String label, String tooltip, int min, int max, int val,
                              Color accent, java.util.function.IntConsumer onChange) {
        JPanel labelCol = new JPanel(new BorderLayout()); labelCol.setOpaque(false);
        labelCol.setPreferredSize(new Dimension(90, 36));
        JLabel lb = new JLabel(label); lb.setForeground(new Color(180,185,210)); lb.setFont(new Font("SansSerif",Font.BOLD,11));
        JLabel tt = new JLabel(tooltip); tt.setForeground(new Color(70,80,110)); tt.setFont(new Font("SansSerif",Font.PLAIN,8));
        labelCol.add(lb, BorderLayout.NORTH); labelCol.add(tt, BorderLayout.SOUTH);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx=0; c.gridy=rowIdx; c.insets=new Insets(3,0,3,8); c.anchor=GridBagConstraints.WEST; p.add(labelCol,c);
        JSlider s = new JSlider(min, max, Math.min(max, Math.max(min, val))); s.setOpaque(false); s.setToolTipText(tooltip);
        c=new GridBagConstraints(); c.gridx=1; c.gridy=rowIdx; c.weightx=1.0; c.fill=GridBagConstraints.HORIZONTAL; c.insets=new Insets(3,0,3,6); p.add(s,c);
        JLabel vl = new JLabel(String.valueOf(val)); vl.setForeground(accent); vl.setFont(new Font("Consolas",Font.BOLD,13));
        vl.setPreferredSize(new Dimension(40,20)); vl.setHorizontalAlignment(SwingConstants.RIGHT);
        s.addChangeListener(e -> { int v=s.getValue(); vl.setText(""+v); onChange.accept(v); });
        s.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                onChange.accept(s.getValue());
            }
        });
        c=new GridBagConstraints(); c.gridx=2; c.gridy=rowIdx; c.insets=new Insets(3,0,3,0); c.anchor=GridBagConstraints.EAST; p.add(vl,c);
        return s;
    }

    static JButton actionBtn(String text, Color bg) {
        JButton b = new JButton(text); b.setBackground(bg); b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif",Font.BOLD,11)); b.setFocusPainted(false); b.setBorderPainted(false);
        b.setOpaque(true); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMargin(new Insets(6,10,6,10)); return b;
    }

    static void styleCb(JCheckBox cb) {
        cb.setForeground(new Color(165,170,200)); cb.setOpaque(false);
        cb.setFont(new Font("SansSerif",Font.PLAIN,11)); cb.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    static void syncSliders(VicBarcelonaTrafficSim s) {
        if (s.sVic    != null) s.sVic.setValue(s.entryVic);
        if (s.sCen    != null) s.sCen.setValue(s.entryCen);
        if (s.sGran   != null) s.sGran.setValue(s.exitGran);
        if (s.sBcn    != null) s.sBcn.setValue(s.exitBcn);
        if (s.sGap    != null) s.sGap.setValue(s.gap);
        if (s.sTick   != null) s.sTick.setValue(s.tickMs);
        if (s.sRabCap != null) s.sRabCap.setValue(s.rabCap);
        if (s.sRabExit!= null) s.sRabExit.setValue(s.rabExit);
        if (s.sPct    != null) s.sPct.setValue(s.pctExit);
        s.diagnostic();
    }
}