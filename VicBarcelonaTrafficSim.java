import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class VicBarcelonaTrafficSim extends JPanel {

    static final int ROAD_LEN = 180, LANES = 3;
    static final int E_VIC = 0, E_CENTELLES = 50, X_GRANOLLERS = 125, X_BARCELONA = 175;
    static final int SEG_SIZE = 8;
    static final int DEF_ENTRY_VIC = 30, DEF_ENTRY_CEN = 20, DEF_EXIT_GRAN = 12, DEF_EXIT_BCN = 20;
    static final int DEF_GAP = 2, DEF_TICK = 80, DEF_RAB_CAP = 6, DEF_RAB_EXIT = 10, DEF_PCT_EXIT = 35;

    volatile int entryVic = DEF_ENTRY_VIC, entryCen = DEF_ENTRY_CEN;
    volatile int exitGran = DEF_EXIT_GRAN, exitBcn = DEF_EXIT_BCN;
    volatile int gap = DEF_GAP, tickMs = DEF_TICK;
    volatile int rabCap = DEF_RAB_CAP, rabExit = DEF_RAB_EXIT, pctExit = DEF_PCT_EXIT;
    volatile boolean rabOn = true, lightsOn = true;

    static class Car {
        final int id; int lane, pos; boolean braking, wantsExit, inRab; int rabProg; final Color color;
        Car(int id, int pos, int lane, boolean we) {
            this.id=id; this.pos=pos; this.lane=lane; this.wantsExit=we;
            color = Color.getHSBColor((id*0.071f)%1f, 0.55f, 0.92f);
        }
    }

    static class Bucket {
        double tokens, rate;
        Bucket(double r) { rate = Math.max(0,r); }
        void tick(long dt) { tokens = Math.min(tokens + rate*dt/60000.0, 8); }
        boolean consume() { if (tokens>=1) { tokens--; return true; } return false; }
    }

    static class Light {
        int pos; String name; boolean red; int greenMs, redMs; long last=System.currentTimeMillis();
        Light(int p, String n, int g, int r) { pos=p; name=n; greenMs=g; redMs=r; }
        void update(long now) {
            if (red && now-last>=redMs) { red=false; last=now; }
            else if (!red && now-last>=greenMs) { red=true; last=now; }
        }
    }

    final Car[][] road = new Car[LANES][ROAD_LEN];
    final List<Car> rabCars = Collections.synchronizedList(new ArrayList<>());
    final ConcurrentLinkedQueue<Integer> qVic = new ConcurrentLinkedQueue<>(), qCen = new ConcurrentLinkedQueue<>();
    final AtomicInteger idGen = new AtomicInteger(1);
    final Bucket bGran = new Bucket(DEF_EXIT_GRAN), bBcn = new Bucket(DEF_EXIT_BCN), bRab = new Bucket(DEF_RAB_EXIT);
    final List<Light> lights = new ArrayList<>();
    volatile long exGran, exBcn, enVic, enCen, maxQV, maxQC;
    volatile int carsOn, bnSeg=-1;
    volatile String bnName="", diagMsg="Ajusta los parametros para empezar!";
    volatile Color diagColor = new Color(100,200,255);
    final double[] segD = new double[ROAD_LEN/SEG_SIZE+1];
    static final int HIST=200;
    final double[] histCars = new double[HIST];
    int hIdx; long lastH, prevEx;
    final ScheduledExecutorService sched = Executors.newScheduledThreadPool(3);
    volatile boolean running=true;
    long lastTick=System.currentTimeMillis(), simStart=System.currentTimeMillis();
    volatile long simSec;
    JSlider sVic, sCen, sGran, sBcn, sGap, sTick, sRabCap, sRabExit, sPct;
    JLabel lAdv;
    JTextArea lEdu;

    public VicBarcelonaTrafficSim() {
        setBackground(new Color(30,32,40));
        setPreferredSize(new Dimension(1100,700));
        lights.add(new Light(X_GRANOLLERS-5,"Pre-Granollers",5000,3500));
        lights.add(new Light(E_CENTELLES+3,"Centelles",6000,2500));
    }

    void start() {
        sched.scheduleAtFixedRate(()->{if(!running)return;if(entryVic>0&&Math.random()<entryVic/750.0)qVic.add(idGen.getAndIncrement());},0,80,TimeUnit.MILLISECONDS);
        sched.scheduleAtFixedRate(()->{if(!running)return;if(entryCen>0&&Math.random()<entryCen/750.0)qCen.add(idGen.getAndIncrement());},0,80,TimeUnit.MILLISECONDS);
        sched.scheduleAtFixedRate(()->{
            if(!running)return;
            long now=System.currentTimeMillis(); long dt=now-lastTick; lastTick=now;
            simSec=(now-simStart)/1000;
            try{tick(dt);}catch(Exception e){e.printStackTrace();}
            SwingUtilities.invokeLater(this::repaint);
        },0,tickMs,TimeUnit.MILLISECONDS);
    }

    synchronized void tick(long dt) {
        bGran.rate=exitGran; bBcn.rate=exitBcn; bRab.rate=rabExit;
        bGran.tick(dt); bBcn.tick(dt); bRab.tick(dt);
        if(lightsOn){long now=System.currentTimeMillis();for(Light l:lights)l.update(now);}
        entries(); movement(); if(rabOn)roundabout(); metrics(); bottleneck(); diagnostic();
        maxQV=Math.max(maxQV,qVic.size()); maxQC=Math.max(maxQC,qCen.size());
    }

    void entries() {
        Integer id=qVic.peek();
        if(id!=null)for(int l=0;l<LANES;l++)if(canPlace(l,E_VIC)){qVic.poll();road[l][E_VIC]=new Car(id,E_VIC,l,Math.random()*100<pctExit);enVic++;break;}
        id=qCen.peek();
        if(id!=null)for(int l=0;l<LANES;l++)if(canPlace(l,E_CENTELLES)){qCen.poll();road[l][E_CENTELLES]=new Car(id,E_CENTELLES,l,Math.random()*100<pctExit);enCen++;break;}
    }

    boolean canPlace(int l,int p) {
        if(p<0||p>=ROAD_LEN||road[l][p]!=null)return false;
        for(int k=1;k<=gap;k++){if(p+k<ROAD_LEN&&road[l][p+k]!=null)return false;if(p-k>=0&&road[l][p-k]!=null)return false;}
        return true;
    }

    void movement() {
        for(int l=0;l<LANES;l++)for(int i=ROAD_LEN-1;i>=0;i--){
            Car c=road[l][i]; if(c==null)continue; c.braking=false;
            if(i>=X_BARCELONA){if(bBcn.consume()){road[l][i]=null;exBcn++;continue;}c.braking=true;}
            if(c.wantsExit&&i>=X_GRANOLLERS-3&&i<=X_GRANOLLERS){
                if(rabOn){if(rabCars.size()<rabCap){c.inRab=true;c.rabProg=0;rabCars.add(c);road[l][i]=null;continue;}c.braking=true;}
                else{if(bGran.consume()){road[l][i]=null;exGran++;continue;}c.braking=true;}
            }
            if(lightsOn)for(Light tl:lights)if(tl.red&&i<tl.pos&&i>=tl.pos-4)c.braking=true;
            if(!c.braking){int n=i+1;if(n<ROAD_LEN&&canFwd(l,n)){road[l][i]=null;c.pos=n;road[l][n]=c;}else c.braking=true;}
            if(c.braking&&road[l][i]==c)laneChange(l,i,c);
        }
    }

    boolean canFwd(int l,int p) {
        if(p<0||p>=ROAD_LEN||road[l][p]!=null)return false;
        for(int k=1;k<=gap;k++)if(p+k<ROAD_LEN&&road[l][p+k]!=null)return false;
        return true;
    }

    void laneChange(int l,int p,Car c) {
        for(int nl:new int[]{l-1,l+1}){if(nl<0||nl>=LANES)continue;if(canPlace(nl,p)){road[l][p]=null;c.lane=nl;road[nl][p]=c;return;}}
    }

    void roundabout() {
        synchronized(rabCars){Iterator<Car> it=rabCars.iterator();while(it.hasNext()){Car c=it.next();c.rabProg++;if(c.rabProg>=16){if(bRab.consume()||c.rabProg>40){it.remove();exGran++;}}}}
    }

    void metrics() {
        int cnt=0; Arrays.fill(segD,0);
        for(int l=0;l<LANES;l++)for(int i=0;i<ROAD_LEN;i++)if(road[l][i]!=null){cnt++;int s=i/SEG_SIZE;if(s<segD.length)segD[s]++;}
        cnt+=rabCars.size(); carsOn=cnt;
        for(int s=0;s<segD.length;s++)segD[s]/=(SEG_SIZE*LANES);
        long now=System.currentTimeMillis();
        if(now-lastH>=600){histCars[hIdx%HIST]=carsOn;hIdx++;lastH=now;}
    }

    void bottleneck() {
        double mx=0; int ms=-1;
        for(int s=0;s<segD.length;s++)if(segD[s]>mx){mx=segD[s];ms=s;}
        if(mx>0.40&&ms>=0){
            bnSeg=ms; int pos=ms*SEG_SIZE;
            if(pos>=X_GRANOLLERS-12&&pos<=X_GRANOLLERS+4)bnName="Rotonda Granollers";
            else if(pos>=X_BARCELONA-8)bnName="Salida Barcelona";
            else if(pos>=E_CENTELLES-4&&pos<=E_CENTELLES+8)bnName="Entrada Centelles";
            else bnName="Tramo km "+(pos*70/ROAD_LEN);
        }else{bnSeg=-1;bnName="";}
    }

    void diagnostic() {
        double inR=entryVic+entryCen;
        double outR=exitBcn+(rabOn?Math.min(exitGran,rabExit):exitGran);
        if(bnSeg>=0){
            int pos=bnSeg*SEG_SIZE;
            if(pos>=X_GRANOLLERS-12&&pos<=X_GRANOLLERS+4){diagMsg="ATASCO en ROTONDA! El bloque synchronized tiene demasiada contention. Sube capacidad o baja entradas.";diagColor=new Color(255,80,80);}
            else if(pos>=X_BARCELONA-8){diagMsg="ATASCO en BARCELONA! El consumidor de threads es lento. Sube la tasa de salida de Barcelona.";diagColor=new Color(255,120,60);}
            else{diagMsg="CONGESTION en "+bnName+"! Los threads compiten por el recurso. Reduce entradas o aumenta salidas.";diagColor=new Color(255,160,40);}
        }else if(inR>outR*1.3){diagMsg="CUIDADO: Entran "+(int)inR+"/min pero salen ~"+(int)outR+"/min. Productor > Consumidor = atasco en breve!";diagColor=new Color(255,200,60);}
        else if(carsOn==0&&inR==0){diagMsg="Carretera vacia. Sube las entradas para empezar la simulacion.";diagColor=new Color(150,150,180);}
        else if(carsOn<10){diagMsg="Trafico fluido. Sin contention significativa. Los threads circulan libremente.";diagColor=new Color(80,220,130);}
        else{diagMsg="Trafico equilibrado. Entrada "+(int)inR+"/min alineada con salida. Sistema estable.";diagColor=new Color(100,200,255);}
        if(lAdv!=null){
            String html="<html><body style='width:200px;padding:5px;'><b>CONSEJO DEL TUTOR:</b><br>"+diagMsg+"</body></html>";
            SwingUtilities.invokeLater(()->{lAdv.setText(html);lAdv.setForeground(diagColor);});
        }
    }

    void explainAction(String param, int value) {
        if (lEdu == null) return;
        String title, body, code;
        switch (param) {
            case "vic":
                title = "Productor de Threads - Vic";
                if (value == 0)          body = "Productor detenido. No se crean nuevos threads desde este punto.";
                else if (value < 40)     body = "Ritmo bajo (" + value + "/min). El sistema absorbe los threads sin colas.";
                else if (value < 90)     body = "Ritmo medio (" + value + "/min). Observa si el consumidor sigue el ritmo.";
                else                     body = "Ritmo ALTO (" + value + "/min). Riesgo de cola infinita si el consumidor es mas lento.";
                code = "executor.submit(() -> trabajo());  // x" + value + " veces/min";
                break;
            case "cen":
                title = "Segundo Productor - Centelles";
                if (value == 0) body = "Segundo productor detenido. Solo Vic genera threads.";
                else            body = "Segundo productor activo (" + value + "/min). Total entrando: " + (entryVic + value) + "/min.";
                code = "// Dos productores en paralelo. Total: " + (entryVic + value) + " threads/min";
                break;
            case "bcn":
                title = "Consumidor Principal - ThreadPool Barcelona";
                if (value < 10)      body = "Pool MUY lento (" + value + "/min). Como ThreadPoolExecutor con 1 solo worker. Threads acumulados.";
                else if (value < 30) body = "Pool moderado (" + value + "/min). Entrada total: " + (entryVic+entryCen) + "/min. Compara ambos.";
                else                 body = "Pool rapido (" + value + "/min). Absorbe bien la carga. Poca latencia.";
                code = "new ThreadPoolExecutor(" + Math.max(1,value/10) + ", " + Math.max(2,value/5) + ", 60L, TimeUnit.SECONDS, queue)";
                break;
            case "gran":
                title = "Consumidor Secundario - Salida Granollers";
                body = "Salida alternativa a " + value + "/min. " +
                    (value > exitBcn ? "Ahora es mas rapida que Barcelona." : "Barcelona sigue siendo el cuello de botella.");
                code = "// Balanceo de carga: Gran=" + value + " BCN=" + exitBcn + " threads/min";
                break;
            case "rabcap":
                title = "Capacidad del Mutex / Semaphore";
                if (value == 1)      body = "Mutex puro: 1 thread a la vez. Maxima serializacion. Los demas se bloquean.";
                else if (value < 5)  body = "Semaphore(" + value + "): muy restrictivo. Contention alta con muchos threads.";
                else if (value < 15) body = "Semaphore(" + value + "): capacidad moderada. Cierto paralelismo en la seccion critica.";
                else                 body = "Semaphore(" + value + "): alta capacidad. Poca contention. Casi libre.";
                code = "Semaphore sem = new Semaphore(" + value + ");  sem.acquire();  ...  sem.release();";
                break;
            case "rabexit":
                title = "Duracion del Trabajo en Seccion Critica";
                int ms = 60000 / Math.max(1, value);
                if (value < 10)      body = "Muy lento (~" + ms + "ms/op). Otros threads esperan mucho. MALO para rendimiento.";
                else if (value < 30) body = "Moderado (~" + ms + "ms/op). Buena practica: seccion critica corta.";
                else                 body = "Rapido (~" + ms + "ms/op). Lock liberado pronto. Poca contention.";
                code = "synchronized(lock) {  Thread.sleep(" + ms + ");  }  // lock liberado";
                break;
            case "pct":
                title = "Porcentaje de Threads que usan el Lock";
                if (value < 20)      body = value + "% usan el lock. Contention baja. La mayoria pasa sin parar.";
                else if (value < 60) body = value + "% usan el lock. Moderado. Observa si se forma cola en la rotonda.";
                else                 body = value + "% compiten por el mismo lock. Contention maxima. Cola larga esperada.";
                code = "if (Math.random() < " + String.format("%.2f", value/100.0) + ") { synchronized(recurso) { ... } }";
                break;
            case "gap":
                title = "Tiempo de CPU por Thread";
                if (value < 3)      body = "Threads muy densos (~" + (value*50) + "ms). Alta ocupacion CPU. Poco yield().";
                else if (value < 6) body = "Espaciado normal (~" + (value*50) + "ms). Uso de CPU realista.";
                else                body = "Threads espaciados (~" + (value*50) + "ms). Bajo uso CPU. Como threads con mucho sleep.";
                code = "Thread.sleep(" + (value*50) + ");  // cede CPU periodicamente";
                break;
            case "tick":
                title = "Velocidad del Scheduler del SO";
                if (value < 60)       body = "Scheduler rapido (cada " + value + "ms). Alta resolucion. Sistema de tiempo real.";
                else if (value < 150) body = "Scheduler normal (cada " + value + "ms). SO convencional.";
                else                  body = "Scheduler lento (cada " + value + "ms). Sistema bajo carga de CPU alta.";
                code = "executor.scheduleAtFixedRate(tarea, 0, " + value + ", TimeUnit.MILLISECONDS);";
                break;
            case "rab":
                if (value == 1) {
                    title = "Seccion Critica ACTIVADA (synchronized)";
                    body  = "Los threads deben adquirir el lock para pasar. Con contention se forma cola visible en la rotonda.";
                    code  = "synchronized(rotonda) {  // max " + rabCap + " threads simultaneos  }";
                } else {
                    title = "Seccion Critica DESACTIVADA";
                    body  = "Sin seccion critica: paso libre. Como codigo sin synchronized. Mas rapido, sin control de acceso.";
                    code  = "trabajo();  // sin lock, sin espera, sin garantias de orden";
                }
                break;
            case "lights":
                if (value == 1) {
                    title = "ReentrantLock ACTIVADO";
                    body  = "Locks explicitos activos. Los threads se paran aunque no haya contention real. Hay coste de adquirir/liberar el lock.";
                    code  = "lock.lock();  try { paso(); } finally { lock.unlock(); }";
                } else {
                    title = "ReentrantLock DESACTIVADO";
                    body  = "Sin locks: flujo libre. Maximo rendimiento pero sin garantias de orden de ejecucion.";
                    code  = "paso();  // directo, sin overhead de lock";
                }
                break;
            default:
                title = "Parametro modificado";
                body  = "Ajuste aplicado. Observa el efecto en la simulacion.";
                code  = "";
        }
        String sep = "\n";
        String txt = "[" + title + "]" + sep + sep + body
            + (code.isEmpty() ? "" : sep + sep + "Ejemplo Java:" + sep + code);
        SwingUtilities.invokeLater(() -> { lEdu.setText(txt); lEdu.setCaretPosition(0); });
    }

    @Override
    protected synchronized void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        int W=getWidth(), H=getHeight(), M=20;
        g.setPaint(new GradientPaint(0,0,new Color(18,20,30),W,H,new Color(28,35,48)));
        g.fillRect(0,0,W,H);
        g.setFont(new Font("SansSerif",Font.BOLD,16)); g.setColor(new Color(100,190,255));
        g.drawString("SIMULADOR DE TRAFICO  C-17:  VIC >>> BARCELONA",M,22);
        g.setFont(new Font("SansSerif",Font.PLAIN,12)); g.setColor(new Color(150,150,170));
        g.drawString(String.format("Tiempo: %02d:%02d",simSec/60,simSec%60),W-130,22);
        int roadX=M, roadW=W-2*M;
        double cellW=roadW/(double)ROAD_LEN;
        int[][] cities={{E_VIC,0},{E_CENTELLES,0},{X_GRANOLLERS,1},{X_BARCELONA,1}};
        String[] names={"VIC","CENTELLES","GRANOLLERS","BARCELONA"};
        String[] types={"ENTRADA","ENTRADA","SALIDA","SALIDA"};
        Color[] cols={new Color(70,200,120),new Color(70,160,230),new Color(230,160,50),new Color(220,80,75)};
        for(int ci=0;ci<4;ci++){
            int cx=(int)(roadX+cities[ci][0]*cellW); boolean isEntry=cities[ci][1]==0; Color col=cols[ci];
            g.setFont(new Font("SansSerif",Font.BOLD,13)); g.setColor(col);
            FontMetrics fm1=g.getFontMetrics(); g.drawString(names[ci],cx-fm1.stringWidth(names[ci])/2,42);
            g.setFont(new Font("SansSerif",Font.PLAIN,9)); g.setColor(new Color(col.getRed(),col.getGreen(),col.getBlue(),180));
            FontMetrics fm2=g.getFontMetrics(); g.drawString(types[ci],cx-fm2.stringWidth(types[ci])/2,54);
            if(isEntry){int qs=(ci==0)?qVic.size():qCen.size();if(qs>0){g.setFont(new Font("Consolas",Font.BOLD,11));g.setColor(qs>20?new Color(255,90,70):new Color(255,190,70));String qt="Cola: "+qs;FontMetrics fm3=g.getFontMetrics();g.drawString(qt,cx-fm3.stringWidth(qt)/2,66);}}
            if(!isEntry){long cnt=(ci==2)?exGran:exBcn;g.setFont(new Font("Consolas",Font.BOLD,10));g.setColor(new Color(190,190,210));String ct="Salidos: "+cnt;FontMetrics fm3=g.getFontMetrics();g.drawString(ct,cx-fm3.stringWidth(ct)/2,66);}
        }
        int roadY=92, laneH=30, roadH=laneH*LANES;
        for(int ci=0;ci<4;ci++){
            int cx=(int)(roadX+cities[ci][0]*cellW); boolean isEntry=cities[ci][1]==0; Color col=cols[ci];
            g.setColor(col); g.setStroke(new BasicStroke(2.5f));
            if(isEntry){g.drawLine(cx,70,cx,roadY-2);g.fillPolygon(new int[]{cx-4,cx+4,cx},new int[]{roadY-7,roadY-7,roadY-1},3);}
            else{int bot=roadY+roadH+2;g.drawLine(cx,bot,cx,bot+16);g.fillPolygon(new int[]{cx-4,cx+4,cx},new int[]{bot+11,bot+11,bot+17},3);}
            g.setStroke(new BasicStroke(1f));
        }
        if(lightsOn)for(Light tl:lights){int sx=(int)(roadX+tl.pos*cellW);g.setColor(new Color(25,25,30));g.fillRoundRect(sx-7,roadY-14,14,13,4,4);g.setColor(tl.red?new Color(255,40,40):new Color(40,255,40));g.fillOval(sx-4,roadY-12,9,9);g.setColor(new Color(80,80,90));g.drawRoundRect(sx-7,roadY-14,14,13,4,4);}
        for(int s=0;s<segD.length;s++){float d=(float)Math.min(1,segD[s]);if(d>0.08){int sx=(int)(roadX+s*SEG_SIZE*cellW);int sw=(int)(SEG_SIZE*cellW)+1;int bh=(int)(d*(laneH*LANES));Color hc=heat(d);g.setColor(new Color(hc.getRed(),hc.getGreen(),hc.getBlue(),40));g.fillRect(sx,roadY-2,sw,roadH+4);}}
        g.setColor(new Color(48,50,58)); g.fillRoundRect(roadX-2,roadY-2,roadW+4,roadH+4,8,8);
        fillZone(g,roadX,roadY,cellW,roadH,E_VIC,6,new Color(70,200,120,20));
        fillZone(g,roadX,roadY,cellW,roadH,E_CENTELLES,6,new Color(70,160,230,20));
        fillZone(g,roadX,roadY,cellW,roadH,X_GRANOLLERS-2,6,new Color(230,160,50,20));
        fillZone(g,roadX,roadY,cellW,roadH,X_BARCELONA-2,6,new Color(220,80,75,20));
        for(int l=0;l<=LANES;l++){int y=roadY+l*laneH;if(l==0||l==LANES){g.setColor(new Color(180,180,180,130));g.setStroke(new BasicStroke(2f));}else{g.setColor(new Color(255,255,255,50));g.setStroke(new BasicStroke(1f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,10f,new float[]{6f,5f},0f));}g.drawLine(roadX,y,roadX+roadW,y);}
        g.setStroke(new BasicStroke(1f));
        if(bnSeg>=0){long pulse=System.currentTimeMillis()%1000;int a=(int)(25+35*Math.sin(pulse*Math.PI/500.0));int bx=(int)(roadX+bnSeg*SEG_SIZE*cellW);int bw=(int)(SEG_SIZE*cellW)+4;g.setColor(new Color(255,40,40,a));g.fillRoundRect(bx-2,roadY-2,bw,roadH+4,6,6);g.setColor(new Color(255,70,70,100));g.setStroke(new BasicStroke(2f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,10f,new float[]{4f,3f},0f));g.drawRoundRect(bx-2,roadY-2,bw,roadH+4,6,6);g.setStroke(new BasicStroke(1f));}
        for(int l=0;l<LANES;l++)for(int i=0;i<ROAD_LEN;i++){Car c=road[l][i];if(c==null)continue;int cx=(int)(roadX+i*cellW);int cy=roadY+l*laneH+5;int cw=(int)Math.max(8,cellW-1);int ch=laneH-10;g.setColor(new Color(0,0,0,25));g.fillRoundRect(cx+1,cy+1,cw,ch,4,4);g.setColor(c.braking?c.color.darker():c.color);g.fillRoundRect(cx,cy,cw,ch,4,4);if(c.braking){g.setColor(new Color(255,25,25,200));g.fillOval(cx,cy+1,3,3);g.fillOval(cx,cy+ch-4,3,3);}if(c.wantsExit&&i>X_GRANOLLERS-25&&i<X_GRANOLLERS){if(System.currentTimeMillis()%600<300){g.setColor(new Color(255,200,40,200));g.fillOval(cx+cw-3,cy,3,3);}}}
        int belowY=roadY+roadH+4;
        g.setFont(new Font("SansSerif",Font.PLAIN,9)); g.setColor(new Color(90,90,110));
        for(int km=0;km<=70;km+=10){int x=(int)(roadX+(km*ROAD_LEN/70.0)*cellW);g.drawLine(x,belowY,x,belowY+5);g.drawString("km"+km,x-10,belowY+15);}
        if(rabOn){
            int rx=(int)(roadX+X_GRANOLLERS*cellW)+50, ry=belowY+30, sz=44;
            float fill=(float)rabCars.size()/Math.max(1,rabCap);
            g.setColor(new Color(40,42,52)); g.fillOval(rx-sz/2,ry-sz/2,sz,sz);
            g.setColor(heat(fill)); g.setStroke(new BasicStroke(3f)); g.drawOval(rx-sz/2,ry-sz/2,sz,sz); g.setStroke(new BasicStroke(1f));
            g.setFont(new Font("SansSerif",Font.BOLD,11)); g.setColor(Color.WHITE);
            String rt=rabCars.size()+"/"+rabCap; FontMetrics fmr=g.getFontMetrics(); g.drawString(rt,rx-fmr.stringWidth(rt)/2,ry+4);
            synchronized(rabCars){int n=rabCars.size();for(int i=0;i<n;i++){double ang=2*Math.PI*i/Math.max(1,n);int px=(int)(rx+(sz/2-8)*Math.cos(ang));int py=(int)(ry+(sz/2-8)*Math.sin(ang));g.setColor(rabCars.get(i).color);g.fillRoundRect(px-3,py-2,6,4,2,2);}}
            g.setFont(new Font("SansSerif",Font.BOLD,10)); g.setColor(new Color(190,170,240)); g.drawString("ROTONDA",rx-sz/2-2,ry-sz/2-6);
            g.setFont(new Font("SansSerif",Font.ITALIC,8)); g.setColor(new Color(140,140,170)); g.drawString("(seccion critica)",rx-sz/2-6,ry-sz/2+4);
            int exitX=(int)(roadX+X_GRANOLLERS*cellW); g.setColor(new Color(80,80,100)); g.setStroke(new BasicStroke(1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND)); g.drawLine(exitX,belowY+2,exitX,belowY+18); g.drawLine(exitX,belowY+18,rx-sz/2,ry); g.setStroke(new BasicStroke(1f));
        }
        if(bnSeg>=0){int bx=(int)(roadX+bnSeg*SEG_SIZE*cellW);g.setFont(new Font("SansSerif",Font.BOLD,11));g.setColor(new Color(255,90,90));g.drawString("ATASCO",bx-8,belowY+16);}
        int diagY=belowY+58, diagH=48; drawBox(g,M,diagY,W-2*M,diagH);
        g.setFont(new Font("SansSerif",Font.BOLD,12)); g.setColor(diagColor); g.drawString("DIAGNOSTICO:",M+10,diagY+16);
        g.setFont(new Font("SansSerif",Font.PLAIN,11)); g.setColor(diagColor.brighter());
        FontMetrics fmd=g.getFontMetrics(); int maxTW=W-2*M-24;
        if(fmd.stringWidth(diagMsg)>maxTW){int cut=diagMsg.length();while(cut>0&&fmd.stringWidth(diagMsg.substring(0,cut))>maxTW)cut--;cut=diagMsg.lastIndexOf(' ',cut);if(cut>0){g.drawString(diagMsg.substring(0,cut),M+10,diagY+32);g.drawString(diagMsg.substring(cut+1),M+10,diagY+44);}else g.drawString(diagMsg,M+10,diagY+32);}
        else g.drawString(diagMsg,M+10,diagY+32);
        int metY=diagY+diagH+6, metH=56; drawBox(g,M,metY,W-2*M,metH);
        g.setFont(new Font("Consolas",Font.PLAIN,12)); g.setColor(new Color(185,200,240));
        int c1=M+10, c2=M+(W-2*M)/3, c3=M+2*(W-2*M)/3;
        g.drawString("Coches: "+carsOn,c1,metY+17); g.drawString("Entrados: "+(enVic+enCen),c1,metY+33); g.drawString("Salidos:  "+(exGran+exBcn),c1,metY+49);
        g.drawString("Cola Vic: "+qVic.size()+" (max "+maxQV+")",c2,metY+17); g.drawString("Cola Cen: "+qCen.size()+" (max "+maxQC+")",c2,metY+33); g.drawString("Rotonda:  "+rabCars.size()+"/"+rabCap,c2,metY+49);
        g.drawString("Salidos Gran: "+exGran,c3,metY+17); g.drawString("Salidos BCN:  "+exBcn,c3,metY+33);
        g.setColor(bnSeg>=0?new Color(255,100,100):new Color(100,255,100)); g.drawString(bnSeg>=0?"Cuello: "+bnName:"Sin atascos",c3,metY+49);
        int gY=metY+metH+6, gH=44, gW=(W-2*M-6)/2; drawBox(g,M,gY,gW,gH);
        g.setFont(new Font("SansSerif",Font.PLAIN,9)); g.setColor(new Color(130,130,150)); g.drawString("Densidad por zona",M+6,gY+11);
        for(int s=0;s<segD.length;s++){int bx=M+4+(int)((gW-8.0)*s/segD.length);int bw=Math.max(2,(int)((gW-8.0)/segD.length)-1);int bh=(int)(segD[s]*(gH-16));g.setColor(heat((float)segD[s]));g.fillRect(bx,gY+gH-bh-2,bw,bh);}
        int g2X=M+gW+6, g2W=gW; drawBox(g,g2X,gY,g2W,gH);
        g.setFont(new Font("SansSerif",Font.PLAIN,9)); g.setColor(new Color(130,130,150)); g.drawString("Historico coches",g2X+6,gY+11);
        if(hIdx>1){int n=Math.min(hIdx,HIST);double mx=1;for(int i=0;i<n;i++)mx=Math.max(mx,histCars[(hIdx-n+i)%HIST]);g.setColor(new Color(80,180,255,160));int px=-1,py=-1;for(int i=0;i<n;i++){int xx=g2X+4+(int)((g2W-8.0)*i/n);int yy=gY+gH-4-(int)(histCars[(hIdx-n+i)%HIST]/mx*(gH-18));if(px>=0)g.drawLine(px,py,xx,yy);px=xx;py=yy;}}
        int aY=gY+gH+6;
        if(aY+28<H){drawBox(g,M,aY,W-2*M,26);g.setFont(new Font("SansSerif",Font.ITALIC,10));g.setColor(new Color(150,160,195));g.drawString("ANALOGIA:  Coche=Thread  |  Carretera=Recurso  |  Rotonda=synchronized  |  Semaforo=Lock  |  Atasco=Contention",M+10,aY+17);}
    }

    void fillZone(Graphics2D g,int rx,int ry,double cw,int rh,int pos,int len,Color c){g.setColor(c);g.fillRect((int)(rx+pos*cw),ry,(int)(len*cw),rh);}
    void drawBox(Graphics2D g,int x,int y,int w,int h){g.setColor(new Color(18,22,36,200));g.fillRoundRect(x,y,w,h,8,8);g.setColor(new Color(50,65,105,60));g.drawRoundRect(x,y,w,h,8,8);}
    Color heat(float v){v=Math.max(0,Math.min(1,v));if(v<0.3f)return new Color((int)(v/0.3f*200),210,80);if(v<0.6f)return new Color(240,(int)(210-(v-0.3f)/0.3f*130),50);return new Color(255,(int)(80-(v-0.6f)/0.4f*70),(int)(50-(v-0.6f)/0.4f*40));}

    public static void main(String[] args) {
        try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception e){}
        SwingUtilities.invokeLater(()->{
            VicBarcelonaTrafficSim sim=new VicBarcelonaTrafficSim();
            JFrame frame=new JFrame("Simulador Trafico C-17: Vic - Barcelona");
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            JPanel ctrl=buildCtrl(sim); ctrl.setPreferredSize(new Dimension(400,0));
            JPanel content=new JPanel(new BorderLayout());
            content.add(sim,BorderLayout.CENTER); content.add(ctrl,BorderLayout.EAST);
            frame.setContentPane(content); frame.setSize(1600,900);
            frame.setLocationRelativeTo(null); frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setVisible(true); sim.start();
            frame.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){sim.running=false;sim.sched.shutdownNow();}});
        });
    }


    // Panel que siempre se estira al ancho del viewport del JScrollPane
    static class FillPanel extends JPanel implements javax.swing.Scrollable {
        FillPanel(LayoutManager lm) { super(lm); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle vr, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle vr, int o, int d) { return 60; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static JPanel buildCtrl(VicBarcelonaTrafficSim sim) {
        JPanel mainP=new JPanel(new BorderLayout());
        mainP.setBackground(new Color(22,24,34));
        final FillPanel scrollContent = new FillPanel(new GridBagLayout());
        scrollContent.setBackground(new Color(22,24,34));
        scrollContent.setBorder(BorderFactory.createEmptyBorder(0,0,20,0));
        final int[] rowIdx={0};

        JPanel header=new JPanel(new BorderLayout());
        header.setBackground(new Color(15,17,26)); header.setBorder(BorderFactory.createEmptyBorder(14,16,14,16));
        JLabel hTitle=new JLabel("Panel de Control"); hTitle.setForeground(new Color(130,200,255)); hTitle.setFont(new Font("SansSerif",Font.BOLD,15));
        JLabel hSub=new JLabel("Hilos y Concurrencia - C17 Vic/Barcelona"); hSub.setForeground(new Color(80,90,120)); hSub.setFont(new Font("SansSerif",Font.ITALIC,11));
        JPanel hTexts=new JPanel(new BorderLayout()); hTexts.setOpaque(false); hTexts.add(hTitle,BorderLayout.NORTH); hTexts.add(hSub,BorderLayout.SOUTH);
        header.add(hTexts,BorderLayout.WEST);
        addRowTo(scrollContent,rowIdx,header); addRowTo(scrollContent,rowIdx,makeAccentLine(new Color(40,60,100)));

        addRowTo(scrollContent,rowIdx,makeSectionHeader("ESCENARIOS","Progresion didactica: de fluido a caos total",new Color(80,200,130)));
        addRowTo(scrollContent,rowIdx,makePresetCard("1  FLUIDO - Sin contencion","Productores y consumidores equilibrados. Threads circulan sin esperar.",new Color(28,55,35),new Color(40,180,80),()->{sim.entryVic=20;sim.entryCen=10;sim.exitGran=20;sim.exitBcn=30;sim.rabCap=15;sim.rabExit=20;sim.pctExit=30;sim.lightsOn=false;sim.rabOn=true;syncSliders(sim);}));
        addRowTo(scrollContent,rowIdx,makePresetCard("2  LOCKS - Semaforos activos","Se activan los Locks. Threads se detienen y esperan turno. Latencia visible.",new Color(50,50,15),new Color(200,190,40),()->{sim.entryVic=35;sim.entryCen=25;sim.exitGran=18;sim.exitBcn=25;sim.rabCap=12;sim.rabExit=18;sim.pctExit=35;sim.lightsOn=true;sim.rabOn=true;syncSliders(sim);}));
        addRowTo(scrollContent,rowIdx,makePresetCard("3  CONTENCION - Rotonda saturada","Seccion critica con poca capacidad. Threads en cola esperando el mutex.",new Color(55,35,10),new Color(220,140,30),()->{sim.entryVic=70;sim.entryCen=50;sim.exitGran=8;sim.exitBcn=30;sim.rabCap=3;sim.rabExit=6;sim.pctExit=50;sim.lightsOn=true;sim.rabOn=true;syncSliders(sim);}));
        addRowTo(scrollContent,rowIdx,makePresetCard("4  PRODUCTOR > CONSUMIDOR","Entran mas threads de los que salen. La cola crece hasta el colapso.",new Color(60,25,20),new Color(220,70,50),()->{sim.entryVic=80;sim.entryCen=60;sim.exitBcn=8;sim.exitGran=10;sim.rabCap=8;sim.rabExit=10;sim.pctExit=40;sim.lightsOn=true;sim.rabOn=true;syncSliders(sim);}));
        addRowTo(scrollContent,rowIdx,makePresetCard("5  DEADLOCK - Caos total","Todo saturado: rotonda minima, salidas colapsadas, entradas al maximo.",new Color(55,15,15),new Color(200,40,40),()->{sim.entryVic=130;sim.entryCen=100;sim.exitBcn=3;sim.exitGran=3;sim.rabCap=2;sim.rabExit=3;sim.pctExit=60;sim.lightsOn=true;sim.rabOn=true;syncSliders(sim);}));
        addRowTo(scrollContent,rowIdx,makeVSpacer(8));

        addRowTo(scrollContent,rowIdx,makeSectionHeader("TUTOR EN VIVO","Analisis automatico del estado del sistema",new Color(120,170,255)));
        JPanel tutorPanel=new JPanel(new BorderLayout(10,0)); tutorPanel.setBackground(new Color(28,32,48));
        tutorPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,3,0,0,new Color(80,130,220)),BorderFactory.createEmptyBorder(10,12,10,12)));
        sim.lAdv=new JLabel(); sim.lAdv.setForeground(new Color(100,200,255)); sim.lAdv.setFont(new Font("SansSerif",Font.PLAIN,11)); sim.diagnostic();
        JLabel tutorIcon=new JLabel("?"); tutorIcon.setForeground(new Color(80,130,220)); tutorIcon.setFont(new Font("SansSerif",Font.BOLD,22));
        tutorPanel.add(tutorIcon,BorderLayout.WEST); tutorPanel.add(sim.lAdv,BorderLayout.CENTER);
        JPanel tutorWrap=new JPanel(new BorderLayout()); tutorWrap.setOpaque(false); tutorWrap.setBorder(BorderFactory.createEmptyBorder(0,10,0,10)); tutorWrap.add(tutorPanel,BorderLayout.CENTER);
        addRowTo(scrollContent,rowIdx,tutorWrap); addRowTo(scrollContent,rowIdx,makeVSpacer(8));

        addRowTo(scrollContent,rowIdx,makeSectionHeader("EXPLICACION EN TIEMPO REAL","Mueve un slider para ver la explicacion en terminos de hilos",new Color(255,160,50)));
        JPanel eduWrap=new JPanel(new BorderLayout()); eduWrap.setOpaque(false); eduWrap.setBorder(BorderFactory.createEmptyBorder(0,10,0,10));
        JPanel eduBox=new JPanel(new BorderLayout()); eduBox.setBackground(new Color(22,26,38));
        eduBox.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,3,0,0,new Color(255,160,50)),BorderFactory.createEmptyBorder(10,12,10,12)));
        sim.lEdu=new JTextArea("Mueve cualquier slider o activa/desactiva opciones\npara ver aqui la explicacion en terminos de hilos Java.\n\nCada control tiene su equivalente en codigo real.");
        sim.lEdu.setForeground(new Color(200,190,150)); sim.lEdu.setBackground(new Color(22,26,38));
        sim.lEdu.setFont(new Font("Monospaced",Font.PLAIN,10)); sim.lEdu.setEditable(false);
        sim.lEdu.setLineWrap(true); sim.lEdu.setWrapStyleWord(true); sim.lEdu.setBorder(null);
        eduBox.add(sim.lEdu,BorderLayout.CENTER); eduWrap.add(eduBox,BorderLayout.CENTER);
        addRowTo(scrollContent,rowIdx,eduWrap); addRowTo(scrollContent,rowIdx,makeVSpacer(8));

        addRowTo(scrollContent,rowIdx,makeSectionHeader("PRODUCTORES  - Entradas","Generan nuevos threads (coches) al sistema",new Color(70,200,120)));
        addRowTo(scrollContent,rowIdx,makeConceptChip("Coche = Thread  |  Cola = BlockingQueue  |  Entrada llena = productor bloqueado"));
        JPanel slidersIn=new JPanel(new GridBagLayout()); slidersIn.setOpaque(false); slidersIn.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        sim.sVic=addSliderGBC(slidersIn,0,"Vic","threads/min que genera Vic",0,150,sim.entryVic,new Color(70,200,120),v->{sim.entryVic=v;sim.explainAction("vic",v);});
        sim.sCen=addSliderGBC(slidersIn,1,"Centelles","threads/min que genera Centelles",0,150,sim.entryCen,new Color(70,160,230),v->{sim.entryCen=v;sim.explainAction("cen",v);});
        addRowTo(scrollContent,rowIdx,slidersIn); addRowTo(scrollContent,rowIdx,makeVSpacer(8));

        addRowTo(scrollContent,rowIdx,makeSectionHeader("CONSUMIDORES  - Salidas","Velocidad a la que los threads abandonan el sistema",new Color(220,80,75)));
        addRowTo(scrollContent,rowIdx,makeConceptChip("Thread pool = salida  |  Saturacion = consumidor mas lento que productor"));
        JPanel slidersOut=new JPanel(new GridBagLayout()); slidersOut.setOpaque(false); slidersOut.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        sim.sBcn=addSliderGBC(slidersOut,0,"Barcelona","capacidad del consumidor principal",0,80,sim.exitBcn,new Color(220,80,75),v->{sim.exitBcn=v;sim.explainAction("bcn",v);});
        sim.sGran=addSliderGBC(slidersOut,1,"Granollers","velocidad de la salida secundaria",0,80,sim.exitGran,new Color(230,160,50),v->{sim.exitGran=v;sim.explainAction("gran",v);});
        addRowTo(scrollContent,rowIdx,slidersOut); addRowTo(scrollContent,rowIdx,makeVSpacer(8));

        addRowTo(scrollContent,rowIdx,makeSectionHeader("SECCION CRITICA  - Rotonda","Simula un bloque synchronized o un Mutex",new Color(190,140,255)));
        addRowTo(scrollContent,rowIdx,makeConceptChip("Rotonda = synchronized  |  Capacidad = Semaphore  |  Cola = threads bloqueados en el lock"));
        JPanel slidersRab=new JPanel(new GridBagLayout()); slidersRab.setOpaque(false); slidersRab.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        sim.sRabCap=addSliderGBC(slidersRab,0,"Capacidad","max threads en la seccion critica a la vez",1,40,sim.rabCap,new Color(190,140,255),v->{sim.rabCap=v;sim.explainAction("rabcap",v);});
        sim.sRabExit=addSliderGBC(slidersRab,1,"Vel. salida","velocidad de proceso dentro del lock",1,50,sim.rabExit,new Color(160,120,230),v->{sim.rabExit=v;sim.explainAction("rabexit",v);});
        sim.sPct=addSliderGBC(slidersRab,2,"% desviados","porcentaje de threads que usan la sec. critica",0,100,sim.pctExit,new Color(140,100,210),v->{sim.pctExit=v;sim.explainAction("pct",v);});
        addRowTo(scrollContent,rowIdx,slidersRab);
        JPanel cbPanel1=new JPanel(new BorderLayout()); cbPanel1.setOpaque(false); cbPanel1.setBorder(BorderFactory.createEmptyBorder(2,14,4,10));
        JCheckBox cbRab=new JCheckBox("Activar rotonda (seccion critica)",sim.rabOn); styleCb(cbRab);
        cbRab.addActionListener(e->{sim.rabOn=cbRab.isSelected();sim.explainAction("rab",sim.rabOn?1:0);});
        cbPanel1.add(cbRab,BorderLayout.WEST); addRowTo(scrollContent,rowIdx,cbPanel1); addRowTo(scrollContent,rowIdx,makeVSpacer(8));

        addRowTo(scrollContent,rowIdx,makeSectionHeader("LOCKS y FISICA","Semaforos explicitos y parametros de simulacion",new Color(255,190,70)));
        addRowTo(scrollContent,rowIdx,makeConceptChip("Semaforo = Lock/ReentrantLock  |  Distancia = tiempo CPU  |  Tick = scheduler"));
        JPanel slidersPhys=new JPanel(new GridBagLayout()); slidersPhys.setOpaque(false); slidersPhys.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));
        sim.sGap=addSliderGBC(slidersPhys,0,"Distancia min","separacion entre threads (tiempo CPU)",1,8,sim.gap,new Color(255,190,70),v->{sim.gap=v;sim.explainAction("gap",v);});
        sim.sTick=addSliderGBC(slidersPhys,1,"Velocidad sim","ms por tick (menor = mas rapido)",20,300,sim.tickMs,new Color(200,160,60),v->{sim.tickMs=v;sim.explainAction("tick",v);});
        addRowTo(scrollContent,rowIdx,slidersPhys);
        JPanel cbPanel2=new JPanel(new BorderLayout()); cbPanel2.setOpaque(false); cbPanel2.setBorder(BorderFactory.createEmptyBorder(2,14,4,10));
        JCheckBox cbLt=new JCheckBox("Activar semaforos (Locks explicitos)",sim.lightsOn); styleCb(cbLt);
        cbLt.addActionListener(e->{sim.lightsOn=cbLt.isSelected();sim.explainAction("lights",sim.lightsOn?1:0);});
        cbPanel2.add(cbLt,BorderLayout.WEST); addRowTo(scrollContent,rowIdx,cbPanel2); addRowTo(scrollContent,rowIdx,makeVSpacer(8));

        addRowTo(scrollContent,rowIdx,makeAccentLine(new Color(40,50,80)));
        JPanel ctrlBar=new JPanel(new FlowLayout(FlowLayout.LEFT,10,10)); ctrlBar.setBackground(new Color(18,20,30));
        JButton bPause=makeActionBtn("Pausar",new Color(180,150,40));
        bPause.addActionListener(e->{sim.running=!sim.running;bPause.setText(sim.running?"Pausar":"Seguir");bPause.setBackground(sim.running?new Color(180,150,40):new Color(50,160,80));});
        JButton bReset=makeActionBtn("Reiniciar",new Color(160,60,50));
        bReset.addActionListener(e->{for(int l=0;l<LANES;l++)Arrays.fill(sim.road[l],null);sim.rabCars.clear();sim.qVic.clear();sim.qCen.clear();sim.exGran=sim.exBcn=sim.enVic=sim.enCen=0;sim.simStart=System.currentTimeMillis();sim.simSec=0;sim.repaint();});
        ctrlBar.add(bPause); ctrlBar.add(bReset); addRowTo(scrollContent,rowIdx,ctrlBar);
        JLabel leyenda=new JLabel("<html><font color='#606880'>Coche=Thread  Carretera=Recurso  Rotonda=synchronized  Semaforo=Lock</font></html>");
        leyenda.setFont(new Font("SansSerif",Font.ITALIC,9)); leyenda.setBorder(BorderFactory.createEmptyBorder(0,14,10,14));
        addRowTo(scrollContent,rowIdx,leyenda);
        {GridBagConstraints _c=new GridBagConstraints();_c.gridx=0;_c.gridy=rowIdx[0]++;_c.weighty=1.0;_c.fill=GridBagConstraints.BOTH;scrollContent.add(Box.createGlue(),_c);}

        JScrollPane scroll=new JScrollPane(scrollContent);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.getViewport().setBackground(new Color(22,24,34));
        scroll.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI(){
            @Override protected void configureScrollBarColors(){this.thumbColor=new Color(55,65,100);this.trackColor=new Color(22,24,34);}
            @Override protected JButton createDecreaseButton(int o){JButton b=new JButton();b.setPreferredSize(new Dimension(0,0));return b;}
            @Override protected JButton createIncreaseButton(int o){JButton b=new JButton();b.setPreferredSize(new Dimension(0,0));return b;}
        });
        mainP.add(scroll,BorderLayout.CENTER);
        return mainP;
    }

    static JPanel makeVSpacer(int h){JPanel p=new JPanel();p.setOpaque(false);p.setPreferredSize(new Dimension(0,h));p.setMinimumSize(new Dimension(0,h));p.setMaximumSize(new Dimension(9999,h));return p;}

    static void addRowTo(JPanel panel,int[] rowIdx,JComponent comp){
        GridBagConstraints c=new GridBagConstraints();
        c.gridx=0;c.gridy=rowIdx[0]++;c.weightx=1.0;c.fill=GridBagConstraints.HORIZONTAL;c.anchor=GridBagConstraints.NORTHWEST;
        panel.add(comp,c);
    }

    static JPanel makePresetCard(String title,String desc,Color bg,Color accent,Runnable action){
        JPanel outer=new JPanel(new BorderLayout());outer.setOpaque(false);outer.setBorder(BorderFactory.createEmptyBorder(2,10,2,10));
        JPanel card=new JPanel(new BorderLayout(8,0));card.setBackground(bg);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,4,0,0,accent),BorderFactory.createEmptyBorder(7,10,7,8)));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel texts=new JPanel(new BorderLayout());texts.setOpaque(false);
        JLabel tl=new JLabel("<html><b>"+title+"</b></html>");tl.setForeground(Color.WHITE);tl.setFont(new Font("SansSerif",Font.BOLD,11));
        JLabel dl=new JLabel("<html><div style='color:#8898bb;font-size:9px'>"+desc+"</div></html>");dl.setFont(new Font("SansSerif",Font.PLAIN,9));
        texts.add(tl,BorderLayout.NORTH);texts.add(dl,BorderLayout.CENTER);
        JButton btn=new JButton(">");btn.setBackground(accent);btn.setForeground(Color.WHITE);btn.setFont(new Font("SansSerif",Font.BOLD,13));
        btn.setFocusPainted(false);btn.setBorderPainted(false);btn.setOpaque(true);btn.setPreferredSize(new Dimension(30,30));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));btn.addActionListener(e->action.run());
        card.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){action.run();}});
        card.add(texts,BorderLayout.CENTER);card.add(btn,BorderLayout.EAST);outer.add(card,BorderLayout.CENTER);return outer;
    }

    static JSlider addSliderGBC(JPanel p,int rowIdx,String label,String tooltip,int min,int max,int val,Color accent,java.util.function.IntConsumer onChange){
        GridBagConstraints c;
        JPanel labelCol=new JPanel(new BorderLayout());labelCol.setOpaque(false);labelCol.setPreferredSize(new Dimension(90,36));
        JLabel lb=new JLabel(label);lb.setForeground(new Color(180,185,210));lb.setFont(new Font("SansSerif",Font.BOLD,11));
        JLabel tt=new JLabel(tooltip);tt.setForeground(new Color(70,80,110));tt.setFont(new Font("SansSerif",Font.PLAIN,8));
        labelCol.add(lb,BorderLayout.NORTH);labelCol.add(tt,BorderLayout.SOUTH);
        c=new GridBagConstraints();c.gridx=0;c.gridy=rowIdx;c.insets=new Insets(3,0,3,8);c.anchor=GridBagConstraints.WEST;p.add(labelCol,c);
        JSlider s=new JSlider(min,max,Math.min(max,Math.max(min,val)));s.setOpaque(false);s.setToolTipText(tooltip);
        c=new GridBagConstraints();c.gridx=1;c.gridy=rowIdx;c.weightx=1.0;c.fill=GridBagConstraints.HORIZONTAL;c.insets=new Insets(3,0,3,6);p.add(s,c);
        JLabel vl=new JLabel(String.valueOf(val));vl.setForeground(accent);vl.setFont(new Font("Consolas",Font.BOLD,13));
        vl.setPreferredSize(new Dimension(40,20));vl.setHorizontalAlignment(SwingConstants.RIGHT);
        s.addChangeListener(e->{int v=s.getValue();vl.setText(""+v);onChange.accept(v);});
        c=new GridBagConstraints();c.gridx=2;c.gridy=rowIdx;c.insets=new Insets(3,0,3,0);c.anchor=GridBagConstraints.EAST;p.add(vl,c);
        return s;
    }

    static JPanel makeSectionHeader(String title,String subtitle,Color accent){
        JPanel p=new JPanel(new BorderLayout(8,0));p.setBackground(new Color(26,28,40));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(2,0,0,0,new Color(35,40,65)),BorderFactory.createEmptyBorder(9,10,7,10)));
        JLabel t=new JLabel(title);t.setForeground(accent);t.setFont(new Font("SansSerif",Font.BOLD,12));
        JLabel s=new JLabel("<html><i><font color='#555870'>"+subtitle+"</font></i></html>");s.setFont(new Font("SansSerif",Font.ITALIC,9));
        JPanel texts=new JPanel(new BorderLayout());texts.setOpaque(false);texts.add(t,BorderLayout.NORTH);texts.add(s,BorderLayout.CENTER);
        JPanel accentBar=new JPanel();accentBar.setBackground(accent);accentBar.setPreferredSize(new Dimension(4,0));
        p.add(accentBar,BorderLayout.WEST);p.add(texts,BorderLayout.CENTER);return p;
    }

    static JPanel makeConceptChip(String text){
        JPanel wrap=new JPanel(new BorderLayout());wrap.setOpaque(false);wrap.setBorder(BorderFactory.createEmptyBorder(0,10,0,10));
        JPanel p=new JPanel(new BorderLayout());p.setBackground(new Color(32,36,52));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,1,1,1,new Color(45,55,90)),BorderFactory.createEmptyBorder(5,10,5,10)));
        JLabel l=new JLabel("<html><div style='color:#606ca8;font-size:9px'>"+text+"</div></html>");l.setFont(new Font("SansSerif",Font.PLAIN,9));
        p.add(l,BorderLayout.CENTER);wrap.add(p,BorderLayout.CENTER);return wrap;
    }

    static JPanel makeAccentLine(Color color){JPanel line=new JPanel();line.setBackground(color);line.setMaximumSize(new Dimension(9999,1));line.setMinimumSize(new Dimension(0,1));line.setPreferredSize(new Dimension(0,1));return line;}

    static JButton makeActionBtn(String text,Color bg){JButton b=new JButton(text);b.setBackground(bg);b.setForeground(Color.WHITE);b.setFont(new Font("SansSerif",Font.BOLD,11));b.setFocusPainted(false);b.setBorderPainted(false);b.setOpaque(true);b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));b.setMargin(new Insets(6,10,6,10));return b;}

    static void syncSliders(VicBarcelonaTrafficSim s){
        if(s.sVic!=null)s.sVic.setValue(s.entryVic);if(s.sCen!=null)s.sCen.setValue(s.entryCen);
        if(s.sGran!=null)s.sGran.setValue(s.exitGran);if(s.sBcn!=null)s.sBcn.setValue(s.exitBcn);
        if(s.sGap!=null)s.sGap.setValue(s.gap);if(s.sTick!=null)s.sTick.setValue(s.tickMs);
        if(s.sRabCap!=null)s.sRabCap.setValue(s.rabCap);if(s.sRabExit!=null)s.sRabExit.setValue(s.rabExit);
        if(s.sPct!=null)s.sPct.setValue(s.pctExit);s.diagnostic();
    }

    static void styleCb(JCheckBox cb){cb.setForeground(new Color(165,170,200));cb.setOpaque(false);cb.setFont(new Font("SansSerif",Font.PLAIN,11));cb.setAlignmentX(Component.LEFT_ALIGNMENT);}
}