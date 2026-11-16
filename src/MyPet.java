import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.*;

/** 바탕화면 공부 펫: 뽀모도로 + 급식/시간표/학사일정 + 할 일/디데이 */
public class MyPet {

    // ---- 학교 설정 (인증키는 환경변수 MYPET_NEIS_KEY) ----
    static final String OFFICE = "F10";
    static final String SCHOOL = "7140392";
    static final String TYPE = "his";              // his 고 / mis 중 / els 초
    static final String GRADE = "1";
    static final String CLS = "3";
    static final LocalTime LUNCH_END = LocalTime.of(13, 30);
    static final LocalTime DINNER_END = LocalTime.of(19, 30);
    static final String[] PERIOD = { "08:40", "09:40", "10:40", "11:40", "13:30", "14:30", "15:30" };
    static final int NOTICE_MIN = 5;

    static final String KEY = System.getenv("MYPET_NEIS_KEY") == null ? "" : System.getenv("MYPET_NEIS_KEY");
    static final String SEP = "\u001F";

    // ---- 설정 저장 ----
    static final File FILE = new File(System.getProperty("user.home"), ".mypet/settings.properties");
    static final Properties cfg = new Properties();

    static String get(String k) { return cfg.getProperty(k, ""); }

    static void set(String k, String v) {
        cfg.setProperty(k, v);
        try {
            FILE.getParentFile().mkdirs();
            try (Writer w = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
                cfg.store(w, null);
            }
        } catch (IOException ignored) { }
    }

    static int num(String k, int def) {
        try { return Integer.parseInt(get(k)); } catch (Exception e) { return def; }
    }

    static boolean on(String k) { return !get(k).equals("off"); }

    static List<String> list(String k) {
        List<String> l = new ArrayList<>();
        for (String s : get(k).split(SEP)) if (!s.trim().isEmpty()) l.add(s);
        return l;
    }

    static void setList(String k, List<String> l) { set(k, String.join(SEP, l)); }

    // ---- 상태 ----
    static JWindow petWin, bubbleWin;
    static Pet pet;
    static Bubble bubble;
    static JPopupMenu menu;
    static JMenu charMenu, todoMenu, ddayMenu;
    static Timer hideTimer, pomoTimer, classTimer;
    static TrayIcon tray;
    static String mode = "IDLE";                   // IDLE / FOCUS / BREAK
    static int left;
    static Image charImg;
    static String charPath = "";
    static int imgW, imgH, grabX, grabY, noticed = -1;
    static Map<Integer, String> periodCache;
    static LocalDate cacheDay;

    public static void main(String[] args) {
        load();
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> {
            makePet();
            makeBubble();
            makeMenu();
            makeTimers();
            makeTray();
            loadChar();
            placePet();
            petWin.setVisible(true);
            if (get("name").isEmpty()) {
                String n = ask("이름을 알려주세요", "");
                if (n != null && !n.isEmpty()) set("name", n);
            }
            say(hello(), 6);
        });
    }

    static void load() {
        if (!FILE.exists()) return;
        try (Reader r = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
            cfg.load(r);
        } catch (IOException ignored) { }
    }

    static String hello() {
        String n = get("name");
        String s = (n.isEmpty() ? "안녕하세요!" : "안녕하세요 " + n + "님!") + "\n오늘도 같이 공부해요\n" + ddayLine();
        return todoLeft() > 0 ? s + "\n할 일 " + todoLeft() + "개 남았어요" : s;
    }

    // ---- 펫 창 (투명 + 항상 위 + 드래그) ----
    static void makePet() {
        petWin = new JWindow();
        petWin.setAlwaysOnTop(true);
        petWin.setFocusableWindowState(false);
        petWin.setBackground(new Color(0, 0, 0, 0));
        pet = new Pet();
        petWin.setContentPane(pet);

        MouseAdapter m = new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                grabX = e.getX();
                grabY = e.getY();
                if (e.isPopupTrigger()) popup(e);
            }
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) popup(e);
                cfg.setProperty("x", "" + petWin.getX());
                set("y", "" + petWin.getY());
            }
            public void mouseDragged(MouseEvent e) {
                petWin.setLocation(e.getXOnScreen() - grabX, e.getYOnScreen() - grabY);
                if (bubbleWin.isVisible()) placeBubble();
            }
        };
        pet.addMouseListener(m);
        pet.addMouseMotionListener(m);
    }

    static void placePet() {
        Dimension s = Toolkit.getDefaultToolkit().getScreenSize();
        int x = Math.min(num("x", s.width - petWin.getWidth() - 60), s.width - 40);
        int y = Math.min(num("y", s.height - petWin.getHeight() - 90), s.height - 40);
        petWin.setLocation(Math.max(0, x), Math.max(0, y));
    }

    // ---- 말풍선 ----
    static void makeBubble() {
        bubbleWin = new JWindow();
        bubbleWin.setAlwaysOnTop(true);
        bubbleWin.setFocusableWindowState(false);
        bubbleWin.setBackground(new Color(0, 0, 0, 0));
        bubble = new Bubble();
        bubbleWin.setContentPane(bubble);
        bubble.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { bubbleWin.setVisible(false); }
        });
        hideTimer = new Timer(5000, e -> bubbleWin.setVisible(false));
        hideTimer.setRepeats(false);
    }

    static void say(String text, int sec) {
        bubble.lines = text.split("\n");
        bubbleWin.pack();
        placeBubble();
        bubbleWin.setVisible(true);
        hideTimer.setInitialDelay(sec * 1000);
        hideTimer.restart();
    }

    static void placeBubble() {
        Dimension b = bubbleWin.getSize(), s = Toolkit.getDefaultToolkit().getScreenSize();
        int cx = petWin.getX() + petWin.getWidth() / 2;
        int x = Math.max(0, Math.min(cx - b.width / 2, s.width - b.width));
        int y = petWin.getY() - b.height + (mode.equals("IDLE") ? 24 : 0);
        bubble.up = y < 0;
        if (bubble.up) y = petWin.getY() + petWin.getHeight();
        bubble.tail = Math.max(14, Math.min(cx - x, b.width - 14));
        bubbleWin.setLocation(x, y);
    }

    // ---- 우클릭 메뉴 ----
    static void makeMenu() {
        menu = new JPopupMenu();
        item(menu, "뽀모도로 시작", () -> {
            beep(true);
            start("FOCUS", num("focus", 25));
            bubbleWin.setVisible(false);
        });
        item(menu, "뽀모도로 중지", () -> {
            pomoTimer.stop();
            mode = "IDLE";
            pet.repaint();
            say("수고했어요!", 3);
        });
        item(menu, "집중 기록", () -> say(statLine(), 6));
        menu.addSeparator();
        item(menu, "오늘 급식", () -> async(MyPet::meal));
        item(menu, "오늘 시간표", () -> async(MyPet::timetable));
        item(menu, "학사일정", () -> async(MyPet::schedule));
        menu.addSeparator();
        menu.add(ddayMenu = new JMenu("디데이"));
        menu.add(todoMenu = new JMenu("할 일"));
        menu.addSeparator();

        JMenu s = new JMenu("설정");
        item(s, "이름 바꾸기", MyPet::rename);
        item(s, "뽀모도로 시간", MyPet::setTime);
        toggle(s, "알림 소리", "sound");
        toggle(s, "다음 교시 알림", "classNotice");
        JCheckBoxMenuItem auto = new JCheckBoxMenuItem("윈도우 시작할 때 실행", startupBat() != null && startupBat().exists());
        auto.addActionListener(e -> autoStart(auto.isSelected()));
        s.add(auto);
        menu.add(s);
        menu.add(charMenu = new JMenu("캐릭터"));
        menu.addSeparator();
        if (SystemTray.isSupported()) item(menu, "숨기기", MyPet::hide);
        item(menu, "종료", () -> System.exit(0));
    }

    static void popup(MouseEvent e) {
        buildChar();
        buildDday();
        buildTodo();
        menu.show(pet, e.getX(), e.getY());
    }

    static void item(JComponent parent, String label, Runnable run) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> run.run());
        parent.add(i);
    }

    static void toggle(JMenu parent, String label, String key) {
        JCheckBoxMenuItem c = new JCheckBoxMenuItem(label, on(key));
        c.addActionListener(e -> set(key, c.isSelected() ? "on" : "off"));
        parent.add(c);
    }

    static JMenuItem none() {
        JMenuItem i = new JMenuItem("(없음)");
        i.setEnabled(false);
        return i;
    }

    // ---- 입력창 ----
    static String ask(String msg, String init) {
        JOptionPane p = pane(msg);
        p.setInitialSelectionValue(init);
        Object v = show(p);
        return v == null ? null : v.toString().trim();
    }

    static String choose(String msg, Object[] options) {
        JOptionPane p = pane(msg);
        p.setSelectionValues(options);
        Object v = show(p);
        return v == null ? null : v.toString();
    }

    static JOptionPane pane(String msg) {
        JOptionPane p = new JOptionPane(msg, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        p.setWantsInput(true);
        return p;
    }

    static Object show(JOptionPane p) {
        JDialog d = p.createDialog("MyPet");
        d.setAlwaysOnTop(true);
        d.setVisible(true);
        d.dispose();
        Object clicked = p.getValue(), v = p.getInputValue();
        boolean ok = clicked instanceof Integer && (Integer) clicked == JOptionPane.OK_OPTION;
        return ok && v != null && v != JOptionPane.UNINITIALIZED_VALUE ? v : null;
    }

    static void rename() {
        String n = ask("새 이름", get("name"));
        if (n != null && !n.isEmpty()) {
            set("name", n);
            say("반가워요 " + n + "님!", 3);
        }
    }

    // ---- 뽀모도로 + 집중 기록 ----
    static void makeTimers() {
        pomoTimer = new Timer(1000, e -> {
            if (--left <= 0) {
                if (mode.equals("FOCUS")) {
                    record(num("focus", 25));
                    beep(false);
                    start("BREAK", num("rest", 5));
                } else {
                    beep(true);
                    start("FOCUS", num("focus", 25));
                }
            }
            pet.repaint();
        });
        classTimer = new Timer(30000, e -> checkClass());
        classTimer.start();
    }

    static void start(String m, int min) {
        mode = m;
        left = min * 60;
        pomoTimer.restart();
        pet.repaint();
    }

    static void setTime() {
        String f = ask("집중 시간(분)", "" + num("focus", 25));
        if (f == null || !f.matches("[1-9]\\d{0,2}")) return;
        String r = ask("쉬는 시간(분)", "" + num("rest", 5));
        if (r == null || !r.matches("[1-9]\\d{0,2}")) return;
        cfg.setProperty("focus", f);
        set("rest", r);
        say("집중 " + f + "분 / 휴식 " + r + "분", 4);
    }

    static void record(int min) {
        int[] t = statOf(LocalDate.now());
        set("stat." + LocalDate.now(), (t[0] + 1) + "," + (t[1] + min));
    }

    static int[] statOf(LocalDate d) {
        String[] v = get("stat." + d).split(",");
        try { return v.length == 2 ? new int[] { Integer.parseInt(v[0]), Integer.parseInt(v[1]) } : new int[2]; }
        catch (Exception e) { return new int[2]; }
    }

    static String statLine() {
        int[] t = statOf(LocalDate.now());
        LocalDate d = t[0] > 0 ? LocalDate.now() : LocalDate.now().minusDays(1);
        int streak = 0;
        while (statOf(d)[0] > 0) {
            streak++;
            d = d.minusDays(1);
        }
        if (t[0] == 0 && streak == 0) return "오늘은 아직 기록이 없어요\n뽀모도로 시작해 볼까요?";
        return "오늘 집중 " + t[0] + "번 / " + t[1] + "분" + (streak > 1 ? "\n연속 " + streak + "일!" : "");
    }

    // ---- 알림 소리 (사인파를 직접 만들어서 재생) ----
    static void beep(boolean up) {
        if (!on("sound")) return;
        new Thread(() -> {
            try {
                tone(up ? 784 : 1047, 110);
                tone(up ? 1047 : 659, 180);
            } catch (Exception ignored) { }
        }).start();
    }

    static void tone(int hz, int ms) throws Exception {
        AudioFormat f = new AudioFormat(44100f, 8, 1, true, false);
        byte[] d = new byte[44100 * ms / 1000];
        for (int i = 0; i < d.length; i++) {
            double fade = Math.min(1, Math.min(i, d.length - i) / 880.0);
            d[i] = (byte) (Math.sin(2 * Math.PI * hz * i / 44100) * 70 * fade);
        }
        try (SourceDataLine line = AudioSystem.getSourceDataLine(f)) {
            line.open(f);
            line.start();
            line.write(d, 0, d.length);
            line.drain();
        }
    }

    // ---- 디데이 ----
    static List<String[]> ddays() {
        List<String[]> l = new ArrayList<>();
        for (String s : list("ddays")) {
            int i = s.lastIndexOf('|');
            LocalDate d = i < 0 ? null : date(s.substring(i + 1));
            if (d != null) l.add(new String[] { s.substring(0, i), d.toString() });
        }
        l.sort(Comparator.comparingLong(a -> {
            long n = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(a[1]));
            return n < 0 ? 100000 - n : n;             // 지난 디데이는 뒤로
        }));
        return l;
    }

    static String label(String[] d) {
        long n = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(d[1]));
        return d[0] + " " + (n > 0 ? "D-" + n : n == 0 ? "D-Day!" : "D+" + -n);
    }

    static String ddayLine() {
        List<String[]> l = ddays();
        return l.isEmpty() ? "디데이가 없어요" : label(l.get(0));
    }

    static LocalDate date(String s) {
        if (s == null) return null;
        s = s.trim().replace(".", "-").replace("/", "-");
        if (s.matches("\\d{8}")) s = s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6);
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    static void buildDday() {
        ddayMenu.removeAll();
        List<String[]> l = ddays();
        for (String[] d : l) item(ddayMenu, label(d), () -> say(label(d), 4));
        if (l.isEmpty()) ddayMenu.add(none());
        ddayMenu.addSeparator();
        item(ddayMenu, "추가...", () -> {
            String t = ask("디데이 이름", "");
            if (t == null || t.isEmpty()) return;
            LocalDate d = date(ask("날짜 (예: 2026-12-18)", ""));
            if (d == null) return;
            List<String> x = list("ddays");
            x.add(t.replace("|", " ") + "|" + d);
            setList("ddays", x);
            say(label(new String[] { t, d.toString() }), 4);
        });
        item(ddayMenu, "지우기...", () -> {
            List<String[]> x = ddays();
            if (x.isEmpty()) return;
            String[] labels = new String[x.size()];
            for (int i = 0; i < x.size(); i++) labels[i] = label(x.get(i));
            String picked = choose("어떤 디데이를 지울까요?", labels);
            if (picked == null) return;
            String[] gone = x.get(Arrays.asList(labels).indexOf(picked));
            List<String> keep = new ArrayList<>();
            for (String s : list("ddays")) if (!s.equals(gone[0] + "|" + gone[1])) keep.add(s);
            setList("ddays", keep);
            say("지웠어요", 3);
        });
    }

    // ---- 할 일 ----
    static int todoLeft() {
        int n = 0;
        for (String t : list("todos")) if (t.startsWith("0:")) n++;
        return n;
    }

    static void buildTodo() {
        todoMenu.removeAll();
        todoMenu.setText(todoLeft() > 0 ? "할 일 (" + todoLeft() + ")" : "할 일");
        List<String> l = list("todos");
        for (int i = 0; i < l.size(); i++) {
            String t = l.get(i);
            int index = i;
            JCheckBoxMenuItem c = new JCheckBoxMenuItem(t.substring(2), t.startsWith("1:"));
            c.addActionListener(e -> {
                List<String> x = list("todos");
                String s = x.get(index);
                x.set(index, (s.startsWith("1:") ? "0:" : "1:") + s.substring(2));
                setList("todos", x);
                pet.repaint();
            });
            todoMenu.add(c);
        }
        if (l.isEmpty()) todoMenu.add(none());
        todoMenu.addSeparator();
        item(todoMenu, "추가...", () -> {
            String t = ask("할 일", "");
            if (t == null || t.isEmpty()) return;
            List<String> x = list("todos");
            x.add("0:" + t.replace(SEP, " "));
            setList("todos", x);
            pet.repaint();
        });
        item(todoMenu, "완료한 것 지우기", () -> {
            List<String> x = list("todos");
            x.removeIf(s -> s.startsWith("1:"));
            setList("todos", x);
            pet.repaint();
        });
    }

    // ---- 캐릭터 (다운로드 폴더의 gif/png) ----
    static File downloads() {
        File home = new File(System.getProperty("user.home"));
        File kr = new File(home, "다운로드");
        return kr.isDirectory() ? kr : new File(home, "Downloads");
    }

    static void buildChar() {
        charMenu.removeAll();
        JRadioButtonMenuItem basic = new JRadioButtonMenuItem("기본", charPath.isEmpty());
        basic.addActionListener(e -> pickChar(""));
        charMenu.add(basic);

        File[] files = downloads().listFiles((d, n) ->
                n.toLowerCase().endsWith(".gif") || n.toLowerCase().endsWith(".png"));
        if (files == null) files = new File[0];
        Arrays.sort(files);
        for (File f : files) {
            String p = f.getAbsolutePath();
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(f.getName(), p.equals(charPath));
            r.addActionListener(e -> pickChar(p));
            charMenu.add(r);
        }
        charMenu.addSeparator();
        item(charMenu, "다운로드 폴더에서 고르기...", () -> {
            JFileChooser c = new JFileChooser(downloads());
            c.setFileFilter(new FileNameExtensionFilter("GIF / PNG", "gif", "png"));
            if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                pickChar(c.getSelectedFile().getAbsolutePath());
            }
        });
    }

    static void pickChar(String path) {
        set("char", path);
        loadChar();
        say("짜잔!", 2);
    }

    static void loadChar() {
        String p = get("char");
        charImg = null;
        charPath = "";
        if (!p.isEmpty() && new File(p).exists()) {
            ImageIcon icon = new ImageIcon(p);
            if (icon.getIconWidth() > 0) {
                double s = Math.min(1, Math.min(220.0 / icon.getIconWidth(), 170.0 / icon.getIconHeight()));
                imgW = (int) (icon.getIconWidth() * s);
                imgH = (int) (icon.getIconHeight() * s);
                charImg = icon.getImage();
                charPath = p;
            }
        }
        int bottom = petWin.getY() + petWin.getHeight();
        pet.setPreferredSize(new Dimension(
                charImg == null ? 120 : Math.max(imgW, 100),
                charImg == null ? 150 : imgH + 36));
        petWin.pack();
        petWin.setLocation(petWin.getX(), bottom - petWin.getHeight());
    }

    // ---- 트레이 / 자동 실행 ----
    static void makeTray() {
        if (!SystemTray.isSupported()) return;
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(255, 225, 140));
            g.fillOval(1, 1, 14, 14);
            g.setColor(Color.DARK_GRAY);
            g.drawOval(1, 1, 14, 14);
            g.dispose();

            PopupMenu p = new PopupMenu();
            MenuItem show = new MenuItem("보이기");
            show.addActionListener(e -> SwingUtilities.invokeLater(MyPet::unhide));
            MenuItem quit = new MenuItem("종료");
            quit.addActionListener(e -> System.exit(0));
            p.add(show);
            p.add(quit);

            tray = new TrayIcon(img, "MyPet", p);
            tray.setImageAutoSize(true);
            tray.addActionListener(e -> SwingUtilities.invokeLater(MyPet::unhide));
            SystemTray.getSystemTray().add(tray);
        } catch (Exception ignored) { }
    }

    static void hide() {
        bubbleWin.setVisible(false);
        petWin.setVisible(false);
    }

    static void unhide() {
        petWin.setVisible(true);
        say("다시 왔어요!", 3);
    }

    static File startupBat() {
        String appData = System.getenv("APPDATA");
        return appData == null ? null
                : new File(appData + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\MyPet.bat");
    }

    static void autoStart(boolean turnOn) {
        File bat = startupBat();
        if (bat == null) return;
        if (!turnOn) {
            bat.delete();
            return;
        }
        File jar = jarFile();
        if (jar == null) {
            say("jar로 실행했을 때만 등록돼요", 4);
            return;
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(bat), StandardCharsets.UTF_8)) {
            w.write("@echo off\r\nstart \"\" javaw -jar \"" + jar.getAbsolutePath() + "\"\r\n");
            say("컴퓨터를 켜면 같이 나올게요!", 4);
        } catch (Exception ignored) { }
    }

    static File jarFile() {
        try {
            File f = new File(MyPet.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f : null;
        } catch (Exception e) { return null; }
    }

    // ---- NEIS (급식 / 시간표 / 학사일정) ----
    static void async(Supplier<String> job) {
        say("불러오는 중...", 2);
        new Thread(() -> {
            String s = job.get();
            SwingUtilities.invokeLater(() -> say(s, 8));
        }).start();
    }

    static String http(String url) throws Exception {
        return HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
                .body();
    }

    static String api(String path) {
        return "https://open.neis.go.kr/hub/" + path + "?Type=json&pSize=100"
                + (KEY.isEmpty() ? "" : "&KEY=" + KEY)
                + "&ATPT_OFCDC_SC_CODE=" + OFFICE + "&SD_SCHUL_CODE=" + SCHOOL;
    }

    static String field(String s, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"(.*?)\"").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    static String[] rows(String body) {
        return body.split("(?=\\{\"ATPT_OFCDC_SC_CODE\")");
    }

    static String ymd(LocalDate d) { return d.format(DateTimeFormatter.BASIC_ISO_DATE); }

    /** 점심 -> (13:30 이후) 저녁 -> (19:30 이후) 내일 아침 */
    static String meal() {
        LocalDate today = LocalDate.now(), tomorrow = today.plusDays(1);
        LocalTime now = LocalTime.now();

        if (now.isAfter(DINNER_END)) {
            String breakfast = mealOf(tomorrow, "1");
            if (breakfast == null) return "급식을 불러오지 못했어요";
            if (!breakfast.isEmpty()) return "내일 아침\n" + breakfast;
            String lunch = mealOf(tomorrow, "2");
            return lunch != null && !lunch.isEmpty() ? "내일 점심\n" + lunch : "내일 급식 정보가 없어요";
        }

        boolean dinner = now.isAfter(LUNCH_END);
        String m = mealOf(today, dinner ? "3" : "2");
        if (m == null) return "급식을 불러오지 못했어요";
        if (!m.isEmpty()) return (dinner ? "오늘 저녁\n" : "오늘 점심\n") + m;
        if (dinner) {
            String lunch = mealOf(today, "2");
            if (lunch != null && !lunch.isEmpty()) return "오늘 점심\n" + lunch;
        }
        return "오늘은 급식 정보가 없어요";
    }

    /** 1 아침 / 2 점심 / 3 저녁. 없으면 "", 실패하면 null */
    static String mealOf(LocalDate d, String code) {
        try {
            String raw = field(http(api("mealServiceDietInfo") + "&MLSV_YMD=" + ymd(d) + "&MMEAL_SC_CODE=" + code),
                    "DDISH_NM");
            return raw.replace("<br/>", "\n").replaceAll("\\([0-9.]+\\)", "");
        } catch (Exception e) {
            return null;
        }
    }

    /** 오늘 시간표 (하루에 한 번만 받아옴) */
    static Map<Integer, String> periods() {
        if (periodCache != null && LocalDate.now().equals(cacheDay)) return periodCache;
        try {
            TreeMap<Integer, String> m = new TreeMap<>();
            String body = http(api(TYPE + "Timetable") + "&ALL_TI_YMD=" + ymd(LocalDate.now())
                    + "&GRADE=" + GRADE + "&CLASS_NM=" + CLS);
            for (String row : rows(body)) {
                String p = field(row, "PERIO"), subject = field(row, "ITRT_CNTNT");
                if (p.matches("\\d+") && !subject.isEmpty()) m.putIfAbsent(Integer.parseInt(p), subject);
            }
            cacheDay = LocalDate.now();
            return periodCache = m;
        } catch (Exception e) {
            return null;
        }
    }

    static String timetable() {
        Map<Integer, String> m = periods();
        if (m == null) return "시간표를 불러오지 못했어요";
        if (m.isEmpty()) return "오늘은 시간표가 없어요";
        StringBuilder sb = new StringBuilder("오늘 시간표 (" + GRADE + "-" + CLS + ")");
        m.forEach((k, v) -> sb.append("\n").append(k).append("교시  ").append(v));
        return sb.toString();
    }

    static String schedule() {
        try {
            LocalDate from = LocalDate.now();
            String body = http(api("SchoolSchedule")
                    + "&AA_FROM_YMD=" + ymd(from) + "&AA_TO_YMD=" + ymd(from.plusDays(60)));
            TreeSet<String> events = new TreeSet<>();
            for (String row : rows(body)) {
                String d = field(row, "AA_YMD"), name = field(row, "EVENT_NM").trim();
                if (d.matches("\\d{8}") && !name.isEmpty() && !name.equals("-")) events.add(d + " " + name);
            }
            if (events.isEmpty()) return "다가오는 학사일정이 없어요";

            StringBuilder sb = new StringBuilder("다가오는 학사일정");
            int i = 0;
            for (String e : events) {
                LocalDate d = LocalDate.parse(e.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
                long n = ChronoUnit.DAYS.between(from, d);
                sb.append("\n").append(d.getMonthValue()).append("/").append(d.getDayOfMonth())
                  .append(" ").append(e.substring(9)).append(n == 0 ? " (오늘)" : " (D-" + n + ")");
                if (++i == 6) break;
            }
            return sb.toString();
        } catch (Exception e) {
            return "학사일정을 불러오지 못했어요";
        }
    }

    /** 교시 시작 NOTICE_MIN분 전에 다음 과목 알려주기 */
    static void checkClass() {
        if (!on("classNotice")) return;
        DayOfWeek w = LocalDate.now().getDayOfWeek();
        if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) return;

        LocalTime now = LocalTime.now();
        for (int i = 0; i < PERIOD.length; i++) {
            LocalTime s = LocalTime.parse(PERIOD[i]);
            int p = i + 1;
            if (!now.isBefore(s.minusMinutes(NOTICE_MIN)) && now.isBefore(s) && noticed != p) {
                noticed = p;
                new Thread(() -> {
                    Map<Integer, String> m = periods();
                    if (m == null || m.get(p) == null) return;
                    SwingUtilities.invokeLater(() -> say("다음은 " + m.get(p) + "이에요\n(" + p + "교시)", 6));
                }).start();
                return;
            }
        }
    }

    // ---- 그리기 ----
    static class Pet extends JPanel {
        Pet() {
            setOpaque(false);
            setPreferredSize(new Dimension(120, 150));
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.Clear);                 // 투명 창 잔상 지우기
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (charImg != null) g2.drawImage(charImg, (getWidth() - imgW) / 2, getHeight() - imgH, imgW, imgH, this);
            else blob(g2);

            if (!mode.equals("IDLE")) {
                String t = String.format("%02d:%02d", left / 60, left % 60);
                g2.setFont(new Font("맑은 고딕", Font.BOLD, 15));
                int w = g2.getFontMetrics().stringWidth(t) + 16;
                int x = (getWidth() - w) / 2;
                g2.setColor(mode.equals("FOCUS") ? new Color(215, 70, 85) : new Color(70, 140, 225));
                g2.fillRoundRect(x, 2, w, 24, 12, 12);
                g2.setColor(Color.WHITE);
                g2.drawString(t, x + 8, 20);
            }

            int todo = todoLeft();
            if (todo > 0) {
                int s = 20, x = getWidth() - s - 2, y = getHeight() - s - 2;
                g2.setColor(new Color(255, 205, 100));
                g2.fillOval(x, y, s, s);
                g2.setColor(new Color(70, 55, 40));
                g2.setFont(new Font("맑은 고딕", Font.BOLD, 12));
                g2.drawString(todo > 9 ? "9+" : "" + todo, x + (todo > 9 ? 3 : 7), y + 15);
            }
            g2.dispose();
        }

        void blob(Graphics2D g2) {
            int y = getHeight() - 100;
            g2.setColor(mode.equals("FOCUS") ? new Color(255, 190, 190)
                    : mode.equals("BREAK") ? new Color(190, 230, 255) : new Color(255, 230, 150));
            g2.fillOval(15, y, 90, 90);
            g2.setColor(Color.DARK_GRAY);
            g2.setStroke(new BasicStroke(2));
            g2.drawOval(15, y, 90, 90);
            if (mode.equals("FOCUS")) {
                g2.drawLine(38, y + 38, 52, y + 38);
                g2.drawLine(68, y + 38, 82, y + 38);
            } else {
                g2.fillOval(40, y + 32, 10, 14);
                g2.fillOval(70, y + 32, 10, 14);
            }
            g2.drawArc(50, y + 48, 20, 14, 180, 180);
        }
    }

    static class Bubble extends JPanel {
        static final Color PAPER = new Color(255, 252, 225), INK = new Color(80, 65, 50);
        static final Font FONT = new Font("맑은 고딕", Font.PLAIN, 14);
        static final int TAIL = 10;

        String[] lines = { "" };
        int tail = 40;
        boolean up = false;

        Bubble() { setOpaque(false); }

        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(FONT);
            int w = 0;
            for (String l : lines) w = Math.max(w, fm.stringWidth(l));
            return new Dimension(w + 30, lines.length * fm.getHeight() + 24 + TAIL);
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.Clear);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int h = getHeight() - TAIL, top = up ? TAIL : 0;
            int tipY = up ? 0 : getHeight(), baseY = up ? TAIL : h;
            Polygon t = new Polygon(new int[] { tail - 8, tail + 8, tail }, new int[] { baseY, baseY, tipY }, 3);

            g2.setColor(PAPER);
            g2.fillRoundRect(0, top, getWidth(), h, 16, 16);
            g2.fill(t);
            g2.setColor(INK);
            g2.drawRoundRect(0, top, getWidth() - 1, h - 1, 16, 16);
            g2.drawLine(t.xpoints[0], t.ypoints[0], t.xpoints[2], t.ypoints[2]);
            g2.drawLine(t.xpoints[1], t.ypoints[1], t.xpoints[2], t.ypoints[2]);

            g2.setFont(FONT);
            g2.setColor(new Color(60, 50, 40));
            FontMetrics fm = g2.getFontMetrics();
            int y = top + 12 + fm.getAscent();
            for (String line : lines) {
                g2.drawString(line, 15, y);
                y += fm.getHeight();
            }
            g2.dispose();
        }
    }
}
