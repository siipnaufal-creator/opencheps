
package com.opencheps.vision;

import android.graphics.Bitmap;
import android.util.Log;

import com.opencheps.util.Side;

public final class PieceClassifier {

    private static final String TAG = "OC/Classifier";
    private static final char UNKNOWN = '?';

    // RMS distance: rentang teoritis 0 sampai 1.
    private static final float MAX_TEMPLATE_DISTANCE = 0.22f;
    private static final float MIN_CLASS_MARGIN = 0.015f;
    private static final float MIN_COLOR_CONFIDENCE = 0.025f;

    // frameChangeThreshold menggunakan SUM squared distance,
    // bukan RMS. Jangan samakan kedua satuan ini.
    private static final float MIN_FRAME_CHANGE_THRESHOLD = 0.30f;

    private static final char[] START_WHITE_POV = {
            'r','n','b','q','k','b','n','r',
            'p','p','p','p','p','p','p','p',
            '.','.','.','.','.','.','.','.',
            '.','.','.','.','.','.','.','.',
            '.','.','.','.','.','.','.','.',
            '.','.','.','.','.','.','.','.',
            'P','P','P','P','P','P','P','P',
            'R','N','B','Q','K','B','N','R'
    };

    private static final char[] PIECE_TYPES = {
            'P','N','B','R','Q','K',
            'p','n','b','r','q','k'
    };

    private SquareSampler.Sample[] templates;
    private SquareSampler.Sample[] previousFrame;

    private char[] previousLabels;
    private char[] labels;

    private float emptyEdgeMax;
    private float occupiedEdgeMin;
    private float pieceColorThreshold;

    private float frameChangeThreshold =
            MIN_FRAME_CHANGE_THRESHOLD;

    private float whitePieceLumAvg;
    private float blackPieceLumAvg;

    private boolean trained;
    private Side bootstrapSide;

    public boolean isTrained() {
        return trained;
    }

    public Side bootstrapSide() {
        return bootstrapSide;
    }

    public synchronized boolean tryBootstrap(
            Bitmap bmp,
            BoardDetector.Board board
    ) {
        if (bmp == null || bmp.isRecycled() || board == null) {
            return false;
        }

        SquareSampler.Sample[] samples = sampleAll(bmp, board);

        if (samples == null) {
            return false;
        }

        float maxEmptyEdge = 0f;

        for (int row = 2; row <= 5; row++) {
            for (int file = 0; file < 8; file++) {
                float edge = samples[row * 8 + file].edgeEnergy;
                maxEmptyEdge = Math.max(maxEmptyEdge, edge);
            }
        }

        float minOccupiedEdge = Float.MAX_VALUE;

        for (int row = 0; row < 8; row++) {
            if (row >= 2 && row <= 5) {
                continue;
            }

            for (int file = 0; file < 8; file++) {
                float edge = samples[row * 8 + file].edgeEnergy;
                minOccupiedEdge = Math.min(minOccupiedEdge, edge);
            }
        }

        if (minOccupiedEdge == Float.MAX_VALUE
                || minOccupiedEdge < maxEmptyEdge * 1.5f) {

            Log.w(TAG, "Bootstrap rejected: weak empty/occupied separation");
            return false;
        }

        float topLumSum = 0f;
        float bottomLumSum = 0f;

        int topCount = 0;
        int bottomCount = 0;

        for (int file = 0; file < 8; file++) {
            for (int row = 0; row <= 1; row++) {
                topLumSum += samples[row * 8 + file].meanLum;
                topCount++;
            }

            for (int row = 6; row <= 7; row++) {
                bottomLumSum += samples[row * 8 + file].meanLum;
                bottomCount++;
            }
        }

        if (topCount == 0 || bottomCount == 0) {
            return false;
        }

        float topAverage = topLumSum / topCount;
        float bottomAverage = bottomLumSum / bottomCount;

        if (Math.abs(topAverage - bottomAverage)
                < MIN_COLOR_CONFIDENCE) {

            Log.w(TAG, "Bootstrap rejected: cannot identify board side");
            return false;
        }

        Side detectedSide =
                bottomAverage > topAverage ? Side.WHITE : Side.BLACK;

        char[] initialLabels =
                detectedSide == Side.WHITE
                        ? START_WHITE_POV.clone()
                        : flipLabels(START_WHITE_POV);

        float whiteSum = 0f;
        float blackSum = 0f;

        int whiteCount = 0;
        int blackCount = 0;

        for (int i = 0; i < 64; i++) {
            char piece = initialLabels[i];

            if (piece == '.') {
                continue;
            }

            float lum = samples[i].meanLum;

            if (Character.isUpperCase(piece)) {
                whiteSum += lum;
                whiteCount++;
            } else {
                blackSum += lum;
                blackCount++;
            }
        }

        if (whiteCount == 0 || blackCount == 0) {
            return false;
        }

        float whiteAverage = whiteSum / whiteCount;
        float blackAverage = blackSum / blackCount;

        if (Math.abs(whiteAverage - blackAverage)
                < MIN_COLOR_CONFIDENCE) {

            Log.w(TAG, "Bootstrap rejected: piece colors ambiguous");
            return false;
        }

        templates = samples;
        labels = initialLabels;
        bootstrapSide = detectedSide;

        emptyEdgeMax = maxEmptyEdge;
        occupiedEdgeMin = minOccupiedEdge;

        pieceColorThreshold =
                (emptyEdgeMax + occupiedEdgeMin) * 0.5f;

        whitePieceLumAvg = whiteAverage;
        blackPieceLumAvg = blackAverage;

        previousFrame = samples;
        previousLabels = initialLabels.clone();

        // Pertahankan satuan asli SquareSampler.lumDistance().
        // Hitung noise dari kotak kosong dengan warna kotak sama.
        float maxEmptyNoise = 0f;

        for (int i = 0; i < 64; i++) {
            if (labels[i] != '.') {
                continue;
            }

            for (int j = i + 1; j < 64; j++) {
                if (labels[j] != '.') {
                    continue;
                }

                boolean sameSquareColor =
                        (((i / 8) + (i % 8)) & 1)
                        == (((j / 8) + (j % 8)) & 1);

                if (!sameSquareColor) {
                    continue;
                }

                float distance = SquareSampler.lumDistance(
                        samples[i].feature,
                        samples[j].feature
                );

                maxEmptyNoise = Math.max(
                        maxEmptyNoise,
                        distance
                );
            }
        }

        frameChangeThreshold = Math.max(
                MIN_FRAME_CHANGE_THRESHOLD,
                maxEmptyNoise * 2f + 0.1f
        );

        trained = true;

        Log.i(TAG,
                "BOOTSTRAPPED"
                        + " side=" + bootstrapSide
                        + " emptyMax=" + emptyEdgeMax
                        + " occupiedMin=" + occupiedEdgeMin
                        + " edgeThreshold=" + pieceColorThreshold
                        + " frameThresholdSum=" + frameChangeThreshold
                        + " emptyNoise=" + maxEmptyNoise);

        return true;
    }

    public synchronized char[][] classifyBoard(
            Bitmap bmp,
            BoardDetector.Board board
    ) {
        if (!trained || bmp == null || bmp.isRecycled()
                || board == null) {
            return null;
        }

        SquareSampler.Sample[] samples = sampleAll(bmp, board);

        if (samples == null) {
            return null;
        }

        char[][] grid = new char[8][8];

        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {

                int index = row * 8 + file;
                SquareSampler.Sample current = samples[index];

                if (previousFrame != null
                        && previousLabels != null) {

                    // Satuan sama dengan frameChangeThreshold:
                    // SUM squared difference, bukan RMS.
                    float frameDistance = SquareSampler.lumDistance(
                            current.feature,
                            previousFrame[index].feature
                    );

                    if (frameDistance < frameChangeThreshold) {
                        grid[row][file] = previousLabels[index];
                        continue;
                    }
                }

                grid[row][file] = classifyOne(
                        current,
                        row,
                        file
                );
            }
        }

        return grid;
    }

    public synchronized void commitConfirmed(
            char[][] grid,
            Bitmap bmp,
            BoardDetector.Board board
    ) {
        if (!trained || grid == null
                || bmp == null || bmp.isRecycled()
                || board == null) {
            return;
        }

        SquareSampler.Sample[] samples = sampleAll(bmp, board);

        if (samples == null) {
            return;
        }

        previousFrame = samples;

        if (previousLabels == null) {
            previousLabels = new char[64];
        }

        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                char piece = grid[row][file];

                previousLabels[row * 8 + file] =
                        piece == 0 ? '.' : piece;
            }
        }
    }

    private SquareSampler.Sample[] sampleAll(
            Bitmap bmp,
            BoardDetector.Board board
    ) {
        if (bmp == null || bmp.isRecycled()
                || board.square < 8
                || board.side <= 0) {
            return null;
        }

        if (board.left < 0 || board.top < 0
                || board.left + board.side > bmp.getWidth()
                || board.top + board.side > bmp.getHeight()) {
            return null;
        }

        int radius = Math.max(
                4,
                (int) (board.square * 0.32f)
        );

        SquareSampler.Sample[] samples =
                new SquareSampler.Sample[64];

        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {

                int centerX = Math.round(
                        board.left + (file + 0.5f) * board.square
                );

                int centerY = Math.round(
                        board.top + (row + 0.5f) * board.square
                );

                if (centerX - radius < 0
                        || centerY - radius < 0
                        || centerX + radius >= bmp.getWidth()
                        || centerY + radius >= bmp.getHeight()) {
                    return null;
                }

                samples[row * 8 + file] = SquareSampler.sample(
                        bmp,
                        centerX,
                        centerY,
                        radius
                );
            }
        }

        return samples;
    }

    private char classifyOne(
            SquareSampler.Sample sample,
            int row,
            int file
    ) {
        if (sample == null) {
            return UNKNOWN;
        }

        if (sample.edgeEnergy < pieceColorThreshold) {
            return '.';
        }

        float whiteDistance =
                Math.abs(sample.meanLum - whitePieceLumAvg);

        float blackDistance =
                Math.abs(sample.meanLum - blackPieceLumAvg);

        if (Math.abs(whiteDistance - blackDistance)
                < MIN_COLOR_CONFIDENCE) {
            return UNKNOWN;
        }

        boolean whitePiece = whiteDistance < blackDistance;

        float[] bestDistances = new float[12];

        for (int i = 0; i < bestDistances.length; i++) {
            bestDistances[i] = Float.MAX_VALUE;
        }

        boolean squareIsLight = ((row + file) & 1) == 0;

        for (int i = 0; i < templates.length; i++) {
            char templatePiece = labels[i];

            if (templatePiece == '.') {
                continue;
            }

            if (Character.isUpperCase(templatePiece) != whitePiece) {
                continue;
            }

            int templateRow = i / 8;
            int templateFile = i % 8;

            boolean templateIsLight =
                    ((templateRow + templateFile) & 1) == 0;

            if (templateIsLight != squareIsLight) {
                continue;
            }

            int typeIndex = pieceTypeIndex(templatePiece);

            if (typeIndex < 0) {
                continue;
            }

            // RMS dipakai hanya untuk perbandingan template.
            float distance = normalizedDistance(
                    sample.feature,
                    templates[i].feature
            );

            if (distance < bestDistances[typeIndex]) {
                bestDistances[typeIndex] = distance;
            }
        }

        int bestIndex = -1;
        float best = Float.MAX_VALUE;
        float secondBest = Float.MAX_VALUE;

        for (int i = 0; i < bestDistances.length; i++) {
            float distance = bestDistances[i];

            if (distance < best) {
                secondBest = best;
                best = distance;
                bestIndex = i;
            } else if (distance < secondBest) {
                secondBest = distance;
            }
        }

        if (bestIndex < 0 || best == Float.MAX_VALUE) {
            return UNKNOWN;
        }

        if (best > MAX_TEMPLATE_DISTANCE) {
            Log.d(TAG,
                    "Unknown square " + row + "," + file
                            + " distance=" + best);
            return UNKNOWN;
        }

        if (secondBest != Float.MAX_VALUE) {
            float margin = secondBest - best;

            if (margin < MIN_CLASS_MARGIN) {
                Log.d(TAG,
                        "Ambiguous square " + row + "," + file
                                + " best=" + best
                                + " second=" + secondBest);
                return UNKNOWN;
            }
        }

        return PIECE_TYPES[bestIndex];
    }

    private int pieceTypeIndex(char piece) {
        for (int i = 0; i < PIECE_TYPES.length; i++) {
            if (PIECE_TYPES[i] == piece) {
                return i;
            }
        }

        return -1;
    }

    private float normalizedDistance(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0
                || a.length != b.length) {
            return Float.MAX_VALUE;
        }

        double sum = 0.0;

        for (int i = 0; i < a.length; i++) {
            double difference = a[i] - b[i];
            sum += difference * difference;
        }

        return (float) Math.sqrt(sum / a.length);
    }

    private char[] flipLabels(char[] source) {
        char[] output = new char[64];

        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                output[row * 8 + file] =
                        source[(7 - row) * 8 + (7 - file)];
            }
        }

        return output;
    }

    public synchronized void reset() {
        templates = null;
        previousFrame = null;
        previousLabels = null;
        labels = null;

        emptyEdgeMax = 0f;
        occupiedEdgeMin = 0f;
        pieceColorThreshold = 0f;

        whitePieceLumAvg = 0f;
        blackPieceLumAvg = 0f;

        frameChangeThreshold = MIN_FRAME_CHANGE_THRESHOLD;

        trained = false;
        bootstrapSide = null;
    }
}
