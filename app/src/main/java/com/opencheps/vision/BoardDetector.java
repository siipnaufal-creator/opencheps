
package com.opencheps.vision;

import android.graphics.Bitmap;
import android.util.Log;

public final class BoardDetector {

    public static class Board {
        public final int left;
        public final int top;
        public final int side;
        public final float square;
        public final double confidence;

        public Board(int left, int top, int side, double confidence) {
            this.left = left;
            this.top = top;
            this.side = side;
            this.square = side / 8f;
            this.confidence = confidence;
        }
    }

    private static final String TAG = "OC/Detector";

    // Ukuran maksimum gambar untuk proses deteksi.
    private static final int WORK_MAX_EDGE = 360;

    // Nilai minimum kualitas kandidat papan.
    private static final double ACCEPT_THRESHOLD = 12.0;

    private BoardDetector() {}

    public static Board locate(Bitmap src) {
        if (src == null || src.isRecycled()) return null;

        long started = System.nanoTime();

        int originalW = src.getWidth();
        int originalH = src.getHeight();

        if (originalW < 100 || originalH < 100) {
            Log.w(TAG, "Image too small");
            return null;
        }

        // Jangan memperbesar bitmap yang sudah kecil.
        float resize = Math.min(
                1f,
                (float) WORK_MAX_EDGE / Math.max(originalW, originalH)
        );

        int width = Math.max(1, Math.round(originalW * resize));
        int height = Math.max(1, Math.round(originalH * resize));

        Bitmap work = src;

        if (width != originalW || height != originalH) {
            work = Bitmap.createScaledBitmap(
                    src, width, height, true
            );
        }

        int[] pixels = new int[width * height];
        work.getPixels(
                pixels, 0, width,
                0, 0, width, height
        );

        if (work != src) {
            work.recycle();
        }

        // Konversi grayscale.
        int[] gray = new int[width * height];

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];

            int r = (p >> 16) & 255;
            int g = (p >> 8) & 255;
            int b = p & 255;

            gray[i] = (r * 299 + g * 587 + b * 114) / 1000;
        }

        // Integral image untuk mempercepat pengukuran area.
        int stride = width + 1;
        long[] integral = new long[stride * (height + 1)];

        for (int y = 0; y < height; y++) {
            long rowSum = 0;

            for (int x = 0; x < width; x++) {
                rowSum += gray[y * width + x];

                integral[(y + 1) * stride + x + 1] =
                        integral[y * stride + x + 1] + rowSum;
            }
        }

        int minDimension = Math.min(width, height);

        int minCell = Math.max(8, minDimension / 24);
        int maxCell = (int) (minDimension / 8f * 0.98f);

        if (maxCell < minCell) {
            Log.w(TAG, "No possible board size");
            return null;
        }

        Candidate best = null;

        int sizeStep = Math.max(1, (maxCell - minCell) / 24);

        // Pencarian awal.
        for (int cell = minCell; cell <= maxCell; cell += sizeStep) {

            int side = cell * 8;

            if (side > width || side > height) continue;

            int positionStep = Math.max(2, cell / 3);

            for (int y = 0; y + side <= height; y += positionStep) {
                for (int x = 0; x + side <= width; x += positionStep) {

                    double score = scoreBoard(
                            integral, stride, width, height,
                            x, y, cell
                    );

                    if (best == null || score > best.score) {
                        best = new Candidate(x, y, cell, score);
                    }
                }
            }
        }

        if (best == null) {
            Log.w(TAG, "No candidate found");
            return null;
        }

        // Penyempurnaan posisi.
        best = refinePosition(
                integral, stride, width, height, best
        );

        // Penyempurnaan ukuran.
        best = refineSize(
                integral, stride, width, height, best
        );

        // Penyempurnaan posisi terakhir.
        best = refinePosition(
                integral, stride, width, height, best
        );

        long elapsed =
                (System.nanoTime() - started) / 1_000_000L;

        Log.i(TAG,
                "Result: x=" + best.x
                        + " y=" + best.y
                        + " side=" + (best.cell * 8)
                        + " cell=" + best.cell
                        + " score=" + best.score
                        + " time=" + elapsed + "ms"
        );

        if (best.score < ACCEPT_THRESHOLD) {
            Log.w(TAG, "Rejected: confidence too low");
            return null;
        }

        float scaleX = width / (float) originalW;
        float scaleY = height / (float) originalH;
        float scaleSide = (scaleX + scaleY) * 0.5f;

        int left = Math.round(best.x / scaleX);
        int top = Math.round(best.y / scaleY);
        int side = Math.round((best.cell * 8) / scaleSide);

        // Pastikan hasil tidak keluar dari bitmap.
        left = Math.max(0, Math.min(left, originalW - 1));
        top = Math.max(0, Math.min(top, originalH - 1));

        side = Math.max(1, Math.min(
                side,
                Math.min(originalW - left, originalH - top)
        ));

        return new Board(left, top, side, best.score);
    }

    private static Candidate refinePosition(
            long[] ii,
            int stride,
            int width,
            int height,
            Candidate current
    ) {
        Candidate best = current;

        int radius = Math.max(3, current.cell / 2);

        int minX = Math.max(0, current.x - radius);
        int maxX = Math.min(
                width - current.cell * 8,
                current.x + radius
        );

        int minY = Math.max(0, current.y - radius);
        int maxY = Math.min(
                height - current.cell * 8,
                current.y + radius
        );

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {

                double score = scoreBoard(
                        ii, stride, width, height,
                        x, y, current.cell
                );

                if (score > best.score) {
                    best = new Candidate(
                            x, y, current.cell, score
                    );
                }
            }
        }

        return best;
    }

    private static Candidate refineSize(
            long[] ii,
            int stride,
            int width,
            int height,
            Candidate current
    ) {
        Candidate best = current;

        int radius = Math.max(2, current.cell / 8);

        int minCell = Math.max(5, current.cell - radius);
        int maxCell = current.cell + radius;

        for (int cell = minCell; cell <= maxCell; cell++) {

            int side = cell * 8;

            if (best.x + side > width || best.y + side > height) {
                continue;
            }

            double score = scoreBoard(
                    ii, stride, width, height,
                    best.x, best.y, cell
            );

            if (score > best.score) {
                best = new Candidate(
                        best.x, best.y, cell, score
                );
            }
        }

        return best;
    }

    private static double scoreBoard(
            long[] ii,
            int stride,
            int width,
            int height,
            int x,
            int y,
            int cell
    ) {
        int side = cell * 8;

        if (cell < 5 || x < 0 || y < 0
                || x + side > width
                || y + side > height) {
            return -1;
        }

        double[] light = new double[32];
        double[] dark = new double[32];

        double[] corners = new double[4];

        int lightIndex = 0;
        int darkIndex = 0;

        // Sampel di empat sudut bagian dalam setiap kotak.
        int inset = Math.max(1, Math.round(cell * 0.20f));
        int sample = Math.max(1, Math.round(cell * 0.18f));

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {

                int sx = x + col * cell;
                int sy = y + row * cell;

                corners[0] = boxMean(
                        ii, stride,
                        sx + inset,
                        sy + inset,
                        sample
                );

                corners[1] = boxMean(
                        ii, stride,
                        sx + cell - inset - sample,
                        sy + inset,
                        sample
                );

                corners[2] = boxMean(
                        ii, stride,
                        sx + inset,
                        sy + cell - inset - sample,
                        sample
                );

                corners[3] = boxMean(
                        ii, stride,
                        sx + cell - inset - sample,
                        sy + cell - inset - sample,
                        sample
                );

                sort4(corners);

                // Median mengurangi pengaruh objek/bidak di sudut.
                double value = (corners[1] + corners[2]) * 0.5;

                if (((row + col) & 1) == 0) {
                    light[lightIndex++] = value;
                } else {
                    dark[darkIndex++] = value;
                }
            }
        }

        double meanLight = mean(light);
        double meanDark = mean(dark);

        double deviationLight = std(light, meanLight);
        double deviationDark = std(dark, meanDark);

        double separation = Math.abs(meanLight - meanDark);

        // Pola kotak harus konsisten, bukan sekadar memiliki
        // beberapa area terang dan gelap.
        double consistencyPenalty =
                (deviationLight + deviationDark) * 0.55;

        double edge = boundaryContrast(
                ii, stride, width, height,
                x, y, side
        );

        return separation
                - consistencyPenalty
                + edge * 0.10;
    }

    private static double boundaryContrast(
            long[] ii,
            int stride,
            int width,
            int height,
            int x,
            int y,
            int side
    ) {
        int band = Math.max(2, Math.min(4, side / 100));
        int step = Math.max(3, side / 24);

        double total = 0;
        int count = 0;

        for (int p = band; p < side - band; p += step) {

            // Batas atas.
            if (y >= band) {
                total += Math.abs(
                        boxMean(ii, stride, x + p, y, band)
                                - boxMean(ii, stride,
                                x + p, y - band, band)
                );
                count++;
            }

            // Batas bawah.
            if (y + side + band <= height) {
                total += Math.abs(
                        boxMean(ii, stride,
                                x + p, y + side - band, band)
                                - boxMean(ii, stride,
                                x + p, y + side, band)
                );
                count++;
            }

            // Batas kiri.
            if (x >= band) {
                total += Math.abs(
                        boxMean(ii, stride, x, y + p, band)
                                - boxMean(ii, stride,
                                x - band, y + p, band)
                );
                count++;
            }

            // Batas kanan.
            if (x + side + band <= width) {
                total += Math.abs(
                        boxMean(ii, stride,
                                x + side - band, y + p, band)
                                - boxMean(ii, stride,
                                x + side, y + p, band)
                );
                count++;
            }
        }

        return count == 0 ? 0 : total / count;
    }

    private static double boxMean(
            long[] ii,
            int stride,
            int x,
            int y,
            int size
    ) {
        int x2 = x + size;
        int y2 = y + size;

        long sum =
                ii[y2 * stride + x2]
                        - ii[y * stride + x2]
                        - ii[y2 * stride + x]
                        + ii[y * stride + x];

        return sum / (double) (size * size);
    }

    private static void sort4(double[] a) {
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                if (a[i] > a[j]) {
                    double temp = a[i];
                    a[i] = a[j];
                    a[j] = temp;
                }
            }
        }
    }

    private static double mean(double[] values) {
        double sum = 0;

        for (double value : values) {
            sum += value;
        }

        return sum / values.length;
    }

    private static double std(double[] values, double average) {
        double sum = 0;

        for (double value : values) {
            double difference = value - average;
            sum += difference * difference;
        }

        return Math.sqrt(sum / values.length);
    }

    private static final class Candidate {
        final int x;
        final int y;
        final int cell;
        final double score;

        Candidate(int x, int y, int cell, double score) {
            this.x = x;
            this.y = y;
            this.cell = cell;
            this.score = score;
        }
    }
}
