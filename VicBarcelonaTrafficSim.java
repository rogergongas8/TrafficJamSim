import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ════════════════════════════════════════════════════════════════════════
 * SIMULADOR DE TRAFICO C-17: VIC -> BARCELONA
 * Version Educativa e Interactiva
 * ════════════════════════════════════════════════════════════════════════
 *
 * Compilar: javac -encoding UTF-8 VicBarcelonaTrafficSim.java
 * Ejecutar: java VicBarcelonaTrafficSim
 */
public class VicBarcelonaTrafficSim extends JPanel {

    // ══════════════ CONSTANTES ══════════════
    static final int ROAD_LEN = 160;
    static final int LANES = 3;
    static final int E_VIC = 0;
    static final int E_CENTELLES = 45;
    static final int X_GRANOLLERS = 110;
    static final int X_BARCELONA = 155;
    static final int SEG_SIZE = 8;

    // ══════════════ VALORES POR DEFECTO ══════════════
    static final int DEF_ENTRY_VIC = 30, DEF_ENTRY_CEN = 20,
            DEF_EXIT_GRAN = 12, DEF_EXIT_BCN = 20,
            DEF_GAP = 2, DEF_TICK = 80, DEF_RAB_CAP = 6,
            DEF_RAB_EXIT = 10, DEF_PCT_EXIT = 35;

    // ══════════════ PARAMETROS VIVOS ══════════════
    volatile int entryVic = DEF_ENTRY_VIC, entryCen = DEF_ENTRY_CEN;
    volatile int exitGran = DEF_EXIT_GRAN, exitBcn = DEF_EXIT_BCN;
    volatile int gap = DEF_GAP, tickMs = DEF_TICK;
    volatile int rabCap = DEF_RAB_CAP, rabExit = DEF_RAB_EXIT, pctExit = DEF_PCT_EXIT;
    volatile boolean rabOn = true, lightsOn = true;

    // ══════════════ MODELO ══════════════
    static class Car {
        final int id;
        int lane, pos;
        boolean braking, wantsExit, inRab;
        int rabProg;
        final Color color;

        Car(int id, int pos, int lane, boolean we) {
            this.id = id;
            this.pos = pos;
            this.lane = lane;
            this.wantsExit = we;
            color = Color.getHSBColor((id * 0.071f) % 1f, 0.55f, 0.92f);
        }
    }

    static class Bucket {
        double tokens, rate;

        Bucket(double r) {
            rate = Math.max(0, r);
        }

        void tick(long dt) {
            tokens = Math.min(tokens + rate * dt / 60000.0, 8);
        }

        boolean consume() {
            if (tokens >= 1) {
                tokens--;
                return true;
            }
            return false;
        }
    }

    static class Light {
        int pos;
        String name;
        boolean red;
        int greenMs, redMs;
        long last = System.currentTimeMillis();

        Light(int p, String n, int g, int r) {
            pos = p;
            name = n;
            greenMs = g;
            redMs = r;
        }

        void update(long now) {
            if (red && now - last >= redMs) {
                red = false;
                last = now;
            } else if (!red && now - last >= greenMs) {
                red = true;
                last = now;
            }
        }
    }

    // ══════════════ ESTADO ══════════════
    final Car[][] road = new Car[LANES][ROAD_LEN];
    final List<Car> rabCars = Collections.synchronizedList(new ArrayList<>());
    final ConcurrentLinkedQueue<Integer> qVic = new ConcurrentLinkedQueue<>(),
            qCen = new ConcurrentLinkedQueue<>();
    final AtomicInteger idGen = new AtomicInteger(1);
    final Bucket bGran = new Bucket(DEF_EXIT_GRAN),
            bBcn = new Bucket(DEF_EXIT_BCN), bRab = new Bucket(DEF_RAB_EXIT);
    final List<Light> lights = new ArrayList<>();
    volatile long exGran, exBcn, enVic, enCen, maxQV, maxQC;
    volatile int carsOn, bnSeg = -1;
    volatile String bnName = "", diagMsg = "Ajusta los parametros para empezar!";
    volatile Color diagColor = new Color(100, 200, 255);
    final double[] segD = new double[ROAD_LEN / SEG_SIZE + 1];
    static final int HIST = 200;
    final double[] histCars = new double[HIST];
    int hIdx;
    long lastH, prevEx;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(3);
    volatile boolean running = true;
    long lastTick = System.currentTimeMillis(), simStart = System.currentTimeMillis();
    volatile long simSec;
    JSlider sVic, sCen, sGran, sBcn, sGap, sTick, sRabCap, sRabExit, sPct;

    public VicBarcelonaTrafficSim() {
        setBackground(new Color(30, 32, 40));
        setPreferredSize(new Dimension(1100, 700));
        lights.add(new Light(X_GRANOLLERS - 5, "Pre-Granollers", 5000, 3500));
        lights.add(new Light(E_CENTELLES + 3, "Centelles", 6000, 2500));
    }

    // ══════════════ ARRANQUE ══════════════
    void start() {
        sched.scheduleAtFixedRate(() -> {
            if (!running)
                return;
            if (entryVic > 0 && Math.random() < entryVic / 750.0)
                qVic.add(idGen.getAndIncrement());
        }, 0, 80, TimeUnit.MILLISECONDS);
        sched.scheduleAtFixedRate(() -> {
            if (!running)
                return;
            if (entryCen > 0 && Math.random() < entryCen / 750.0)
                qCen.add(idGen.getAndIncrement());
        }, 0, 80, TimeUnit.MILLISECONDS);
        sched.scheduleAtFixedRate(() -> {
            if (!running)
                return;
            long now = System.currentTimeMillis();
            long dt = now - lastTick;
            lastTick = now;
            simSec = (now - simStart) / 1000;
            try {
                tick(dt);
            } catch (Exception e) {
                e.printStackTrace();
            }
            SwingUtilities.invokeLater(this::repaint);
        }, 0, tickMs, TimeUnit.MILLISECONDS);
    }

    // ══════════════ TICK ══════════════
    synchronized void tick(long dt) {
        bGran.rate = exitGran;
        bBcn.rate = exitBcn;
        bRab.rate = rabExit;
        bGran.tick(dt);
        bBcn.tick(dt);
        bRab.tick(dt);
        if (lightsOn) {
            long now = System.currentTimeMillis();
            for (Light l : lights)
                l.update(now);
        }
        entries();
        movement();
        if (rabOn)
            roundabout();
        metrics();
        bottleneck();
        diagnostic();
        maxQV = Math.max(maxQV, qVic.size());
        maxQC = Math.max(maxQC, qCen.size());
    }

    void entries() {
        Integer id = qVic.peek();
        if (id != null)
            for (int l = 0; l < LANES; l++)
                if (canPlace(l, E_VIC)) {
                    qVic.poll();
                    road[l][E_VIC] = new Car(id, E_VIC, l, Math.random() * 100 < pctExit);
                    enVic++;
                    break;
                }
        id = qCen.peek();
        if (id != null)
            for (int l = 0; l < LANES; l++)
                if (canPlace(l, E_CENTELLES)) {
                    qCen.poll();
                    road[l][E_CENTELLES] = new Car(id, E_CENTELLES, l, Math.random() * 100 < pctExit);
                    enCen++;
                    break;
                }
    }

    boolean canPlace(int l, int p) {
        if (p < 0 || p >= ROAD_LEN || road[l][p] != null)
            return false;
        for (int k = 1; k <= gap; k++) {
            if (p + k < ROAD_LEN && road[l][p + k] != null)
                return false;
            if (p - k >= 0 && road[l][p - k] != null)
                return false;
        }
        return true;
    }

    void movement() {
        for (int l = 0; l < LANES; l++)
            for (int i = ROAD_LEN - 1; i >= 0; i--) {
                Car c = road[l][i];
                if (c == null)
                    continue;
                c.braking = false;
                if (i >= X_BARCELONA) {
                    if (bBcn.consume()) {
                        road[l][i] = null;
                        exBcn++;
                        continue;
                    }
                    c.braking = true;
                }
                if (c.wantsExit && i >= X_GRANOLLERS - 3 && i <= X_GRANOLLERS) {
                    if (rabOn) {
                        if (rabCars.size() < rabCap) {
                            c.inRab = true;
                            c.rabProg = 0;
                            rabCars.add(c);
                            road[l][i] = null;
                            continue;
                        }
                        c.braking = true;
                    } else {
                        if (bGran.consume()) {
                            road[l][i] = null;
                            exGran++;
                            continue;
                        }
                        c.braking = true;
                    }
                }
                if (lightsOn)
                    for (Light tl : lights)
                        if (tl.red && i < tl.pos && i >= tl.pos - 4)
                            c.braking = true;
                if (!c.braking) {
                    int n = i + 1;
                    if (n < ROAD_LEN && canFwd(l, n)) {
                        road[l][i] = null;
                        c.pos = n;
                        road[l][n] = c;
                    } else
                        c.braking = true;
                }
                if (c.braking && road[l][i] == c)
                    laneChange(l, i, c);
            }
    }

    boolean canFwd(int l, int p) {
        if (p < 0 || p >= ROAD_LEN || road[l][p] != null)
            return false;
        for (int k = 1; k <= gap; k++)
            if (p + k < ROAD_LEN && road[l][p + k] != null)
                return false;
        return true;
    }

    void laneChange(int l, int p, Car c) {
        for (int nl : new int[] { l - 1, l + 1 }) {
            if (nl < 0 || nl >= LANES)
                continue;
            if (canPlace(nl, p)) {
                road[l][p] = null;
                c.lane = nl;
                road[nl][p] = c;
                return;
            }
        }
    }

    void roundabout() {
        synchronized (rabCars) {
            Iterator<Car> it = rabCars.iterator();
            while (it.hasNext()) {
                Car c = it.next();
                c.rabProg++;
                if (c.rabProg >= 16) {
                    if (bRab.consume() || c.rabProg > 40) {
                        it.remove();
                        exGran++;
                    }
                }
            }
        }
    }

    void metrics() {
        int cnt = 0;
        Arrays.fill(segD, 0);
        for (int l = 0; l < LANES; l++)
            for (int i = 0; i < ROAD_LEN; i++)
                if (road[l][i] != null) {
                    cnt++;
                    int s = i / SEG_SIZE;
                    if (s < segD.length)
                        segD[s]++;
                }
        cnt += rabCars.size();
        carsOn = cnt;
        for (int s = 0; s < segD.length; s++)
            segD[s] /= (SEG_SIZE * LANES);
        long now = System.currentTimeMillis();
        if (now - lastH >= 600) {
            histCars[hIdx % HIST] = carsOn;
            hIdx++;
            lastH = now;
        }
    }

    void bottleneck() {
        double mx = 0;
        int ms = -1;
        for (int s = 0; s < segD.length; s++)
            if (segD[s] > mx) {
                mx = segD[s];
                ms = s;
            }
        if (mx > 0.40 && ms >= 0) {
            bnSeg = ms;
            int pos = ms * SEG_SIZE;
            if (pos >= X_GRANOLLERS - 12 && pos <= X_GRANOLLERS + 4)
                bnName = "Rotonda Granollers";
            else if (pos >= X_BARCELONA - 8)
                bnName = "Salida Barcelona";
            else if (pos >= E_CENTELLES - 4 && pos <= E_CENTELLES + 8)
                bnName = "Entrada Centelles";
            else
                bnName = "Tramo km " + (pos * 70 / ROAD_LEN);
        } else {
            bnSeg = -1;
            bnName = "";
        }
    }

    void diagnostic() {
        double inR = entryVic + entryCen;
        double outR = exitBcn + (rabOn ? Math.min(exitGran, rabExit) : exitGran);
        if (bnSeg >= 0) {
            int pos = bnSeg * SEG_SIZE;
            if (pos >= X_GRANOLLERS - 12 && pos <= X_GRANOLLERS + 4) {
                diagMsg = "ATASCO en ROTONDA! Actua como seccion critica (synchronized). Capacidad=" + rabCap
                        + " insuficiente. Sube capacidad o baja entradas.";
                diagColor = new Color(255, 80, 80);
            } else if (pos >= X_BARCELONA - 8) {
                diagMsg = "ATASCO en BARCELONA! Salida lenta = consumidor lento (thread pool pequeno). Sube tasa salida Barcelona.";
                diagColor = new Color(255, 120, 60);
            } else {
                diagMsg = "CONGESTION en " + bnName + "! Densidad alta. Reduce entradas o aumenta salidas.";
                diagColor = new Color(255, 160, 40);
            }
        } else if (inR > outR * 1.3) {
            diagMsg = "CUIDADO: Entran " + (int) inR + "/min pero salen ~" + (int) outR
                    + "/min. Productor > Consumidor = atasco inminente!";
            diagColor = new Color(255, 200, 60);
        } else if (carsOn == 0 && inR == 0) {
            diagMsg = "Carretera vacia. Sube las entradas para empezar.";
            diagColor = new Color(150, 150, 180);
        } else if (carsOn < 10) {
            diagMsg = "Trafico fluido. Sin contention. Los threads circulan libremente.";
            diagColor = new Color(80, 220, 130);
        } else {
            diagMsg = "Trafico normal. Entrada (" + (int) inR + "/min) equilibrada con salida (" + (int) outR
                    + "/min). Sistema estable.";
            diagColor = new Color(100, 200, 255);
        }
    }

    // ══════════════════════════════════════════════════
    // RENDER
    // ══════════════════════════════════════════════════
    @Override
    protected synchronized void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        int W = getWidth(), H = getHeight();
        int M = 20; // margen lateral

        // ── Fondo degradado ──
        g.setPaint(new GradientPaint(0, 0, new Color(18, 20, 30), W, H, new Color(28, 35, 48)));
        g.fillRect(0, 0, W, H);

        // ════════════════════════════════════════════════
        // ZONA 1: TITULO (y = 0..30)
        // ════════════════════════════════════════════════
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.setColor(new Color(100, 190, 255));
        g.drawString("SIMULADOR DE TRAFICO  C-17:  VIC >>> BARCELONA", M, 22);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(150, 150, 170));
        g.drawString(String.format("Tiempo: %02d:%02d", simSec / 60, simSec % 60), W - 130, 22);

        // ════════════════════════════════════════════════
        // ZONA 2: ETIQUETAS DE CIUDADES (y = 35..65)
        // ════════════════════════════════════════════════
        int roadX = M;
        int roadW = W - 2 * M;
        double cellW = roadW / (double) ROAD_LEN;

        int[][] cities = { { E_VIC, 0 }, { E_CENTELLES, 0 }, { X_GRANOLLERS, 1 }, { X_BARCELONA, 1 } };
        String[] names = { "VIC", "CENTELLES", "GRANOLLERS", "BARCELONA" };
        String[] types = { "ENTRADA", "ENTRADA", "SALIDA", "SALIDA" };
        Color[] cols = {
                new Color(70, 200, 120), new Color(70, 160, 230),
                new Color(230, 160, 50), new Color(220, 80, 75)
        };

        for (int ci = 0; ci < 4; ci++) {
            int cx = (int) (roadX + cities[ci][0] * cellW);
            boolean isEntry = cities[ci][1] == 0;
            Color col = cols[ci];

            // Nombre ciudad
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.setColor(col);
            FontMetrics fm1 = g.getFontMetrics();
            g.drawString(names[ci], cx - fm1.stringWidth(names[ci]) / 2, 42);

            // Tipo (ENTRADA/SALIDA)
            g.setFont(new Font("SansSerif", Font.PLAIN, 9));
            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 180));
            FontMetrics fm2 = g.getFontMetrics();
            g.drawString(types[ci], cx - fm2.stringWidth(types[ci]) / 2, 54);

            // Colas (solo entradas, si hay)
            if (isEntry) {
                int qs = (ci == 0) ? qVic.size() : qCen.size();
                if (qs > 0) {
                    g.setFont(new Font("Consolas", Font.BOLD, 11));
                    g.setColor(qs > 20 ? new Color(255, 90, 70) : new Color(255, 190, 70));
                    String qt = "Cola: " + qs;
                    FontMetrics fm3 = g.getFontMetrics();
                    g.drawString(qt, cx - fm3.stringWidth(qt) / 2, 66);
                }
            }
            // Contadores salida
            if (!isEntry) {
                long cnt = (ci == 2) ? exGran : exBcn;
                g.setFont(new Font("Consolas", Font.BOLD, 10));
                g.setColor(new Color(190, 190, 210));
                String ct = "Salidos: " + cnt;
                FontMetrics fm3 = g.getFontMetrics();
                g.drawString(ct, cx - fm3.stringWidth(ct) / 2, 66);
            }
        }

        // ════════════════════════════════════════════════
        // ZONA 3: FLECHAS + SEMAFOROS (y = 68..88)
        // ════════════════════════════════════════════════
        int roadY = 92; // Top de la carretera
        int laneH = 30;
        int roadH = laneH * LANES;

        // Flechas de conexion (ciudad -> carretera)
        for (int ci = 0; ci < 4; ci++) {
            int cx = (int) (roadX + cities[ci][0] * cellW);
            boolean isEntry = cities[ci][1] == 0;
            Color col = cols[ci];
            g.setColor(col);
            g.setStroke(new BasicStroke(2.5f));

            if (isEntry) {
                // Flecha hacia abajo (ciudad -> carretera)
                g.drawLine(cx, 70, cx, roadY - 2);
                g.fillPolygon(new int[] { cx - 4, cx + 4, cx }, new int[] { roadY - 7, roadY - 7, roadY - 1 }, 3);
            } else {
                // Flecha hacia abajo (carretera -> ciudad)
                int bot = roadY + roadH + 2;
                g.drawLine(cx, bot, cx, bot + 16);
                g.fillPolygon(new int[] { cx - 4, cx + 4, cx }, new int[] { bot + 11, bot + 11, bot + 17 }, 3);
            }
            g.setStroke(new BasicStroke(1f));
        }

        // Semaforos (pequenos, en la linea superior de la carretera)
        if (lightsOn) {
            for (Light tl : lights) {
                int sx = (int) (roadX + tl.pos * cellW);
                // Caja del semaforo pegada al borde superior de la carretera
                g.setColor(new Color(25, 25, 30));
                g.fillRoundRect(sx - 7, roadY - 14, 14, 13, 4, 4);
                g.setColor(tl.red ? new Color(255, 40, 40) : new Color(40, 255, 40));
                g.fillOval(sx - 4, roadY - 12, 9, 9);
                // Borde
                g.setColor(new Color(80, 80, 90));
                g.drawRoundRect(sx - 7, roadY - 14, 14, 13, 4, 4);
            }
        }

        // ════════════════════════════════════════════════
        // ZONA 4: CARRETERA (y = roadY .. roadY+roadH)
        // ════════════════════════════════════════════════

        // Mapa de calor (fondo transparente detras del asfalto)
        for (int s = 0; s < segD.length; s++) {
            float d = (float) Math.min(1, segD[s]);
            if (d > 0.08) {
                int sx = (int) (roadX + s * SEG_SIZE * cellW);
                int sw = (int) (SEG_SIZE * cellW) + 1;
                Color hc = heat(d);
                g.setColor(new Color(hc.getRed(), hc.getGreen(), hc.getBlue(), 40));
                g.fillRect(sx, roadY - 2, sw, roadH + 4);
            }
        }

        // Asfalto principal
        g.setColor(new Color(48, 50, 58));
        g.fillRoundRect(roadX - 2, roadY - 2, roadW + 4, roadH + 4, 8, 8);

        // Zonas coloreadas suaves para entrada/salida
        fillZone(g, roadX, roadY, cellW, roadH, E_VIC, 6, new Color(70, 200, 120, 20));
        fillZone(g, roadX, roadY, cellW, roadH, E_CENTELLES, 6, new Color(70, 160, 230, 20));
        fillZone(g, roadX, roadY, cellW, roadH, X_GRANOLLERS - 2, 6, new Color(230, 160, 50, 20));
        fillZone(g, roadX, roadY, cellW, roadH, X_BARCELONA - 2, 6, new Color(220, 80, 75, 20));

        // Lineas de carril
        for (int l = 0; l <= LANES; l++) {
            int y = roadY + l * laneH;
            if (l == 0 || l == LANES) {
                g.setColor(new Color(180, 180, 180, 130));
                g.setStroke(new BasicStroke(2f));
            } else {
                g.setColor(new Color(255, 255, 255, 50));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 10f, new float[] { 6f, 5f }, 0f));
            }
            g.drawLine(roadX, y, roadX + roadW, y);
        }
        g.setStroke(new BasicStroke(1f));

        // Cuello de botella (flash rojo)
        if (bnSeg >= 0) {
            long pulse = System.currentTimeMillis() % 1000;
            int a = (int) (25 + 35 * Math.sin(pulse * Math.PI / 500.0));
            int bx = (int) (roadX + bnSeg * SEG_SIZE * cellW);
            int bw = (int) (SEG_SIZE * cellW) + 4;
            g.setColor(new Color(255, 40, 40, a));
            g.fillRoundRect(bx - 2, roadY - 2, bw, roadH + 4, 6, 6);
            g.setColor(new Color(255, 70, 70, 100));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10f, new float[] { 4f, 3f }, 0f));
            g.drawRoundRect(bx - 2, roadY - 2, bw, roadH + 4, 6, 6);
            g.setStroke(new BasicStroke(1f));
        }

        // COCHES
        for (int l = 0; l < LANES; l++) {
            for (int i = 0; i < ROAD_LEN; i++) {
                Car c = road[l][i];
                if (c == null)
                    continue;
                int cx = (int) (roadX + i * cellW);
                int cy = roadY + l * laneH + 5;
                int cw = (int) Math.max(8, cellW - 1);
                int ch = laneH - 10;

                // Sombra
                g.setColor(new Color(0, 0, 0, 25));
                g.fillRoundRect(cx + 1, cy + 1, cw, ch, 4, 4);
                // Cuerpo
                g.setColor(c.braking ? c.color.darker() : c.color);
                g.fillRoundRect(cx, cy, cw, ch, 4, 4);
                // Luces freno
                if (c.braking) {
                    g.setColor(new Color(255, 25, 25, 200));
                    g.fillOval(cx, cy + 1, 3, 3);
                    g.fillOval(cx, cy + ch - 4, 3, 3);
                }
                // Intermitente
                if (c.wantsExit && i > X_GRANOLLERS - 25 && i < X_GRANOLLERS) {
                    if (System.currentTimeMillis() % 600 < 300) {
                        g.setColor(new Color(255, 200, 40, 200));
                        g.fillOval(cx + cw - 3, cy, 3, 3);
                    }
                }
            }
        }

        // ════════════════════════════════════════════════
        // ZONA 5: BAJO CARRETERA (y = roadY+roadH .. +45)
        // Rotonda + kilometros + etiqueta atasco
        // ════════════════════════════════════════════════
        int belowY = roadY + roadH + 4;

        // Kilometros
        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g.setColor(new Color(90, 90, 110));
        for (int km = 0; km <= 70; km += 10) {
            int x = (int) (roadX + (km * ROAD_LEN / 70.0) * cellW);
            g.drawLine(x, belowY, x, belowY + 5);
            g.drawString("km" + km, x - 10, belowY + 15);
        }

        // Rotonda (a la derecha de la salida Granollers, debajo de la carretera)
        if (rabOn) {
            int rx = (int) (roadX + X_GRANOLLERS * cellW) + 50;
            int ry = belowY + 30;
            int sz = 44;
            float fill = (float) rabCars.size() / Math.max(1, rabCap);

            // Circulo fondo
            g.setColor(new Color(40, 42, 52));
            g.fillOval(rx - sz / 2, ry - sz / 2, sz, sz);
            // Borde coloreado segun llenado
            g.setColor(heat(fill));
            g.setStroke(new BasicStroke(3f));
            g.drawOval(rx - sz / 2, ry - sz / 2, sz, sz);
            g.setStroke(new BasicStroke(1f));

            // Texto dentro
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(Color.WHITE);
            String rt = rabCars.size() + "/" + rabCap;
            FontMetrics fmr = g.getFontMetrics();
            g.drawString(rt, rx - fmr.stringWidth(rt) / 2, ry + 4);

            // Mini coches en rotonda
            synchronized (rabCars) {
                int n = rabCars.size();
                for (int i = 0; i < n; i++) {
                    double ang = 2 * Math.PI * i / Math.max(1, n);
                    int px = (int) (rx + (sz / 2 - 8) * Math.cos(ang));
                    int py = (int) (ry + (sz / 2 - 8) * Math.sin(ang));
                    g.setColor(rabCars.get(i).color);
                    g.fillRoundRect(px - 3, py - 2, 6, 4, 2, 2);
                }
            }

            // Etiqueta
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            g.setColor(new Color(190, 170, 240));
            g.drawString("ROTONDA", rx - sz / 2 - 2, ry - sz / 2 - 6);
            g.setFont(new Font("SansSerif", Font.ITALIC, 8));
            g.setColor(new Color(140, 140, 170));
            g.drawString("(seccion critica)", rx - sz / 2 - 6, ry - sz / 2 + 4);

            // Conector visual: linea de la carretera a la rotonda
            int exitX = (int) (roadX + X_GRANOLLERS * cellW);
            g.setColor(new Color(80, 80, 100));
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(exitX, belowY + 2, exitX, belowY + 18);
            g.drawLine(exitX, belowY + 18, rx - sz / 2, ry);
            g.setStroke(new BasicStroke(1f));
        }

        // Etiqueta cuello de botella
        if (bnSeg >= 0) {
            int bx = (int) (roadX + bnSeg * SEG_SIZE * cellW);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(new Color(255, 90, 90));
            g.drawString("ATASCO", bx - 8, belowY + 16);
        }

        // ════════════════════════════════════════════════
        // ZONA 6: DIAGNOSTICO (debajo de rotonda/km)
        // ════════════════════════════════════════════════
        int diagY = belowY + 58;
        int diagH = 48;
        drawBox(g, M, diagY, W - 2 * M, diagH);

        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(diagColor);
        g.drawString("DIAGNOSTICO:", M + 10, diagY + 16);

        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.setColor(diagColor.brighter());
        // Wrap text
        FontMetrics fmd = g.getFontMetrics();
        int maxTW = W - 2 * M - 24;
        if (fmd.stringWidth(diagMsg) > maxTW) {
            int cut = diagMsg.length();
            while (cut > 0 && fmd.stringWidth(diagMsg.substring(0, cut)) > maxTW)
                cut--;
            cut = diagMsg.lastIndexOf(' ', cut);
            if (cut > 0) {
                g.drawString(diagMsg.substring(0, cut), M + 10, diagY + 32);
                g.drawString(diagMsg.substring(cut + 1), M + 10, diagY + 44);
            } else
                g.drawString(diagMsg, M + 10, diagY + 32);
        } else {
            g.drawString(diagMsg, M + 10, diagY + 32);
        }

        // ════════════════════════════════════════════════
        // ZONA 7: METRICAS (3 columnas)
        // ════════════════════════════════════════════════
        int metY = diagY + diagH + 6;
        int metH = 56;
        drawBox(g, M, metY, W - 2 * M, metH);

        g.setFont(new Font("Consolas", Font.PLAIN, 12));
        g.setColor(new Color(185, 200, 240));
        int c1 = M + 10, c2 = M + (W - 2 * M) / 3, c3 = M + 2 * (W - 2 * M) / 3;

        g.drawString("Coches: " + carsOn, c1, metY + 17);
        g.drawString("Entrados: " + (enVic + enCen), c1, metY + 33);
        g.drawString("Salidos:  " + (exGran + exBcn), c1, metY + 49);

        g.drawString("Cola Vic: " + qVic.size() + " (max " + maxQV + ")", c2, metY + 17);
        g.drawString("Cola Cen: " + qCen.size() + " (max " + maxQC + ")", c2, metY + 33);
        g.drawString("Rotonda:  " + rabCars.size() + "/" + rabCap, c2, metY + 49);

        g.drawString("Salidos Gran: " + exGran, c3, metY + 17);
        g.drawString("Salidos BCN:  " + exBcn, c3, metY + 33);
        g.setColor(bnSeg >= 0 ? new Color(255, 100, 100) : new Color(100, 255, 100));
        g.drawString(bnSeg >= 0 ? "Cuello: " + bnName : "Sin atascos", c3, metY + 49);

        // ════════════════════════════════════════════════
        // ZONA 8: GRAFICO DENSIDAD (barras)
        // ════════════════════════════════════════════════
        int gY = metY + metH + 6;
        int gH = 44;
        int gW = (W - 2 * M - 6) / 2; // Mitad izquierda
        drawBox(g, M, gY, gW, gH);

        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g.setColor(new Color(130, 130, 150));
        g.drawString("Densidad por zona", M + 6, gY + 11);

        for (int s = 0; s < segD.length; s++) {
            int bx = M + 4 + (int) ((gW - 8.0) * s / segD.length);
            int bw = Math.max(2, (int) ((gW - 8.0) / segD.length) - 1);
            int bh = (int) (segD[s] * (gH - 16));
            g.setColor(heat((float) segD[s]));
            g.fillRect(bx, gY + gH - bh - 2, bw, bh);
        }

        // ════════════════════════════════════════════════
        // ZONA 9: GRAFICO HISTORICO (linea) - mitad derecha
        // ════════════════════════════════════════════════
        int g2X = M + gW + 6;
        int g2W = gW;
        drawBox(g, g2X, gY, g2W, gH);

        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g.setColor(new Color(130, 130, 150));
        g.drawString("Historico coches", g2X + 6, gY + 11);

        if (hIdx > 1) {
            int n = Math.min(hIdx, HIST);
            double mx = 1;
            for (int i = 0; i < n; i++)
                mx = Math.max(mx, histCars[(hIdx - n + i) % HIST]);
            g.setColor(new Color(80, 180, 255, 160));
            int px = -1, py = -1;
            for (int i = 0; i < n; i++) {
                int xx = g2X + 4 + (int) ((g2W - 8.0) * i / n);
                int yy = gY + gH - 4 - (int) (histCars[(hIdx - n + i) % HIST] / mx * (gH - 18));
                if (px >= 0)
                    g.drawLine(px, py, xx, yy);
                px = xx;
                py = yy;
            }
        }

        // ════════════════════════════════════════════════
        // ZONA 10: ANALOGIA (pie)
        // ════════════════════════════════════════════════
        int aY = gY + gH + 6;
        if (aY + 28 < H) {
            drawBox(g, M, aY, W - 2 * M, 26);
            g.setFont(new Font("SansSerif", Font.ITALIC, 10));
            g.setColor(new Color(150, 160, 195));
            g.drawString(
                    "ANALOGIA:  Coche=Thread  |  Carretera=Recurso  |  Rotonda=synchronized  |  Semaforo=Lock  |  Atasco=Contention",
                    M + 10, aY + 17);
        }
    }

    // ── Helpers graficos ──
    void fillZone(Graphics2D g, int rx, int ry, double cw, int rh, int pos, int len, Color c) {
        g.setColor(c);
        g.fillRect((int) (rx + pos * cw), ry, (int) (len * cw), rh);
    }

    void drawBox(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(18, 22, 36, 200));
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setColor(new Color(50, 65, 105, 60));
        g.drawRoundRect(x, y, w, h, 8, 8);
    }

    Color heat(float v) {
        v = Math.max(0, Math.min(1, v));
        if (v < 0.3f)
            return new Color((int) (v / 0.3f * 200), 210, 80);
        if (v < 0.6f)
            return new Color(240, (int) (210 - (v - 0.3f) / 0.3f * 130), 50);
        return new Color(255, (int) (80 - (v - 0.6f) / 0.4f * 70), (int) (50 - (v - 0.6f) / 0.4f * 40));
    }

    // ══════════════ MAIN ══════════════
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
        }
        SwingUtilities.invokeLater(() -> {
            VicBarcelonaTrafficSim sim = new VicBarcelonaTrafficSim();
            JFrame frame = new JFrame("Simulador Trafico C-17: Vic - Barcelona");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

            JPanel ctrl = buildCtrl(sim);
            JScrollPane scroll = new JScrollPane(ctrl);
            scroll.setBorder(null);
            scroll.setPreferredSize(new Dimension(320, 700));
            scroll.getVerticalScrollBar().setUnitIncrement(16);

            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sim, scroll);
            split.setDividerLocation(1200);
            split.setResizeWeight(1.0);
            frame.setContentPane(split);
            frame.setSize(1500, 750);
            frame.setLocationRelativeTo(null);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setVisible(true);
            sim.start();
            frame.addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    sim.running = false;
                    sim.sched.shutdownNow();
                }
            });
        });
    }

    // ══════════════ PANEL DE CONTROL ══════════════
    static JPanel buildCtrl(VicBarcelonaTrafficSim sim) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(new Color(28, 30, 40));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        hdr(p, "PANEL DE CONTROL");
        p.add(Box.createVerticalStrut(6));

        // Presets
        section(p, "ESCENARIOS");
        JPanel presets = new JPanel(new GridLayout(2, 2, 4, 4));
        presets.setOpaque(false);
        presets.setAlignmentX(Component.LEFT_ALIGNMENT);
        presets.setMaximumSize(new Dimension(300, 70));

        addPreset(presets, "Flujo Normal", new Color(60, 160, 100),
                "Equilibrio entrada/salida", () -> {
                    sim.entryVic = 30;
                    sim.entryCen = 20;
                    sim.exitGran = 12;
                    sim.exitBcn = 20;
                    sim.gap = 2;
                    sim.rabCap = 8;
                    sim.rabExit = 12;
                    sim.pctExit = 35;
                    sim.rabOn = true;
                    sim.lightsOn = true;
                    syncSliders(sim);
                });
        addPreset(presets, "Atasco Rotonda", new Color(200, 140, 40),
                "Rotonda pequena + mucho trafico", () -> {
                    sim.entryVic = 80;
                    sim.entryCen = 60;
                    sim.exitGran = 8;
                    sim.exitBcn = 25;
                    sim.gap = 1;
                    sim.rabCap = 3;
                    sim.rabExit = 5;
                    sim.pctExit = 50;
                    sim.rabOn = true;
                    sim.lightsOn = true;
                    syncSliders(sim);
                });
        addPreset(presets, "Atasco BCN", new Color(200, 80, 60),
                "Salida Barcelona muy lenta", () -> {
                    sim.entryVic = 60;
                    sim.entryCen = 40;
                    sim.exitGran = 20;
                    sim.exitBcn = 5;
                    sim.gap = 2;
                    sim.rabCap = 10;
                    sim.rabExit = 15;
                    sim.pctExit = 20;
                    sim.rabOn = true;
                    sim.lightsOn = true;
                    syncSliders(sim);
                });
        addPreset(presets, "Caos Total", new Color(180, 40, 40),
                "Todo al maximo, salidas minimas", () -> {
                    sim.entryVic = 120;
                    sim.entryCen = 100;
                    sim.exitGran = 5;
                    sim.exitBcn = 5;
                    sim.gap = 1;
                    sim.rabCap = 2;
                    sim.rabExit = 3;
                    sim.pctExit = 60;
                    sim.rabOn = true;
                    sim.lightsOn = true;
                    syncSliders(sim);
                });
        p.add(presets);
        p.add(Box.createVerticalStrut(8));

        // Entradas
        section(p, "ENTRADAS (coches/min)");
        tip(p, "= Productores de threads");
        sim.sVic = addSlider(p, "Vic:", 0, 150, sim.entryVic, v -> sim.entryVic = v);
        sim.sCen = addSlider(p, "Centelles:", 0, 150, sim.entryCen, v -> sim.entryCen = v);
        p.add(Box.createVerticalStrut(4));

        // Salidas
        section(p, "SALIDAS (coches/min)");
        tip(p, "= Consumidores de threads");
        sim.sGran = addSlider(p, "Granollers:", 0, 80, sim.exitGran, v -> sim.exitGran = v);
        sim.sBcn = addSlider(p, "Barcelona:", 0, 80, sim.exitBcn, v -> sim.exitBcn = v);
        p.add(Box.createVerticalStrut(4));

        // Rotonda
        section(p, "ROTONDA (seccion critica)");
        tip(p, "= synchronized { } con capacidad limitada");
        sim.sRabCap = addSlider(p, "Capacidad:", 1, 40, sim.rabCap, v -> sim.rabCap = v);
        sim.sRabExit = addSlider(p, "Salida/min:", 1, 50, sim.rabExit, v -> sim.rabExit = v);
        sim.sPct = addSlider(p, "% salen aq:", 0, 100, sim.pctExit, v -> sim.pctExit = v);
        JCheckBox cbRab = new JCheckBox("Rotonda activa", sim.rabOn);
        styleCb(cbRab);
        cbRab.addActionListener(e -> sim.rabOn = cbRab.isSelected());
        p.add(cbRab);
        p.add(Box.createVerticalStrut(4));

        // Fisica
        section(p, "FISICA");
        sim.sGap = addSlider(p, "Dist. min:", 1, 8, sim.gap, v -> sim.gap = v);
        sim.sTick = addSlider(p, "Tick (ms):", 20, 300, sim.tickMs, v -> sim.tickMs = v);
        tip(p, "Tick bajo = simulacion mas rapida");
        JCheckBox cbLt = new JCheckBox("Semaforos (=Locks)", sim.lightsOn);
        styleCb(cbLt);
        cbLt.addActionListener(e -> sim.lightsOn = cbLt.isSelected());
        p.add(cbLt);
        p.add(Box.createVerticalStrut(8));

        // Controles
        section(p, "CONTROLES");
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        btns.setOpaque(false);
        btns.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton bPause = styledBtn("Pausar", new Color(180, 150, 40));
        bPause.addActionListener(e -> {
            sim.running = !sim.running;
            bPause.setText(sim.running ? "Pausar" : "Reanudar");
            bPause.setBackground(sim.running ? new Color(180, 150, 40) : new Color(60, 160, 60));
        });
        btns.add(bPause);

        JButton bReset = styledBtn("Reset", new Color(180, 70, 60));
        bReset.addActionListener(e -> {
            sim.running = false;
            for (int l = 0; l < LANES; l++)
                Arrays.fill(sim.road[l], null);
            sim.rabCars.clear();
            sim.qVic.clear();
            sim.qCen.clear();
            sim.exGran = sim.exBcn = sim.enVic = sim.enCen = sim.maxQV = sim.maxQC = 0;
            sim.simStart = System.currentTimeMillis();
            sim.simSec = 0;
            sim.hIdx = 0;
            sim.running = true;
            sim.repaint();
        });
        btns.add(bReset);

        JButton bDef = styledBtn("Defecto", new Color(80, 120, 180));
        bDef.setToolTipText("Restaurar valores por defecto");
        bDef.addActionListener(e -> {
            sim.entryVic = DEF_ENTRY_VIC;
            sim.entryCen = DEF_ENTRY_CEN;
            sim.exitGran = DEF_EXIT_GRAN;
            sim.exitBcn = DEF_EXIT_BCN;
            sim.gap = DEF_GAP;
            sim.tickMs = DEF_TICK;
            sim.rabCap = DEF_RAB_CAP;
            sim.rabExit = DEF_RAB_EXIT;
            sim.pctExit = DEF_PCT_EXIT;
            sim.rabOn = true;
            sim.lightsOn = true;
            cbRab.setSelected(true);
            cbLt.setSelected(true);
            syncSliders(sim);
        });
        btns.add(bDef);
        p.add(btns);
        p.add(Box.createVerticalStrut(10));

        // Info educativa
        section(p, "COMO FUNCIONA");
        JTextArea info = new JTextArea(
                "TRAFICO = CONCURRENCIA\n\n" +
                        "Coche     = Thread\n" +
                        "Carretera = Recurso compartido\n" +
                        "Entrada   = Productor\n" +
                        "Salida    = Consumidor\n" +
                        "Rotonda   = synchronized\n" +
                        "Semaforo  = Lock / Mutex\n" +
                        "Atasco    = Contention\n\n" +
                        "REGLA CLAVE:\n" +
                        "Produccion > Consumo = ATASCO!\n\n" +
                        "EXPERIMENTA:\n" +
                        "1.Sube entradas, baja salidas\n" +
                        "2.Baja capacidad rotonda\n" +
                        "3.Desactiva semaforos\n" +
                        "4.Sube distancia minima");
        info.setEditable(false);
        info.setOpaque(false);
        info.setForeground(new Color(155, 160, 185));
        info.setFont(new Font("SansSerif", Font.PLAIN, 11));
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.setMaximumSize(new Dimension(300, 380));
        p.add(info);
        return p;
    }

    static void addPreset(JPanel p, String name, Color bg, String tip, Runnable action) {
        JButton b = styledBtn(name, bg);
        b.setToolTipText(tip);
        b.addActionListener(e -> action.run());
        p.add(b);
    }

    static void syncSliders(VicBarcelonaTrafficSim s) {
        if (s.sVic != null)
            s.sVic.setValue(s.entryVic);
        if (s.sCen != null)
            s.sCen.setValue(s.entryCen);
        if (s.sGran != null)
            s.sGran.setValue(s.exitGran);
        if (s.sBcn != null)
            s.sBcn.setValue(s.exitBcn);
        if (s.sGap != null)
            s.sGap.setValue(s.gap);
        if (s.sTick != null)
            s.sTick.setValue(s.tickMs);
        if (s.sRabCap != null)
            s.sRabCap.setValue(s.rabCap);
        if (s.sRabExit != null)
            s.sRabExit.setValue(s.rabExit);
        if (s.sPct != null)
            s.sPct.setValue(s.pctExit);
    }

    // ── UI helpers ──
    static void hdr(JPanel p, String t) {
        JLabel l = new JLabel(t);
        l.setForeground(new Color(100, 190, 255));
        l.setFont(new Font("SansSerif", Font.BOLD, 15));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l);
    }

    static void section(JPanel p, String t) {
        JLabel l = new JLabel(t);
        l.setForeground(new Color(160, 180, 235));
        l.setFont(new Font("SansSerif", Font.BOLD, 12));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(55, 65, 95)));
        p.add(l);
    }

    static void tip(JPanel p, String t) {
        JLabel l = new JLabel(t);
        l.setForeground(new Color(125, 135, 165));
        l.setFont(new Font("SansSerif", Font.ITALIC, 10));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l);
    }

    static JSlider addSlider(JPanel p, String label, int min, int max, int val,
            java.util.function.IntConsumer onChange) {
        JPanel row = new JPanel(new BorderLayout(3, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(300, 34));
        JLabel lb = new JLabel(label);
        lb.setForeground(new Color(165, 170, 190));
        lb.setFont(new Font("SansSerif", Font.PLAIN, 11));
        lb.setPreferredSize(new Dimension(95, 20));
        JSlider s = new JSlider(min, max, Math.min(max, Math.max(min, val)));
        s.setOpaque(false);
        s.setPreferredSize(new Dimension(140, 22));
        JLabel vl = new JLabel(String.valueOf(val));
        vl.setForeground(new Color(100, 210, 150));
        vl.setFont(new Font("Consolas", Font.BOLD, 12));
        vl.setPreferredSize(new Dimension(35, 20));
        s.addChangeListener(e -> {
            int v = s.getValue();
            vl.setText("" + v);
            onChange.accept(v);
        });
        row.add(lb, BorderLayout.WEST);
        row.add(s, BorderLayout.CENTER);
        row.add(vl, BorderLayout.EAST);
        p.add(row);
        return s;
    }

    static JButton styledBtn(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", Font.BOLD, 11));
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setMargin(new Insets(4, 8, 4, 8));
        return b;
    }

    static void styleCb(JCheckBox cb) {
        cb.setForeground(new Color(165, 170, 190));
        cb.setOpaque(false);
        cb.setFont(new Font("SansSerif", Font.PLAIN, 11));
        cb.setAlignmentX(Component.LEFT_ALIGNMENT);
    }
}
