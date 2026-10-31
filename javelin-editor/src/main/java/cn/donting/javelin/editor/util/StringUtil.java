package cn.donting.javelin.editor.util;

import java.util.Collection;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * 字符串工具类
 * 
 * <p>提供常用的字符串处理方法，包括格式化、判空、截取、转换等。</p>
 * 
 * @author: javelin
 * @create: 2026/3/28
 * @Version 1.0
 */
public class StringUtil {
    
    /** 空字符串 */
    public static final String EMPTY = "";
    
    /** 空格 */
    public static final String SPACE = " ";
    
    /** 下划线 */
    public static final char UNDERLINE = '_';
    
    /** 逗号 */
    public static final String COMMA = ",";
    
    /** 默认分隔符 */
    public static final String DEFAULT_DELIMITER = ", ";
    
    /** 用于驼峰转下划线的正则 */
    private static final Pattern CAMEL_PATTERN = Pattern.compile("[A-Z]");
    
    /**
     * 格式化字符串（类似 String.format，但使用 %s 作为占位符）
     * 
     * <p>示例：format("Hello %s, age %s", "World", 18) → "Hello World, age 18"</p>
     * 
     * @param template 模板字符串
     * @param args 参数值
     * @return 格式化后的字符串
     */
    public static String format(String template, Object... args) {
        if (isEmpty(template) || args == null || args.length == 0) {
            return template;
        }
        return String.format(template, args);
    }
    
    /**
     * 格式化字符串，使用 {} 作为占位符（支持格式控制）
     * 
     * <p>示例：</p>
     * <pre>
     * formatSlf4j("Hello {}, age {}", "World", 18) → "Hello World, age 18"
     * formatSlf4j("Value: {%.2f}", 3.14159) → "Value: 3.14"
     * formatSlf4j("Hex: {%x}", 255) → "Hex: ff"
     * </pre>
     * 
     * @param template 模板字符串
     * @param args 参数值
     * @return 格式化后的字符串
     */
    public static String formatSlf4j(String template, Object... args) {
        if (isEmpty(template) || args == null || args.length == 0) {
            return template;
        }
        
        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        int templateLen = template.length();
        
        for (int i = 0; i < templateLen; i++) {
            char c = template.charAt(i);
            
            // 检查是否是转义的 {
            if (c == '\\' && i + 1 < templateLen && template.charAt(i + 1) == '{') {
                sb.append('{');
                i++; // 跳过下一个字符
            }
            // 检查是否是带格式的占位符 {xxx}
            else if (c == '{' && i + 1 < templateLen) {
                // 查找结束符 }
                int endIndex = template.indexOf('}', i + 1);
                if (endIndex >= i + 1) {
                    String formatSpec = template.substring(i + 1, endIndex);
                    if (argIndex < args.length) {
                        if (formatSpec.isEmpty()) {
                            // 简单占位符 {}
                            sb.append(args[argIndex]);
                        } else {
                            // 带格式的占位符 {.2f}、{s} 等（去掉开头可能的 %）
                            String spec = formatSpec.startsWith("%") ? formatSpec.substring(1) : formatSpec;
                            String formatStr = "%" + spec;
                            sb.append(String.format(formatStr, args[argIndex]));
                        }
                        argIndex++;
                    } else {
                        // 参数不足，保留占位符
                        sb.append('{').append(formatSpec).append('}');
                    }
                    i = endIndex; // 跳到结束符
                } else {
                    // 没有结束符，当作普通字符
                    sb.append(c);
                }
            }
            // 普通字符
            else {
                sb.append(c);
            }
        }
        
        return sb.toString();
    }
    
    // ==================== 判空方法 ====================
    
    /**
     * 检查字符串是否为空（null 或长度为 0）
     * 
     * @param str 待检查字符串
     * @return true 如果为 null 或空字符串
     */
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }
    
    /**
     * 检查字符串是否不为空
     * 
     * @param str 待检查字符串
     * @return true 如果不为 null 且不为空字符串
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }
    
    /**
     * 检查字符串是否为空白（null、空字符串或仅包含空白字符）
     * 
     * @param str 待检查字符串
     * @return true 如果为 null、空或仅空白字符
     */
    public static boolean isBlank(String str) {
        if (isEmpty(str)) {
            return true;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 检查字符串是否不为空白
     * 
     * @param str 待检查字符串
     * @return true 如果包含非空白字符
     */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }
    
    /**
     * 检查任意一个字符串是否为空
     * 
     * @param strs 待检查字符串数组
     * @return true 如果任意一个为空
     */
    public static boolean isAnyEmpty(String... strs) {
        if (strs == null || strs.length == 0) {
            return true;
        }
        for (String str : strs) {
            if (isEmpty(str)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查所有字符串是否都不为空
     * 
     * @param strs 待检查字符串数组
     * @return true 如果所有都不为空
     */
    public static boolean isAllNotEmpty(String... strs) {
        return !isAnyEmpty(strs);
    }
    
    // ==================== 默认值方法 ====================
    
    /**
     * 如果字符串为空，返回默认值
     * 
     * @param str 原字符串
     * @param defaultStr 默认值
     * @return 原字符串（如果不为空）或默认值
     */
    public static String defaultIfEmpty(String str, String defaultStr) {
        return isEmpty(str) ? defaultStr : str;
    }
    
    /**
     * 如果字符串为空白，返回默认值
     * 
     * @param str 原字符串
     * @param defaultStr 默认值
     * @return 原字符串（如果不为空白）或默认值
     */
    public static String defaultIfBlank(String str, String defaultStr) {
        return isBlank(str) ? defaultStr : str;
    }
    
    /**
     * 如果字符串为空，返回空字符串
     * 
     * @param str 原字符串
     * @return 原字符串或空字符串
     */
    public static String emptyIfNull(String str) {
        return str == null ? EMPTY : str;
    }
    
    /**
     * 如果字符串为空，返回 null
     * 
     * @param str 原字符串
     * @return 原字符串或 null
     */
    public static String nullIfEmpty(String str) {
        return isEmpty(str) ? null : str;
    }
    
    // ==================== Trim 方法 ====================
    
    /**
     * 安全 trim，null 安全
     * 
     * @param str 原字符串
     * @return trim 后的字符串，null 返回 null
     */
    public static String trim(String str) {
        return str == null ? null : str.trim();
    }
    
    /**
     * trim 后如果为空则返回空字符串
     * 
     * @param str 原字符串
     * @return trim 后的字符串，不会返回 null
     */
    public static String trimToEmpty(String str) {
        return str == null ? EMPTY : str.trim();
    }
    
    /**
     * trim 后如果为空则返回 null
     * 
     * @param str 原字符串
     * @return trim 后的字符串，可能返回 null
     */
    public static String trimToNull(String str) {
        if (str == null) {
            return null;
        }
        String trimmed = str.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
    
    // ==================== 截取方法 ====================
    
    /**
     * 截取字符串前 n 个字符
     * 
     * @param str 原字符串
     * @param maxLength 最大长度
     * @return 截取后的字符串
     */
    public static String truncate(String str, int maxLength) {
        if (str == null || maxLength < 0) {
            return str;
        }
        return str.length() <= maxLength ? str : str.substring(0, maxLength);
    }
    
    /**
     * 截取字符串，超出部分显示省略号
     * 
     * @param str 原字符串
     * @param maxLength 最大长度（包含省略号）
     * @return 截取后的字符串
     */
    public static String truncateWithEllipsis(String str, int maxLength) {
        if (str == null || maxLength < 3 || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }
    
    /**
     * 安全截取子串
     * 
     * @param str 原字符串
     * @param beginIndex 开始索引
     * @param endIndex 结束索引
     * @return 子串，越界时返回能截取的部分
     */
    public static String safeSubstring(String str, int beginIndex, int endIndex) {
        if (str == null) {
            return null;
        }
        int len = str.length();
        int begin = Math.max(0, Math.min(beginIndex, len));
        int end = Math.max(begin, Math.min(endIndex, len));
        return str.substring(begin, end);
    }
    
    // ==================== 分割与连接 ====================
    
    /**
     * 安全分割字符串
     * 
     * @param str 原字符串
     * @param delimiter 分隔符
     * @return 分割后的数组，不会返回 null
     */
    public static String[] split(String str, String delimiter) {
        if (isEmpty(str)) {
            return new String[0];
        }
        return str.split(Pattern.quote(delimiter));
    }
    
    /**
     * 连接字符串集合
     * 
     * @param collection 字符串集合
     * @param delimiter 分隔符
     * @return 连接后的字符串
     */
    public static String join(Collection<?> collection, String delimiter) {
        if (collection == null || collection.isEmpty()) {
            return EMPTY;
        }
        StringJoiner joiner = new StringJoiner(defaultIfEmpty(delimiter, DEFAULT_DELIMITER));
        for (Object obj : collection) {
            joiner.add(Objects.toString(obj, EMPTY));
        }
        return joiner.toString();
    }
    
    /**
     * 连接字符串数组
     * 
     * @param array 字符串数组
     * @param delimiter 分隔符
     * @return 连接后的字符串
     */
    public static String join(Object[] array, String delimiter) {
        if (array == null || array.length == 0) {
            return EMPTY;
        }
        StringJoiner joiner = new StringJoiner(defaultIfEmpty(delimiter, DEFAULT_DELIMITER));
        for (Object obj : array) {
            joiner.add(Objects.toString(obj, EMPTY));
        }
        return joiner.toString();
    }
    
    // ==================== 大小写转换 ====================
    
    /**
     * 首字母大写
     * 
     * @param str 原字符串
     * @return 首字母大写的字符串
     */
    public static String capitalize(String str) {
        if (isEmpty(str)) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
    
    /**
     * 首字母小写
     * 
     * @param str 原字符串
     * @return 首字母小写的字符串
     */
    public static String uncapitalize(String str) {
        if (isEmpty(str)) {
            return str;
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }
    
    /**
     * 驼峰命名转下划线命名（如：userName → user_name）
     * 
     * @param str 驼峰字符串
     * @return 下划线字符串
     */
    public static String camelToUnderscore(String str) {
        if (isEmpty(str)) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append(UNDERLINE);
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    
    /**
     * 下划线命名转驼峰命名（如：user_name → userName）
     * 
     * @param str 下划线字符串
     * @return 驼峰字符串
     */
    public static String underscoreToCamel(String str) {
        if (isEmpty(str)) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == UNDERLINE) {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    
    // ==================== 其他常用方法 ====================
    
    /**
     * 重复字符串 n 次
     * 
     * @param str 原字符串
     * @param count 重复次数
     * @return 重复后的字符串
     */
    public static String repeat(String str, int count) {
        if (str == null || count <= 0) {
            return EMPTY;
        }
        if (count == 1) {
            return str;
        }
        StringBuilder sb = new StringBuilder(str.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
    
    /**
     * 反转字符串
     * 
     * @param str 原字符串
     * @return 反转后的字符串
     */
    public static String reverse(String str) {
        if (isEmpty(str)) {
            return str;
        }
        return new StringBuilder(str).reverse().toString();
    }
    
    /**
     * 移除前缀（如果存在）
     * 
     * @param str 原字符串
     * @param prefix 前缀
     * @return 移除前缀后的字符串
     */
    public static String removePrefix(String str, String prefix) {
        if (isEmpty(str) || isEmpty(prefix)) {
            return str;
        }
        if (str.startsWith(prefix)) {
            return str.substring(prefix.length());
        }
        return str;
    }
    
    /**
     * 移除后缀（如果存在）
     * 
     * @param str 原字符串
     * @param suffix 后缀
     * @return 移除后缀后的字符串
     */
    public static String removeSuffix(String str, String suffix) {
        if (isEmpty(str) || isEmpty(suffix)) {
            return str;
        }
        if (str.endsWith(suffix)) {
            return str.substring(0, str.length() - suffix.length());
        }
        return str;
    }
    
    /**
     * 左填充字符到指定长度
     * 
     * @param str 原字符串
     * @param minLength 最小长度
     * @param padChar 填充字符
     * @return 填充后的字符串
     */
    public static String padLeft(String str, int minLength, char padChar) {
        if (str == null) {
            str = EMPTY;
        }
        if (str.length() >= minLength) {
            return str;
        }
        StringBuilder sb = new StringBuilder(minLength);
        for (int i = str.length(); i < minLength; i++) {
            sb.append(padChar);
        }
        sb.append(str);
        return sb.toString();
    }
    
    /**
     * 右填充字符到指定长度
     * 
     * @param str 原字符串
     * @param minLength 最小长度
     * @param padChar 填充字符
     * @return 填充后的字符串
     */
    public static String padRight(String str, int minLength, char padChar) {
        if (str == null) {
            str = EMPTY;
        }
        if (str.length() >= minLength) {
            return str;
        }
        StringBuilder sb = new StringBuilder(minLength);
        sb.append(str);
        for (int i = str.length(); i < minLength; i++) {
            sb.append(padChar);
        }
        return sb.toString();
    }
    
    /**
     * 安全比较两个字符串（null 安全）
     * 
     * @param str1 字符串1
     * @param str2 字符串2
     * @return true 如果相等（都为 null 也返回 true）
     */
    public static boolean equals(String str1, String str2) {
        return Objects.equals(str1, str2);
    }
    
    /**
     * 忽略大小写比较两个字符串
     * 
     * @param str1 字符串1
     * @param str2 字符串2
     * @return true 如果相等（忽略大小写）
     */
    public static boolean equalsIgnoreCase(String str1, String str2) {
        if (str1 == null || str2 == null) {
            return str1 == str2;
        }
        return str1.equalsIgnoreCase(str2);
    }
    
    /**
     * 安全的 contains 检查
     * 
     * @param str 原字符串
     * @param searchStr 查找的字符串
     * @return true 如果包含
     */
    public static boolean contains(String str, String searchStr) {
        if (str == null || searchStr == null) {
            return false;
        }
        return str.contains(searchStr);
    }
    
    /**
     * 安全的 startsWith 检查
     * 
     * @param str 原字符串
     * @param prefix 前缀
     * @return true 如果以指定前缀开头
     */
    public static boolean startsWith(String str, String prefix) {
        if (str == null || prefix == null) {
            return false;
        }
        return str.startsWith(prefix);
    }
    
    /**
     * 安全的 endsWith 检查
     * 
     * @param str 原字符串
     * @param suffix 后缀
     * @return true 如果以指定后缀结尾
     */
    public static boolean endsWith(String str, String suffix) {
        if (str == null || suffix == null) {
            return false;
        }
        return str.endsWith(suffix);
    }
    
    /**
     * 计算字符串长度（null 安全）
     * 
     * @param str 字符串
     * @return 长度，null 返回 0
     */
    public static int length(String str) {
        return str == null ? 0 : str.length();
    }
    
    /**
     * 计算非空白字符数
     * 
     * @param str 字符串
     * @return 非空白字符数
     */
    public static int countNonBlank(String str) {
        if (isEmpty(str)) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                count++;
            }
        }
        return count;
    }
    
    // 私有构造函数，防止实例化
    private StringUtil() {
        throw new AssertionError("StringUtil 是工具类，不能实例化");
    }
}
