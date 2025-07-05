import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.*;

/**
 * NusantaraTower.java
 *
 * Sebuah game membangun kota yang terinspirasi dari City Bloxx,
 * dibuat dalam satu file untuk kemudahan.
 *
 * Versi ini memiliki tombol kembali di menu Upgrade.
 *
 * Game ini secara spesifik mendemonstrasikan penggunaan 6 struktur data fundamental:
 * 1. Stack: Untuk tumpukan balok menara.
 * 2. Queue: Untuk antrean balok yang akan datang.
 * 3. HashMap: Untuk merepresentasikan grid kota.
 * 4. Graph: Untuk menghitung bonus kedekatan antar bangunan.
 * 5. Tree: Untuk sistem upgrade.
 * 6. LinkedList: Untuk daftar skor tertinggi.
 *
 * Kontrol:
 * - [SPACE]: Jatuhkan balok.
 * - [ENTER]: Lanjut setelah gagal/sukses membangun menara, atau mulai ulang.
 * - [U]: Buka/Tutup menu Upgrade.
 */
public class NusantaraTower extends JPanel implements Runnable {

    // =================================================================================
    // 1. STRUKTUR DATA INTI PERMAINAN
    // =================================================================================

    private Stack<Block> towerStack;
    private Queue<BlockType> upcomingBlocksQueue;
    private Map<Point, FinishedBuilding> cityGrid;
    private Map<Point, List<Point>> cityAdjacencyGraph;
    private UpgradeNode upgradeTreeRoot;
    private LinkedList<GameScore> highScores;

    // =================================================================================
    // Variabel State & Konfigurasi Game
    // =================================================================================
    private Thread gameThread;
    private GameState gameState;
    private boolean showingUpgrades = false;

    // Variabel Crane & Balok
    private Block hangingBlock;
    private int craneX = 200;
    private int craneDirection = 1; // 1 untuk kanan, -1 untuk kiri
    private boolean blockIsFalling = false;
    private int craneSpeedMultiplier = 1;
    private int baseBlockWidth = 100;

    // Variabel Skor, Kota & NYAWA
    private long currentScore = 0;
    private int playerLives;
    private Point nextCityPlot = new Point(0, 0);
    private final int CITY_GRID_WIDTH = 5;
    private final int CITY_GRID_HEIGHT = 4;
    private final int STARTING_LIVES = 3;

    // =================================================================================
    // Kelas-kelas Internal (Data Structures)
    // =================================================================================

    enum BlockType {
        PERUMAHAN(new Color(220, 100, 100)),
        BISNIS(new Color(100, 100, 220)),
        TAMAN(new Color(100, 220, 100));
        public final Color color;
        BlockType(Color color) { this.color = color; }
    }

    enum GameState {
        PLAYING, TOWER_COMPLETE, TOWER_FAILED, GAME_OVER
    }

    static class Block {
        int x, y, width, height;
        BlockType type;
        public Block(int x, int y, int width, int height, BlockType type) {
            this.x = x; this.y = y; this.width = width; this.height = height; this.type = type;
        }
    }

    static class FinishedBuilding {
        Point position;
        int height;
        BlockType type;
        public FinishedBuilding(Point p, int height, BlockType type) {
            this.position = p; this.height = height; this.type = type;
        }
    }

    static class UpgradeNode {
        String name;
        String description;
        int cost;
        boolean purchased = false;
        Runnable effect;
        List<UpgradeNode> children = new ArrayList<>();
        public UpgradeNode(String name, String desc, int cost, Runnable effect) {
            this.name = name; this.description = desc; this.cost = cost; this.effect = effect;
        }
        public void addChild(UpgradeNode child) { this.children.add(child); }
    }

    static class GameScore {
        long score;
        Date date;
        public GameScore(long score) { this.score = score; this.date = new Date(); }
    }

    // =================================================================================
    // Konstruktor dan Inisialisasi
    // =================================================================================
    public NusantaraTower() {
        setPreferredSize(new Dimension(800, 600));
        setBackground(new Color(135, 206, 235));
        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleInput(e);
            }
        });
        initGame();
    }

    private void initGame() {
        towerStack = new Stack<>();
        upcomingBlocksQueue = new LinkedList<>();
        cityGrid = new HashMap<>();
        cityAdjacencyGraph = new HashMap<>();
        if (highScores == null) highScores = new LinkedList<>();

        resetTower();
        for (int i = 0; i < 3; i++) {
            upcomingBlocksQueue.offer(getRandomBlockType());
        }
        buildCityGraph();
        buildUpgradeTree();

        gameState = GameState.PLAYING;
        showingUpgrades = false;
        currentScore = 0;
        playerLives = STARTING_LIVES;
        craneX = 200;
        craneDirection = 1;
        craneSpeedMultiplier = 1;
        baseBlockWidth = 100;
        nextCityPlot = new Point(0, 0);

        prepareNextHangingBlock();

        if (gameThread == null) {
            gameThread = new Thread(this);
            gameThread.start();
        }
    }

    private void resetTower() {
        towerStack.clear();
        towerStack.push(new Block(350, 550, baseBlockWidth, 15, BlockType.PERUMAHAN));
    }

    private void buildCityGraph() {
        for (int y = 0; y < CITY_GRID_HEIGHT; y++) {
            for (int x = 0; x < CITY_GRID_WIDTH; x++) {
                Point current = new Point(x, y);
                cityAdjacencyGraph.putIfAbsent(current, new ArrayList<>());
                int[] dx = {0, 0, 1, -1}, dy = {1, -1, 0, 0};
                for(int i=0; i<4; i++){
                    Point neighbor = new Point(x + dx[i], y + dy[i]);
                    if(neighbor.x >= 0 && neighbor.x < CITY_GRID_WIDTH && neighbor.y >=0 && neighbor.y < CITY_GRID_HEIGHT){
                        cityAdjacencyGraph.get(current).add(neighbor);
                    }
                }
            }
        }
    }

    private void buildUpgradeTree() {
        upgradeTreeRoot = new UpgradeNode("Root", "", 0, ()->{});
        upgradeTreeRoot.purchased = true;
        UpgradeNode fastCrane = new UpgradeNode("Crane Cepat", "Kecepatan crane +50%", 500, () -> craneSpeedMultiplier = 2);
        UpgradeNode widerBlocks = new UpgradeNode("Balok Lebih Lebar", "Lebar balok awal +20", 800, () -> baseBlockWidth = 120);
        upgradeTreeRoot.addChild(fastCrane);
        upgradeTreeRoot.addChild(widerBlocks);
        UpgradeNode superFastCrane = new UpgradeNode("Crane Super Cepat", "Kecepatan crane +100%", 2000, () -> craneSpeedMultiplier = 3);
        fastCrane.addChild(superFastCrane);
    }

    // =================================================================================
    // Game Loop Utama
    // =================================================================================
    @Override
    public void run() {
        while (true) {
            if(gameState == GameState.PLAYING && !showingUpgrades) {
                updateGame();
            }
            repaint();
            try {
                Thread.sleep(16); // ~60 FPS
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void updateGame() {
        if (!blockIsFalling) {
            craneX += 3 * craneDirection * craneSpeedMultiplier;
            if (craneX > getWidth() - 150 || craneX < 150) {
                craneDirection *= -1;
            }
            hangingBlock.x = craneX - (hangingBlock.width / 2);
        }

        if (blockIsFalling) {
            hangingBlock.y += 5;
            checkCollision();
        }
    }

    // =================================================================================
    // Logika Inti Permainan
    // =================================================================================

    private void handleInput(KeyEvent e) {
        int key = e.getKeyCode();

        if (gameState == GameState.PLAYING) {
            if (key == KeyEvent.VK_U) { // Tombol U sekarang menjadi toggle
                showingUpgrades = !showingUpgrades;
            }

            if (!showingUpgrades) { // Hanya proses input game jika menu upgrade tidak tampil
                if (key == KeyEvent.VK_SPACE && !blockIsFalling) {
                    blockIsFalling = true;
                }
            } else { // Proses input untuk menu upgrade
                if (key >= KeyEvent.VK_1 && key <= KeyEvent.VK_9) {
                    purchaseUpgrade(key - KeyEvent.VK_1);
                }
            }
        } else if (key == KeyEvent.VK_ENTER) {
            switch(gameState) {
                case GAME_OVER:
                    initGame();
                    break;
                case TOWER_COMPLETE:
                    placeTowerInCity();
                    break;
                case TOWER_FAILED:
                    playerLives--;
                    if (playerLives <= 0) {
                        addFinalScore();
                        gameState = GameState.GAME_OVER;
                    } else {
                        resetTower();
                        prepareNextHangingBlock();
                        gameState = GameState.PLAYING;
                    }
                    break;
            }
        }
    }

    private void checkCollision() {
        Block topOfStack = towerStack.peek();
        if (hangingBlock.y + hangingBlock.height >= topOfStack.y) {
            int overlapX1 = Math.max(hangingBlock.x, topOfStack.x);
            int overlapX2 = Math.min(hangingBlock.x + hangingBlock.width, topOfStack.x + topOfStack.width);
            int overlapWidth = overlapX2 - overlapX1;

            if (overlapWidth > 20) {
                hangingBlock.y = topOfStack.y - hangingBlock.height;
                towerStack.push(hangingBlock);

                int centerDiff = Math.abs((hangingBlock.x + hangingBlock.width / 2) - (topOfStack.x + topOfStack.width / 2));
                int precisionBonus = Math.max(0, 100 - centerDiff * 2);
                currentScore += 10 + precisionBonus;

                if(towerStack.size() >= 10) {
                    gameState = GameState.TOWER_COMPLETE;
                } else {
                    prepareNextHangingBlock();
                }

            } else {
                gameState = GameState.TOWER_FAILED;
            }
        }
    }

    private void placeTowerInCity() {
        if(nextCityPlot.y >= CITY_GRID_HEIGHT) {
            addFinalScore();
            gameState = GameState.GAME_OVER;
            return;
        }

        FinishedBuilding newBuilding = new FinishedBuilding(new Point(nextCityPlot), towerStack.size(), towerStack.peek().type);
        cityGrid.put(new Point(nextCityPlot), newBuilding);

        long bonus = calculateSynergyBonus(nextCityPlot);
        currentScore += bonus;

        nextCityPlot.x++;
        if(nextCityPlot.x >= CITY_GRID_WIDTH){
            nextCityPlot.x=0;
            nextCityPlot.y++;
        }

        resetTower();
        prepareNextHangingBlock();
        gameState = GameState.PLAYING;
    }

    private long calculateSynergyBonus(Point position) {
        long bonus = 0;
        FinishedBuilding sourceBuilding = cityGrid.get(position);
        if(sourceBuilding == null) return 0;

        List<Point> neighbors = cityAdjacencyGraph.get(position);

        for(Point neighborPos : neighbors) {
            FinishedBuilding neighborBuilding = cityGrid.get(neighborPos);
            if(neighborBuilding != null) {
                if(sourceBuilding.type == BlockType.PERUMAHAN && neighborBuilding.type == BlockType.TAMAN) bonus += 250;
                if(sourceBuilding.type == BlockType.BISNIS && neighborBuilding.type == BlockType.PERUMAHAN) bonus += 150;
            }
        }
        return bonus;
    }

    private void purchaseUpgrade(int index) {
        List<UpgradeNode> availableUpgrades = new ArrayList<>();
        collectAvailableUpgrades(upgradeTreeRoot, availableUpgrades);

        if(index < availableUpgrades.size()) {
            UpgradeNode toBuy = availableUpgrades.get(index);
            if(currentScore >= toBuy.cost && !toBuy.purchased) {
                currentScore -= toBuy.cost;
                toBuy.purchased = true;
                toBuy.effect.run();
            }
        }
    }

    private void prepareNextHangingBlock() {
        blockIsFalling = false;
        BlockType nextType = upcomingBlocksQueue.poll();
        upcomingBlocksQueue.offer(getRandomBlockType());
        int lastWidth = towerStack.peek().width;
        hangingBlock = new Block(craneX - (lastWidth / 2), 100, lastWidth, 15, nextType);
    }

    private void addFinalScore() {
        highScores.addFirst(new GameScore(currentScore));
        highScores.sort((s1, s2) -> Long.compare(s2.score, s1.score));
        while(highScores.size() > 5) {
            highScores.removeLast();
        }
    }

    private BlockType getRandomBlockType() {
        return BlockType.values()[new Random().nextInt(BlockType.values().length)];
    }

    // =================================================================================
    // Metode Rendering (Menggambar ke Layar)
    // =================================================================================
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawCity(g2d);
        drawTower(g2d);
        if (gameState != GameState.GAME_OVER) {
            drawCraneAndHangingBlock(g2d);
        }
        drawUI(g2d);

        if(showingUpgrades) {
            drawUpgradeMenu(g2d);
        } else if (gameState != GameState.PLAYING) {
            drawEndScreen(g2d);
        }
    }

    private void drawTower(Graphics2D g) {
        for (Block b : towerStack) {
            g.setColor(b.type.color.darker());
            g.fillRect(b.x, b.y, b.width, b.height);
            g.setColor(Color.DARK_GRAY);
            g.drawRect(b.x, b.y, b.width, b.height);
        }
    }

    private void drawCity(Graphics2D g) {
        g.setColor(new Color(100, 150, 100));
        g.fillRect(20, 20, 260, 210);

        for(int y = 0; y < CITY_GRID_HEIGHT; y++) {
            for(int x = 0; x < CITY_GRID_WIDTH; x++) {
                Point p = new Point(x, y);
                FinishedBuilding building = cityGrid.get(p);
                if(building != null) {
                    g.setColor(building.type.color);
                    g.fillRect(25 + x * 50, 25 + y * 50, 40, 40);
                    g.setColor(Color.WHITE);
                    g.drawString("" + building.height, 35 + x * 50, 45 + y * 50);
                } else {
                    g.setColor(new Color(80, 130, 80));
                    g.drawRect(25 + x * 50, 25 + y * 50, 40, 40);
                }
            }
        }
    }

    private void drawCraneAndHangingBlock(Graphics2D g) {
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 75, getWidth(), 10);
        g.setColor(Color.GRAY);
        g.fillRect(craneX - 25, 70, 50, 25);

        g.setColor(Color.BLACK);
        g.drawLine(craneX, 85, hangingBlock.x + hangingBlock.width / 2, hangingBlock.y);

        g.setColor(hangingBlock.type.color);
        g.fillRect(hangingBlock.x, hangingBlock.y, hangingBlock.width, hangingBlock.height);
        g.setColor(Color.DARK_GRAY);
        g.drawRect(hangingBlock.x, hangingBlock.y, hangingBlock.width, hangingBlock.height);
    }

    private void drawUI(Graphics2D g) {
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 24));
        g.drawString("Skor: " + currentScore, 600, 50);

        g.drawString("Nyawa: ", 600, 80);
        g.setColor(Color.RED);
        for(int i = 0; i < playerLives; i++) {
            g.fillOval(690 + (i * 30), 62, 20, 20);
        }

        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 18));
        g.drawString("Berikutnya:", 600, 130);
        int i = 0;
        for(BlockType type : upcomingBlocksQueue) {
            g.setColor(type.color);
            g.fillRect(600, 140 + i * 25, 50, 20);
            i++;
        }

        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.PLAIN, 14));
        g.drawString("Tekan [SPACE] untuk jatuh", 320, 30);
        g.drawString("Tekan [U] untuk Upgrade", 320, 50);
    }

    private void drawEndScreen(Graphics2D g) {
        g.setColor(new Color(0,0,0,150));
        g.fillRect(0,0,800,600);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 48));

        String title;
        String subtitle;

        switch(gameState) {
            case GAME_OVER:
                title = "GAME OVER";
                subtitle = "Tekan [ENTER] untuk Mulai Ulang";
                break;
            case TOWER_COMPLETE:
                title = "MENARA SELESAI!";
                subtitle = "Tekan [ENTER] untuk Lanjut";
                break;
            case TOWER_FAILED:
                title = "MENARA GAGAL!";
                subtitle = "Tekan [ENTER] untuk Coba Lagi";
                g.setColor(Color.ORANGE);
                break;
            default:
                title = "";
                subtitle = "";
        }

        int titleWidth = g.getFontMetrics().stringWidth(title);
        g.drawString(title, (getWidth() - titleWidth) / 2, 150);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 24));
        int subtitleWidth = g.getFontMetrics().stringWidth(subtitle);
        g.drawString(subtitle, (getWidth() - subtitleWidth) / 2, 200);

        if(gameState == GameState.GAME_OVER) {
            g.drawString("Skor Tertinggi:", (getWidth() - g.getFontMetrics().stringWidth("Skor Tertinggi:"))/2, 280);
            int yPos = 310;
            for(GameScore gs : highScores) {
                String scoreString = String.format("%-10d", gs.score);
                int scoreWidth = g.getFontMetrics().stringWidth(scoreString);
                g.drawString(scoreString, (getWidth() - scoreWidth) / 2, yPos);
                yPos += 30;
            }
        }
    }

    private void drawUpgradeMenu(Graphics2D g) {
        g.setColor(new Color(0,0,0,200));
        g.fillRect(150, 100, 500, 400);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 24));
        g.drawString("Menu Upgrade", 320, 140);
        g.setFont(new Font("Arial", Font.PLAIN, 16));
        g.drawString("Skor Anda: " + currentScore, 330, 170);

        List<UpgradeNode> availableUpgrades = new ArrayList<>();
        collectAvailableUpgrades(upgradeTreeRoot, availableUpgrades);

        int yPos = 220;
        int index = 1;
        for (UpgradeNode node : availableUpgrades) {
            String status = node.purchased ? "[SUDAH DIBELI]" : "[" + node.cost + " Skor]";
            g.setColor(node.purchased || currentScore < node.cost ? Color.GRAY : Color.GREEN);
            if (node.purchased) {
                g.drawString(String.format("%s", node.name), 180, yPos);
            } else {
                g.drawString(String.format("[%d] %s %s", index, node.name, status), 180, yPos);
            }
            yPos += 30;
            if(!node.purchased) index++;
        }

        // PERUBAHAN DI SINI: Menambahkan tombol kembali
        g.setFont(new Font("Arial", Font.BOLD, 18));
        g.setColor(Color.YELLOW);
        String backText = "Tekan [U] untuk Kembali";
        int textWidth = g.getFontMetrics().stringWidth(backText);
        g.drawString(backText, (getWidth() - textWidth)/2, 480);
    }

    private void collectAvailableUpgrades(UpgradeNode node, List<UpgradeNode> list) {
        if (!node.purchased) {
            list.add(node);
        } else {
            for (UpgradeNode child : node.children) {
                if(!child.purchased) {
                    list.add(child);
                }
            }
        }
    }

    // =================================================================================
    // Metode Main untuk Menjalankan Game
    // =================================================================================
    public static void main(String[] args) {
        JFrame frame = new JFrame("Nusantara Tower");
        NusantaraTower game = new NusantaraTower();
        frame.add(game);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        game.requestFocusInWindow();
    }
}
