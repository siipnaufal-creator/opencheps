
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

                Change change = new Change(
                        row, file, before, after
                );

                if (before == '.' && after != '.') {
                    filled.add(change);
                } else if (before != '.' && after == '.') {
                    emptied.add(change);
                } else {
                    replaced.add(change);
                }
            }
        }

        if (changes == 0) {
            return new Result(Kind.NONE, null, 0);
        }

        if (changes > 4) {
            return unknown(changes);
        }

        if (emptied.size() == 1
                && filled.size() == 1
                && replaced.isEmpty()) {

            return analyzeQuiet(
                    emptied.get(0),
                    filled.get(0),
                    changes
            );
        }

        if (emptied.size() == 1
                && filled.isEmpty()
                && replaced.size() == 1) {

            return analyzeCapture(
                    emptied.get(0),
                    replaced.get(0),
                    changes
            );
        }

        if (emptied.size() == 2
                && filled.size() == 1
                && replaced.isEmpty()) {

            return analyzeEnPassant(
                    emptied,
                    filled.get(0),
                    changes
            );
        }

        if (emptied.size() == 2
                && filled.size() == 2
                && replaced.isEmpty()) {

            return analyzeCastle(
                    emptied,
                    filled,
                    changes
            );
        }

        return unknown(changes);
    }

    private static Result analyzeQuiet(
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
                    && isPromotionRank(to.row)
                    && isPromotionPiece(arrived)) {

                return result(Kind.PROMOTION, piece, changes);
            }

            return unknown(changes);
        }

        if (!validMovementShape(piece, from, to)) {
            return unknown(changes);
        }

        return result(Kind.QUIET, piece, changes);
    }

    private static Result analyzeCapture(
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
                    && isPromotionRank(destination.row)
                    && isPromotionPiece(arrived)) {

                return result(Kind.PROMOTION, attacker, changes);
            }

            return unknown(changes);
        }

        if (!validMovementShape(attacker, from, destination)) {
            return unknown(changes);
        }

        return result(Kind.CAPTURE, attacker, changes);
    }

    private static Result analyzeEnPassant(
            List<Change> emptied,
            Change filled,
            int changes
    ) {
        Change first = emptied.get(0);
        Change second = emptied.get(1);

        Change pawnFrom = null;
        Change victimFrom = null;

        for (Change c : emptied) {
            if (Character.toLowerCase(c.before) != 'p') {
                return unknown(changes);
            }

            if (sameColor(c.before, filled.after)) {
                pawnFrom = c;
            } else {
                victimFrom = c;
            }
        }

        if (pawnFrom == null || victimFrom == null) {
            return unknown(changes);
        }

        if (!sameColor(pawnFrom.before, filled.after)) {
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
                kingFrom = c;
            } else if (Character.toLowerCase(c.before) == 'r') {
                rookFrom = c;
            }
        }

        for (Change c : filled) {
            if (Character.toLowerCase(c.after) == 'k') {
                kingTo = c;
            } else if (Character.toLowerCase(c.after) == 'r') {
                rookTo = c;
            }
        }

        if (kingFrom == null || rookFrom == null
                || kingTo == null || rookTo == null) {
            return unknown(changes);
        }

        if (!sameColor(kingFrom.before, rookFrom.before)
                || !sameColor(kingTo.after, rookTo.after)) {
            return unknown(changes);
        }

        if (!sameColor(kingFrom.before, kingTo.after)) {
            return unknown(changes);
        }

        if (kingFrom.row != kingTo.row
                || rookFrom.row != rookTo.row
                || kingFrom.row != rookFrom.row) {
            return unknown(changes);
        }

        if (Math.abs(kingTo.file - kingFrom.file) != 2) {
            return unknown(changes);
        }

        if (Math.abs(rookTo.file - rookFrom.file) != 2) {
            return unknown(changes);
        }

        if (kingTo.file == rookTo.file) {
            return unknown(changes);
        }

        return result(Kind.CASTLE, kingFrom.before, changes);
    }

    private static boolean validMovementShape(
            char piece,
            Change from,
            Change to
    ) {
        int dr = to.row - from.row;
        int df = to.file - from.file;

        int ar = Math.abs(dr);
        int af = Math.abs(df);

        switch (Character.toLowerCase(piece)) {
            case 'p': {
                int direction = isWhite(piece) ? -1 : 1;

                if (df == 0 && dr == direction) {
                    return true;
                }

                int startingRow = isWhite(piece) ? 6 : 1;

                return df == 0
                        && from.row == startingRow
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

    private static boolean isPromotionRank(int row) {
        return row == 0 || row == 7;
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
