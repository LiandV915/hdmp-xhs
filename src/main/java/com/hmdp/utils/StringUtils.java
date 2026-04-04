package com.hmdp.utils;

public class StringUtils {
        /**
         * 判断字符串是否为空
         */
        public static boolean isEmpty(String str) {
            return str == null || str.trim().isEmpty();
        }

        /**
         * 判断字符串是否不为空
         */
        public static boolean isNotEmpty(String str) {
            return !isEmpty(str);
        }

        /**
         * 将首字母大写
         */
        public static String capitalize(String str) {
            if (isEmpty(str)) {
                return str;
            }
            return str.substring(0, 1).toUpperCase() + str.substring(1);
        }

        /**
         * 简单将多个字符串用指定分隔符拼接
         */
        public static String join(String delimiter, String... strs) {
            if (strs == null || strs.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < strs.length; i++) {
                sb.append(strs[i]);
                if (i != strs.length - 1) {
                    sb.append(delimiter);
                }
            }
            return sb.toString();
        }
    }

