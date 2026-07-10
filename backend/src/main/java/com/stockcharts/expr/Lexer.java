package com.stockcharts.expr;

import java.util.ArrayList;
import java.util.List;

/** Turns an expression string into tokens. Keywords and/or/not are case-insensitive. */
final class Lexer {

    private final String src;
    private int i = 0;

    Lexer(String src) {
        this.src = src;
    }

    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (Character.isDigit(c) || (c == '.' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)))) {
                tokens.add(number(start));
            } else if (Character.isLetter(c) || c == '_') {
                tokens.add(identifier(start));
            } else {
                tokens.add(operator(start));
            }
        }
        tokens.add(new Token(Token.Type.EOF, "", 0, i));
        return tokens;
    }

    private Token number(int start) {
        while (i < src.length() && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) {
            i++;
        }
        String t = src.substring(start, i);
        try {
            return new Token(Token.Type.NUMBER, t, Double.parseDouble(t), start);
        } catch (NumberFormatException e) {
            throw new ExpressionException("Invalid number '" + t + "' at position " + start);
        }
    }

    private Token identifier(int start) {
        while (i < src.length()
                && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
            i++;
        }
        String t = src.substring(start, i);
        return switch (t.toLowerCase()) {
            case "and" -> new Token(Token.Type.AND, t, 0, start);
            case "or" -> new Token(Token.Type.OR, t, 0, start);
            case "not" -> new Token(Token.Type.NOT, t, 0, start);
            default -> new Token(Token.Type.IDENT, t, 0, start);
        };
    }

    private Token operator(int start) {
        char c = src.charAt(i);
        char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
        // two-character operators
        if (c == '>' && next == '=') return advance2(Token.Type.GE, ">=", start);
        if (c == '<' && next == '=') return advance2(Token.Type.LE, "<=", start);
        if (c == '=' && next == '=') return advance2(Token.Type.EQ, "==", start);
        if (c == '!' && next == '=') return advance2(Token.Type.NE, "!=", start);
        if (c == '&' && next == '&') return advance2(Token.Type.AND, "&&", start);
        if (c == '|' && next == '|') return advance2(Token.Type.OR, "||", start);
        i++;
        return switch (c) {
            case '+' -> new Token(Token.Type.PLUS, "+", 0, start);
            case '-' -> new Token(Token.Type.MINUS, "-", 0, start);
            case '*' -> new Token(Token.Type.STAR, "*", 0, start);
            case '/' -> new Token(Token.Type.SLASH, "/", 0, start);
            case '%' -> new Token(Token.Type.PERCENT, "%", 0, start);
            case '>' -> new Token(Token.Type.GT, ">", 0, start);
            case '<' -> new Token(Token.Type.LT, "<", 0, start);
            case '=' -> new Token(Token.Type.EQ, "==", 0, start); // tolerate single '='
            case '(' -> new Token(Token.Type.LPAREN, "(", 0, start);
            case ')' -> new Token(Token.Type.RPAREN, ")", 0, start);
            case ',' -> new Token(Token.Type.COMMA, ",", 0, start);
            default -> throw new ExpressionException("Unexpected character '" + c + "' at position " + start);
        };
    }

    private Token advance2(Token.Type type, String text, int start) {
        i += 2;
        return new Token(type, text, 0, start);
    }
}
