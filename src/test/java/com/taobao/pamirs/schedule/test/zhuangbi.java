package com.taobao.pamirs.schedule.test;

/**
 * Created by assbing on 15/8/13.
 */
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Created by assbing on 15/8/13.
 */
public class zhuangbi {
    public static void main(String[] args) throws IllegalAccessException {
        String field1 = "1";
        String field0 = "0";
        String field2 = "2";
        String field3 = "3";
        String field4 = "4";

        Map<String,Object> inMap = new LinkedHashMap<String, Object>();
        inMap.put("field0",field0);
        inMap.put("field1",field1);
        inMap.put("field2",field2);
        inMap.put("field3",field3);
        inMap.put("field4", field4);
        Beans target = new Beans();
        replaceFields(inMap, target);
        String a = "a";
        String b = a+"a";



    }
    public static void replaceFields(Map<String,Object> inMap,Object object) throws IllegalAccessException {
        Field[] fields = object.getClass().getDeclaredFields();
        for (Field field :fields){
            String fieldName =field.getName();
            if (inMap.get(fieldName)!=null){
                field.setAccessible(true);
                field.set(object,inMap.get(fieldName));
                field.setAccessible(false);
            }
        }
    }



}