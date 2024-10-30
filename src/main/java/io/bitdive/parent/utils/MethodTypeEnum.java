package io.bitdive.parent.utils;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@Getter
public enum MethodTypeEnum {
    DB("Repository"),

    SCHEDULER("Scheduled"),

    WEB_GET("GetMapping"),
    WEB_POST("PostMapping"),
    WEB_PUT("PutMapping"),
    WEB_DELETE("DeleteMapping"),
    WEB_PATCH("PatchMapping"),
    METHOD("");

    final String annotationName;

    MethodTypeEnum(String annotationName) {
        this.annotationName = annotationName;
    }

    public static List<MethodTypeEnum> getListWebMethodType() {
        return Arrays.asList(WEB_GET, WEB_POST, WEB_PUT, WEB_DELETE, WEB_PATCH);
    }

    public static List<MethodTypeEnum> getListMethodFlagInPoint() {
        return Arrays.asList(WEB_GET, WEB_POST, WEB_PUT, WEB_DELETE, WEB_PATCH, SCHEDULER);
    }
}
