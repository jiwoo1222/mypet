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
