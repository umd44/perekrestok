import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

// Симулятор площади Октября (Барнаул).
// Класс po = запуск (main) + карта + состояние симуляции.
// ООП: абстракция (Actor), наследование (Vehicle → Car/Bus/Tram), полиморфизм (draw/canMove/halted),
// инкапсуляция (private-состояние симуляции, protected-состояние участников).
public class po extends JPanel {
    // Виды маршрутов (порядок = номер в списке paths) и виды остановок
    private static final List<String> KINDS = List.of("CAR", "BUS", "TRAM", "PED"), STOPS = List.of("BUS", "TRAM", "BOTH");
    // Правила появления участников в порядке: машина, автобус, трамвай, пешеход
    private static final double[] MIN = {.2, .8, 1.5, .2}, CLEAR = {34, 40, 48, 14}; // мин. интервал спавна, свободное место у старта
    private static final int[] LIM = {8, 5, 4, 18};                                   // макс. участников на один маршрут
    // Цвета, шрифты и штрихи создаём один раз, а не при каждой перерисовке
    private static final Color BG = new Color(31, 42, 52), CW = new Color(255, 255, 255, 220), SH = new Color(0, 0, 0, 55),
            SH170 = new Color(0, 0, 0, 170), W235 = new Color(255, 255, 255, 235), DARK = new Color(20, 25, 25), BLUE = new Color(55, 145, 235);
    private static final Color[] ROUTE = {new Color(0, 150, 255, 120), new Color(40, 220, 120, 170), new Color(255, 60, 60, 170), new Color(255, 220, 0, 150)},
            STOP = {new Color(0, 120, 245), new Color(220, 60, 50), new Color(130, 50, 180)};
    private static final Font F9 = new Font("SansSerif", Font.BOLD, 9), F12 = new Font("SansSerif", Font.BOLD, 12),
            F15 = new Font("SansSerif", Font.BOLD, 15), F16 = new Font("SansSerif", Font.BOLD, 16);
    private static final BasicStroke S1 = new BasicStroke(1), S2 = new BasicStroke(2), S3 = new BasicStroke(3), S13 = new BasicStroke(1.3f),
            S15 = new BasicStroke(1.5f), ROUND = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    // Данные симуляции (скрыты от других классов)
    // Все участники движения, которые сейчас есть на карте
    private final List<Actor> actors = new ArrayList<>();
    private final List<List<Path>> paths = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>()); // CAR, BUS, TRAM, PED
    // Объекты карты из файла: светофоры, переходы, стоп-линии, подземные переходы, точки ожидания, остановки, подписи
    private final List<Obj> lights = new ArrayList<>(), cross = new ArrayList<>(), lines = new ArrayList<>(), unders = new ArrayList<>(),
            waits = new ArrayList<>(), stops = new ArrayList<>(), labels = new ArrayList<>();
    private final List<Integer> groups = new ArrayList<>();                          // группы, у которых есть светофор
    private final Map<Integer, Integer> dur = new HashMap<>(Map.of(0, 6, 1, 5, 2, 4, 3, 6)); // длительность фазы группы, с
    private final int[] dens = {50, 35, 35, 70}, ivl = {18, 110, 160, 15}, next = new int[4]; // плотность %, интервал спавна (0.1 с) выбор маршрута
    // Таймеры до появления следующего участника каждого вида
    private final double[] spawn = new double[4];
    // Случайные числа (например, выбор троллейбуса)
    private final Random rnd = new Random();
    // Фоновая картинка, размер карты, скорость, время в секундах и время текущей фазы светофора
    private BufferedImage bg;
    private double W = 1024, H = 724, speed = 2, t = 13 * 3600, lightT;
    // Номер текущей группы светофоров и запасная длительность фазы
    private int phase, lightSec = 30;
    // Настройки показа и состояние светофоров
    private boolean paused, routes, showStops = true, names = true, detailed = true, allRed;

    // Создаём симуляцию: фон, карта, стартовые участники
    private po() {
        setBackground(new Color(80, 90, 80));
        try { bg = ImageIO.read(new File("october_square_satellite.png")); } catch (Exception ignored) { }
        if (bg != null) { W = bg.getWidth(); H = bg.getHeight(); }
        load();
        reset();
    }

    // Запуск программы и окно
    // Точка входа: окно с картой, верхней панелью и панелью настроек
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            po m = new po();
            JFrame f = new JFrame("Симулятор движения — Площадь Октября");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setSize(1536, 950);
            f.setMinimumSize(new Dimension(1150, 720));
            f.setLocationRelativeTo(null);

            // Верхняя панель: часы, кнопки запуска и скорость
            JLabel clock = lbl("", 12, false), speedL = lbl("Скорость: 2.0x", 12, false), stats = lbl("", 12, false);
            JSlider sp = new JSlider(5, 50, 20);
            sp.setPreferredSize(new Dimension(120, 22));
            sp.setOpaque(false);
            sp.addChangeListener(e -> { m.speed = sp.getValue() / 10.0; speedL.setText(String.format("Скорость: %.1fx", m.speed)); });
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)), bar = new JPanel(new BorderLayout());
            right.setOpaque(false);
            for (Component c : new Component[]{clock, btn("▶", () -> m.paused = false), btn("❚❚", () -> m.paused = true),
                    btn("■", () -> { m.paused = true; m.reset(); }), speedL, sp}) right.add(c);
            bar.setBackground(new Color(28, 37, 46));
            bar.setBorder(new EmptyBorder(5, 10, 5, 10));
            bar.add(lbl(f.getTitle(), 16, true), BorderLayout.WEST);
            bar.add(right, BorderLayout.EAST);

            // единственная вкладка — «Симуляция»
            JPanel tab = new JPanel();
            tab.setOpaque(false);
            tab.setLayout(new BoxLayout(tab, BoxLayout.Y_AXIS));
            tab.setBorder(new EmptyBorder(10, 10, 10, 10));
            tab.add(lbl("Параметры симуляции", 16, true));
            String[] who = {"автомобилей", "автобусов", "трамваев", "пешеходов"}, every = {"машин", "автобусов/троллейбусов", "трамваев", "пешеходов"};
            int[][] range = {{3, 100}, {10, 300}, {20, 400}, {3, 100}};
            for (int i = 0; i < 4; i++) { int k = i; slider(tab, "Плотность " + who[i], 0, 100, m.dens[i], v -> v + " %", v -> m.dens[k] = v); }
            tab.add(Box.createVerticalStrut(8));
            tab.add(lbl("Частота появления объектов", 13, true));
            for (int i = 0; i < 4; i++) { int k = i; slider(tab, "Интервал " + every[i], range[i][0], range[i][1], m.ivl[i], v -> String.format("%.1f с", v / 10.0), v -> m.ivl[k] = v); }
            slider(tab, "Базовый цикл светофора, сек", 15, 60, 30, v -> v + " с", v -> m.lightSec = v);
            tab.add(Box.createVerticalStrut(10));
            check(tab, "Показать маршруты", false, b -> m.routes = b);
            check(tab, "Показать остановки", true, b -> m.showStops = b);
            check(tab, "Показать названия", true, b -> m.names = b);
            check(tab, "Детальный режим", true, b -> m.detailed = b);

            // Боковая панель с прокруткой и статистикой внизу
            JScrollPane sc = new JScrollPane(tab);
            sc.setBorder(null);
            sc.getViewport().setBackground(BG);
            sc.getVerticalScrollBar().setUnitIncrement(16);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Симуляция", sc);
            stats.setBorder(new EmptyBorder(6, 10, 8, 10));
            JPanel side = new JPanel(new BorderLayout());
            side.setBackground(BG);
            side.setPreferredSize(new Dimension(Math.max(300, Math.min(430, Toolkit.getDefaultToolkit().getScreenSize().width / 4)), 0));
            side.add(tabs, BorderLayout.CENTER);
            side.add(stats, BorderLayout.SOUTH);

            f.add(bar, BorderLayout.NORTH);
            f.add(m, BorderLayout.CENTER);
            f.add(side, BorderLayout.EAST);
            // Таймер: каждые 16 мс двигает симуляцию, обновляет часы и перерисовывает карту
            int[] n = {0};
            new javax.swing.Timer(16, e -> {
                if (!m.paused) m.step(0.016 * m.speed);
                long s = (long) m.t % 86400;
                clock.setText(String.format("☀  %02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60));
                if (n[0]++ % 15 == 0) stats.setText(m.stats());
                m.repaint();
            }).start();
            f.setVisible(true);
        });
    }

    // Белая подпись нужного размера
    private static JLabel lbl(String s, int size, boolean bold) {
        JLabel l = new JLabel(s);
        l.setForeground(Color.WHITE);
        l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        l.setAlignmentX(0f);
        return l;
    }

    private static JButton btn(String s, Runnable r) { JButton b = new JButton(s); b.addActionListener(e -> r.run()); return b; }

    // Флажок: при нажатии передаёт новое значение
    private static void check(JPanel p, String s, boolean v, Consumer<Boolean> a) {
        JCheckBox c = new JCheckBox(s, v);
        c.setForeground(Color.WHITE);
        c.setOpaque(false);
        c.setAlignmentX(0f);
        c.addActionListener(e -> a.accept(c.isSelected()));
        p.add(c);
    }

    // Ползунок с названием и текущим значением
    private static void slider(JPanel p, String name, int min, int max, int v, IntFunction<String> f, IntConsumer a) {
        JLabel val = lbl(f.apply(v), 12, false);
        val.setForeground(new Color(160, 210, 255));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setAlignmentX(0f);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        head.add(lbl(name, 12, false), BorderLayout.WEST);
        head.add(val, BorderLayout.EAST);
        JSlider s = new JSlider(min, max, v);
        s.setOpaque(false);
        s.setAlignmentX(0f);
        s.addChangeListener(e -> { val.setText(f.apply(s.getValue())); a.accept(s.getValue()); });
        p.add(Box.createVerticalStrut(8));
        p.add(head);
        p.add(s);
    }

    // Загрузка карты из файла october_square_layout.txt
    // Короткие обёртки для чтения чисел из файла
    private static double d(String s) { return Double.parseDouble(s); }
    private static int i(String s) { return Integer.parseInt(s); }

    // Читаем карту построчно: первое слово строки - тип объекта
    private void load() {
        try {
            for (String l : Files.readAllLines(new File("october_square_layout.txt").toPath())) {
                String[] t = l.trim().split("(?<!\\\\)\\s+"); // пробел, экранированный "\ ", разделителем не считается
                if (t.length < 2 || t[0].startsWith("#")) continue;
                switch (t[0]) {
                    case "PHASE" -> { for (int j = 1; j < t.length; j++) dur.put(j - 1, i(t[j])); }
                    case "LIGHT" -> lights.add(new Obj(d(t[1]), d(t[2]), 0, 0, i(t[3]), null));
                    case "CROSSWALK" -> cross.add(new Obj(d(t[1]), d(t[2]), d(t[3]), d(t[4]), i(t[5]), null));
                    case "STOPLINE2" -> lines.add(new Obj(d(t[1]), d(t[2]), d(t[3]), d(t[4]), i(t[5]), null));
                    case "UNDERPASS" -> unders.add(new Obj(d(t[1]), d(t[2]), 0, 0, t.length > 3 ? i(t[3]) : -1, null));
                    case "PEDWAIT" -> waits.add(new Obj(d(t[1]), d(t[2]), 0, 0, i(t[3]), null));
                    case "LABEL" -> labels.add(new Obj(d(t[2]), d(t[3]), d(t[4]), 0, 0, t[1].replaceAll("\\\\(.)", "$1"))); // x2 = угол
                    case "STOP" -> stops.add(new Obj(d(t[1]), d(t[2]), 0, 0, Math.max(0, STOPS.indexOf(t[4])), t[3].replaceAll("\\\\(.)", "$1")));
                    case "ROUTE" -> {
                        List<double[]> pts = new ArrayList<>();
                        for (int j = 3; j < t.length; j++) { String[] xy = t[j].split(","); pts.add(new double[]{d(xy[0]), d(xy[1])}); }
                        if (KINDS.contains(t[1]) && pts.size() > 1) paths.get(KINDS.indexOf(t[1])).add(new Path(pts));
                    }
                    default -> { }
                }
            }
        } catch (Exception e) {
            System.err.println("Не удалось загрузить карту: " + e);
            Stream.of(lights, cross, lines, unders, waits, stops, labels).forEach(List::clear);
            paths.forEach(List::clear);
        }
        // Запоминаем группы со светофорами и заранее считаем пересечения маршрутов
        for (Obj l : lights) if (!groups.contains(l.g)) groups.add(l.g);
        Collections.sort(groups);
        paths.forEach(list -> list.forEach(p -> p.bind(this)));
    }

    // Логика симуляции
    // Горит ли сейчас зелёный у группы g
    private boolean green(int g) { return g == phase && !allRed; }

    // Сброс: очищаем карту и запускаем по одному участнику на каждый маршрут
    private void reset() {
        actors.clear();
        Arrays.fill(spawn, 0);
        Arrays.fill(next, 0);
        phase = 0;
        lightT = 0;
        for (int k = 0; k < 4; k++) for (int j = 0; j < paths.get(k).size(); j++) put(k, j);
    }

    // Один шаг симуляции: светофоры, появление новых участников, движение
    private void step(double dt) {
        t += dt;
        lightT += dt;
        if (!groups.isEmpty()) { // крутим только группы, у которых реально есть светофор
            if (!groups.contains(phase)) { phase = groups.get(0); lightT = 0; }
            int len = dur.getOrDefault(phase, 8);
            if (len <= 0) len = Math.max(2, lightSec);
            if (lightT > len) { lightT = 0; phase = groups.get((groups.indexOf(phase) + 1) % groups.size()); }
            allRed = lightT >= len - .1; // короткая пауза «все красные» перед сменой
        }
        // Появление новых участников по таймеру
        for (int k = 0; k < 4; k++) {
            spawn[k] += dt;
            int n = paths.get(k).size();
            if (spawn[k] > Math.max(MIN[k], ivl[k] / 10.0 * (1.55 - dens[k] / 100.0))) {
                spawn[k] = 0;
                if (n > 0) { put(k, next[k] % n); next[k] = (next[k] + 1) % n; }
            }
        }
        for (Actor a : actors) a.update(dt, this); // полиморфизм: все участники обновляются одним вызовом
        actors.removeIf(a -> !a.active);
        if (actors.size() > 220) actors.subList(0, actors.size() - 220).clear();
    }

    // Ставим участника в начало маршрута, если там свободно и на маршруте не слишком много участников
    private void put(int k, int route) {
        Path p = paths.get(k).get(route);
        int on = 0;
        for (Actor a : actors) {
            if (!a.active) continue;
            if (Math.hypot(p.x[0] - a.x, p.y[0] - a.y) < CLEAR[k]) return;
            if (a.path == p) on++;
        }
        if (on < LIM[k]) actors.add(switch (k) {
            case 0 -> new Car(p);
            case 1 -> new Bus(p, rnd.nextDouble() < .4); // троллейбусы едут по полосе автобусов
            case 2 -> new Tram(p);
            default -> new Ped(p);
        });
    }

    // Остановка, которую ждёт пешеход: маршрут заканчивается у неё.
    private Obj stopFor(Ped p) {
        if (p.path.len - p.progress > 40) return null;
        Obj best = null;
        double bd = 18;
        for (Obj s : stops) { double dd = Math.hypot(p.x - s.x, p.y - s.y); if (dd < bd) { bd = dd; best = s; } }
        return best;
    }

    // Посадка: забирает пешеходов, которые ждут именно эту остановку.
    private int board(Vehicle v, Obj stop) {
        int n = 0;
        for (Actor a : actors)
            if (a.active && a instanceof Ped p && stopFor(p) == stop && Math.hypot(a.x - stop.x, a.y - stop.y) <= 20) { a.active = false; n++; }
        v.passengers += n;
        return n;
    }

    // Текст статистики для боковой панели
    private String stats() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String s : List.of("Машины", "Автобусы", "Троллейбусы", "Трамваи", "Люди")) m.put(s, 0);
        int pass = 0;
        for (Actor a : actors) { m.merge(a.label, 1, Integer::sum); if (a instanceof Vehicle v) pass += v.passengers; }
        StringBuilder sb = new StringBuilder("<html><b>На карте</b>");
        m.forEach((k, v) -> sb.append("<br>").append(k).append(": ").append(v));
        return sb.append("<br>Пассажиры в транспорте: ").append(pass).append("</html>").toString();
    }

    // Рисование карты
    // Рисуем слоями: фон, маршруты, переходы, светофоры, остановки, участники, подписи
    @Override protected void paintComponent(Graphics gg) {
        super.paintComponent(gg);
        Graphics2D g = (Graphics2D) gg.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        if (bg != null) g.drawImage(bg, 0, 0, w, h, null);
        g.scale(w / W, h / H);
        if (routes) for (int k = 0; k < 4; k++) { g.setStroke(S3); g.setColor(ROUTE[k]); for (Path p : paths.get(k)) g.draw(p.shape); }
        g.setStroke(S3);
        g.setColor(CW);
        for (Obj c : cross) { // «зебра»
            double l = Math.max(1, Math.hypot(c.x2 - c.x, c.y2 - c.y)), nx = -(c.y2 - c.y) / l * 2, ny = (c.x2 - c.x) / l * 2;
            for (int j = -4; j <= 4; j += 2) g.draw(new Line2D.Double(c.x + nx * j, c.y + ny * j, c.x2 + nx * j, c.y2 + ny * j));
        }
        // Подземные переходы
        for (Obj u : unders) {
            int ix = (int) u.x, iy = (int) u.y;
            g.setColor(new Color(35, 110, 205));
            g.fillRoundRect(ix - 13, iy - 13, 26, 26, 6, 6);
            g.setColor(Color.WHITE);
            g.setStroke(S2);
            g.drawRoundRect(ix - 13, iy - 13, 26, 26, 6, 6);
            g.setFont(F16);
            g.drawString("↘", ix - 9, iy + 6);
            g.setFont(F9);
            g.drawString("П", ix - 3, iy - 3);
            if (u.g >= 0) g.drawString("U" + u.g, ix - 10, iy + 12);
        }
        // Светофоры
        for (Obj l : lights) {
            int ix = (int) l.x, iy = (int) l.y;
            boolean gr = green(l.g);
            g.setColor(DARK);
            g.fillRoundRect(ix - 6, iy - 14, 12, 28, 4, 4);
            g.setColor(gr ? Color.GREEN : Color.RED);
            g.fillOval(ix - 4, iy + (gr ? 4 : -11), 8, 8);
        }
        // Остановки с названиями
        if (showStops) for (Obj s : stops) {
            int ix = (int) s.x, iy = (int) s.y;
            g.setColor(STOP[s.g]);
            g.fillRoundRect(ix - 8, iy - 8, 16, 16, 4, 4);
            g.setColor(Color.WHITE);
            g.setStroke(S1);
            g.drawRoundRect(ix - 8, iy - 8, 16, 16, 4, 4);
            g.setFont(F12);
            g.drawString("▣", ix - 5, iy + 5);
            g.setColor(SH170);
            g.fillRoundRect(ix + 10, iy - 10, s.s.length() * 7 + 8, 20, 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(s.s, ix + 14, iy + 5);
        }
        // Каждый участник рисует себя сам
        for (Actor a : actors) a.draw(g, detailed);
        // Названия улиц с поворотом и тенью
        if (names) {
            g.setFont(F15);
            for (Obj b : labels) {
                AffineTransform old = g.getTransform();
                g.rotate(b.x2, b.x, b.y);
                g.setColor(SH170);
                g.drawString(b.s, (int) b.x + 2, (int) b.y + 2);
                g.setColor(W235);
                g.drawString(b.s, (int) b.x, (int) b.y);
                g.setTransform(old);
            }
        }
        g.dispose();
    }

    // Модель: объекты карты и участники движения

    // Универсальный неизменяемый объект карты: x,y(,x2,y2) — геометрия, g — группа/вид/пара, s — текст. Для подписи x2 = угол.
    static final class Obj {
        final double x, y, x2, y2;
        final int g;
        final String s;
        Obj(double x, double y, double x2, double y2, int g, String s) { this.x = x; this.y = y; this.x2 = x2; this.y2 = y2; this.g = g; this.s = s; }
    }

    // Маршрут-полилиния. Все пересечения со стоп-линиями, переходами и остановками считаются ОДИН раз (bind).
    static final class Path {
        final double[] x, y, c; // координаты вершин и накопленная длина
        final double len;
        final Path2D.Double shape = new Path2D.Double();
        final List<double[]> stopLines = new ArrayList<>(), pedWaits = new ArrayList<>(), walkWaits = new ArrayList<>(), zebras = new ArrayList<>(), stopsNear = new ArrayList<>();
        double[][] cw; // по каждому переходу: {прогресс середины, прогресс точки ожидания}

        // Строим маршрут из точек и считаем длину до каждой вершины
        Path(List<double[]> p) {
            int n = p.size();
            x = new double[n]; y = new double[n]; c = new double[n];
            for (int j = 0; j < n; j++) {
                x[j] = p.get(j)[0]; y[j] = p.get(j)[1];
                if (j == 0) shape.moveTo(x[j], y[j]); else { c[j] = c[j - 1] + Math.hypot(x[j] - x[j - 1], y[j] - y[j - 1]); shape.lineTo(x[j], y[j]); }
            }
            len = c[n - 1];
        }

        // Точка на расстоянии d от начала маршрута → o[0], o[1].
        void at(double d, double[] o) {
            d = Math.max(0, Math.min(len, d));
            int j = Arrays.binarySearch(c, d);
            if (j < 0) j = -j - 2;
            j = Math.max(0, Math.min(x.length - 2, j));
            double seg = c[j + 1] - c[j], k = seg <= 0 ? 0 : (d - c[j]) / seg;
            o[0] = x[j] + (x[j + 1] - x[j]) * k;
            o[1] = y[j] + (y[j + 1] - y[j]) * k;
        }

        // Проекция точки на маршрут → {прогресс, расстояние}.
        double[] project(double px, double py) {
            double bd = Double.MAX_VALUE, bp = 0;
            for (int j = 0; j < x.length - 1; j++) {
                double dx = x[j + 1] - x[j], dy = y[j + 1] - y[j], l2 = dx * dx + dy * dy;
                double k = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - x[j]) * dx + (py - y[j]) * dy) / l2));
                double dd = Math.hypot(px - x[j] - dx * k, py - y[j] - dy * k);
                if (dd < bd) { bd = dd; bp = c[j] + Math.sqrt(l2) * k; }
            }
            return new double[]{bp, bd};
        }

        // Заранее находим, где маршрут пересекает стоп-линии, переходы и остановки
        void bind(po m) {
            for (Obj l : m.lines) { // стоп-линия: лучшее из 41 точки отрезка
                double[] best = null;
                for (int j = 0; j <= 40; j++) { double[] r = project(l.x + (l.x2 - l.x) * j / 40, l.y + (l.y2 - l.y) * j / 40); if (best == null || r[1] < best[1]) best = r; }
                if (best[1] <= 14 && m.groups.contains(l.g)) stopLines.add(new double[]{best[0], l.g});
            }
            for (Obj w : m.waits) { double[] r = project(w.x, w.y); if (r[1] <= 32) pedWaits.add(new double[]{r[0], w.g}); }
            // Для каждого перехода: где он на маршруте и где пешеход должен ждать
            cw = new double[m.cross.size()][];
            for (int j = 0; j < cw.length; j++) {
                Obj o = m.cross.get(j);
                double[] r = project((o.x + o.x2) / 2, (o.y + o.y2) / 2);
                double wait = Math.max(0, r[0] - 10), bd = Double.MAX_VALUE;
                for (Obj w : m.waits) { double[] q = project(w.x, w.y); if (w.g == o.g && q[1] <= 32 && q[1] < bd) { bd = q[1]; wait = q[0]; } }
                cw[j] = new double[]{r[0], wait};
                if (r[1] <= 38 && r[0] - 18 >= 28) zebras.add(new double[]{r[0] - 18, j});
                if (o.g >= 0 && m.groups.contains(o.g) && r[1] <= 45) walkWaits.add(new double[]{Math.max(0, r[0] - 10), o.g});
            }
            for (int j = 0; j < m.stops.size(); j++) { double[] r = project(m.stops.get(j).x, m.stops.get(j).y); if (r[1] <= 55) stopsNear.add(new double[]{r[0], j}); }
        }
    }

    // Абстракция: любой участник движения. Шаблонный метод update(); детали — в наследниках.
    static abstract class Actor {
        protected double x, y, speed, progress;
        protected boolean active = true;
        protected final Path path;
        protected final String label; // название для статистики
        protected final double[] tmp = new double[2];

        // Участник появляется в начале своего маршрута
        Actor(Path p, double speed, String label) { path = p; this.speed = speed; this.label = label; x = p.x[0]; y = p.y[0]; }

        // Что у каждого вида своё: рисунок, число подшагов и условия движения
        abstract void draw(Graphics2D g, boolean detailed);
        protected abstract int substeps();
        protected abstract boolean canMove(double np, double nx, double ny, double dt, po m);
        protected boolean halted(double dt, po m) { return false; } // ожидание до начала шага (подземный переход, светофор)

        // Общий алгоритм движения: проверки перед шагом, потом короткие шаги по маршруту
        final void update(double dt, po m) {
            if (progress >= path.len) { active = false; return; }
            if (halted(dt, m)) return;
            int n = substeps();
            double s = speed * dt / n;
            for (int j = 0; j < n && progress < path.len; j++) {
                double np = Math.min(path.len, progress + s);
                path.at(np, tmp);
                if (!canMove(np, tmp[0], tmp[1], dt, m)) return;
                progress = np; x = tmp[0]; y = tmp[1];
            }
        }

        // Метка как на Яндекс Картах: тень, белый кант, цветной круг.
        protected void pin(Graphics2D g, Color fill, int r) {
            int cx = (int) x, cy = (int) y;
            g.setColor(SH); g.fillOval(cx - r + 2, cy - r + 3, r * 2, r * 2);
            g.setColor(Color.WHITE); g.fillOval(cx - r - 2, cy - r - 2, r * 2 + 4, r * 2 + 4);
            g.setColor(fill); g.fillOval(cx - r, cy - r, r * 2, r * 2);
        }
    }

    // Транспорт: остановки на остановках, красный свет, уступание пешеходам, дистанция.
    static abstract class Vehicle extends Actor {
        protected int passengers;
        private final int stopKind, half, gap, off; // вид остановки (-1 нет), полудлина, зазор у стоп-линии, отступ до остановки
        private double dwell, crossWait;
        private Obj cur;
        private final Set<Obj> served = new HashSet<>();

        // Параметры транспорта: вид остановки, размер и отступы
        Vehicle(Path p, double v, String label, int stopKind, int half, int gap, int off) {
            super(p, v, label);
            this.stopKind = stopKind; this.half = half; this.gap = gap; this.off = off;
        }

        @Override protected int substeps() { return 6; }

        @Override protected boolean canMove(double np, double nx, double ny, double dt, po m) {
            // Едем, только если не стоим на остановке, нет красного, не уступаем пешеходам и никто не мешает
            return !atStop(dt, np, m) && !red(np, m) && !giveWay(np, dt, m) && !blocked(nx, ny, m);
        }

        // Стоянка на остановке: сажаем пассажиров 2-3 секунды
        private boolean atStop(double dt, double np, po m) {
            if (dwell > 0) { dwell = Math.max(0, dwell - dt); if (cur != null) m.board(this, cur); return true; }
            cur = null;
            Obj s = ahead(np, m);
            if (s == null) return false;
            served.add(s);
            cur = s;
            dwell = Math.min(3, 2 + m.board(this, s) * .15); // стоянка 2–3 с
            return true;
        }

        // Ближайшая остановка впереди, которую ещё не обслужили
        private Obj ahead(double np, po m) {
            if (stopKind < 0) return null;
            Obj best = null;
            double bp = Double.MAX_VALUE;
            for (double[] s : path.stopsNear) {
                Obj o = m.stops.get((int) s[1]);
                double tg = Math.max(0, s[0] - off);
                if ((o.g != 2 && o.g != stopKind) || served.contains(o) || tg < 24 || tg < progress - 2) continue;
                if (tg <= np + 1.5 && tg < bp) { best = o; bp = tg; }
            }
            return best;
        }

        // Нужно ли остановиться перед стоп-линией на красный
        private boolean red(double np, po m) {
            double near = Double.MAX_VALUE;
            for (double[] s : path.stopLines) {
                double sp = Math.max(0, s[0] - gap);
                if (sp >= 12 && sp > progress + .25 && !m.green((int) s[1])) near = Math.min(near, sp);
            }
            return np >= near - .25;
        }

        // Уступаем дорогу пешеходам на переходе
        private boolean giveWay(double np, double dt, po m) {
            boolean wait = false, reg = false;
            for (double[] z : path.zebras) {
                if (z[0] <= progress || np < z[0]) continue;
                Obj c = m.cross.get((int) z[1]);
                for (Actor a : m.actors) {
                    if (!(a instanceof Ped) || !a.active || Line2D.ptSegDist(c.x, c.y, c.x2, c.y2, a.x, a.y) >= 16) continue;
                    if (c.g >= 0) { // на регулируемом переходе считаем только тех, кто уже вышел на «зебру»
                        double[] q = a.path.cw[(int) z[1]];
                        if (a.progress <= q[1] + 1.5 || a.progress > q[0] + 14) continue;
                    }
                    wait = true; reg = c.g >= 0;
                    break;
                }
                if (wait) break;
            }
            if (!wait) { crossWait = 0; return false; }
            crossWait += dt;
            if (!reg && crossWait > 6) { crossWait = 0; return false; } // на нерегулируемом — не стоим вечно
            return true;
        }

        // Держим дистанцию и не наезжаем на другой транспорт
        private boolean blocked(double nx, double ny, po m) {
            for (Actor o : m.actors) {
                if (o == this || !o.active || !(o instanceof Vehicle v)) continue;
                if (o.path == path) { if (o.progress > progress && o.progress - progress < half + v.half + 7) return true; continue; }
                double req = half + v.half + 2;
                if (Math.hypot(nx - o.x, ny - o.y) < req && Math.hypot(nx - o.x, ny - o.y) < Math.hypot(x - o.x, y - o.y) - .02
                        && (progress < o.progress - 1 || Math.abs(progress - o.progress) <= 1 && System.identityHashCode(this) > System.identityHashCode(o))) return true;
            }
            return false;
        }

        // Значок автобуса/трамвая: метка + белый корпус с окном и колёсами.
        protected void box(Graphics2D g, Color c, int r, boolean detailed) {
            pin(g, c, r);
            if (!detailed) return;
            int cx = (int) x, cy = (int) y;
            g.setColor(Color.WHITE); g.fillRoundRect(cx - 6, cy - 7, 12, 13, 4, 4);
            g.setColor(c); g.fillRect(cx - 4, cy - 5, 8, 4);
            g.setColor(Color.WHITE); g.fillOval(cx - 5, cy + 4, 3, 3); g.fillOval(cx + 2, cy + 4, 3, 3);
        }
    }

    static class Car extends Vehicle {
        Car(Path p) { super(p, 85 + Math.random() * 25, "Машины", -1, 11, 5, 16); }
        @Override void draw(Graphics2D g, boolean detailed) {
            pin(g, BLUE, 10);
            if (!detailed) return;
            int cx = (int) x, cy = (int) y;
            g.setColor(Color.WHITE); g.fillRoundRect(cx - 6, cy - 3, 12, 7, 4, 4); g.fillRect(cx - 4, cy - 6, 8, 4);
            g.setColor(BLUE); g.fillRect(cx - 2, cy - 5, 4, 2);
            g.setColor(Color.WHITE); g.fillOval(cx - 5, cy + 2, 3, 3); g.fillOval(cx + 2, cy + 2, 3, 3);
        }
    }

    // Автобус или троллейбус (штанги на крыше).
    static class Bus extends Vehicle {
        private final boolean trolley;
        private final Color color;
        Bus(Path p, boolean trolley) {
            super(p, trolley ? 60 : 65, trolley ? "Троллейбусы" : "Автобусы", 0, 18, 6, 16);
            this.trolley = trolley;
            color = trolley ? new Color(30, 136, 229) : Math.random() < .5 ? new Color(56, 180, 48) : new Color(190, 55, 205);
        }
        @Override void draw(Graphics2D g, boolean detailed) {
            box(g, color, 11, detailed);
            if (detailed && trolley) { int cx = (int) x, cy = (int) y; g.setColor(Color.WHITE); g.setStroke(S15); g.drawLine(cx - 3, cy - 7, cx - 5, cy - 11); g.drawLine(cx + 3, cy - 7, cx + 5, cy - 11); }
        }
    }

    static class Tram extends Vehicle {
        private static final Color COLOR = new Color(225, 65, 70);
        Tram(Path p) { super(p, 52, "Трамваи", 1, 22, 7, 20); }
        @Override void draw(Graphics2D g, boolean detailed) {
            box(g, COLOR, 12, detailed);
            if (detailed) { int cx = (int) x, cy = (int) y; g.setColor(Color.WHITE); g.setStroke(S13); g.drawLine(cx - 5, cy + 5, cx + 5, cy + 5); g.drawLine(cx - 4, cy + 7, cx - 1, cy + 4); g.drawLine(cx + 4, cy + 7, cx + 1, cy + 4); }
        }
    }

    // Пешеход: ждёт зелёного, ждёт транспорт на остановке, ходит через подземные переходы.
    static class Ped extends Actor {
        private static final Color COLOR = new Color(255, 145, 35);
        private double timer, cool, until = -1; // таймер подземки, пауза после выхода, «разрешённый» участок перехода
        private Obj exit;

        Ped(Path p) { super(p, 18 + Math.random() * 8, "Люди"); }

        @Override protected int substeps() { return 1; }

        @Override protected boolean canMove(double np, double nx, double ny, double dt, po m) {
            for (Actor a : m.actors) if (a != this && a.active && a instanceof Ped && Math.hypot(nx - a.x, ny - a.y) < 6) return false;
            return true;
        }

        // Подземный переход и ожидание перед шагом
        @Override protected boolean halted(double dt, po m) {
            if (cool > 0) cool = Math.max(0, cool - dt);
            if (timer > 0) { // под землёй: через 1.5 с выходит у парного входа
                timer -= dt;
                if (timer <= 0 && exit != null) {
                    double[] r = path.project(exit.x, exit.y);
                    if (r[1] <= 90) { progress = r[0]; path.at(progress, tmp); x = tmp[0]; y = tmp[1]; } else { x = exit.x; y = exit.y; }
                    exit = null;
                    cool = 2;
                }
                return true;
            }
            if (cool <= 0) for (Obj e : m.unders) {
                if (e.g < 0 || Math.hypot(x - e.x, y - e.y) > 15) continue;
                for (Obj u : m.unders) if (u != e && u.g == e.g) { exit = u; timer = 1.5; return true; }
            }
            return mustWait(m);
        }

        // Нужно ли пешеходу ждать: остановка или красный/зелёный на переходе
        private boolean mustWait(po m) {
            if (m.stopFor(this) != null) return true; // ждёт транспорт на остановке
            if (until >= 0) { if (progress < until) return false; until = -1; }
            for (double[] w : path.pedWaits) { // ручные точки ожидания (WAIT G): ждём, пока машинам зелёный
                int g = (int) w[1];
                if (!m.groups.contains(g)) continue;
                double dd = w[0] - progress;
                if (m.green(g) && dd >= -.5 && dd <= 14) return true;
                if (!m.green(g) && dd >= -1.5 && dd <= 14) { until = Math.min(path.len, w[0] + 32); return false; } // красный — проходим всю зебру
            }
            if (!path.pedWaits.isEmpty()) return false;
            for (double[] w : path.walkWaits) { // переход со светофором: идём, когда группе НЕ зелёный
                double dd = w[0] - progress;
                if (m.green((int) w[1]) && dd >= 0 && dd <= 14) return true;
            }
            return false;
        }

        @Override void draw(Graphics2D g, boolean detailed) {
            if (timer > 0) return;
            pin(g, COLOR, 9);
            if (!detailed) return;
            int cx = (int) x, cy = (int) y;
            g.setColor(Color.WHITE);
            g.fillOval(cx - 2, cy - 6, 4, 4);
            g.setStroke(ROUND);
            g.drawLine(cx, cy - 1, cx, cy + 3); g.drawLine(cx, cy, cx - 4, cy + 2); g.drawLine(cx, cy, cx + 4, cy + 2);
            g.drawLine(cx, cy + 3, cx - 3, cy + 6); g.drawLine(cx, cy + 3, cx + 3, cy + 6);
        }
    }
}
