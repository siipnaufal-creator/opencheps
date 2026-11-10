
package com.opencheps.vision;

import android.graphics.Bitmap;
import android.util.Log;

import com.opencheps.util.Side;

public final class PieceClassifier {

    private static final String TAG = "OC/Classifier";
    private static final char UNKNOWN = '?';

    private static final float MAX_TEMPLATE_DISTANCE = 0.22f;
    private static final float MIN_CLASS_MARGIN = 0.015f;
    private static final float MIN_COLOR_CONFIDENCE = 0.025f;
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
                maxEmptyEdge = Math.max(
                        maxEmptyEdge,
                        samples[row * 8 + file].edgeEnergy
                );
            }
        }

        float minOccupiedEdge = Float.MAX_VALUE;

        for (int row = 0; row < 8; row++) {
            if (row >= 2 && row <= 5) continue;

            for (int file = 0; file < 8; file++) {
                minOccupiedEdge = Math.min(
                        minOccupiedEdge,
                        samples[row * 8 + file].edgeEnergy
                );
            }
        }

        if (minOccupiedEdge == Float.MAX_VALUE
                || minOccupiedEdge < maxEmptyEdge * 1.5f) {
            Log.w(TAG, "Bootstrap rejected: weak edge separation");
            return false;
        }

        float topSum = 0f;
        float bottomSum = 0f;

        for (int file = 0; file < 8; file++) {
            for (int row = 0; row <= 1; row++) {
                topSum += samples[row * 8 + file].meanLum;
            }

            for (int row = 6; row <= 7; row++) {
                bottomSum += samples[row * 8 + file].meanLum;
            }
        }

        float topAverage = topSum / 16f;
        float bottomAverage = bottomSum / 16f;

        if (Math.abs(topAverage - bottomAverage)
                < MIN_COLOR_CONFIDENCE) {
            Log.w(TAG, "Bootstrap rejected: board orientation unclear");
            return false;
        }

        bootstrapSide = bottomAverage > topAverage
                ? Side.WHITE
                : Side.BLACK;

        labels = bootstrapSide == Side.WHITE
                ? START_WHITE_POV.clone()
                : flipLabels(START_WHITE_POV);

        float whiteSum = 0f;
        float blackSum = 0f;
        int whiteCount = 0;
        int blackCount = 0;

        for (int i = 0; i < 64; i++) {
            char piece = labels[i];

            if (piece == '.') continue;

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

        whitePieceLumAvg = whiteSum / whiteCount;
        blackPieceLumAvg = blackSum / blackCount;

        if (Math.abs(whitePieceLumAvg - blackPieceLumAvg)
                < MIN_COLOR_CONFIDENCE) {
            Log.w(TAG, "Bootstrap rejected: piece colors unclear");
            return false;
        }

        templates = samples.clone();
        previousFrame = samples;
        previousLabels = labels.clone();

        pieceColorThreshold =
                (maxEmptyEdge + minOccupiedEdge) * 0.5f;

        float maxEmptyNoise = 0f;

        for (int i = 0; i < 64; i++) {
            if (labels[i] != '.') continue;

            for (int j = i + 1; j < 64; j++) {
                if (labels[j] != '.') continue;

                boolean sameColor =
                        (((i / 8) + (i % 8)) & 1)
                                == (((j / 8) + (j % 8)) & 1);

                if (!sameColor) continue;

                maxEmptyNoise = Math.max(
                        maxEmptyNoise,
                        SquareSampler.lumDistance(
                                samples[i].feature,
                                samples[j].feature
                        )
                );
            }
        }

        frameChangeThreshold = Math.max(
                MIN_FRAME_CHANGE_THRESHOLD,
                maxEmptyNoise * 2f + 0.1f
        );

        trained = true;

        Log.i(TAG,
                "BOOTSTRAPPED side=" + bootstrapSide
                        + " edgeThreshold=" + pieceColorThreshold
                        + " frameThreshold=" + frameChangeThreshold);

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

        if (samples == null) return null;

        char[][] grid = new char[8][8];

        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                int index = row * 8 + file;
                SquareSampler.Sample current = samples[index];

                if (previousFrame != null
                        && previousLabels != null
                        && previousFrame[index] != null) {

                    float distance = SquareSampler.lumDistance(
                            current.feature,
                            previousFrame[index].feature
                    );

                    if (distance < frameChangeThreshold) {
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

        if (samples == null) return;

        if (previousLabels == null) {
            previousLabels = new char[64];
        }

        // Simpan contoh baru hanya dari bidak yang sudah
        // dikonfirmasi oleh sistem gerakan.
        // Template lama pada petak kosong tetap dipertahankan.
        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                int index = row * 8 + file;
                char piece = grid[row][file];

                if (piece == 0) piece = '.';

                previousLabels[index] = piece;

                if (piece != '.' && samples[index] != null) {
                    templates[index] = samples[index];
                    labels[index] = piece;
                }
            }
        }

        previousFrame = samples;
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
                int cx = Math.round(
                        board.left + (file + 0.5f) * board.square
                );

                int cy = Math.round(
                        board.top + (row + 0.5f) * board.square
                );

                if (cx - radius < 0 || cy - radius < 0
                        || cx + radius >= bmp.getWidth()
                        || cy + radius >= bmp.getHeight()) {
                    return null;
                }

                samples[row * 8 + file] =
                        SquareSampler.sample(bmp, cx, cy, radius);
            }
        }

        return samples;
    }

    private char classifyOne(
            SquareSampler.Sample sample,
            int row,
            int file
    ) {
        if (sample == null) return UNKNOWN;

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
        boolean targetLight = ((row + file) & 1) == 0;

        float[] bestDistances = new float[12];

        for (int i = 0; i < bestDistances.length; i++) {
            bestDistances[i] = Float.MAX_VALUE;
        }

        for (int i = 0; i < templates.length; i++) {
            char templatePiece = labels[i];

            if (templatePiece == '.' || templates[i] == null) {
                continue;
            }

            if (Character.isUpperCase(templatePiece) != whitePiece) {
                continue;
            }

            int templateRow = i / 8;
            int templateFile = i % 8;

            boolean templateLight =
                    ((templateRow + templateFile) & 1) == 0;

            char type = Character.toLowerCase(templatePiece);

            // Raja dan ratu dapat berpindah ke warna petak
            // berbeda. Jangan menolak seluruh templatenya.
            boolean royalPiece = type == 'k' || type == 'q';

            if (templateLight != targetLight && !royalPiece) {
                continue;
            }

            int typeIndex = pieceTypeIndex(templatePiece);

            if (typeIndex < 0) continue;

            float distance;

            if (templateLight == targetLight) {
                distance = normalizedDistance(
                        sample.feature,
                        templates[i].feature
                );
            } else {
                distance = normalizedShapeDistance(
                        sample.feature,
                        templates[i].feature
                );
            }

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

        if (bestIndex < 0 || best > MAX_TEMPLATE_DISTANCE) {
            return UNKNOWN;
        }

        if (secondBest != Float.MAX_VALUE
                && secondBest - best < MIN_CLASS_MARGIN) {
            return UNKNOWN;
        }

        return PIECE_TYPES[bestIndex];
    }

    private int pieceTypeIndex(char piece) {
        for (int i = 0; i < PIECE_TYPES.length; i++) {
            if (PIECE_TYPES[i] == piece) return i;
        }
        return -1;
    }

    private float normalizedDistance(float[] a, float[] b) {
        if (a == null || b == null
                || a.length == 0 || a.length != b.length) {
            return Float.MAX_VALUE;
        }

        double sum = 0;

        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }

        return (float) Math.sqrt(sum / a.length);
    }

    // Membandingkan bentuk relatif ketika warna petak berbeda.
    private float normalizedShapeDistance(float[] a, float[] b) {
        if (a == null || b == null
                || a.length == 0 || a.length != b.length) {
            return Float.MAX_VALUE;
        }

        double meanA = 0;
        double meanB = 0;

        for (int i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }

        meanA /= a.length;
        meanB /= b.length;

        double varianceA = 0;
        double varianceB = 0;

        for (int i = 0; i < a.length; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;

            varianceA += da * da;
            varianceB += db * db;
        }

        double stdA = Math.sqrt(varianceA / a.length);
        double stdB = Math.sqrt(varianceB / b.length);

        if (stdA < 0.001 || stdB < 0.001) {
            return Float.MAX_VALUE;
        }

        double sum = 0;

        for (int i = 0; i < a.length; i++) {
            double na = (a[i] - meanA) / stdA;
            double nb = (b[i] - meanB) / stdB;
            double d = na - nb;
            sum += d * d;
        }

        return (float) Math.sqrt(sum / a.length) * 0.10f;
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

        pieceColorThreshold = 0f;
        whitePieceLumAvg = 0f;
        blackPieceLumAvg = 0f;

        frameChangeThreshold = MIN_FRAME_CHANGE_THRESHOLD;

        trained = false;
        bootstrapSide = null;
    }
}
