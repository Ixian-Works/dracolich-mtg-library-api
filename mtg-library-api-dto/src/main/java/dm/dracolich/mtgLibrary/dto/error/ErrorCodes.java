package dm.dracolich.mtgLibrary.dto.error;

import dm.dracolich.forge.error.ErrorCode;
import lombok.Getter;

@Getter
public enum ErrorCodes implements ErrorCode {
    DMD021("DMD021", "%s: %s not found: %s"),
    DMD045("DMD045", "Unknown sort \"%s\". Sort by %s, each optionally followed by \",asc\" or \",desc\""),
    DMD046("DMD046", "Page %s of size %s is out of range: page must be 0 or more, and size from 1 to 100"),
    DMD047("DMD047", "Invalid card collection request: %s");

    private final String code;
    private final String message;

    ErrorCodes(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String format(String... args) {
        return String.format(message, args);
    }
}
