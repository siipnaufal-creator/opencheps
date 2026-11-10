
package com.opencheps.vision;

import com.opencheps.util.Side;

public final class FenBuilder {

    public static class Validation {
        public final boolean ok;
        public final String reason;

        Validation(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }
    }

    private FenBuilder() {
    }

    public static String build(
            char[][] grid,
            Side bootstrapSide,
            Side stm
    ) {
        if (bootstrapSide == null || stm == null) {
            return null;
        }

        if (!validate(grid).ok) {
            return null;
        }

        char[][] position =
                bootstrapSide == Side.BLACK ? flip(grid) : copy(grid);

        Validation validation = validate(position);
        if (!validation.ok) {
            return null;
        }

        StringBuilder fen = new StringBuilder();

        for (int row = 0; row < 8; row++) {
            int empty = 0;

            for (int file = 0; file < 8; file++) {
                char piece = position[row][file];

                if (piece == '.') {
                    empty++;
                } else {
                    if (empty > 0) {
                        fen.append(empty);
                        empty = 0;
                    }
                    fen.append(piece);
                }
            }

            if (empty > 0) {
                fen.append(empty);
            }

            if (row < 7) {
                fen.append('/');
            }
        }

        // Castling dan en-passant belum dapat dipastikan
        // hanya dari gambar papan.
        fen.append(stm == Side.WHITE ? " w " : " b ");
        fen.append("- - 0 1");

        return fen.toString();
    }

    public static Validation validate(char[][] grid) {
        if (grid == null || grid.length != 8) {
            return invalid("Invalid board dimensions");
        }

        int whiteKing = 0;
        int blackKing = 0;

        int whitePawns = 0;
        int blackPawns = 0;

        int whitePieces = 0;
        int blackPieces = 0;

        int whiteQueens = 0;
        int blackQueens = 0;

        int whiteRooks = 0;
        int blackRooks = 0;

        int whiteBishops = 0;
        int blackBishops = 0;

        int whiteKnights = 0;
        int blackKnights = 0;

        for (int row = 0; row < 8; row++) {
            if (grid[row] == null || grid[row].length != 8) {
                return invalid("Invalid row dimensions");
            }

            for (int file = 0; file < 8; file++) {
                char piece = grid[row][file];

                if (piece == 0) {
                    piece = '.';
                }

                if (piece == '.') {
                    continue;
                }

                if ("KQRBNPkqrbnp".indexOf(piece) < 0) {
                    return invalid(
                            "Unknown piece at row " + row
                                    + ", file " + file
                    );
                }

                switch (piece) {
                    case 'K':
                        whiteKing++;
                        whitePieces++;
                        break;

                    case 'k':
                        blackKing++;
                        blackPieces++;
                        break;

                    case 'P':
                        whitePawns++;
                        whitePieces++;
                        if (row == 0 || row == 7) {
                            return invalid("White pawn on back rank");
                        }
                        break;

                    case 'p':
                        blackPawns++;
                        blackPieces++;
                        if (row == 0 || row == 7) {
                            return invalid("Black pawn on back rank");
                        }
                        break;

                    case 'Q':
                        whiteQueens++;
                        whitePieces++;
                        break;

                    case 'q':
                        blackQueens++;
                        blackPieces++;
                        break;

                    case 'R':
                        whiteRooks++;
                        whitePieces++;
                        break;

                    case 'r':
                        blackRooks++;
                        blackPieces++;
                        break;

                    case 'B':
                        whiteBishops++;
                        whitePieces++;
                        break;

                    case 'b':
                        blackBishops++;
                        blackPieces++;
                        break;

                    case 'N':
                        whiteKnights++;
                        whitePieces++;
                        break;

                    case 'n':
                        blackKnights++;
                        blackPieces++;
                        break;
                }
            }
        }

        if (whiteKing != 1) {
            return invalid("White king count: " + whiteKing);
        }

        if (blackKing != 1) {
            return invalid("Black king count: " + blackKing);
        }

        if (whitePawns > 8 || blackPawns > 8) {
            return invalid("Too many pawns");
        }

        if (whitePieces > 16 || blackPieces > 16) {
            return invalid("Too many pieces");
        }

        // Tanpa promosi, jumlah tiap jenis tidak boleh
        // melebihi jumlah awalnya.
        // Promosi tetap dimungkinkan jika jumlah pion berkurang.
        if (whiteQueens > 1 + (8 - whitePawns)
                || blackQueens > 1 + (8 - blackPawns)) {
            return invalid("Impossible queen count");
        }

        if (whiteRooks > 2 + (8 - whitePawns)
                || blackRooks > 2 + (8 - blackPawns)) {
            return invalid("Impossible rook count");
        }

        if (whiteBishops > 2 + (8 - whitePawns)
                || blackBishops > 2 + (8 - blackPawns)) {
            return invalid("Impossible bishop count");
        }

        if (whiteKnights > 2 + (8 - whitePawns)
                || blackKnights > 2 + (8 - blackPawns)) {
            return invalid("Impossible knight count");
        }

        if (kingsAdjacent(grid)) {
            return invalid("Kings are adjacent");
        }

        return new Validation(true, null);
    }

    private static Validation invalid(String reason) {
        return new Validation(false, reason);
    }

    private static boolean kingsAdjacent(char[][] grid) {
        int whiteRow = -1;
        int whiteFile = -1;
        int blackRow = -1;
        int blackFile = -1;

        for (int row = 0; row < 8; row++) {
            for (int file = 0; file < 8; file++) {
                if (grid[row][file] == 'K') {
                    whiteRow = row;
                    whiteFile = file;
                } else if (grid[row][file] == 'k') {
                    blackRow = row;
                    blackFile = file;
                }
            }
        }

        if (whiteRow < 0 || blackRow < 0) {
            return false;
        }

        return Math.abs(whiteRow - blackRow) <= 1
                && Math.abs(whiteFile - blackFile) <= 1;
    }

    public static char[][] flip(char[][] grid) {
        if (grid == null || grid.length != 8) {
            return null;
        }

        char[][] output = new char[8][8];

        for (int row = 0; row < 8; row++) {
            if (grid[row] == null || grid[row].length != 8) {
                return null;
            }

            for (int file = 0; file < 8; file++) {
                char piece = grid[7 - row][7 - file];
                output[row][file] = piece == 0 ? '.' : piece;
            }
        }

        return output;
    }

    private static char[][] copy(char[][] grid) {
        char[][] output = new char[8][8];

        for (int row = 0; row < 8; row++) {
            System.arraycopy(grid[row], 0, output[row], 0, 8);

            for (int file = 0; file < 8; file++) {
                if (output[row][file] == 0) {
                    output[row][file] = '.';
                }
            }
        }

        return output;
    }

    public static int materialDiff(char[][] grid) {
        if (grid == null || grid.length != 8) {
            return 0;
        }

        int white = 0;
        int black = 0;

        for (int row = 0; row < 8; row++) {
            if (grid[row] == null || grid[row].length != 8) {
                return 0;
            }

            for (int file = 0; file < 8; file++) {
                char piece = grid[row][file];

                if (Character.isUpperCase(piece)) {
                    white += value(piece);
                } else {
                    black += value(piece);
                }
            }
        }

        return white - black;
    }

    private static int value(char piece) {
        switch (Character.toLowerCase(piece)) {
            case 'p': return 1;
            case 'n':
            case 'b': return 3;
            case 'r': return 5;
            case 'q': return 9;
            default: return 0;
        }
    }
}
