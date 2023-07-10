package myraft.exception;

public class MyRaftException extends RuntimeException {

    public MyRaftException(String message) {
        super(message);
    }

    public MyRaftException(String message, Throwable cause) {
        super(message, cause);
    }
}
