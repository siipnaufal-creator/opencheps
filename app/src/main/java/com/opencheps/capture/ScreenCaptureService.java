
package com.opencheps.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.opencheps.R;
import com.opencheps.engine.StockfishEngine;
import com.opencheps.overlay.ArrowOverlayView;
import com.opencheps.overlay.FloatingPanel;
import com.opencheps.overlay.OverlayService;
import com.opencheps.util.Prefs;
import com.opencheps.util.Side;
import com.opencheps.vision.BoardDetector;
import com.opencheps.vision.FenBuilder;
import com.opencheps.vision.MoveAnalyzer;
import com.opencheps.vision.PieceClassifier;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ScreenCaptureService extends Service {

    private static final String TAG = "OC/Capture";
    private static final String CHANNEL_ID = "opencheps_capture";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_USER_SIDE = "user_side";
    public static final String ACTION_STOP = "com.opencheps.STOP";

    private static final int[] ARROW_COLORS = {
            0xFF00C853, 0xFFFFB300, 0xFF29B6F6
    };

    private static final float[] ARROW_WIDTHS = {
            18f, 13f, 10f
    };

    private static final long SAMPLE_PERIOD_MS = 500;
    private static final int LOST_BOARD_THRESHOLD = 8;
    private static final int MAX_UNKNOWN_REPEATS = 5;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private HandlerThread workerThread;
    private Handler worker;

    private StockfishEngine engine;
    private boolean engineReady;
    private PieceClassifier classifier;

    private int captureW;
    private int captureH;
    private int density;

    private int screenW;
    private int screenH;

    private float capToScreenX = 1f;
    private float capToScreenY = 1f;

    private volatile boolean busy = false;
    private volatile boolean paused = false;

    private Side userSide = Side.WHITE;

    private char[][] confirmedGrid;
    private char[][] candidateGrid;

    private int candidateRepeats;

    private Side observedStm = Side.WHITE;

    private String lastEngineFen = "";
    private String lastDisplayedArrowFen = "";

    private int lostBoardCount = 0;

    private BoardDetector.Board lastKnownBoard;

    private List<ArrowOverlayView.Arrow> currentArrows =
            new ArrayList<>();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startInForeground();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);

        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);

        String sideStr = intent.getStringExtra(EXTRA_USER_SIDE);

        if (sideStr == null) {
            sideStr = Prefs.userSide(this);
        }

        userSide = "b".equals(sideStr)
                ? Side.BLACK
                : Side.WHITE;

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(
                        MEDIA_PROJECTION_SERVICE
                );

        projection = mpm.getMediaProjection(resultCode, data);

        if (projection == null) {
            Log.e(TAG, "MediaProjection acquisition failed");
            stopSelf();
            return START_NOT_STICKY;
        }

        projection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        stopSelf();
                    }
                },
                null
        );

        workerThread = new HandlerThread(
                "oc-worker",
                Thread.NORM_PRIORITY
        );

        workerThread.start();

        worker = new Handler(workerThread.getLooper());

        engine = new StockfishEngine();
        classifier = new PieceClassifier();

        worker.post(() -> {
            try {
                engine.start(getApplicationContext());
                engineReady = true;

                panelStatus(
                        "Engine ready · playing as " + userSide
                );

            } catch (Exception e) {
                Log.e(TAG, "engine start failed", e);

                panelStatus(
                        "Engine error: " + e.getMessage()
                );
            }
        });

        setupVirtualDisplay();
        wirePanel();

        worker.postDelayed(sampleLoop, 1000);

        panelStatus("Starting as " + userSide);

        return START_STICKY;
    }

    private void wirePanel() {
        new Handler(getMainLooper()).postDelayed(() -> {

            FloatingPanel panel = OverlayService.panel();

            if (panel != null) {

                panel.placeBottomLeft(screenW, screenH);
                panel.setUserSide(userSide);

                panel.setListener(new FloatingPanel.Listener() {

                    @Override
                    public void onPauseToggle(boolean isPaused) {
                        paused = isPaused;

                        if (isPaused) {
                            OverlayService.clearAll();
                            panelStatus("Paused");

                        } else {
                            lastEngineFen = "";
                            lastDisplayedArrowFen = "";

                            candidateGrid = null;
                            candidateRepeats = 0;

                            if (worker != null) {
                                worker.post(() -> drainReader());
                            }

                            panelStatus("Resumed");
                        }
                    }

                    @Override
                    public void onStop() {
                        stopSelf();
                    }

                    @Override
                    public void onRecalibrate() {
                        classifier.reset();
                        resetGameState();

                        OverlayService.clearAll();

                        panelStatus(
                                "Recalibrating · show starting position"
                        );
                    }

                    @Override
                    public void onSideChange(Side newSide) {
                        userSide = newSide;

                        Prefs.setUserSide(
                                getApplicationContext(),
                                newSide == Side.BLACK ? "b" : "w"
                        );

                        if (classifier.isTrained()
                                && classifier.bootstrapSide() != newSide) {

                            classifier.reset();
                            resetGameState();

                            OverlayService.clearAll();

                            panelStatus(
                                    "Switched to " + newSide
                                            + " · recalibrate"
                            );

                        } else {
                            lastEngineFen = "";
                            lastDisplayedArrowFen = "";

                            observedStm = newSide;

                            panelStatus("Now playing " + newSide);
                        }
                    }
                });
            }

        }, 400);
    }

    private void resetGameState() {
        confirmedGrid = null;
        candidateGrid = null;
        candidateRepeats = 0;

        lastEngineFen = "";
        lastDisplayedArrowFen = "";

        observedStm = Side.WHITE;

        currentArrows.clear();
    }

    private final Runnable sampleLoop = new Runnable() {

        @Override
        public void run() {

            try {
                if (!paused) {
                    sampleOnce();
                } else {
                    drainReader();
                }

            } catch (Throwable t) {
                Log.e(TAG, "sample error", t);
            }

            if (worker != null) {
                worker.postDelayed(this, SAMPLE_PERIOD_MS);
            }
        }
    };

    private void drainReader() {
        if (reader == null) {
            return;
        }

        while (true) {
            Image image;

            try {
                image = reader.acquireNextImage();

            } catch (Exception e) {
                break;
            }

            if (image == null) {
                break;
            }

            image.close();
        }
    }

    private void sampleOnce() {

        if (busy || reader == null) {
            return;
        }

        Image img;

        try {
            img = reader.acquireLatestImage();

        } catch (Exception e) {
            return;
        }

        if (img == null) {
            return;
        }

        busy = true;

        Bitmap bmp = null;

        try {
            bmp = imageToBitmap(img);

            img.close();
            img = null;

            BoardDetector.Board board =
                    BoardDetector.locate(bmp);

            if (board == null) {

                lostBoardCount++;

                if (lostBoardCount < LOST_BOARD_THRESHOLD) {

                    if (lostBoardCount == 1) {
                        panelStatus("Looking for board…");
                    }

                    return;
                }

                panelStatus(
                        "Board lost (" + lostBoardCount + ")"
                );

                OverlayService.clearAll();

                return;
            }

            lostBoardCount = 0;
            lastKnownBoard = board;

            Rect screenBounds = boardBoundsOnScreen(board);

            OverlayService.setBoardBounds(screenBounds);

            if (!engineReady) {
                panelStatus("Board found · waiting for engine");
                return;
            }

            // Initial calibration.
            if (!classifier.isTrained()) {

                boolean ok = classifier.tryBootstrap(
                        bmp,
                        board
                );

                if (!ok) {
                    panelStatus(
                            "Show STARTING position to calibrate"
                    );

                    OverlayService.setBoardFrameColor(
                            0x66FFB300
                    );

                    return;
                }

                Side detected = classifier.bootstrapSide();

                if (detected != userSide) {

                    classifier.reset();

                    panelStatus(
                            "Board shows " + detected
                                    + " — flip or change colour"
                    );

                    OverlayService.setBoardFrameColor(
                            0x66D32F2F
                    );

                    return;
                }

                OverlayService.setBoardFrameColor(
                        0x6600C853
                );

                confirmedGrid = startingGridFor(userSide);

                observedStm = Side.WHITE;

                candidateGrid = null;
                candidateRepeats = 0;

                panelStatus(
                        "Calibrated · you play " + userSide
                );

                if (observedStm == userSide) {
                    queryEngineAndRender(board, bmp);

                } else {
                    panelStatus(
                            observedStm + "'s turn — waiting"
                    );
                }

                return;
            }

            char[][] grid = classifier.classifyBoard(
                    bmp,
                    board
            );

            if (grid == null) {
                return;
            }

            // Do not repair or invent piece identities here.
            // Unknown detections must remain unknown.

            if (gridsEqual(grid, confirmedGrid)) {

                candidateGrid = null;
                candidateRepeats = 0;

                classifier.commitConfirmed(
                        confirmedGrid,
                        bmp,
                        board
                );

                if (observedStm == userSide
                        && !lastEngineFen.equals(
                                fenOf(confirmedGrid)
                        )) {

                    queryEngineAndRender(board, bmp);

                } else if (observedStm == userSide) {

                    repushCurrentArrows(board);

                    panelStatus("Your move (ready)");

                } else {

                    OverlayService.clearArrowsOnly();

                    panelStatus(
                            observedStm + "'s turn — waiting"
                    );
                }

                return;
            }

            if (!gridsEqual(grid, candidateGrid)) {

                candidateGrid = grid;
                candidateRepeats = 1;

                panelStatus("Saw change…");

                return;
            }

            candidateRepeats++;

            if (candidateRepeats < 2) {
                panelStatus("Saw change… confirming");
                return;
            }

            MoveAnalyzer.Result move =
                    MoveAnalyzer.analyze(
                            confirmedGrid,
                            grid
                    );

            Log.i(
                    TAG,
                    "Move attempt: kind=" + move.kind
                            + " mover=" + move.mover
                            + " changes=" + move.changeCount
            );

            // Unknown positions are never forced into the game.
            if (move.kind == MoveAnalyzer.Kind.UNKNOWN
                    || move.mover == null) {

                candidateRepeats = Math.min(
                        candidateRepeats,
                        MAX_UNKNOWN_REPEATS
                );

                if (candidateRepeats >= MAX_UNKNOWN_REPEATS) {

                    panelStatus(
                            "Position unclear · "
                                    + move.changeCount
                                    + " changed · recalibrate if needed"
                    );

                } else {

                    panelStatus(
                            "Unclear change · "
                                    + move.changeCount + " sq"
                    );
                }

                return;
            }

            // Reject a move made by the wrong side.
            if (move.mover != observedStm) {

                panelStatus(
                        "Unexpected mover · expected "
                                + observedStm
                                + " but detected "
                                + move.mover
                );

                return;
            }

            FenBuilder.Validation validation =
                    FenBuilder.validate(
                            classifier.bootstrapSide() == Side.BLACK
                                    ? FenBuilder.flip(grid)
                                    : grid
                    );

            if (!validation.ok) {

                panelStatus(
                        "Position invalid ("
                                + validation.reason
                                + ") · keeping previous"
                );

                return;
            }

            // Only a recognized, expected-side move with a
            // structurally valid position can be confirmed.
            confirmedGrid = grid;

            classifier.commitConfirmed(
                    confirmedGrid,
                    bmp,
                    board
            );

            observedStm = move.mover == Side.WHITE
                    ? Side.BLACK
                    : Side.WHITE;

            candidateGrid = null;
            candidateRepeats = 0;

            panelStatus(
                    move.mover + " played " + move.kind
            );

            if (observedStm == userSide) {

                queryEngineAndRender(board, bmp);

            } else {

                OverlayService.clearArrowsOnly();

                currentArrows.clear();
                lastDisplayedArrowFen = "";

                panelStatus(
                        observedStm + "'s turn — waiting"
                );

                panelEval("");
            }

        } catch (Exception e) {

            Log.w(
                    TAG,
                    "loop iter failed: " + e.getMessage(),
                    e
            );

        } finally {

            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }

            if (img != null) {
                try {
                    img.close();
                } catch (Exception ignored) {
                }
            }

            busy = false;
        }
    }

    private void queryEngineAndRender(
            BoardDetector.Board board,
            Bitmap currentBitmap
    ) {

        if (confirmedGrid == null) {
            return;
        }

        FenBuilder.Validation validation =
                FenBuilder.validate(
                        classifier.bootstrapSide() == Side.BLACK
                                ? FenBuilder.flip(confirmedGrid)
                                : confirmedGrid
                );

        if (!validation.ok) {
            panelStatus("Invalid: " + validation.reason);
            return;
        }

        String fen = FenBuilder.build(
                confirmedGrid,
                classifier.bootstrapSide(),
                userSide
        );

        if (fen == null) {
            panelStatus("FEN build failed");
            return;
        }

        if (fen.equals(lastEngineFen)) {
            repushCurrentArrows(board);
            return;
        }

        lastEngineFen = fen;

        int think = Prefs.thinkMs(
                getApplicationContext()
        );

        int multiPv = Math.max(
                1,
                Math.min(
                        3,
                        Prefs.multiPv(getApplicationContext())
                )
        );

        panelStatus("Thinking " + think + " ms…");

        try {

            long startTime = System.currentTimeMillis();

            List<StockfishEngine.Pv> pvs =
                    engine.topMoves(
                            fen,
                            think,
                            multiPv
                    );

            long elapsed =
                    System.currentTimeMillis() - startTime;

            if (pvs.isEmpty()) {
                panelStatus("No legal move");
                return;
            }

            currentArrows = buildArrows(
                    pvs,
                    board
            );

            lastDisplayedArrowFen = fen;

            OverlayService.pushArrows(
                    currentArrows,
                    boardBoundsOnScreen(board)
            );

            StockfishEngine.Pv best = pvs.get(0);

            String evalStr = best.mate
                    ? "M" + Math.abs(plyToMate(best.cp))
                    : String.format("%+.2f", best.cp / 100.0);

            panelEval(
                    "YOU (" + userSide + ") "
                            + formatUci(best.uci)
                            + "  " + evalStr
            );

            panelStatus(
                    "Your move (" + elapsed + " ms)"
            );

        } catch (Exception e) {

            Log.w(TAG, "engine query failed", e);

            panelStatus("Engine query failed");
        }
    }

    private void repushCurrentArrows(
            BoardDetector.Board board
    ) {

        if (currentArrows == null
                || currentArrows.isEmpty()) {
            return;
        }

        List<ArrowOverlayView.Arrow> rebuilt =
                rebuildArrowsForNewBounds(board);

        OverlayService.pushArrows(
                rebuilt,
                boardBoundsOnScreen(board)
        );
    }

    private List<ArrowOverlayView.Arrow> rebuildArrowsForNewBounds(
            BoardDetector.Board board
    ) {
        return currentArrows;
    }

    private List<ArrowOverlayView.Arrow> buildArrows(
            List<StockfishEngine.Pv> pvs,
            BoardDetector.Board board
    ) {

        List<ArrowOverlayView.Arrow> output =
                new ArrayList<>();

        Side perspective = classifier.bootstrapSide();

        float cellX = board.square * capToScreenX;
        float cellY = board.square * capToScreenY;

        float cellAverage = (cellX + cellY) * 0.5f;

        float originX = board.left * capToScreenX;
        float originY = board.top * capToScreenY;

        for (int i = 0; i < pvs.size(); i++) {

            String uci = pvs.get(i).uci;

            if (uci == null || uci.length() < 4) {
                continue;
            }

            int fromX = uci.charAt(0) - 'a';
            int fromY = 8 - (uci.charAt(1) - '0');

            int toX = uci.charAt(2) - 'a';
            int toY = 8 - (uci.charAt(3) - '0');

            if (fromX < 0 || fromX > 7
                    || fromY < 0 || fromY > 7
                    || toX < 0 || toX > 7
                    || toY < 0 || toY > 7) {
                continue;
            }

            if (perspective == Side.BLACK) {

                fromX = 7 - fromX;
                fromY = 7 - fromY;

                toX = 7 - toX;
                toY = 7 - toY;
            }

            float startX =
                    originX + (fromX + 0.5f) * cellX;

            float startY =
                    originY + (fromY + 0.5f) * cellY;

            float endX =
                    originX + (toX + 0.5f) * cellX;

            float endY =
                    originY + (toY + 0.5f) * cellY;

            String label =
                    (i == 0 ? "★ " : "") + uci;

            int styleIndex = Math.min(
                    i,
                    ARROW_WIDTHS.length - 1
            );

            float width = Math.max(
                    8f,
                    ARROW_WIDTHS[styleIndex]
                            * Math.min(
                                    1.5f,
                                    cellAverage / 60f
                            )
            );

            int color = ARROW_COLORS[styleIndex];

            output.add(
                    new ArrowOverlayView.Arrow(
                            startX,
                            startY,
                            endX,
                            endY,
                            color,
                            width,
                            label
                    )
            );
        }

        return output;
    }

    private String fenOf(char[][] grid) {

        if (grid == null) {
            return "";
        }

        String fen = FenBuilder.build(
                grid,
                classifier.bootstrapSide(),
                userSide
        );

        return fen == null ? "" : fen;
    }

    private boolean gridsEqual(
            char[][] a,
            char[][] b
    ) {

        if (a == null || b == null) {
            return false;
        }

        if (a.length != 8 || b.length != 8) {
            return false;
        }

        for (int row = 0; row < 8; row++) {

            if (a[row] == null || b[row] == null
                    || !Arrays.equals(a[row], b[row])) {
                return false;
            }
        }

        return true;
    }

    private char[][] startingGridFor(Side side) {

        char[][] base = {
                {'r','n','b','q','k','b','n','r'},
                {'p','p','p','p','p','p','p','p'},
                {'.','.','.','.','.','.','.','.'},
                {'.','.','.','.','.','.','.','.'},
                {'.','.','.','.','.','.','.','.'},
                {'.','.','.','.','.','.','.','.'},
                {'P','P','P','P','P','P','P','P'},
                {'R','N','B','Q','K','B','N','R'}
        };

        if (side == Side.BLACK) {

            char[][] output = new char[8][8];

            for (int row = 0; row < 8; row++) {
                for (int file = 0; file < 8; file++) {

                    output[row][file] =
                            base[7 - row][7 - file];
                }
            }

            return output;
        }

        return base;
    }

    private int plyToMate(int cp) {
        return cp > 0
                ? 100000 - cp
                : -(100000 + cp);
    }

    private String formatUci(String uci) {

        if (uci == null || uci.length() < 4) {
            return uci == null ? "?" : uci;
        }

        return uci.substring(0, 2)
                + "→"
                + uci.substring(2, 4)
                + (uci.length() > 4
                        ? "=" + Character.toUpperCase(
                                uci.charAt(4)
                        )
                        : "");
    }

    private Rect boardBoundsOnScreen(
            BoardDetector.Board board
    ) {

        return new Rect(
                Math.round(board.left * capToScreenX),
                Math.round(board.top * capToScreenY),
                Math.round(
                        (board.left + board.side) * capToScreenX
                ),
                Math.round(
                        (board.top + board.side) * capToScreenY
                )
        );
    }

    private void panelStatus(String status) {

        FloatingPanel panel = OverlayService.panel();

        if (panel != null) {
            panel.setStatus(status);
        }
    }

    private void panelEval(String evaluation) {

        FloatingPanel panel = OverlayService.panel();

        if (panel != null) {
            panel.setEval(evaluation);
        }
    }

    private void setupVirtualDisplay() {

        DisplayMetrics metrics = new DisplayMetrics();

        ((WindowManager) getSystemService(WINDOW_SERVICE))
                .getDefaultDisplay()
                .getRealMetrics(metrics);

        screenW = metrics.widthPixels;
        screenH = metrics.heightPixels;

        float maxEdge = Math.max(screenW, screenH);

        float scale = Math.min(
                1f,
                800f / maxEdge
        );

        captureW = Math.round(screenW * scale);
        captureH = Math.round(screenH * scale);

        density = metrics.densityDpi;

        capToScreenX = (float) screenW / captureW;
        capToScreenY = (float) screenH / captureH;

        Log.i(
                TAG,
                "screen=" + screenW + "x" + screenH
                        + " capture=" + captureW + "x" + captureH
        );

        reader = ImageReader.newInstance(
                captureW,
                captureH,
                PixelFormat.RGBA_8888,
                4
        );

        virtualDisplay = projection.createVirtualDisplay(
                "OpenChepsVD",
                captureW,
                captureH,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                worker
        );
    }

    private Bitmap imageToBitmap(Image image) {

        Image.Plane plane = image.getPlanes()[0];

        ByteBuffer buffer = plane.getBuffer();

        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        int width = image.getWidth();
        int height = image.getHeight();

        int rowPadding =
                rowStride - pixelStride * width;

        int bitmapWidth =
                width + rowPadding / pixelStride;

        Bitmap raw = Bitmap.createBitmap(
                bitmapWidth,
                height,
                Bitmap.Config.ARGB_8888
        );

        buffer.rewind();
        raw.copyPixelsFromBuffer(buffer);

        if (bitmapWidth != width) {

            Bitmap cropped = Bitmap.createBitmap(
                    raw,
                    0,
                    0,
                    width,
                    height
            );

            raw.recycle();

            return cropped;
        }

        return raw;
    }

    private void startInForeground() {

        NotificationManager manager =
                (NotificationManager) getSystemService(
                        NOTIFICATION_SERVICE
                );

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            getString(R.string.notif_channel),
                            NotificationManager.IMPORTANCE_LOW
                    );

            manager.createNotificationChannel(channel);
        }

        Intent stopIntent = new Intent(
                this,
                ScreenCaptureService.class
        ).setAction(ACTION_STOP);

        PendingIntent stopPendingIntent =
                PendingIntent.getService(
                        this,
                        1,
                        stopIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        Notification notification =
                new NotificationCompat.Builder(
                        this,
                        CHANNEL_ID
                )
                .setContentTitle(
                        getString(R.string.notif_title)
                )
                .setContentText(
                        getString(R.string.notif_text)
                )
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .addAction(
                        0,
                        "Stop",
                        stopPendingIntent
                )
                .build();

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                    7,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );

        } else {

            startForeground(
                    7,
                    notification
            );
        }

        startService(
                new Intent(
                        this,
                        com.opencheps.overlay.OverlayService.class
                )
        );
    }

    @Override
    public void onDestroy() {

        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
        }

        try {
            if (engine != null) {
                engine.stop();
            }
        } catch (Exception ignored) {
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
        }

        if (reader != null) {
            reader.close();
        }

        if (projection != null) {
            projection.stop();
        }

        if (workerThread != null) {
            workerThread.quitSafely();
        }

        stopService(
                new Intent(
                        this,
                        com.opencheps.overlay.OverlayService.class
                )
        );

        super.onDestroy();
    }
}
