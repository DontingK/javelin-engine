package com.javelin.editor.util;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

/**
 * @author: javelin
 * @create: 2026/7/4
 * @Version 1.0
 **/
public class JSONUtil {
    private static final Logger LOGGER=org.slf4j.LoggerFactory.getLogger(JSONUtil.class);
    private  static ObjectMapper objectMapper=new ObjectMapper();

    public static String toJSON(Object object){
        try {
            return objectMapper.writeValueAsString(object);
        } catch (Exception e) {
            LOGGER.error("JSON转换错误",e);
            throw new RuntimeException(e);
        }
    }
    public static <T> T toObject(String json, Class<T> valueType){
        try {
            return objectMapper.readValue(json, valueType);
        } catch (Exception e) {
            LOGGER.error("JSON转换错误",e);
            throw new RuntimeException(e);
        }
    }

}
