package com.baros.esupl;

/** Safe diagnostic built from our own text and numeric/date fields, never API bodies or URLs. */
public class EsuplSyncException extends IllegalStateException {
    private final String code;

    public EsuplSyncException(String code, String safeDetails) {
        super(safeDetails);
        this.code = code;
    }

    public String diagnostic() {
        return "Код: " + code + "\n" + getMessage();
    }
}
