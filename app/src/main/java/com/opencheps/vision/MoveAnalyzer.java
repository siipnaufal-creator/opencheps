
package com.opencheps.vision;

import com.opencheps.util.Side;

import java.util.ArrayList;
import java.util.List;

public final class MoveAnalyzer {

    public enum Kind {
        NONE,
        QUIET,
        CAPTURE,
        CASTLE,
        EN_PASSANT,
        PROMOTION,
        UNKNOWN
    }

    public static class Result {
        public final Kind kind;
        public final Side mover;
        public final int changeCount;

        public Result(Kind kind, Side mover, int changeCount) {
            this.kind = kind;
            this.mover = mover;
            this.changeCount = changeCount;
        }
    }

    private static class Change {
        final int row;
        final int file;
        final char before;
        final char after;

        Change(int row, int file, char before, char after) {
            this.row = row;
            this.file = file;
            this.before = before;
            this.after = after;
        }
    }

    private MoveAnalyzer() {
    }

    public static Result analyze(char[][] previous, char[][] current) {
        if (!validGrid(previous) || !validGrid(current)) {
            return unknown(0);
        }

        List<Change> emptied = new ArrayList<>();
        List<Change> filled = new ArrayList<>();
        List<Change> replaced = new ArrayList<>();

        int changes = 0;

        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                char before = normalize(previous[row][file]);
                char after = normalize(current[row][file]);

                if (before == after) {
                    continue;
                }

                changes++;

                Change c = new Change(row, file, before, after);

                if (before == '.' && after != '.') {
                    filled.add(c);
                } else if (before != '.' && after == '.') {
                    emptied.add(c);
                } else {
                    replaced.add(c);
                }
            }
        }

        if (changes == 0) {
            return new Result(Kind.NONE, null, 0);
        }

        if (changes > 4) {
            return unknown(changes);
        }

        // Gerakan biasa
        if (emptied.size() == 1
                && filled.size() == 1
                && replaced.isEmpty()) {
            return analyzeQuiet(
                    previous,
                    emptied.get(0),
                    filled.get(0),
                    changes
            );
        }

        // Menangkap bidak
        if (emptied.size() == 1
                && filled.isEmpty()
                && replaced.size() == 1) {
            return analyzeCapture(
                    previous,
                    emptied.get(0),
                    replaced.get(0),
                    changes
            );
        }

        // En passant
        if (emptied.size() == 2
                && filled.size() == 1
                && replaced.isEmpty()) {
            return analyzeEnPassant(
                    emptied,
                    filled.get(0),
                    changes
            );
        }

        // Rokade
        if (emptied.size() == 2
                && filled.size() == 2
                && replaced.isEmpty()) {
            return analyzeCastle(
                    previous,
                    emptied,
                    filled,
                    changes
            );
        }

        return unknown(changes);
    }

    private static Result analyzeQuiet(
            char[][] board,
            Change from,
            Change to,
            int changes
    ) {
        char piece = from.before;
        char arrived = to.after;

        if (!sameColor(piece, arrived)) {
            return unknown(changes);
        }

        if (piece != arrived) {
            if (Character.toLowerCase(piece) == 'p'
                    && isPromotionPiece(arrived)
                    && isCorrectPromotion(piece, from, to)
                    && pathClear(board, from, to)) {
                return result(Kind.PROMOTION, piece, changes);
            }

            return unknown(changes);
        }

        if (!validMovementShape(piece, from, to, false)) {
            return unknown(changes);
        }

        if (!pathClear(board, from, to)) {
            return unknown(changes);
        }

        return result(Kind.QUIET, piece, changes);
    }

    private static Result analyzeCapture(
            char[][] board,
            Change from,
            Change destination,
            int changes
    ) {
        char attacker = from.before;
        char victim = destination.before;
        char arrived = destination.after;

        if (!sameColor(attacker, arrived)) {
            return unknown(changes);
        }

        if (sameColor(attacker, victim)) {
            return unknown(changes);
        }

        if (attacker != arrived) {
            if (Character.toLowerCase(attacker) == 'p'
                    && isPromotionPiece(arrived)
                    && isCorrectPromotion(attacker, from, destination)
                    && pathClear(board, from, destination)) {
                return result(Kind.PROMOTION, attacker, changes);
            }

            return unknown(changes);
        }

        if (!validMovementShape(attacker, from, destination, true)) {
            return unknown(changes);
        }

        if (!pathClear(board, from, destination)) {
            return unknown(changes);
        }

        return result(Kind.CAPTURE, attacker, changes);
    }

    private static Result analyzeEnPassant(
            List<Change> emptied,
            Change filled,
            int changes
    ) {
        Change pawnFrom = null;
        Change victimFrom = null;

        for (Change c : emptied) {
            if (Character.toLowerCase(c.before) != 'p') {
                return unknown(changes);
            }

            if (sameColor(c.before, filled.after)) {
                if (pawnFrom != null) {
                    return unknown(changes);
                }
                pawnFrom = c;
            } else {
                if (victimFrom != null) {
                    return unknown(changes);
                }
                victimFrom = c;
            }
        }

        if (pawnFrom == null || victimFrom == null) {
            return unknown(changes);
        }

        if (Character.toLowerCase(filled.after) != 'p') {
            return unknown(changes);
        }

        int direction = isWhite(pawnFrom.before) ? -1 : 1;

        if (filled.row - pawnFrom.row != direction) {
            return unknown(changes);
        }

        if (Math.abs(filled.file - pawnFrom.file) != 1) {
            return unknown(changes);
        }

        if (victimFrom.row != pawnFrom.row
                || victimFrom.file != filled.file) {
            return unknown(changes);
        }

        if (isWhite(victimFrom.before) == isWhite(pawnFrom.before)) {
            return unknown(changes);
        }

        return result(Kind.EN_PASSANT, pawnFrom.before, changes);
    }

    private static Result analyzeCastle(
            char[][] board,
            List<Change> emptied,
            List<Change> filled,
            int changes
    ) {
        Change kingFrom = null;
        Change rookFrom = null;
        Change kingTo = null;
        Change rookTo = null;

        for (Change c : emptied) {
            if (Character.toLowerCase(c.before) == 'k') {
                if (kingFrom != null) return unknown(changes);
                kingFrom = c;
            } else if (Character.toLowerCase(c.before) == 'r') {
                if (rookFrom != null) return unknown(changes);
                rookFrom = c;
            }
        }

        for (Change c : filled) {
            if (Character.toLowerCase(c.after) == 'k') {
                if (kingTo != null) return unknown(changes);
                kingTo = c;
            } else if (Character.toLowerCase(c.after) == 'r') {
                if (rookTo != null) return unknown(changes);
                rookTo = c;
            }
        }

        if (kingFrom == null || rookFrom == null
                || kingTo == null || rookTo == null) {
            return unknown(changes);
        }

        char king = kingFrom.before;

        if (!sameColor(king, rookFrom.before)
                || !sameColor(king, kingTo.after)
                || !sameColor(king, rookTo.after)) {
            return unknown(changes);
        }

        int homeRow = isWhite(king) ? 7 : 0;

        if (kingFrom.row != homeRow
                || kingFrom.file != 4
                || rookFrom.row != homeRow
                || kingTo.row != homeRow
                || rookTo.row != homeRow) {
            return unknown(changes);
        }

        boolean kingSide = rookFrom.file == 7;

        if (!kingSide && rookFrom.file != 0) {
            return unknown(changes);
        }

        int expectedKingFile = kingSide ? 6 : 2;
        int expectedRookFile = kingSide ? 5 : 3;

        if (kingTo.file != expectedKingFile
                || rookTo.file != expectedRookFile) {
            return unknown(changes);
        }

        int step = kingSide ? 1 : -1;

        for (int file = 4 + step;
             file != rookFrom.file;
             file += step) {
            if (board[homeRow][file] != '.') {
                return unknown(changes);
            }
        }

        return result(Kind.CASTLE, king, changes);
    }

    private static boolean validMovementShape(
            char piece,
            Change from,
            Change to,
            boolean capture
    ) {
        int dr = to.row - from.row;
        int df = to.file - from.file;

        int ar = Math.abs(dr);
        int af = Math.abs(df);

        switch (Character.toLowerCase(piece)) {
            case 'p': {
                int direction = isWhite(piece) ? -1 : 1;

                if (capture) {
                    return dr == direction && af == 1;
                }

                if (df != 0) {
                    return false;
                }

                if (dr == direction) {
                    return true;
                }

                int startingRow = isWhite(piece) ? 6 : 1;

                return from.row == startingRow
                        && dr == direction * 2;
            }

            case 'n':
                return (ar == 2 && af == 1)
                        || (ar == 1 && af == 2);

            case 'b':
                return ar == af && ar > 0;

            case 'r':
                return (dr == 0 && df != 0)
                        || (df == 0 && dr != 0);

            case 'q':
                return (ar == af && ar > 0)
                        || (dr == 0 && df != 0)
                        || (df == 0 && dr != 0);

            case 'k':
                return ar <= 1 && af <= 1
                        && (ar + af) > 0;

            default:
                return false;
        }
    }

    private static boolean pathClear(
            char[][] board,
            Change from,
            Change to
    ) {
        char piece = from.before;
        int dr = to.row - from.row;
        int df = to.file - from.file;

        // Kuda loncat kuda tidak perlu pemeriksaan jalur.
        if (Character.toLowerCase(piece) == 'n') {
            return true;
        }

        // Gerakan satu petak tidak memiliki petak perantara.
        if (Math.max(Math.abs(dr), Math.abs(df)) <= 1) {
            return true;
        }

        int rowStep = Integer.compare(dr, 0);
        int fileStep = Integer.compare(df, 0);

        int row = from.row + rowStep;
        int file = from.file + fileStep;

        while (row != to.row || file != to.file) {
            if (row < 0 || row >= 8 || file < 0 || file >= 8) {
                return false;
            }

            if (board[row][file] != '.') {
                return false;
            }

            row += rowStep;
            file += fileStep;
        }

        // Dua langkah pion: petak di tengah wajib kosong.
        if (Character.toLowerCase(piece) == 'p'
                && df == 0
                && Math.abs(dr) == 2) {
            int middleRow = (from.row + to.row) / 2;
            return board[middleRow][from.file] == '.';
        }

        return true;
    }

    private static boolean isCorrectPromotion(
            char pawn,
            Change from,
            Change to
    ) {
        int direction = isWhite(pawn) ? -1 : 1;
        int targetRow = isWhite(pawn) ? 0 : 7;

        if (to.row != targetRow
                || to.row - from.row != direction) {
            return false;
        }

        return Math.abs(to.file - from.file) <= 1;
    }

    private static boolean validGrid(char[][] grid) {
        if (grid == null || grid.length != 8) {
            return false;
        }

        for (int row = 0; row < 8; row++) {
            if (grid[row] == null || grid[row].length != 8) {
                return false;
            }

            for (int file = 0; file < 8; file++) {
                char c = normalize(grid[row][file]);

                if (c != '.'
                        && "KQRBNPkqrbnp".indexOf(c) < 0) {
                    return false;
                }
            }
        }

        return true;
    }

    private static char normalize(char c) {
        return c == 0 ? '.' : c;
    }

    private static boolean isWhite(char piece) {
        return Character.isUpperCase(piece);
    }

    private static boolean sameColor(char a, char b) {
        return isWhite(a) == isWhite(b);
    }

    private static boolean isPromotionPiece(char piece) {
        char p = Character.toLowerCase(piece);
        return p == 'q' || p == 'r' || p == 'b' || p == 'n';
    }

    private static Result result(
            Kind kind,
            char piece,
            int changes
    ) {
        return new Result(
                kind,
                isWhite(piece) ? Side.WHITE : Side.BLACK,
                changes
        );
    }

    private static Result unknown(int changes) {
        return new Result(Kind.UNKNOWN, null, changes);
    }
}
