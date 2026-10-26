package com.copenhagen.mcp.core;

import com.copenhagen.mcp.tool.ToolErrorCode;

public class CoreClientException extends RuntimeException {

    private final ToolErrorCode code;

    public CoreClientException(ToolErrorCode code) {
        this(code, null);
    }

    public CoreClientException(ToolErrorCode code, Throwable cause) {
        super(code.message(), cause);
        this.code = code;
    }

    public ToolErrorCode code() {
        return code;
    }
}
