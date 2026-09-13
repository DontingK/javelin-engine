package com.javelin.editor.util;


import java.util.Date;

/**
 * @author: javelin
 * @create: 2026/4/14
 * @Version 1.0
 **/
public class TimeUtil {

    public static final String DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd";
    public static final String DEFAULT_TIME_PATTERN = "HH:mm:ss";
    public static final String DEFAULT_TIME_PATTERN_NO_SECOND = "yyyyMMddHHmmss";

    public static String formatTime(Date date, String pattern) {
        return new java.text.SimpleDateFormat(pattern).format(date);
    }
}
