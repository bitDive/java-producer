package io.bitdive.parent.trasirovka.agent.utils;

/**
 * Internal lightweight representation of a method argument for monitoring/serialization.
 *
 * <p>This type intentionally lives in the agent artifact to avoid depending on external DTO artifacts.</p>
 */
public class ParamMethod {
    private final int parIndex;
    private final String paramType;
    private final Object val;

    public ParamMethod(int parIndex, String paramType, Object val) {
        this.parIndex = parIndex;
        this.paramType = paramType;
        this.val = val;
    }

    public int getParIndex() {
        return parIndex;
    }

    public String getParamType() {
        return paramType;
    }

    public Object getVal() {
        return val;
    }
}

