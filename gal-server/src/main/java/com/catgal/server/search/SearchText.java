/*
 * 阅读说明
 * MySQL 模糊匹配文本的转义工具，区分用户普通字符与 LIKE 通配符。
 */
package com.catgal.server.search;

public final class SearchText {
    private SearchText() { }

    public static String likePattern(String keyword) {
        String sqlPattern = "%" + keyword + "%";
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < sqlPattern.length(); i++) {
            char character = sqlPattern.charAt(i);
            if (character == '\\' && i + 1 < sqlPattern.length()) {
                appendLiteral(pattern, sqlPattern.charAt(++i));
            } else if (character == '%') {
                pattern.append('*');
            } else if (character == '_') {
                pattern.append('?');
            } else {
                appendLiteral(pattern, character);
            }
        }
        return pattern.toString();
    }

    private static void appendLiteral(StringBuilder target, char character) {
        if (character == '*' || character == '?' || character == '\\') {
            target.append('\\');
        }
        target.append(character);
    }

}
